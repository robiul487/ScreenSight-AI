package com.example.data.remote

import android.util.Base64
import com.example.domain.model.ScreenSightError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

sealed class GeminiServerEvent {
    object SetupComplete : GeminiServerEvent()
    data class TextDelta(val text: String, val isUserTranscript: Boolean = false) : GeminiServerEvent()
    data class AudioChunk(val pcm24kBytes: ByteArray) : GeminiServerEvent()
    object TurnComplete : GeminiServerEvent()
    object Interrupted : GeminiServerEvent()
    data class SessionGoAway(val timeLeft: String) : GeminiServerEvent()
    data class Error(val error: ScreenSightError) : GeminiServerEvent()
    data class Disconnected(val reason: String, val wasClean: Boolean) : GeminiServerEvent()
}

data class ApiValidationResult(
    val isValid: Boolean,
    val discoveredLiveModels: List<String> = emptyList(),
    val error: ScreenSightError? = null
)

/**
 * Robust OkHttp WebSocket client for Google Gemini Live API (`BidiGenerateContent`).
 * Guards against stale WebSocket callbacks, enforces setup handshake timeout,
 * differentiates fatal API/quota errors from transient network drops for automatic reconnection,
 * and filters internal `thought` parts out of spoken transcripts.
 */
class GeminiWebSocketClient(
    private val okHttpClient: OkHttpClient = defaultOkHttpClient()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeWebSocket: WebSocket? = null
    @Volatile
    private var activeModelId: String = ""
    private var setupTimeoutJob: Job? = null
    private val isConnected = AtomicBoolean(false)
    private val isSetupCompleted = AtomicBoolean(false)

    private val _events = MutableSharedFlow<GeminiServerEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<GeminiServerEvent> = _events.asSharedFlow()

    private val _outboundQueueBytes = MutableStateFlow(0L)
    val outboundQueueBytes: StateFlow<Long> = _outboundQueueBytes.asStateFlow()

    /**
     * Validates the user's Gemini API key and discovers all models supporting `bidiGenerateContent`
     * from the authoritative `GET /v1beta/models` endpoint.
     */
    suspend fun validateApiKeyAndModel(apiKey: String, selectedModelId: String): ApiValidationResult =
        withContext(Dispatchers.IO) {
            val trimmedKey = apiKey.trim()
            if (trimmedKey.isEmpty()) {
                return@withContext ApiValidationResult(false, error = ScreenSightError.MissingApiKey)
            }
            val url = "${GeminiLiveConfigBuilder.GEMINI_MODELS_REST_BASE_URL}/models?pageSize=100&key=$trimmedKey"
            val request = Request.Builder().url(url).get().build()

            try {
                okHttpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val mappedError = ScreenSightError.fromHttpOrWebSocketError(
                            code = response.code,
                            message = bodyStr,
                            selectedModel = selectedModelId
                        )
                        return@withContext ApiValidationResult(false, error = mappedError)
                    }

                    val root = GeminiLiveJson.json.parseToJsonElement(bodyStr).jsonObject
                    val modelsArray = root["models"]?.jsonArray ?: emptyList()
                    val bidiModels = mutableListOf<String>()

                    for (elem in modelsArray) {
                        val obj = elem.jsonObject
                        val name = obj["name"]?.jsonPrimitive?.content ?: continue
                        val methods = obj["supportedGenerationMethods"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.content }
                            ?: emptyList()
                        if (methods.any { it.equals("bidiGenerateContent", ignoreCase = true) }) {
                            bidiModels.add(name)
                        }
                    }

                    return@withContext ApiValidationResult(
                        isValid = true,
                        discoveredLiveModels = bidiModels
                    )
                }
            } catch (e: Exception) {
                val mappedError = ScreenSightError.fromHttpOrWebSocketError(
                    code = null,
                    message = e.message,
                    selectedModel = selectedModelId
                )
                return@withContext ApiValidationResult(false, error = mappedError)
            }
        }

    /**
     * Establishes a single authenticated WebSocket connection and sends the initial setup payload.
     * Prevents duplicate active WebSocket connections and ignores stale callbacks from closed sockets.
     */
    @Synchronized
    fun connect(
        apiKey: String,
        modelId: String,
        voiceName: String,
        responseModalities: List<String>,
        systemInstruction: String,
        preferredLanguage: String
    ) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            handleError(ScreenSightError.MissingApiKey)
            return
        }

        // Cleanly close any existing socket before opening a new one
        disconnectInternal(notifyDisconnected = false)

        activeModelId = modelId
        isConnected.set(false)
        isSetupCompleted.set(false)

        val wsUrl = GeminiLiveConfigBuilder.buildWebSocketUrl(trimmedKey)
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val ws = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (webSocket !== activeWebSocket) return
                isConnected.set(true)
                sendSetup(
                    webSocket = webSocket,
                    modelId = modelId,
                    voiceName = voiceName,
                    responseModalities = responseModalities,
                    systemInstruction = systemInstruction,
                    preferredLanguage = preferredLanguage
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== activeWebSocket) return
                receiveMessages(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket !== activeWebSocket) return
                receiveMessages(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== activeWebSocket) return
                runCatching { webSocket.close(code, reason) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== activeWebSocket) return
                setupTimeoutJob?.cancel()
                val wasSetup = isSetupCompleted.getAndSet(false)
                isConnected.set(false)
                _outboundQueueBytes.value = 0L

                if (code != 1000 && reason.isNotBlank()) {
                    val err = ScreenSightError.fromHttpOrWebSocketError(code, reason, activeModelId)
                    // Non-recoverable protocol, model, auth, or quota close reasons surface immediately
                    if (!wasSetup ||
                        code in listOf(1007, 1008) ||
                        err is ScreenSightError.InvalidApiKey ||
                        err is ScreenSightError.UnsupportedModel ||
                        err is ScreenSightError.QuotaExceeded ||
                        err is ScreenSightError.RateLimitExceeded ||
                        err is ScreenSightError.ApiPermissionError ||
                        err is ScreenSightError.BillingRestriction
                    ) {
                        handleError(err)
                        return
                    }
                }
                _events.tryEmit(GeminiServerEvent.Disconnected(reason = reason, wasClean = code == 1000))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== activeWebSocket) return
                setupTimeoutJob?.cancel()
                val wasSetup = isSetupCompleted.getAndSet(false)
                isConnected.set(false)
                _outboundQueueBytes.value = 0L

                val httpCode = response?.code
                val responseBody = runCatching { response?.body?.string() }.getOrNull()
                val combinedMessage = buildString {
                    if (!responseBody.isNullOrBlank()) append(responseBody).append(" ")
                    if (!t.message.isNullOrBlank()) append(t.message)
                }.trim()

                // If the session was already established (wasSetup == true) and a transient transport IOException
                // occurred without an explicit HTTP rejection code, emit Disconnected(wasClean = false)
                // so SessionManager can perform automatic exponential-backoff reconnection.
                if (wasSetup && httpCode == null && t is IOException) {
                    _events.tryEmit(
                        GeminiServerEvent.Disconnected(
                            reason = combinedMessage.ifBlank { "Network transport interrupted" },
                            wasClean = false
                        )
                    )
                    return
                }

                val mappedError = ScreenSightError.fromHttpOrWebSocketError(
                    code = httpCode,
                    message = combinedMessage,
                    selectedModel = activeModelId
                )
                handleError(mappedError)
            }
        })

        activeWebSocket = ws

        setupTimeoutJob?.cancel()
        setupTimeoutJob = scope.launch {
            val completed = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                while (!isSetupCompleted.get() && activeWebSocket === ws) {
                    kotlinx.coroutines.delay(100)
                }
                isSetupCompleted.get()
            }
            if (completed != true && activeWebSocket === ws && !isSetupCompleted.get()) {
                handleError(ScreenSightError.ConnectionTimeout)
                disconnectInternal(notifyDisconnected = false)
            }
        }
    }

    fun sendSetup(
        webSocket: WebSocket? = activeWebSocket,
        modelId: String,
        voiceName: String,
        responseModalities: List<String>,
        systemInstruction: String,
        preferredLanguage: String
    ): Boolean {
        val ws = webSocket ?: return false
        val payload = GeminiLiveConfigBuilder.buildSetupMessageJson(
            modelId = modelId,
            voiceName = voiceName,
            responseModalities = responseModalities,
            systemInstructionText = systemInstruction,
            preferredLanguage = preferredLanguage
        )
        return ws.send(payload)
    }

    /**
     * Sends a compressed JPEG screen frame.
     * Drops the frame if the WebSocket outbound buffer is congested (backpressure handling, Section 13).
     */
    fun sendVideoFrame(jpegBytes: ByteArray): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isConnected.get() || !isSetupCompleted.get()) return false

        val queuedBytes = ws.queueSize()
        _outboundQueueBytes.value = queuedBytes
        if (queuedBytes > MAX_VIDEO_BACKPRESSURE_QUEUE_BYTES) {
            return false
        }

        val base64Jpeg = encodeBase64NoWrap(jpegBytes)
        val jsonPayload = GeminiLiveConfigBuilder.buildVideoFrameMessageJson(base64Jpeg)
        val sent = ws.send(jsonPayload)
        _outboundQueueBytes.value = ws.queueSize()
        return sent
    }

    /**
     * Streams a 16-bit PCM little-endian microphone audio chunk.
     */
    fun sendAudioChunk(pcmBytes: ByteArray, sampleRateHz: Int = 16000): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isConnected.get() || !isSetupCompleted.get()) return false

        val queuedBytes = ws.queueSize()
        _outboundQueueBytes.value = queuedBytes
        if (queuedBytes > MAX_AUDIO_BACKPRESSURE_QUEUE_BYTES) {
            return false
        }

        val base64Audio = encodeBase64NoWrap(pcmBytes)
        val jsonPayload = GeminiLiveConfigBuilder.buildAudioChunkMessageJson(base64Audio, sampleRateHz)
        val sent = ws.send(jsonPayload)
        _outboundQueueBytes.value = ws.queueSize()
        return sent
    }

    /**
     * Signals to Gemini Live that the microphone audio stream was paused/muted.
     */
    fun sendAudioStreamEnd(): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isConnected.get() || !isSetupCompleted.get()) return false
        return ws.send(GeminiLiveConfigBuilder.buildAudioStreamEndMessageJson())
    }

    /**
     * Sends an explicit user text turn during the live session.
     */
    fun sendTextMessage(text: String): Boolean {
        val ws = activeWebSocket ?: return false
        if (!isConnected.get() || !isSetupCompleted.get() || text.isBlank()) return false

        val jsonPayload = GeminiLiveConfigBuilder.buildTextMessageJson(text)
        return ws.send(jsonPayload)
    }

    /**
     * Parses incoming WebSocket JSON frames from Gemini Live API.
     */
    fun receiveMessages(rawJson: String) {
        if (rawJson.isBlank()) return
        try {
            val serverMessage = GeminiLiveJson.json.decodeFromString<BidiServerMessage>(rawJson)

            if (serverMessage.error != null) {
                val err = ScreenSightError.fromHttpOrWebSocketError(
                    code = serverMessage.error.code,
                    message = serverMessage.error.message ?: serverMessage.error.status,
                    selectedModel = activeModelId
                )
                handleError(err)
                return
            }

            if (serverMessage.setupComplete != null) {
                setupTimeoutJob?.cancel()
                isSetupCompleted.set(true)
                _events.tryEmit(GeminiServerEvent.SetupComplete)
            }

            if (serverMessage.goAway != null) {
                _events.tryEmit(
                    GeminiServerEvent.SessionGoAway(serverMessage.goAway.timeLeft ?: "60s")
                )
            }

            serverMessage.serverContent?.let { content ->
                handleServerContent(content)
            }
        } catch (_: Exception) {
            runCatching {
                val jsonObj = GeminiLiveJson.json.parseToJsonElement(rawJson) as? JsonObject
                val errorObj = jsonObj?.get("error")?.jsonObject
                if (errorObj != null) {
                    val code = errorObj["code"]?.jsonPrimitive?.intOrNull
                    val msg = errorObj["message"]?.jsonPrimitive?.content
                    handleError(
                        ScreenSightError.fromHttpOrWebSocketError(code, msg, activeModelId)
                    )
                }
            }
        }
    }

    fun handleServerContent(content: BidiServerContent) {
        if (content.interrupted == true) {
            handleInterrupted()
        }

        // Preserve leading/trailing spaces in word-by-word transcription chunks
        content.inputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { userSpeechText ->
            _events.tryEmit(GeminiServerEvent.TextDelta(text = userSpeechText, isUserTranscript = true))
        }

        val hasOutputTranscription = !content.outputTranscription?.text.isNullOrEmpty()
        content.outputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { aiSpeechText ->
            _events.tryEmit(GeminiServerEvent.TextDelta(text = aiSpeechText, isUserTranscript = false))
        }

        content.modelTurn?.parts?.forEach { part ->
            // Ignore internal reasoning `thought == true` parts when building the user-facing spoken transcript
            if (part.thought != true && !hasOutputTranscription) {
                part.text?.takeIf { it.isNotEmpty() }?.let { textChunk ->
                    _events.tryEmit(GeminiServerEvent.TextDelta(text = textChunk, isUserTranscript = false))
                }
            }

            part.inlineData?.let { inline ->
                if (inline.mimeType.startsWith("audio/pcm", ignoreCase = true) && inline.data.isNotBlank()) {
                    handleAudioResponse(inline.data)
                }
            }
        }

        if (content.turnComplete == true || content.generationComplete == true) {
            handleTurnComplete()
        }
    }

    fun handleAudioResponse(base64PcmData: String) {
        try {
            val pcmBytes = decodeBase64(base64PcmData)
            if (pcmBytes.isNotEmpty()) {
                _events.tryEmit(GeminiServerEvent.AudioChunk(pcmBytes))
            }
        } catch (_: IllegalArgumentException) {
            // Ignore malformed base64 chunk
        }
    }

    fun handleTurnComplete() {
        _events.tryEmit(GeminiServerEvent.TurnComplete)
    }

    fun handleInterrupted() {
        _events.tryEmit(GeminiServerEvent.Interrupted)
    }

    fun handleError(error: ScreenSightError) {
        _events.tryEmit(GeminiServerEvent.Error(error))
    }

    @Synchronized
    fun disconnect() {
        disconnectInternal(notifyDisconnected = true)
    }

    @Synchronized
    private fun disconnectInternal(notifyDisconnected: Boolean) {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = null
        isSetupCompleted.set(false)
        isConnected.set(false)
        _outboundQueueBytes.value = 0L
        val socketToClose = activeWebSocket
        activeWebSocket = null
        socketToClose?.let { ws ->
            runCatching { ws.close(1000, "Session ended") }
            runCatching { ws.cancel() }
        }
        if (notifyDisconnected) {
            _events.tryEmit(GeminiServerEvent.Disconnected(reason = "User disconnected", wasClean = true))
        }
    }

    fun isSessionActive(): Boolean = isConnected.get() && isSetupCompleted.get()

    companion object {
        private const val SETUP_TIMEOUT_MS = 15_000L
        private const val MAX_VIDEO_BACKPRESSURE_QUEUE_BYTES = 256 * 1024L // 256 KB
        private const val MAX_AUDIO_BACKPRESSURE_QUEUE_BYTES = 512 * 1024L // 512 KB

        fun encodeBase64NoWrap(bytes: ByteArray): String {
            return try {
                java.util.Base64.getEncoder().encodeToString(bytes)
            } catch (_: Throwable) {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }

        fun decodeBase64(encoded: String): ByteArray {
            return try {
                java.util.Base64.getMimeDecoder().decode(encoded)
            } catch (_: Throwable) {
                Base64.decode(encoded, Base64.DEFAULT)
            }
        }

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
