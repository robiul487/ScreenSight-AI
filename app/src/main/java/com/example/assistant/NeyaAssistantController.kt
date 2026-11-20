package com.example.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.os.Build
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionType
import com.example.agent.AgentCoordinator
import com.example.agent.LongTermTaskOrchestrator
import com.example.agent.MultiAgentOrchestrator
import com.example.data.ai.AIModelRouter
import com.example.data.local.ApiKeyStorage
import com.example.data.local.SettingsRepository
import com.example.data.local.skill.AgentSkillRepository
import com.example.service.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Abstraction over Android system assistant APIs so runtime queries are real on devices
 * and deterministically testable in JVM/Robolectric unit tests.
 */
interface SystemAssistantProbe {
    val sdkInt: Int
    fun isRoleManagerSupported(): Boolean
    fun isAssistantRoleAvailable(): Boolean
    fun isAssistantRoleHeld(): Boolean
    fun isVoiceInteractionServiceActive(): Boolean
    fun hasRecordAudioPermission(): Boolean
    fun isMicrophonePrivacyBlocked(): Boolean
    fun hasPrivilegedHotwordPermission(): Boolean
    fun createRoleRequestIntent(): Intent?
    fun createVoiceInputSettingsIntent(): Intent
}

class AndroidSystemAssistantProbe(
    private val context: Context
) : SystemAssistantProbe {

    private val appContext = context.applicationContext

    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun isRoleManagerSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun isAssistantRoleAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val rm = appContext.getSystemService(RoleManager::class.java)
            rm?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
    }

    override fun isAssistantRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val rm = appContext.getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        }.getOrDefault(false)
    }

    override fun isVoiceInteractionServiceActive(): Boolean {
        return runCatching {
            val component = ComponentName(appContext, NeyaVoiceInteractionService::class.java)
            VoiceInteractionService.isActiveService(appContext, component)
        }.getOrDefault(false)
    }

    override fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun isMicrophonePrivacyBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return runCatching {
            val spm = appContext.getSystemService(SensorPrivacyManager::class.java)
            // SensorPrivacyManager.isSensorPrivacyEnabled is @SystemApi on some builds;
            // supportsSensorToggle is public API 31+. We check safely via reflection only if exposed,
            // defaulting to false when Android does not disclose sensor toggle state to third-party apps.
            val method = spm?.javaClass?.methods?.firstOrNull {
                it.name == "isSensorPrivacyEnabled" && it.parameterTypes.size == 1
            }
            if (method != null) {
                (method.invoke(spm, SensorPrivacyManager.Sensors.MICROPHONE) as? Boolean) == true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    override fun hasPrivilegedHotwordPermission(): Boolean {
        // Read-only runtime check; NEVER declared in AndroidManifest.xml (restricted signature|privileged).
        val captureHotword = ContextCompat.checkSelfPermission(
            appContext,
            "android.permission.CAPTURE_AUDIO_HOTWORD"
        ) == PackageManager.PERMISSION_GRANTED
        val manageKeyphrases = ContextCompat.checkSelfPermission(
            appContext,
            "android.permission.MANAGE_VOICE_KEYPHRASES"
        ) == PackageManager.PERMISSION_GRANTED
        return captureHotword && manageKeyphrases
    }

    override fun createRoleRequestIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val rm = appContext.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
            } else {
                null
            }
        }.getOrNull()
    }

    override fun createVoiceInputSettingsIntent(): Intent {
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * Single narrow coordinator bridging Android's native VoiceInteraction / RoleManager framework
 * to the EXISTING ScreenSight AI engine (SessionManager, AgentCoordinator, MultiAgentOrchestrator,
 * LongTermTaskOrchestrator, GemmaAgentReasoningEngine).
 *
 * Guarantees:
 * - Zero duplicate AgentCoordinator, AgentToolRegistry, or Gemini Live WebSocket sessions.
 * - Zero duplicate microphone capture streams.
 * - Never auto-starts MediaProjection without explicit user consent.
 * - Never cancels unrelated autonomous tasks when the assistant UI hides.
 * - Honest, verifiable default-assistant role and "Hey Neya" wake-word capability reporting.
 */
class NeyaAssistantController(
    private val context: Context,
    private val sessionManager: SessionManager,
    private val agentCoordinator: AgentCoordinator,
    private val multiAgentOrchestrator: MultiAgentOrchestrator,
    private val longTermTaskOrchestrator: LongTermTaskOrchestrator,
    private val skillRepository: AgentSkillRepository,
    private val settingsRepository: SettingsRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val aiModelRouter: AIModelRouter? = null,
    private val probe: SystemAssistantProbe = AndroidSystemAssistantProbe(context),
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(NeyaAssistantRuntimeState())
    val state: StateFlow<NeyaAssistantRuntimeState> = _state.asStateFlow()

    @Volatile
    private var liveSessionStartedByAssistantSheet: Boolean = false

    @Volatile
    private var lastInvocationTimestamp: Long = 0L

    init {
        refreshSystemStatus(wasCancelledByUser = false)
    }

    /**
     * Queries real Android system APIs (`RoleManager.ROLE_ASSISTANT`, `VoiceInteractionService.isActiveService`,
     * microphone permission, microphone privacy, and privileged hotword access).
     * Never claims Neya is active just because a user tapped a setup button.
     */
    fun refreshSystemStatus(wasCancelledByUser: Boolean = false): NeyaAssistantRuntimeState {
        val roleSupported = probe.isRoleManagerSupported()
        val roleAvailable = if (roleSupported) probe.isAssistantRoleAvailable() else false
        val roleHeld = if (roleSupported && roleAvailable) probe.isAssistantRoleHeld() else false
        val visActive = probe.isVoiceInteractionServiceActive()
        val hasMic = probe.hasRecordAudioPermission()
        val micPrivacyBlocked = probe.isMicrophonePrivacyBlocked()
        val hasPrivilegedHotword = probe.hasPrivilegedHotwordPermission()

        val resolvedRoleStatus = when {
            roleHeld || visActive -> DefaultAssistantRoleStatus.ACTIVE
            wasCancelledByUser -> DefaultAssistantRoleStatus.SELECTION_CANCELLED
            !roleSupported || !roleAvailable -> DefaultAssistantRoleStatus.UNSUPPORTED_DEVICE_OR_API
            else -> DefaultAssistantRoleStatus.NOT_SELECTED
        }

        val dspStatus = when {
            micPrivacyBlocked -> WakeCapabilityStatus.MIC_PRIVACY_BLOCKED
            !hasPrivilegedHotword -> WakeCapabilityStatus.REQUIRES_PRIVILEGED_DSP
            !visActive && !roleHeld -> WakeCapabilityStatus.UNSUPPORTED
            else -> WakeCapabilityStatus.UNSUPPORTED
        }

        val dspReason = when (dspStatus) {
            WakeCapabilityStatus.MIC_PRIVACY_BLOCKED ->
                "Android microphone hardware privacy toggle is currently enabled. Disable Mic Access block in Quick Settings first."
            WakeCapabilityStatus.REQUIRES_PRIVILEGED_DSP ->
                "Third-party apps cannot access Android AlwaysOnHotwordDetector / SoundTrigger DSP (requires privileged system permissions CAPTURE_AUDIO_HOTWORD & MANAGE_VOICE_KEYPHRASES)."
            else ->
                "Hardware DSP keyphrase enrollment for \"Hey Neya\" is not provisioned on this device."
        }

        val softwareStatus = when {
            micPrivacyBlocked -> WakeCapabilityStatus.MIC_PRIVACY_BLOCKED
            !hasMic -> WakeCapabilityStatus.PERMISSION_REQUIRED
            else -> WakeCapabilityStatus.MODEL_NOT_BUNDLED
        }

        val softwareReason = when (softwareStatus) {
            WakeCapabilityStatus.MIC_PRIVACY_BLOCKED ->
                "Android microphone privacy toggle is active. Neya cannot access the microphone."
            WakeCapabilityStatus.PERMISSION_REQUIRED ->
                "Microphone permission is not granted. Even with permission, no offline \"Hey Neya\" neural wake-word model is bundled in this build."
            else ->
                "No offline neural \"Hey Neya\" wake-word model is bundled in this APK. Continuous cloud streaming, SpeechRecognizer loops, and permanent background microphone services are intentionally disabled to protect battery and privacy."
        }

        val capabilityReport = WakeWordCapabilityReport(
            androidSdkInt = probe.sdkInt,
            isRoleManagerSupported = roleSupported,
            isDefaultAssistantHeld = roleHeld,
            isVoiceInteractionServiceActive = visActive,
            hasRecordAudioPermission = hasMic,
            isMicHardwarePrivacyBlocked = micPrivacyBlocked,
            hasPrivilegedHotwordPermission = hasPrivilegedHotword,
            dspStatus = dspStatus,
            dspReason = dspReason,
            softwareFallbackStatus = softwareStatus,
            softwareFallbackReason = softwareReason,
            effectiveActiveMode = WakeDetectionMode.MANUAL_ONLY,
            isRealWakeDetectorAvailable = false,
            isCurrentlyListeningForWakeWord = false,
            batteryPrivacySummary = "Manual system assistant invocation uses zero background mic polling and zero pre-trigger cloud audio streaming."
        )

        val statusNote = when (resolvedRoleStatus) {
            DefaultAssistantRoleStatus.ACTIVE ->
                "Neya is active as your Android default digital assistant. Invoke via system assistant gesture or in-app controls."
            DefaultAssistantRoleStatus.SELECTION_CANCELLED ->
                "Default assistant chooser was cancelled. Neya remains available for manual in-app invocation."
            DefaultAssistantRoleStatus.UNSUPPORTED_DEVICE_OR_API ->
                "RoleManager assistant prompt unavailable on this Android build. Open Android Default Digital Assistant settings manually."
            DefaultAssistantRoleStatus.NOT_SELECTED ->
                "Tap 'Set Neya as Default Assistant' to open Android's official Digital Assistant selector."
            DefaultAssistantRoleStatus.UNVERIFIED ->
                "Could not verify default assistant role from Android system services."
        }

        val updated = _state.value.copy(
            roleStatus = resolvedRoleStatus,
            isVoiceInteractionServiceConnected = visActive || _state.value.isVoiceInteractionServiceConnected,
            lastStatusNote = statusNote,
            capabilityReport = capabilityReport
        )
        _state.value = updated
        return updated
    }

    /**
     * Returns Android's official `RoleManager.createRequestRoleIntent(ROLE_ASSISTANT)` when available
     * (API 29+), or falls back to `Settings.ACTION_VOICE_INPUT_SETTINGS`.
     */
    fun createDefaultAssistantRequestIntent(): Intent {
        return probe.createRoleRequestIntent() ?: probe.createVoiceInputSettingsIntent()
    }

    fun isUsingRoleManagerIntent(): Boolean = probe.createRoleRequestIntent() != null

    fun onVoiceInteractionServiceReady() {
        _state.update {
            it.copy(
                isVoiceInteractionServiceConnected = true,
                lastStatusNote = "Neya VoiceInteractionService connected to Android system."
            )
        }
        refreshSystemStatus(wasCancelledByUser = false)
    }

    fun onVoiceInteractionServiceShutdown() {
        _state.update {
            it.copy(
                isVoiceInteractionServiceConnected = false,
                isSessionShowing = false,
                lastStatusNote = "Neya VoiceInteractionService disconnected by Android."
            )
        }
        refreshSystemStatus(wasCancelledByUser = false)
    }

    /**
     * Records a native assistant invocation (system gesture, ASSIST intent, or manual trigger).
     * Prevents duplicate rapid invocations and NEVER starts a duplicate Gemini Live or mic session.
     * Returns true if this invocation should open/refresh UI, false if debounced as a duplicate.
     */
    fun onAssistantSessionShown(source: AssistantInvocationSource): Boolean {
        val now = clock()
        if (_state.value.isSessionShowing &&
            _state.value.lastInvocationSource == source &&
            (now - lastInvocationTimestamp) < INVOCATION_DEBOUNCE_MS
        ) {
            return false
        }
        lastInvocationTimestamp = now
        val liveAlreadyActive = sessionManager.sessionState.value.isActive
        if (liveAlreadyActive) {
            liveSessionStartedByAssistantSheet = false
        }
        val note = when {
            liveAlreadyActive ->
                "Reusing active Gemini Live session (${sessionManager.sessionState.value.displayName}) — no duplicate microphone stream started."
            agentCoordinator.isTaskRunning.value ->
                "Existing autonomous task is running safely in background."
            else ->
                "Neya ready via ${source.label}. Type a command or tap Voice Live."
        }
        _state.update {
            it.copy(
                isSessionShowing = true,
                lastInvocationSource = source,
                lastInvocationTimeMillis = now,
                lastStatusNote = note
            )
        }
        refreshSystemStatus(wasCancelledByUser = false)
        return true
    }

    /**
     * Cleanly closes the native assistant surface.
     * CRITICAL: Never cancels unrelated running autonomous tasks in AgentCoordinator / LongTermTaskOrchestrator.
     * Only stops Gemini Live if explicitly requested AND the live session was started by this assistant sheet.
     */
    fun onAssistantSessionHidden(stopLiveIfOwnedBySession: Boolean = false) {
        if (stopLiveIfOwnedBySession && liveSessionStartedByAssistantSheet && sessionManager.sessionState.value.isActive) {
            sessionManager.stopSession(clearTranscriptOnStop = false)
        }
        liveSessionStartedByAssistantSheet = false
        _state.update {
            it.copy(
                isSessionShowing = false,
                lastStatusNote = if (agentCoordinator.isTaskRunning.value) {
                    "Assistant overlay closed. Active autonomous task continues safely."
                } else {
                    "Assistant overlay closed cleanly."
                }
            )
        }
    }

    /**
     * Starts or focuses existing Gemini Live voice mode from the native assistant surface.
     * Reuses an active Live session if already connected (zero duplicate mic/WebSocket) and
     * NEVER auto-starts MediaProjection without explicit consent.
     */
    fun startOrFocusLiveVoiceFromAssistant(): String {
        if (probe.isMicrophonePrivacyBlocked()) {
            val msg = "Android microphone privacy toggle is enabled. Disable mic block to speak with Neya."
            _state.update { it.copy(lastStatusNote = msg) }
            return msg
        }
        if (!probe.hasRecordAudioPermission()) {
            val msg = "Microphone permission required. Open ScreenSight AI to grant RECORD_AUDIO."
            _state.update { it.copy(lastStatusNote = msg) }
            return msg
        }
        if (sessionManager.sessionState.value.isActive) {
            if (sessionManager.audioCaptureManager.isMuted.value) {
                sessionManager.toggleMicrophoneMute()
            }
            val msg = "Connected to existing Gemini Live session (${sessionManager.sessionState.value.displayName})."
            _state.update { it.copy(lastStatusNote = msg) }
            return msg
        }
        if (!apiKeyStorage.hasApiKeyFlow.value) {
            val msg = "Gemini API key not configured. You can still run local commands or configure your key in Settings."
            _state.update { it.copy(lastStatusNote = msg) }
            return msg
        }
        liveSessionStartedByAssistantSheet = true
        sessionManager.startSession()
        sessionManager.startAudioCaptureIfPermitted()
        val msg = "Starting Gemini Live voice session (screen sharing stays off until you explicitly consent)."
        _state.update { it.copy(lastStatusNote = msg) }
        return msg
    }

    /**
     * Executes a text or voice-transcribed command from the native assistant session directly
     * through the existing ScreenSight AI architecture:
     * - If Gemini Live is already active -> sends into the active live session.
     * - Otherwise -> runs via existing AgentCoordinator / MultiAgentOrchestrator / LongTermTaskOrchestrator
     *   (Gemma 4 reasoning + local tool planner + AgentSafetyManager), without requiring Gemini Live.
     */
    suspend fun submitAssistantCommand(rawText: String): AssistantCommandResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return AssistantCommandResult.EmptyInput

        if (sessionManager.sessionState.value.isActive) {
            sessionManager.sendUserTextMessage(trimmed)
            val summary = "Sent to active Gemini Live session: \"$trimmed\""
            _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
            return AssistantCommandResult.SentToLiveSession(trimmed)
        }

        agentCoordinator.onUserRequestReceived(trimmed)
        val lower = trimmed.lowercase()

        // Fast safe direct actions matching existing AgentCoordinator quick pipeline
        if (lower.contains("time") || lower.contains("date") || lower.contains("কয়টা") || lower.contains("তারিখ")) {
            agentCoordinator.executeDirectQuickAction(
                request = AgentActionRequest(
                    actionType = AgentActionType.GET_DATE_TIME,
                    reason = "Requested via Neya native assistant"
                ),
                isGeminiAudioActive = false
            )
            val summary = "Checked current date and time locally."
            _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
            return AssistantCommandResult.StartedQuickAction(summary)
        }

        if (lower.startsWith("open ") && trimmed.length > 5) {
            val targetApp = trimmed.substring(5).trim()
            agentCoordinator.executeDirectQuickAction(
                request = AgentActionRequest(
                    actionType = AgentActionType.OPEN_APP,
                    parameters = mapOf("app_name" to targetApp),
                    reason = "Open app requested via Neya native assistant"
                ),
                isGeminiAudioActive = false
            )
            val summary = "Opening $targetApp via ScreenSight Agent..."
            _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
            return AssistantCommandResult.StartedQuickAction(summary)
        }

        val availableSkills = runCatching { skillRepository.getAllSkills() }.getOrDefault(emptyList())
        val ctx = multiAgentOrchestrator.runControlledWorkflow(
            goal = trimmed,
            availableSkills = availableSkills,
            executeSafePlan = false
        )

        if (ctx.plannedSubtasks.isEmpty() && !ctx.directAnswer.isNullOrBlank()) {
            val badge = ctx.modelUsage?.let { usage ->
                if (usage.usedFallback) "${usage.modelDisplayName} • Fallback" else usage.modelDisplayName
            } ?: "Gemma Reasoning"
            sessionManager.appendReasoningTranscriptExchange(
                userText = trimmed,
                assistantReply = ctx.directAnswer,
                modelBadge = badge
            )
            val summary = "[$badge] ${ctx.directAnswer}"
            _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = "Answered via $badge") }
            return AssistantCommandResult.AnsweredDirectly(ctx.directAnswer, badge)
        }

        val usageTag = ctx.modelUsage?.toCompactCheckpointTag()
        return when (val created = longTermTaskOrchestrator.createTask(trimmed, ctx.plannedSubtasks, modelUsageNote = usageTag)) {
            is LongTermTaskOrchestrator.CreateTaskOutcome.Created -> {
                ctx.modelUsage?.let { usage ->
                    aiModelRouter?.attachModelUsageToTask(created.task.taskId, usage)
                }
                when (val outcome = longTermTaskOrchestrator.runTask(created.task.taskId)) {
                    is LongTermTaskOrchestrator.RunOutcome.Completed -> {
                        val summary = "Completed: ${outcome.task.goal} (${outcome.task.completedCount}/${outcome.task.totalCount})"
                        _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                        AssistantCommandResult.ExecutedTask(created.task.taskId, summary)
                    }
                    is LongTermTaskOrchestrator.RunOutcome.ConfirmationRequired -> {
                        val summary = "Confirmation required (${outcome.subtask.title}): ${outcome.reasonBn}"
                        _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                        AssistantCommandResult.BlockedOrFailed(summary)
                    }
                    is LongTermTaskOrchestrator.RunOutcome.BlockedBySafety -> {
                        val summary = "Blocked by safety policy: ${outcome.reasonBn}"
                        _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                        AssistantCommandResult.BlockedOrFailed(summary)
                    }
                    is LongTermTaskOrchestrator.RunOutcome.Paused -> {
                        val summary = "Paused: ${outcome.reason}"
                        _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                        AssistantCommandResult.ExecutedTask(created.task.taskId, summary)
                    }
                    is LongTermTaskOrchestrator.RunOutcome.Cancelled -> {
                        val summary = "Cancelled: ${outcome.task.goal}"
                        _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                        AssistantCommandResult.BlockedOrFailed(summary)
                    }
                    is LongTermTaskOrchestrator.RunOutcome.Failed -> {
                        val summary = "Task failed: ${outcome.reasonEn}"
                        _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                        AssistantCommandResult.BlockedOrFailed(summary)
                    }
                    LongTermTaskOrchestrator.RunOutcome.NotFound -> {
                        val summary = "Task could not be found."
                        _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                        AssistantCommandResult.BlockedOrFailed(summary)
                    }
                }
            }
            is LongTermTaskOrchestrator.CreateTaskOutcome.InvalidDag -> {
                val summary = created.reasonBn
                _state.update { it.copy(lastCommandSummary = summary, lastStatusNote = summary) }
                AssistantCommandResult.BlockedOrFailed(summary)
            }
        }
    }

    fun submitAssistantCommandAsync(
        rawText: String,
        onResult: (AssistantCommandResult) -> Unit = {}
    ) {
        scope.launch {
            val result = submitAssistantCommand(rawText)
            onResult(result)
        }
    }

    fun stopWakeListeningIfActive() {
        _state.update { current ->
            current.copy(
                capabilityReport = current.capabilityReport.copy(
                    isCurrentlyListeningForWakeWord = false
                ),
                lastStatusNote = "Wake listening is off. Neya uses zero background microphone capture."
            )
        }
    }

    suspend fun getPersistedHeyNeyaEnabled(): Boolean =
        runCatching { settingsRepository.settingsFlow.first().heyNeyaEnabled }.getOrDefault(false)

    companion object {
        const val EXTRA_OPEN_ASSISTANT_CONSOLE = "com.example.extra.OPEN_ASSISTANT_CONSOLE"
        const val EXTRA_ASSISTANT_SOURCE = "com.example.extra.ASSISTANT_SOURCE"
        private const val INVOCATION_DEBOUNCE_MS = 600L
    }
}
