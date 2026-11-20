package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.Image
import com.example.agent.AgentAccessibilityService
import com.example.agent.AgentCoordinator
import com.example.agent.AgentMode
import com.example.agent.AgentToolsSchema
import com.example.agent.TrustedContactsRepository
import com.example.agent.VoiceConfirmationParser
import com.example.data.local.ApiKeyStorage
import com.example.data.local.SettingsRepository
import com.example.data.remote.GeminiServerEvent
import com.example.data.repository.GeminiLiveRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.ConnectionQuality
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.LiveSessionState
import com.example.domain.model.MessageSender
import com.example.domain.model.ScreenSightError
import com.example.domain.model.TranscriptMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Central Session & In-Memory Conversation Manager (Sections 4, 11, 12, 13).
 * Coordinates:
 * - Gemini Live WebSocket lifecycle & automatic exponential-backoff reconnection
 * - Real-time AudioCaptureManager (16 kHz PCM microphone input with echo-safe barge-in)
 * - Real-time AudioPlaybackManager (24 kHz PCM AI speech output)
 * - ScreenCaptureService & FrameProcessor (1 FPS JPEG screen sharing with zero-Bitmap static skip)
 * - In-memory session transcript (no cloud database, cleared on demand or session reset)
 */
class SessionManager(
    private val appContext: Context,
    private val geminiRepository: GeminiLiveRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val settingsRepository: SettingsRepository,
    val audioCaptureManager: AudioCaptureManager,
    val audioPlaybackManager: AudioPlaybackManager,
    val frameProcessor: FrameProcessor,
    val agentCoordinator: AgentCoordinator? = null,
    private val trustedContactsRepository: TrustedContactsRepository? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessionState = MutableStateFlow(LiveSessionState.IDLE)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private val _isSharingScreen = MutableStateFlow(false)
    val isSharingScreen: StateFlow<Boolean> = _isSharingScreen.asStateFlow()

    private val _isScreenCapturePaused = MutableStateFlow(false)
    val isScreenCapturePaused: StateFlow<Boolean> = _isScreenCapturePaused.asStateFlow()

    private val _framesSentCount = MutableStateFlow(0)
    val framesSentCount: StateFlow<Int> = _framesSentCount.asStateFlow()

    private val _framesSkippedCount = MutableStateFlow(0)
    val framesSkippedCount: StateFlow<Int> = _framesSkippedCount.asStateFlow()

    private val _lastFrameDimensions = MutableStateFlow("None")
    val lastFrameDimensions: StateFlow<String> = _lastFrameDimensions.asStateFlow()

    private val _connectionQuality = MutableStateFlow(ConnectionQuality.DISCONNECTED)
    val connectionQuality: StateFlow<ConnectionQuality> = _connectionQuality.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptMessage>>(emptyList())
    val transcript: StateFlow<List<TranscriptMessage>> = _transcript.asStateFlow()

    private val _activeError = MutableStateFlow<ScreenSightError?>(null)
    val activeError: StateFlow<ScreenSightError?> = _activeError.asStateFlow()

    private val _sessionStartTimeMillis = MutableStateFlow<Long?>(null)
    val sessionStartTimeMillis: StateFlow<Long?> = _sessionStartTimeMillis.asStateFlow()

    @Volatile
    private var latestSettings: AppSettings = AppSettings()
    private var reconnectAttempts = 0
    private var userRequestedStop = false
    private var sessionJob: Job? = null
    private var reconnectJob: Job? = null
    private val pendingUserVoiceBuffer = StringBuilder()
    private val recentAiOutputBuffer = StringBuilder()
    @Volatile
    private var lastAiOutputAtMs: Long = 0L

    init {
        scope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                latestSettings = settings
                frameProcessor.updateConfiguration(
                    maxDimension = settings.maxFrameDimensionPx,
                    quality = settings.jpegQuality
                )
            }
        }

        scope.launch {
            geminiRepository.outboundQueueBytes.collectLatest { queuedBytes ->
                if (!geminiRepository.isSessionActive()) {
                    _connectionQuality.value = ConnectionQuality.DISCONNECTED
                } else if (queuedBytes > 180 * 1024L) {
                    _connectionQuality.value = ConnectionQuality.SLOW
                } else if (queuedBytes > 48 * 1024L) {
                    _connectionQuality.value = ConnectionQuality.MODERATE
                } else {
                    _connectionQuality.value = ConnectionQuality.EXCELLENT
                }
            }
        }

        scope.launch {
            geminiRepository.serverEvents.collect { event ->
                handleGeminiEvent(event)
            }
        }

        scope.launch {
            audioPlaybackManager.isAiSpeaking.collectLatest { aiSpeaking ->
                val current = _sessionState.value
                if (aiSpeaking && current.isActive) {
                    _sessionState.value = LiveSessionState.SPEAKING
                } else if (!aiSpeaking && current == LiveSessionState.SPEAKING) {
                    _sessionState.value = if (_isSharingScreen.value) {
                        LiveSessionState.SHARING_SCREEN
                    } else {
                        LiveSessionState.LISTENING
                    }
                }
            }
        }
    }

    fun currentFrameRateFps(): Int = latestSettings.frameRateFps
    fun currentMaxFrameDimensionPx(): Int = latestSettings.maxFrameDimensionPx
    fun isLiveSocketReady(): Boolean = geminiRepository.isSessionActive()

    /**
     * Starts a live Gemini session using the user's locally stored API key.
     */
    fun startSession() {
        if (_sessionState.value == LiveSessionState.CONNECTING || geminiRepository.isSessionActive()) return

        reconnectJob?.cancel()
        sessionJob?.cancel()
        sessionJob = scope.launch {
            _activeError.value = null
            userRequestedStop = false
            reconnectAttempts = 0

            val apiKey = apiKeyStorage.getApiKey().trim()
            if (apiKey.isEmpty()) {
                _activeError.value = ScreenSightError.MissingApiKey
                _sessionState.value = LiveSessionState.ERROR
                return@launch
            }

            val settings = settingsRepository.settingsFlow.first()
            latestSettings = settings
            val model = GeminiLiveModel.findByIdOrCustom(settings.selectedModelId)

            _sessionState.value = LiveSessionState.CONNECTING
            appendSystemMessage("Connecting to ${model.displayName} (${model.fullResourceName})...")

            audioPlaybackManager.initialize(
                sampleRateHz = model.outputSampleRateHz,
                onError = { err ->
                    _activeError.value = err
                }
            )

            val (agentInstruction, agentTools) = buildLiveAgentPayload(settings)
            val effectiveLanguage = if (settings.agentMode == AgentMode.FAMILY_EASY_MODE &&
                settings.preferredLanguage.startsWith("Auto")
            ) {
                "Bengali (বাংলা) — also understand English & regional Bangla naturally"
            } else {
                settings.preferredLanguage
            }

            geminiRepository.startLiveConnection(
                apiKey = apiKey,
                modelId = model.fullResourceName,
                voiceName = settings.selectedVoiceName,
                responseModalities = settings.responseModalities,
                systemInstruction = agentInstruction,
                preferredLanguage = effectiveLanguage,
                tools = agentTools
            )
        }
    }

    private suspend fun buildLiveAgentPayload(settings: AppSettings): Pair<String, List<com.example.data.remote.BidiTool>?> {
        val coordinator = agentCoordinator
        if (!settings.agentToolsEnabled || coordinator == null) {
            return settings.customSystemInstruction to null
        }
        val trustedNames = runCatching {
            trustedContactsRepository?.trustedContactsFlow?.first().orEmpty().map {
                if (it.nickname.isNotBlank()) "${it.name} (${it.nickname})" else it.name
            }
        }.getOrDefault(emptyList())

        val memoryContext = if (settings.memoryEnabled) {
            runCatching { coordinator.retrieveSessionBaselineMemoryContext() }.getOrDefault("")
        } else {
            ""
        }
        val skillSummaries = runCatching { coordinator.getEnabledSkillSummaries() }
            .getOrDefault(emptyList())

        val instruction = AgentToolsSchema.buildAgentSystemInstruction(
            baseInstruction = settings.customSystemInstruction,
            agentMode = settings.agentMode,
            isAccessibilityConnected = AgentAccessibilityService.isConnected.value,
            trustedContactNames = trustedNames,
            relevantMemoryContext = memoryContext,
            enabledSkillSummaries = skillSummaries
        )
        return instruction to AgentToolsSchema.buildAgentTools(coordinator.toolRegistry)
    }

    private fun handleGeminiEvent(event: GeminiServerEvent) {
        when (event) {
            is GeminiServerEvent.SetupComplete -> {
                reconnectAttempts = 0
                if (_sessionStartTimeMillis.value == null) {
                    _sessionStartTimeMillis.value = System.currentTimeMillis()
                }
                _connectionQuality.value = ConnectionQuality.EXCELLENT
                _sessionState.value = if (_isSharingScreen.value) {
                    LiveSessionState.SHARING_SCREEN
                } else {
                    LiveSessionState.CONNECTED
                }
                appendSystemMessage("Live session connected. Speak anytime or share your screen.")
                startAudioCaptureIfPermitted()
            }

            is GeminiServerEvent.AudioChunk -> {
                agentCoordinator?.stopLocalVoiceFeedback()
                audioPlaybackManager.enqueueAudioChunk(event.pcm24kBytes)
            }

            is GeminiServerEvent.ToolCall -> {
                if (pendingUserVoiceBuffer.isNotBlank()) {
                    agentCoordinator?.onUserRequestReceived(pendingUserVoiceBuffer.toString())
                    pendingUserVoiceBuffer.clear()
                }
                if (_sessionState.value.isActive) {
                    _sessionState.value = LiveSessionState.THINKING
                }
                agentCoordinator?.onGeminiToolCalls(
                    functionCalls = event.functionCalls,
                    onSystemNote = { note -> appendSystemMessage(note) }
                )
            }

            is GeminiServerEvent.ToolCallCancelled -> {
                agentCoordinator?.onGeminiToolCallsCancelled(event.ids)
            }

            is GeminiServerEvent.TextDelta -> {
                if (event.isUserTranscript && event.text.isNotBlank()) {
                    val elapsedSinceAiMs = System.currentTimeMillis() - lastAiOutputAtMs
                    val recentAiText = recentAiOutputBuffer.toString()
                    if (isLikelySelfEchoTranscript(event.text, recentAiText, elapsedSinceAiMs)) {
                        return
                    }
                    pendingUserVoiceBuffer.append(event.text)
                    // Fast-path natural Bangla/English voice confirmation while WAITING_FOR_USER
                    if (agentCoordinator?.pendingConfirmation?.value != null) {
                        val candidate = pendingUserVoiceBuffer.toString().trim()
                        val intent = VoiceConfirmationParser.classify(candidate)
                        if (intent == VoiceConfirmationParser.ConfirmationIntent.APPROVE ||
                            intent == VoiceConfirmationParser.ConfirmationIntent.REJECT
                        ) {
                            agentCoordinator.onUserRequestReceived(candidate)
                            pendingUserVoiceBuffer.clear()
                        }
                    }
                } else if (!event.isUserTranscript && event.text.isNotBlank()) {
                    if (recentAiOutputBuffer.length > 600) {
                        recentAiOutputBuffer.delete(0, recentAiOutputBuffer.length - 300)
                    }
                    recentAiOutputBuffer.append(event.text)
                    lastAiOutputAtMs = System.currentTimeMillis()
                }
                appendOrUpdateStreamingTranscript(
                    sender = if (event.isUserTranscript) MessageSender.USER else MessageSender.GEMINI,
                    deltaText = event.text
                )
            }

            is GeminiServerEvent.TurnComplete -> {
                if (pendingUserVoiceBuffer.isNotBlank()) {
                    val utterance = pendingUserVoiceBuffer.toString()
                    val elapsedSinceAiMs = System.currentTimeMillis() - lastAiOutputAtMs
                    if (!isLikelySelfEchoTranscript(utterance, recentAiOutputBuffer.toString(), elapsedSinceAiMs)) {
                        agentCoordinator?.onUserRequestReceived(utterance)
                    }
                    pendingUserVoiceBuffer.clear()
                }
                finalizeStreamingTranscript()
                audioPlaybackManager.onTurnComplete()
                if (!audioPlaybackManager.isAiSpeaking.value && _sessionState.value.isActive) {
                    _sessionState.value = if (_isSharingScreen.value) {
                        LiveSessionState.SHARING_SCREEN
                    } else {
                        LiveSessionState.LISTENING
                    }
                }
            }

            is GeminiServerEvent.Interrupted -> {
                audioPlaybackManager.interruptPlayback()
                agentCoordinator?.onUserInterruption(preservePendingConfirmation = true)
                pendingUserVoiceBuffer.clear()
                finalizeStreamingTranscript()
                if (_sessionState.value.isActive) {
                    _sessionState.value = if (_isSharingScreen.value) {
                        LiveSessionState.SHARING_SCREEN
                    } else {
                        LiveSessionState.LISTENING
                    }
                }
            }

            is GeminiServerEvent.SessionGoAway -> {
                appendSystemMessage("Gemini session timeout approaching (${event.timeLeft} remaining).")
            }

            is GeminiServerEvent.Error -> {
                handleSessionError(event.error)
            }

            is GeminiServerEvent.Disconnected -> {
                if (!userRequestedStop && !event.wasClean) {
                    if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                        attemptReconnection()
                    } else {
                        handleSessionError(ScreenSightError.ConnectionLost)
                    }
                } else if (!userRequestedStop && _sessionState.value != LiveSessionState.ERROR) {
                    cleanupActiveStreams(stopService = true)
                    _sessionState.value = LiveSessionState.STOPPED
                    _connectionQuality.value = ConnectionQuality.DISCONNECTED
                }
            }
        }
    }

    private fun attemptReconnection() {
        reconnectAttempts++
        _sessionState.value = LiveSessionState.RECONNECTING
        appendSystemMessage("Connection interrupted. Reconnecting (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1500L * reconnectAttempts)
            if (userRequestedStop) return@launch
            val apiKey = apiKeyStorage.getApiKey().trim()
            if (apiKey.isEmpty()) {
                handleSessionError(ScreenSightError.MissingApiKey)
                return@launch
            }
            val model = GeminiLiveModel.findByIdOrCustom(latestSettings.selectedModelId)
            val (agentInstruction, agentTools) = buildLiveAgentPayload(latestSettings)
            geminiRepository.startLiveConnection(
                apiKey = apiKey,
                modelId = model.fullResourceName,
                voiceName = latestSettings.selectedVoiceName,
                responseModalities = latestSettings.responseModalities,
                systemInstruction = agentInstruction,
                preferredLanguage = latestSettings.preferredLanguage,
                tools = agentTools
            )
        }
    }

    fun startAudioCaptureIfPermitted() {
        if (!audioCaptureManager.hasRecordAudioPermission()) {
            // Do not set error while user is still answering initial permission prompt
            return
        }
        // Promote foreground microphone service only if we are not already sharing screen or awaiting screen consent
        if (!_isSharingScreen.value && _sessionState.value != LiveSessionState.REQUESTING_SCREEN_PERMISSION) {
            ScreenCaptureService.startVoiceSessionService(appContext)
        }
        val model = GeminiLiveModel.findByIdOrCustom(latestSettings.selectedModelId)
        audioCaptureManager.startRecording(
            sampleRateHz = model.inputSampleRateHz,
            isAiCurrentlySpeaking = {
                audioPlaybackManager.isEchoGuardActive.value ||
                    (agentCoordinator?.isLocalVoiceFeedbackSpeaking() == true)
            },
            aiOutputRmsProvider = { audioPlaybackManager.currentOutputRms.value },
            isHeadsetConnected = { audioPlaybackManager.isExternalAudioDeviceConnected() },
            isBargeInEnabled = { latestSettings.bargeInInterruptEnabled },
            onAudioChunk = { pcmChunk, rate ->
                if (geminiRepository.isSessionActive()) {
                    geminiRepository.sendAudioChunk(pcmChunk, rate)
                }
            },
            onUserVoiceDetected = { wasBargeInWhileAiSpeaking ->
                if (wasBargeInWhileAiSpeaking &&
                    latestSettings.bargeInInterruptEnabled &&
                    audioPlaybackManager.isAiSpeaking.value
                ) {
                    audioPlaybackManager.interruptPlayback()
                    agentCoordinator?.onUserInterruption(preservePendingConfirmation = true)
                }
                if (_sessionState.value.isActive &&
                    _sessionState.value != LiveSessionState.SHARING_SCREEN &&
                    !audioPlaybackManager.isAiSpeaking.value
                ) {
                    _sessionState.value = LiveSessionState.LISTENING
                }
            },
            onError = { err ->
                _activeError.value = err
            }
        )
        if (_sessionState.value == LiveSessionState.CONNECTED) {
            _sessionState.value = if (_isSharingScreen.value) {
                LiveSessionState.SHARING_SCREEN
            } else {
                LiveSessionState.LISTENING
            }
        }
    }

    /**
     * Transitions state to REQUESTING_SCREEN_PERMISSION when the system MediaProjection consent dialog is launched.
     */
    fun onRequestingScreenPermission() {
        if (_sessionState.value.isActive) {
            _sessionState.value = LiveSessionState.REQUESTING_SCREEN_PERMISSION
        }
    }

    /**
     * Starts Android MediaProjection foreground service after user grants consent.
     */
    fun onScreenPermissionResult(
        granted: Boolean,
        resultCode: Int,
        data: Intent?,
        autoMinimizeOnStart: Boolean = true
    ) {
        if (!granted || data == null) {
            _activeError.value = ScreenSightError.ScreenCapturePermissionDenied
            if (_sessionState.value == LiveSessionState.REQUESTING_SCREEN_PERMISSION) {
                _sessionState.value = if (geminiRepository.isSessionActive()) {
                    LiveSessionState.LISTENING
                } else {
                    LiveSessionState.IDLE
                }
            }
            return
        }

        if (!_sessionState.value.isActive) {
            startSession()
        }
        frameProcessor.resetDuplicateFilter()
        ScreenCaptureService.startScreenCaptureService(
            context = appContext,
            resultCode = resultCode,
            resultData = data,
            autoMinimizeToHome = autoMinimizeOnStart
        )
    }

    fun onScreenSharingStateChanged(isSharing: Boolean) {
        _isSharingScreen.value = isSharing
        if (!isSharing) {
            _isScreenCapturePaused.value = false
        }
        if (isSharing) {
            if (_sessionState.value.isActive && _sessionState.value != LiveSessionState.SPEAKING) {
                _sessionState.value = LiveSessionState.SHARING_SCREEN
            }
            appendSystemMessage("Screen sharing active (1 FPS optimized JPEG stream).")
        } else {
            if (_sessionState.value == LiveSessionState.SHARING_SCREEN) {
                _sessionState.value = if (geminiRepository.isSessionActive()) {
                    LiveSessionState.LISTENING
                } else {
                    LiveSessionState.STOPPED
                }
            }
        }
    }

    fun onScreenCapturePausedChanged(isPaused: Boolean) {
        _isScreenCapturePaused.value = isPaused
        if (_isSharingScreen.value) {
            appendSystemMessage(
                if (isPaused) "Screen sharing paused." else "Screen sharing resumed."
            )
        }
    }

    fun togglePauseScreenSharing() {
        if (!_isSharingScreen.value) return
        if (_isScreenCapturePaused.value) {
            ScreenCaptureService.resumeScreenCaptureService(appContext)
        } else {
            ScreenCaptureService.pauseScreenCaptureService(appContext)
        }
    }

    fun onScreenProjectionRevokedBySystem() {
        _isSharingScreen.value = false
        _isScreenCapturePaused.value = false
        _activeError.value = ScreenSightError.MediaProjectionRevoked
        appendSystemMessage("Screen sharing was stopped by Android.")
        if (_sessionState.value == LiveSessionState.SHARING_SCREEN) {
            _sessionState.value = if (geminiRepository.isSessionActive()) {
                LiveSessionState.LISTENING
            } else {
                LiveSessionState.STOPPED
            }
        }
    }

    fun onScreenCaptureError(error: ScreenSightError) {
        _isSharingScreen.value = false
        _isScreenCapturePaused.value = false
        _activeError.value = error
    }

    /**
     * Zero-Bitmap-on-duplicate screen capture path called by [ScreenCaptureService] at 1 FPS.
     */
    fun processAndSendScreenImage(image: Image) {
        if (!geminiRepository.isSessionActive() || _isScreenCapturePaused.value) return
        val processed = frameProcessor.processImage(image) ?: return
        recordAndTransmitProcessedFrame(processed)
    }

    /**
     * Bitmap screen capture path used by unit/Robolectric tests and fallback callers.
     */
    fun processAndSendScreenBitmap(bitmap: Bitmap) {
        if (!geminiRepository.isSessionActive() || _isScreenCapturePaused.value) return
        val processed = frameProcessor.processBitmap(bitmap) ?: return
        recordAndTransmitProcessedFrame(processed)
    }

    private fun recordAndTransmitProcessedFrame(processed: ProcessedFrameResult) {
        _lastFrameDimensions.value = "${processed.width}×${processed.height}"

        if (processed.wasSkippedDuplicate) {
            _framesSkippedCount.update { it + 1 }
            return
        }

        if (processed.jpegBytes.isNotEmpty()) {
            val sent = geminiRepository.sendScreenFrame(processed.jpegBytes)
            if (sent) {
                _framesSentCount.update { it + 1 }
            }
        }
    }

    fun stopScreenSharing() {
        if (_isSharingScreen.value) {
            runCatching { ScreenCaptureService.stopScreenCaptureService(appContext) }
            _isSharingScreen.value = false
            _isScreenCapturePaused.value = false
            appendSystemMessage("Screen sharing stopped.")
            if (_sessionState.value == LiveSessionState.SHARING_SCREEN) {
                _sessionState.value = if (geminiRepository.isSessionActive()) {
                    LiveSessionState.LISTENING
                } else {
                    LiveSessionState.STOPPED
                }
            }
        }
    }

    fun sendUserTextMessage(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return

        if (!geminiRepository.isSessionActive()) {
            _activeError.value = ScreenSightError.WebSocketFailure("Start a live session before sending messages.")
            return
        }

        _transcript.update { list ->
            list + TranscriptMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.USER,
                text = clean,
                isStreaming = false,
                hasScreenContext = _isSharingScreen.value && !_isScreenCapturePaused.value
            )
        }

        _sessionState.value = LiveSessionState.THINKING
        agentCoordinator?.onUserRequestReceived(clean)

        if (latestSettings.memoryEnabled && agentCoordinator != null) {
            scope.launch {
                val retrieval = runCatching {
                    agentCoordinator.retrieveRelevantMemoriesForRequest(clean)
                }.getOrNull()
                val outbound = if (retrieval != null && retrieval.promptContext.isNotBlank()) {
                    "${retrieval.promptContext}\n\nCurrent user request (takes absolute priority over stored memory): $clean"
                } else {
                    clean
                }
                if (geminiRepository.isSessionActive()) {
                    geminiRepository.sendTextMessage(outbound)
                }
            }
        } else {
            geminiRepository.sendTextMessage(clean)
        }
    }

    fun toggleMicrophoneMute(): Boolean {
        val nowMuted = audioCaptureManager.toggleMute()
        if (nowMuted && geminiRepository.isSessionActive()) {
            geminiRepository.sendAudioStreamEnd()
        }
        return nowMuted
    }

    fun toggleAiAudioMute(): Boolean {
        return audioPlaybackManager.toggleAiMute()
    }

    fun clearConversationTranscript() {
        _transcript.value = emptyList()
    }

    fun dismissError() {
        _activeError.value = null
        if (_sessionState.value == LiveSessionState.ERROR) {
            _sessionState.value = LiveSessionState.IDLE
        }
    }

    private fun handleSessionError(error: ScreenSightError) {
        reconnectJob?.cancel()
        _activeError.value = error
        cleanupActiveStreams(stopService = true)
        geminiRepository.stopLiveConnection()
        _sessionState.value = LiveSessionState.ERROR
        _connectionQuality.value = ConnectionQuality.DISCONNECTED
        appendSystemMessage(error.userMessage)
    }

    /**
     * Cleanly terminates the live session, stops screen sharing, releases AudioRecord & AudioTrack,
     * closes the WebSocket, and records a non-sensitive session summary locally.
     */
    fun stopSession(clearTranscriptOnStop: Boolean = false, fromService: Boolean = false) {
        userRequestedStop = true
        reconnectJob?.cancel()
        reconnectJob = null
        sessionJob?.cancel()
        sessionJob = null

        val startedAt = _sessionStartTimeMillis.value
        val framesSent = _framesSentCount.value

        agentCoordinator?.cancelCurrentTask()
        cleanupActiveStreams(stopService = !fromService)
        geminiRepository.stopLiveConnection()

        _connectionQuality.value = ConnectionQuality.DISCONNECTED
        _sessionState.value = LiveSessionState.STOPPED
        _sessionStartTimeMillis.value = null
        _framesSentCount.value = 0
        _framesSkippedCount.value = 0
        _lastFrameDimensions.value = "None"

        if (clearTranscriptOnStop) {
            _transcript.value = emptyList()
        } else {
            appendSystemMessage("Session ended.")
        }

        if (startedAt != null) {
            val durationSec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            val summary = "Ended at $timeFormat • ${durationSec}s duration • $framesSent screen frames shared"
            scope.launch {
                settingsRepository.updateLastSessionSummary(summary)
            }
        }
    }

    private fun cleanupActiveStreams(stopService: Boolean) {
        _isSharingScreen.value = false
        _isScreenCapturePaused.value = false
        recentAiOutputBuffer.clear()
        lastAiOutputAtMs = 0L
        if (stopService) {
            runCatching { ScreenCaptureService.stopEntireSessionService(appContext) }
        }
        audioCaptureManager.stopRecording()
        audioPlaybackManager.release()
        frameProcessor.resetDuplicateFilter()
    }

    private fun appendSystemMessage(text: String) {
        _transcript.update { current ->
            val next = current + TranscriptMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.SYSTEM,
                text = text,
                isStreaming = false
            )
            next.takeLast(MAX_TRANSCRIPT_MESSAGES)
        }
    }

    private fun appendOrUpdateStreamingTranscript(sender: MessageSender, deltaText: String) {
        _transcript.update { current ->
            val last = current.lastOrNull()
            if (last != null && last.sender == sender && last.isStreaming) {
                current.dropLast(1) + last.copy(text = last.text + deltaText)
            } else {
                val next = current + TranscriptMessage(
                    id = UUID.randomUUID().toString(),
                    sender = sender,
                    text = deltaText,
                    isStreaming = true,
                    hasScreenContext = _isSharingScreen.value && !_isScreenCapturePaused.value
                )
                next.takeLast(MAX_TRANSCRIPT_MESSAGES)
            }
        }
    }

    private fun finalizeStreamingTranscript() {
        _transcript.update { current ->
            current.map { msg ->
                if (msg.isStreaming) msg.copy(isStreaming = false) else msg
            }
        }
    }

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val MAX_TRANSCRIPT_MESSAGES = 100
        private const val SELF_ECHO_TRANSCRIPT_WINDOW_MS = 2_500L

        /**
         * Belt-and-suspenders textual echo detector: catches residual echo fragments that closely
         * mirror the AI's own spoken output within [SELF_ECHO_TRANSCRIPT_WINDOW_MS], while never
         * suppressing distinct user or remote caller sentences.
         */
        fun isLikelySelfEchoTranscript(
            userTranscript: String,
            recentAiTranscript: String,
            elapsedSinceAiSpeechMs: Long
        ): Boolean {
            if (elapsedSinceAiSpeechMs !in 0L..SELF_ECHO_TRANSCRIPT_WINDOW_MS) return false
            val userClean = userTranscript.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            val aiClean = recentAiTranscript.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            if (userClean.length < 8 || aiClean.isBlank()) return false

            val userWords = userClean.split(Regex("\\s+")).filter { it.length >= 2 }
            if (userWords.size < 3) return false
            val aiWords = aiClean.split(Regex("\\s+")).filter { it.length >= 2 }.toSet()
            if (aiWords.isEmpty()) return false

            val overlap = userWords.count { it in aiWords }
            val ratio = overlap.toFloat() / userWords.size.toFloat()
            return ratio >= 0.80f
        }
    }
}
