package com.example.data.ai

import com.example.agent.AgentTaskLogger
import com.example.data.remote.BidiFunctionDeclaration
import com.example.data.remote.BidiSchema
import com.example.data.remote.GeminiLiveConfigBuilder
import com.example.data.remote.GeminiLiveJson
import com.example.domain.model.ScreenSightError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DiscoveredModelCatalog(
    val liveModels: List<String> = emptyList(),
    val gemmaModels: List<String> = emptyList(),
    val geminiTextModels: List<String> = emptyList()
)

/**
 * Authoritative REST client for Google's Gemini Developer API (`v1beta`),
 * supporting both Gemma 4 (`models/gemma-4-31b-it`, `models/gemma-4-26b-a4b-it`)
 * and Gemini text fallback models (`models/gemini-2.5-flash`) using the SAME
 * existing encrypted Google AI Studio API key via the `x-goog-api-key` HTTP header.
 *
 * Security & Reliability Guarantees:
 * - Passes the API key in the `x-goog-api-key` header (never in URL query strings for generation).
 * - Redacts any accidental key echo from error messages or diagnostics.
 * - Supports cooperative coroutine cancellation (`call.cancel()`).
 * - Enforces explicit request timeouts and bounded exponential backoff on HTTP 429 / 503.
 * - Filters out `thought == true` parts so internal thinking traces never corrupt JSON or replies.
 */
class GoogleGenAiRestClient(
    private val okHttpClient: OkHttpClient = defaultRestOkHttpClient(),
    private val baseUrl: String = GeminiLiveConfigBuilder.GEMINI_MODELS_REST_BASE_URL,
    private val maxTransientRetries: Int = 2,
    private val retryDelayProvider: (Int) -> Long = { attempt -> 600L * (1 shl (attempt - 1)) }
) {
    /**
     * Queries `GET /v1beta/models` and separates discovered models into Live (`bidiGenerateContent`),
     * Gemma (`generateContent`), and Gemini Text (`generateContent`).
     */
    suspend fun fetchModelCatalog(apiKey: String): Result<DiscoveredModelCatalog> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Missing API key"))
        }
        val url = "$baseUrl/models?pageSize=200"
        val request = Request.Builder()
            .url(url)
            .header(API_KEY_HEADER, cleanKey)
            .get()
            .build()

        runCatching {
            val (code, body) = executeCancellable(request, cleanKey, timeoutMs = 15_000L)
            if (code !in 200..299) {
                throw RestHttpException(code, sanitizeErrorBody(body, cleanKey))
            }
            val root = GeminiLiveJson.json.parseToJsonElement(body).jsonObject
            val modelsArray = root["models"]?.jsonArray ?: emptyList()

            val live = mutableListOf<String>()
            val gemma = mutableListOf<String>()
            val geminiText = mutableListOf<String>()

            for (elem in modelsArray) {
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val methods = obj["supportedGenerationMethods"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                if (methods.any { it.equals("bidiGenerateContent", ignoreCase = true) }) {
                    live.add(name)
                }
                if (methods.any { it.equals("generateContent", ignoreCase = true) }) {
                    val lower = name.lowercase()
                    when {
                        lower.contains("gemma") -> gemma.add(name)
                        lower.contains("gemini") && !lower.contains("embedding") -> geminiText.add(name)
                    }
                }
            }
            DiscoveredModelCatalog(
                liveModels = live.distinct(),
                gemmaModels = gemma.distinct(),
                geminiTextModels = geminiText.distinct()
            )
        }
    }

    /**
     * Performs a real `GET /v1beta/models/{modelId}` metadata probe to verify that the
     * configured model exists on this API key and supports `generateContent`.
     */
    suspend fun checkModelAvailability(
        apiKey: String,
        modelDescriptor: AIModelDescriptor
    ): ModelAvailabilityReport = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext ModelAvailabilityReport(
                modelId = modelDescriptor.fullResourceName,
                providerId = modelDescriptor.providerId,
                isAvailable = false,
                statusMessage = "No API key configured.",
                error = ScreenSightError.MissingApiKey
            )
        }

        val url = "$baseUrl/${modelDescriptor.fullResourceName}"
        val request = Request.Builder()
            .url(url)
            .header(API_KEY_HEADER, cleanKey)
            .get()
            .build()

        try {
            val (code, body) = executeCancellable(request, cleanKey, timeoutMs = 15_000L)
            if (code !in 200..299) {
                val mapped = mapRestError(code, body, modelDescriptor.fullResourceName, cleanKey)
                return@withContext ModelAvailabilityReport(
                    modelId = modelDescriptor.fullResourceName,
                    providerId = modelDescriptor.providerId,
                    isAvailable = false,
                    statusMessage = mapped.userMessage,
                    error = mapped
                )
            }
            val obj = GeminiLiveJson.json.parseToJsonElement(body).jsonObject
            val methods = obj["supportedGenerationMethods"]?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            val inputLimit = obj["inputTokenLimit"]?.jsonPrimitive?.intOrNull
            val outputLimit = obj["outputTokenLimit"]?.jsonPrimitive?.intOrNull
            val supportsGenerate = methods.any { it.equals("generateContent", ignoreCase = true) }

            if (!supportsGenerate) {
                val err = ScreenSightError.UnsupportedModel(modelDescriptor.fullResourceName)
                return@withContext ModelAvailabilityReport(
                    modelId = modelDescriptor.fullResourceName,
                    providerId = modelDescriptor.providerId,
                    isAvailable = false,
                    supportedMethods = methods,
                    inputTokenLimit = inputLimit,
                    outputTokenLimit = outputLimit,
                    statusMessage = "Model ${modelDescriptor.fullResourceName} does not list generateContent in supportedGenerationMethods.",
                    error = err
                )
            }

            val limitsNote = if (inputLimit != null && outputLimit != null) {
                " (${inputLimit / 1024}K in / ${outputLimit / 1024}K out)"
            } else {
                ""
            }
            ModelAvailabilityReport(
                modelId = modelDescriptor.fullResourceName,
                providerId = modelDescriptor.providerId,
                isAvailable = true,
                supportedMethods = methods,
                inputTokenLimit = inputLimit,
                outputTokenLimit = outputLimit,
                statusMessage = "${modelDescriptor.displayName} verified on Google Gemini API (generateContent supported$limitsNote)."
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val mapped = mapRestError(null, e.message, modelDescriptor.fullResourceName, cleanKey)
            ModelAvailabilityReport(
                modelId = modelDescriptor.fullResourceName,
                providerId = modelDescriptor.providerId,
                isAvailable = false,
                statusMessage = mapped.userMessage,
                error = mapped
            )
        }
    }

    /**
     * Calls `POST /v1beta/models/{modelId}:generateContent` with cooperative cancellation,
     * timeout enforcement, bounded transient retry (HTTP 429 / 503), native tool declarations,
     * and structured JSON / thought-part parsing.
     */
    suspend fun generateContent(
        apiKey: String,
        modelDescriptor: AIModelDescriptor,
        request: AIReasoningRequest
    ): AIReasoningResponse = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext AIReasoningResponse(
                success = false,
                modelId = modelDescriptor.fullResourceName,
                providerId = modelDescriptor.providerId,
                taskStage = request.taskStage,
                error = ScreenSightError.MissingApiKey
            )
        }

        val payloadJson = buildGenerateContentPayload(modelDescriptor, request)
        val url = "$baseUrl/${modelDescriptor.fullResourceName}:generateContent"
        val httpRequest = Request.Builder()
            .url(url)
            .header(API_KEY_HEADER, cleanKey)
            .header("Content-Type", "application/json")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var lastError: ScreenSightError? = null
        var lastHttpCode: Int? = null
        val totalAttempts = 1 + maxTransientRetries.coerceIn(0, 3)

        for (attempt in 1..totalAttempts) {
            try {
                val timed = withTimeoutOrNull(request.timeoutMs.coerceIn(2_000L, 120_000L)) {
                    executeCancellable(httpRequest, cleanKey, request.timeoutMs)
                }
                if (timed == null) {
                    lastError = ScreenSightError.ConnectionTimeout
                    lastHttpCode = null
                    break
                }

                val (code, body) = timed
                lastHttpCode = code
                if (code in 200..299) {
                    val parsed = parseGenerateContentResponse(
                        rawBody = body,
                        modelId = modelDescriptor.fullResourceName,
                        providerId = modelDescriptor.providerId,
                        taskStage = request.taskStage,
                        latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
                    )
                    return@withContext parsed
                }

                val mapped = mapRestError(code, body, modelDescriptor.fullResourceName, cleanKey)
                lastError = mapped
                val isRetryable = code == 429 || code == 503 || code == 502 || code == 504
                if (isRetryable && attempt < totalAttempts) {
                    delay(retryDelayProvider(attempt))
                    continue
                }
                break
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                lastError = mapRestError(null, e.message, modelDescriptor.fullResourceName, cleanKey)
                val isNetworkRetryable = e is IOException
                if (isNetworkRetryable && attempt < totalAttempts) {
                    delay(retryDelayProvider(attempt))
                    continue
                }
                break
            }
        }

        AIReasoningResponse(
            success = false,
            modelId = modelDescriptor.fullResourceName,
            providerId = modelDescriptor.providerId,
            taskStage = request.taskStage,
            latencyMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L),
            error = lastError ?: ScreenSightError.ServerUnavailable,
            httpStatusCode = lastHttpCode
        )
    }

    /**
     * Streams Server-Sent Events from `POST /v1beta/models/{modelId}:streamGenerateContent?alt=sse`.
     * Cancels the underlying OkHttp call immediately when the downstream Flow collector cancels.
     */
    fun streamGenerateContent(
        apiKey: String,
        modelDescriptor: AIModelDescriptor,
        request: AIReasoningRequest
    ): Flow<AIStreamChunk> = callbackFlow {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            trySend(AIStreamChunk(isDone = true, error = ScreenSightError.MissingApiKey))
            close()
            return@callbackFlow
        }

        val payloadJson = buildGenerateContentPayload(modelDescriptor, request)
        val url = "$baseUrl/${modelDescriptor.fullResourceName}:streamGenerateContent?alt=sse"
        val httpRequest = Request.Builder()
            .url(url)
            .header(API_KEY_HEADER, cleanKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payloadJson.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = okHttpClient.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    trySend(
                        AIStreamChunk(
                            isDone = true,
                            error = mapRestError(null, e.message, modelDescriptor.fullResourceName, cleanKey)
                        )
                    )
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        val errBody = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                        trySend(
                            AIStreamChunk(
                                isDone = true,
                                error = mapRestError(res.code, errBody, modelDescriptor.fullResourceName, cleanKey)
                            )
                        )
                        close()
                        return
                    }
                    val stream = res.body?.charStream()
                    if (stream == null) {
                        trySend(AIStreamChunk(isDone = true))
                        close()
                        return
                    }
                    try {
                        BufferedReader(stream).use { reader ->
                            val eventBuffer = StringBuilder()
                            var line: String? = reader.readLine()
                            while (line != null && !call.isCanceled()) {
                                when {
                                    line.startsWith("data:") -> {
                                        val payload = line.removePrefix("data:").trim()
                                        if (payload == "[DONE]") {
                                            break
                                        }
                                        if (payload.isNotEmpty()) {
                                            eventBuffer.append(payload)
                                        }
                                    }
                                    line.isBlank() && eventBuffer.isNotEmpty() -> {
                                        emitSseJsonChunk(eventBuffer.toString())?.let { trySend(it) }
                                        eventBuffer.clear()
                                    }
                                }
                                line = reader.readLine()
                            }
                            if (eventBuffer.isNotEmpty()) {
                                emitSseJsonChunk(eventBuffer.toString())?.let { trySend(it) }
                            }
                        }
                        trySend(AIStreamChunk(isDone = true))
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            trySend(
                                AIStreamChunk(
                                    isDone = true,
                                    error = mapRestError(null, e.message, modelDescriptor.fullResourceName, cleanKey)
                                )
                            )
                        }
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    private fun emitSseJsonChunk(rawJson: String): AIStreamChunk? {
        return runCatching {
            val root = GeminiLiveJson.json.parseToJsonElement(rawJson).jsonObject
            val (text, calls, usage) = extractResponseFields(root)
            if (text.isEmpty() && calls.isEmpty() && usage == null) {
                null
            } else {
                AIStreamChunk(
                    deltaText = text,
                    proposedToolCalls = calls,
                    tokenUsage = usage,
                    isDone = false
                )
            }
        }.getOrNull()
    }

    internal fun buildGenerateContentPayload(
        modelDescriptor: AIModelDescriptor,
        request: AIReasoningRequest
    ): String {
        val caps = modelDescriptor.capabilities
        val effectiveUserPrompt = if (!caps.supportsSystemInstruction && request.systemInstruction.isNotBlank()) {
            "[INSTRUCTIONS]\n${request.systemInstruction}\n[/INSTRUCTIONS]\n\n${request.userPrompt}"
        } else {
            request.userPrompt
        }

        val root = buildJsonObject {
            if (caps.supportsSystemInstruction && request.systemInstruction.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", request.systemInstruction) })
                    })
                })
            }

            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", effectiveUserPrompt) })
                    })
                })
            })

            if (request.tools.isNotEmpty() && caps.supportsNativeToolCalling) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            request.tools.forEach { decl ->
                                add(encodeFunctionDeclaration(decl))
                            }
                        })
                    })
                })
                put("toolConfig", buildJsonObject {
                    put("functionCallingConfig", buildJsonObject {
                        put("mode", "AUTO")
                    })
                })
            }

            put("generationConfig", buildJsonObject {
                put("temperature", request.temperature.coerceIn(0.0, 2.0))
                put("maxOutputTokens", request.maxOutputTokens.coerceIn(64, caps.maxOutputTokens))
                if (request.expectJsonOutput && request.tools.isEmpty() && caps.supportsStructuredJson) {
                    put("responseMimeType", "application/json")
                }
            })
        }

        return GeminiLiveJson.json.encodeToString(JsonObject.serializer(), root)
    }

    private fun encodeFunctionDeclaration(decl: BidiFunctionDeclaration): JsonObject {
        return buildJsonObject {
            put("name", decl.name)
            put("description", decl.description)
            decl.parameters?.let { schema ->
                put("parameters", encodeBidiSchema(schema))
            }
        }
    }

    private fun encodeBidiSchema(schema: BidiSchema): JsonObject {
        return buildJsonObject {
            put("type", schema.type)
            schema.description?.let { put("description", it) }
            schema.enum?.takeIf { it.isNotEmpty() }?.let { enumVals ->
                put("enum", buildJsonArray { enumVals.forEach { add(JsonPrimitive(it)) } })
            }
            schema.properties?.takeIf { it.isNotEmpty() }?.let { props ->
                put("properties", buildJsonObject {
                    props.forEach { (k, v) -> put(k, encodeBidiSchema(v)) }
                })
            }
            schema.required?.takeIf { it.isNotEmpty() }?.let { req ->
                put("required", buildJsonArray { req.forEach { add(JsonPrimitive(it)) } })
            }
        }
    }

    internal fun parseGenerateContentResponse(
        rawBody: String,
        modelId: String,
        providerId: AIProviderId,
        taskStage: AIReasoningTaskStage,
        latencyMs: Long
    ): AIReasoningResponse {
        return try {
            val root = GeminiLiveJson.json.parseToJsonElement(rawBody).jsonObject
            val (text, calls, usage) = extractResponseFields(root)
            AIReasoningResponse(
                success = true,
                modelId = modelId,
                providerId = providerId,
                taskStage = taskStage,
                text = text.trim(),
                proposedToolCalls = calls,
                tokenUsage = usage ?: AITokenUsage(),
                latencyMs = latencyMs,
                httpStatusCode = 200
            )
        } catch (e: Exception) {
            AIReasoningResponse(
                success = false,
                modelId = modelId,
                providerId = providerId,
                taskStage = taskStage,
                latencyMs = latencyMs,
                error = ScreenSightError.WebSocketFailure(
                    "Malformed JSON response from reasoning model: ${e.message ?: "parse error"}"
                )
            )
        }
    }

    private fun extractResponseFields(
        root: JsonObject
    ): Triple<String, List<AIProposedToolCall>, AITokenUsage?> {
        val textBuilder = StringBuilder()
        val proposedCalls = mutableListOf<AIProposedToolCall>()

        val candidates = root["candidates"]?.jsonArray
        val firstCandidate = candidates?.firstOrNull()?.jsonObject
        val parts = firstCandidate?.get("content")?.jsonObject?.get("parts")?.jsonArray

        parts?.forEach { partElem ->
            val part = partElem.jsonObject
            // Filter out internal reasoning/thought blocks so they never corrupt structured JSON or UI output
            val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
            if (isThought) return@forEach

            part["text"]?.jsonPrimitive?.contentOrNull?.let { t ->
                if (t.isNotEmpty()) {
                    textBuilder.append(t)
                }
            }

            part["functionCall"]?.jsonObject?.let { fc ->
                val fnName = fc["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (fnName.isNotEmpty()) {
                    val callId = fc["id"]?.jsonPrimitive?.contentOrNull
                        ?: "gemma_fc_${UUID.randomUUID().toString().take(8)}"
                    val argsMap = flattenJsonArgs(fc["args"]?.jsonObject)
                    proposedCalls.add(
                        AIProposedToolCall(
                            callId = callId,
                            toolName = fnName,
                            arguments = argsMap,
                            fromNativeFunctionCall = true
                        )
                    )
                }
            }
        }

        val usageObj = root["usageMetadata"]?.jsonObject
        val usage = usageObj?.let { u ->
            AITokenUsage(
                promptTokens = u["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                candidatesTokens = u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                thoughtTokens = u["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                totalTokens = u["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }

        return Triple(textBuilder.toString(), proposedCalls, usage)
    }

    private fun flattenJsonArgs(argsObj: JsonObject?): Map<String, String> {
        if (argsObj == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in argsObj) {
            out[k] = jsonElementToSafeString(v)
        }
        return out
    }

    private fun jsonElementToSafeString(elem: JsonElement): String {
        return when (elem) {
            is JsonPrimitive -> elem.contentOrNull ?: elem.toString()
            is JsonArray -> elem.joinToString(", ") { jsonElementToSafeString(it) }
            is JsonObject -> elem.toString()
        }
    }

    private suspend fun executeCancellable(
        request: Request,
        secretKeyToScrub: String,
        timeoutMs: Long
    ): Pair<Int, String> = suspendCancellableCoroutine { cont ->
        val clientForCall = if (timeoutMs != DEFAULT_TIMEOUT_MS) {
            okHttpClient.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            okHttpClient
        }
        val call = clientForCall.newCall(request)
        cont.invokeOnCancellation {
            call.cancel()
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    val safeMsg = sanitizeErrorBody(e.message.orEmpty(), secretKeyToScrub)
                    cont.resumeWithException(IOException(safeMsg, e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val code = res.code
                    val bodyStr = runCatching { res.body?.string().orEmpty() }.getOrDefault("")
                    if (cont.isActive) {
                        cont.resume(code to bodyStr)
                    }
                }
            }
        })
    }

    private fun mapRestError(
        httpCode: Int?,
        rawMessage: String?,
        modelId: String,
        apiKey: String
    ): ScreenSightError {
        val scrubbed = sanitizeErrorBody(rawMessage.orEmpty(), apiKey)
        return ScreenSightError.fromHttpOrWebSocketError(
            code = httpCode,
            message = scrubbed,
            selectedModel = modelId
        )
    }

    internal fun sanitizeErrorBody(raw: String, apiKey: String): String {
        var safe = AgentTaskLogger.sanitizeForLog(raw)
        if (apiKey.length >= 8) {
            safe = safe.replace(apiKey, "[REDACTED_API_KEY]")
        }
        return safe.take(500)
    }

    class RestHttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

    companion object {
        const val API_KEY_HEADER = "x-goog-api-key"
        private const val DEFAULT_TIMEOUT_MS = 25_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultRestOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
