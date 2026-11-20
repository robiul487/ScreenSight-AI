package com.example.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.BuildConfig
import com.example.agent.AgentAccessibilityService
import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionType
import com.example.agent.AgentCoordinator
import com.example.agent.AgentExecutionState
import com.example.agent.AgentMode
import com.example.agent.AgentPermissionManager
import com.example.agent.AgentPermissionsSnapshot
import com.example.agent.AgentTaskLogger
import com.example.agent.AgentTaskPlan
import com.example.agent.AgentTaskStep
import com.example.agent.AppLauncher
import com.example.agent.DynamicToolManager
import com.example.agent.LastActionVerification
import com.example.agent.LongTermTaskOrchestrator
import com.example.agent.MultiAgentOrchestrator
import com.example.agent.PendingConfirmation
import com.example.agent.PhysicalDeviceTestHarness
import com.example.agent.ProactiveAssistantEngine
import com.example.agent.SafeWebAutomationManager
import com.example.agent.ScreenUiSnapshot
import com.example.agent.SelfReflectionEngine
import com.example.agent.TrustedContact
import com.example.agent.TrustedContactsRepository
import com.example.data.local.ApiKeyRepository
import com.example.data.local.ApiKeySource
import com.example.data.local.ApiKeyStorage
import com.example.data.local.SettingsRepository
import com.example.data.local.checkpoint.AgentCheckpointDatabase
import com.example.data.local.checkpoint.RecoverableTaskCheckpoint
import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.memory.AgentMemoryRepository
import com.example.data.local.memory.MemoryCategory
import com.example.data.local.memory.MemoryExpirationPolicy
import com.example.data.local.memory.MemorySource
import com.example.data.local.metrics.ToolExecutionMetricEntity
import com.example.data.local.orchestration.LongTermTask
import com.example.data.local.schedule.ScheduleRecurrence
import com.example.data.local.schedule.ScheduledActionType
import com.example.data.local.schedule.ScheduledTask
import com.example.data.local.skill.AgentSkill
import com.example.data.local.skill.AgentSkillRepository
import com.example.data.local.skill.SkillProposal
import com.example.data.repository.GeminiLiveRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.AutonomyLevel
import com.example.domain.model.ConnectionQuality
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.LiveSessionState
import com.example.domain.model.ScreenSightError
import com.example.domain.model.ThemePreference
import com.example.domain.model.TranscriptMessage
import com.example.domain.usecase.SendTextMessageUseCase
import com.example.domain.usecase.StartAudioUseCase
import com.example.domain.usecase.StartSessionUseCase
import com.example.domain.usecase.StopSessionUseCase
import com.example.service.AgentTaskScheduler
import com.example.service.ScreenCaptureService
import com.example.service.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

sealed class ApiValidationUiState {
    object Idle : ApiValidationUiState()
    object Validating : ApiValidationUiState()
    data class Valid(val message: String, val discoveredModels: List<String>) : ApiValidationUiState()
    data class Invalid(val error: ScreenSightError) : ApiValidationUiState()
}

data class LocalStorageSnapshot(
    val databaseSizeBytes: Long = 0L,
    val cacheSizeBytes: Long = 0L,
    val memoryCount: Int = 0,
    val skillCount: Int = 0,
    val taskCount: Int = 0,
    val scheduleCount: Int = 0,
    val toolMetricCount: Int = 0
)

class ScreenSightViewModel(
    private val appContext: Context,
    private val checkpointDatabase: AgentCheckpointDatabase,
    private val apiKeyRepository: ApiKeyStorage,
    private val settingsRepository: SettingsRepository,
    private val geminiLiveRepository: GeminiLiveRepository,
    private val sessionManager: SessionManager,
    private val startSessionUseCase: StartSessionUseCase,
    private val stopSessionUseCase: StopSessionUseCase,
    private val startAudioUseCase: StartAudioUseCase,
    private val sendTextMessageUseCase: SendTextMessageUseCase,
    private val agentCoordinator: AgentCoordinator,
    private val agentPermissionManager: AgentPermissionManager,
    private val trustedContactsRepository: TrustedContactsRepository,
    private val appLauncher: AppLauncher,
    private val memoryRepository: AgentMemoryRepository,
    private val skillRepository: AgentSkillRepository,
    private val longTermTaskOrchestrator: LongTermTaskOrchestrator,
    private val taskScheduler: AgentTaskScheduler,
    private val dynamicToolManager: DynamicToolManager,
    private val selfReflectionEngine: SelfReflectionEngine,
    private val proactiveAssistantEngine: ProactiveAssistantEngine,
    private val multiAgentOrchestrator: MultiAgentOrchestrator,
    val safeWebAutomationManager: SafeWebAutomationManager
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppSettings()
    )

    val hasApiKey: StateFlow<Boolean> = apiKeyRepository.hasApiKeyFlow
    val maskedApiKey: StateFlow<String> = apiKeyRepository.maskedApiKeyFlow
    val apiKeySource: StateFlow<ApiKeySource> = apiKeyRepository.keySourceFlow

    val sessionState: StateFlow<LiveSessionState> = sessionManager.sessionState
    val isSharingScreen: StateFlow<Boolean> = sessionManager.isSharingScreen
    val isScreenCapturePaused: StateFlow<Boolean> = sessionManager.isScreenCapturePaused
    val isMicMuted: StateFlow<Boolean> = sessionManager.audioCaptureManager.isMuted
    val isUserSpeaking: StateFlow<Boolean> = sessionManager.audioCaptureManager.isUserSpeaking
    val micLevel: StateFlow<Float> = sessionManager.audioCaptureManager.micLevel
    val isAiMuted: StateFlow<Boolean> = sessionManager.audioPlaybackManager.isAiMuted
    val isAiSpeaking: StateFlow<Boolean> = sessionManager.audioPlaybackManager.isAiSpeaking
    val framesSentCount: StateFlow<Int> = sessionManager.framesSentCount
    val framesSkippedCount: StateFlow<Int> = sessionManager.framesSkippedCount
    val lastFrameDimensions: StateFlow<String> = sessionManager.lastFrameDimensions
    val connectionQuality: StateFlow<ConnectionQuality> = sessionManager.connectionQuality
    val transcript: StateFlow<List<TranscriptMessage>> = sessionManager.transcript
    val activeError: StateFlow<ScreenSightError?> = sessionManager.activeError

    // Android AI Agent State Flows (Phase 1 + Phase 2 + Phase 3 Reliability & Grounding)
    val agentExecutionState: StateFlow<AgentExecutionState> = agentCoordinator.executionState
    val activeAgentPlan: StateFlow<AgentTaskPlan?> = agentCoordinator.activePlan
    val agentTaskSteps: StateFlow<List<AgentTaskStep>> = agentCoordinator.taskSteps
    val agentTaskLogs: StateFlow<List<AgentTaskLogger.TaskLogEntry>> = agentCoordinator.taskLogger.recentEntries
    val agentTaskRecords: StateFlow<List<AgentTaskLogger.TaskExecutionRecord>> = agentCoordinator.taskLogger.records
    val isAgentTaskRunning: StateFlow<Boolean> = agentCoordinator.isTaskRunning
    val pendingConfirmation: StateFlow<PendingConfirmation?> = agentCoordinator.pendingConfirmation
    val agentStatusBannerBn: StateFlow<String> = agentCoordinator.statusBannerBn
    val latestScreenSnapshot: StateFlow<ScreenUiSnapshot?> = AgentAccessibilityService.latestSnapshot
    val activeForegroundPackage: StateFlow<String> = AgentAccessibilityService.activePackageName
    val lastActionVerification: StateFlow<LastActionVerification?> = agentCoordinator.lastActionVerification
    val recoverableCheckpoints: StateFlow<List<RecoverableTaskCheckpoint>> = agentCoordinator.recoverableCheckpoints
    val deviceTestRecords: StateFlow<List<PhysicalDeviceTestHarness.DeviceTestRunRecord>> = agentCoordinator.testHarness.records
    val activeHarnessScenario: StateFlow<PhysicalDeviceTestHarness.DeviceTestScenario?> = agentCoordinator.testHarness.activeScenario
    val permissionsSnapshot: StateFlow<AgentPermissionsSnapshot> = agentPermissionManager.permissions
    val trustedContacts: StateFlow<List<TrustedContact>> = trustedContactsRepository.trustedContactsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Phase 4: On-Device Long-Term Memory & Reusable Skills StateFlows
    val memories: StateFlow<List<AgentMemoryRecord>> = memoryRepository.observeAllMemories().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val skills: StateFlow<List<AgentSkill>> = skillRepository.observeAllSkills().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val pendingSkillProposal: StateFlow<SkillProposal?> = agentCoordinator.pendingSkillProposal
    val activeSkillId: StateFlow<String?> = agentCoordinator.activeSkillId

    private val _memoryFeedbackMessage = MutableStateFlow<String?>(null)
    val memoryFeedbackMessage: StateFlow<String?> = _memoryFeedbackMessage.asStateFlow()

    // Phase 5: Autonomous Orchestration, Schedules, Proactive Assistance, Tool Discovery & Reflection
    val longTermTasks: StateFlow<List<LongTermTask>> = longTermTaskOrchestrator.observeAllTasks().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val scheduledTasks: StateFlow<List<ScheduledTask>> = taskScheduler.observeSchedules().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val toolExecutionHistory: StateFlow<List<ToolExecutionMetricEntity>> = dynamicToolManager.observeExecutionHistory(80).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    val latestReflectionReport: StateFlow<SelfReflectionEngine.TaskReflectionReport?> = agentCoordinator.latestReflectionReport
    val skillImprovementProposals: StateFlow<List<SelfReflectionEngine.SkillImprovementProposal>> = agentCoordinator.skillImprovementProposals

    private val _discoveredTools = MutableStateFlow<List<DynamicToolManager.ToolDescriptor>>(emptyList())
    val discoveredTools: StateFlow<List<DynamicToolManager.ToolDescriptor>> = _discoveredTools.asStateFlow()

    private val _toolReliabilityMetrics = MutableStateFlow<List<SelfReflectionEngine.ToolReliabilitySummary>>(emptyList())
    val toolReliabilityMetrics: StateFlow<List<SelfReflectionEngine.ToolReliabilitySummary>> = _toolReliabilityMetrics.asStateFlow()

    private val _lastMultiAgentContext = MutableStateFlow<MultiAgentOrchestrator.SharedAgentTaskContext?>(null)
    val lastMultiAgentContext: StateFlow<MultiAgentOrchestrator.SharedAgentTaskContext?> = _lastMultiAgentContext.asStateFlow()

    private val _dismissedSuggestionIds = MutableStateFlow<Set<String>>(emptySet())
    val proactiveSuggestions: StateFlow<List<ProactiveAssistantEngine.ProactiveSuggestion>> = combine(
        settings,
        memories,
        skills,
        _dismissedSuggestionIds
    ) { currentSettings, memList, skillList, dismissed ->
        if (!currentSettings.proactiveSuggestionsEnabled) {
            emptyList()
        } else {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            proactiveAssistantEngine.generateSuggestions(
                memories = memList,
                skills = skillList,
                hourOfDay = hour,
                dismissedIds = dismissed
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _autonomousFeedbackMessage = MutableStateFlow<String?>(null)
    val autonomousFeedbackMessage: StateFlow<String?> = _autonomousFeedbackMessage.asStateFlow()

    private val _apiValidationState = MutableStateFlow<ApiValidationUiState>(ApiValidationUiState.Idle)
    val apiValidationState: StateFlow<ApiValidationUiState> = _apiValidationState.asStateFlow()

    private val _showFirstSessionPrivacyDialog = MutableStateFlow(false)
    val showFirstSessionPrivacyDialog: StateFlow<Boolean> = _showFirstSessionPrivacyDialog.asStateFlow()

    private val _storageSnapshot = MutableStateFlow(LocalStorageSnapshot())
    val storageSnapshot: StateFlow<LocalStorageSnapshot> = _storageSnapshot.asStateFlow()

    private val _settingsFeedbackMessage = MutableStateFlow<String?>(null)
    val settingsFeedbackMessage: StateFlow<String?> = _settingsFeedbackMessage.asStateFlow()

    init {
        refreshPermissions()
        refreshDynamicToolsAndMetrics()
        refreshStorageSnapshot()
    }

    fun refreshPermissions() {
        agentPermissionManager.refresh(isScreenSharingActive = sessionManager.isSharingScreen.value)
    }

    fun createAccessibilitySettingsIntent(): Intent = agentPermissionManager.createAccessibilitySettingsIntent()
    fun createOverlaySettingsIntent(): Intent = agentPermissionManager.createOverlaySettingsIntent()
    fun createAppDetailsSettingsIntent(): Intent = agentPermissionManager.createAppDetailsSettingsIntent()

    fun saveApiKey(rawKey: String) {
        viewModelScope.launch {
            val clean = rawKey.trim()
            if (!ApiKeyRepository.isValidApiKeyFormat(clean)) {
                _apiValidationState.value = ApiValidationUiState.Invalid(ScreenSightError.InvalidApiKey)
                return@launch
            }
            apiKeyRepository.saveApiKey(clean)
            _apiValidationState.value = ApiValidationUiState.Valid(
                message = "API key encrypted with Android Keystore (AES-256-GCM) and saved on device.",
                discoveredModels = emptyList()
            )
        }
    }

    fun removeApiKey() {
        viewModelScope.launch {
            apiKeyRepository.clearApiKey()
            _apiValidationState.value = ApiValidationUiState.Idle
        }
    }

    fun validateApiKey(candidateKeyInput: String) {
        viewModelScope.launch {
            val keyToTest = candidateKeyInput.trim().ifEmpty { apiKeyRepository.getApiKey() }
            if (keyToTest.isEmpty()) {
                _apiValidationState.value = ApiValidationUiState.Invalid(ScreenSightError.MissingApiKey)
                return@launch
            }
            if (!ApiKeyRepository.isValidApiKeyFormat(keyToTest)) {
                _apiValidationState.value = ApiValidationUiState.Invalid(ScreenSightError.InvalidApiKey)
                return@launch
            }

            _apiValidationState.value = ApiValidationUiState.Validating
            val currentModelId = settings.value.selectedModelId
            val result = geminiLiveRepository.validateApiKeyAndModel(keyToTest, currentModelId)

            if (result.isValid) {
                if (candidateKeyInput.isNotBlank()) {
                    apiKeyRepository.saveApiKey(keyToTest)
                }
                val modelNormalized = GeminiLiveModel.findByIdOrCustom(currentModelId).fullResourceName
                val modelNotice = if (result.discoveredLiveModels.isNotEmpty() &&
                    result.discoveredLiveModels.none { it.equals(modelNormalized, ignoreCase = true) }
                ) {
                    "Key verified! Warning: $modelNormalized was not reported in your key's bidiGenerateContent list. Tap one of the discovered Live models below to select it."
                } else {
                    "Gemini API key verified and $modelNormalized is ready for BidiGenerateContent streaming."
                }
                _apiValidationState.value = ApiValidationUiState.Valid(
                    message = modelNotice,
                    discoveredModels = result.discoveredLiveModels
                )
            } else {
                _apiValidationState.value = ApiValidationUiState.Invalid(
                    result.error ?: ScreenSightError.InvalidApiKey
                )
            }
        }
    }

    fun resetValidationState() {
        _apiValidationState.value = ApiValidationUiState.Idle
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            settingsRepository.updateSelectedModel(modelId)
        }
    }

    fun selectFallbackModel(modelId: String) {
        viewModelScope.launch {
            settingsRepository.updateFallbackModel(modelId)
            _settingsFeedbackMessage.value = "Fallback model updated."
        }
    }

    fun selectVoice(voiceName: String) {
        viewModelScope.launch {
            settingsRepository.updateSelectedVoice(voiceName)
        }
    }

    fun updatePreferredLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.updatePreferredLanguage(language)
        }
    }

    fun updateSystemInstruction(instruction: String) {
        viewModelScope.launch {
            settingsRepository.updateSystemInstruction(instruction)
        }
    }

    fun updateFrameConfig(fps: Int, maxDimensionPx: Int, jpegQuality: Int) {
        viewModelScope.launch {
            settingsRepository.updateFrameCaptureConfig(fps, maxDimensionPx, jpegQuality)
        }
    }

    fun updateThemePreference(theme: ThemePreference) {
        viewModelScope.launch {
            settingsRepository.updateThemePreference(theme)
        }
    }

    fun updateDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateDynamicColor(enabled)
        }
    }

    fun updateHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateHapticFeedback(enabled)
        }
    }

    fun updateSoundEffects(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateSoundEffects(enabled)
        }
    }

    fun updateAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAnimationsEnabled(enabled)
        }
    }

    fun updateDefaultLandingScreen(route: String) {
        viewModelScope.launch {
            settingsRepository.updateDefaultLandingScreen(route)
        }
    }

    fun resetUiPreferences() {
        viewModelScope.launch {
            settingsRepository.resetUiPreferences()
            _settingsFeedbackMessage.value = "UI preferences restored to defaults."
        }
    }

    fun updateAgentMode(mode: AgentMode) {
        viewModelScope.launch {
            settingsRepository.updateAgentMode(mode)
        }
    }

    fun updateAutonomyLevel(level: AutonomyLevel) {
        viewModelScope.launch {
            settingsRepository.updateAutonomyLevel(level)
            _settingsFeedbackMessage.value = "Autonomy level set to ${level.title}."
        }
    }

    /**
     * Launches the Floating Agent Overlay pill over other apps if overlay permission is granted.
     * Returns true if started, or false if the Activity should route the user to Android Overlay settings.
     */
    fun launchFloatingAgentOverlay(): Boolean {
        val canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)
        if (!canDraw) {
            _autonomousFeedbackMessage.value = "Enable 'Display over other apps' to launch the Floating Agent pill."
            return false
        }
        val intent = Intent(appContext, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START_VOICE_SESSION
        }
        runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }
        if (hasApiKey.value && !sessionState.value.isActive) {
            startSessionUseCase()
        }
        _autonomousFeedbackMessage.value = "Floating Agent Overlay launched."
        return true
    }

    /**
     * Dual-Mode Agent Tab command dispatcher:
     * - When Gemini Live is active, forwards the text turn to the live multimodal session.
     * - When idle, plans & runs an instant on-device autonomous task without requiring screen sharing.
     * Returns true if sent to Gemini Live, or false if executed locally on device.
     */
    fun submitAgentTabInstruction(rawText: String): Boolean {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return false
        if (sessionState.value.isActive) {
            sendTextMessageUseCase(trimmed)
            return true
        }
        agentCoordinator.onUserRequestReceived(trimmed)
        val lower = trimmed.lowercase()
        when {
            lower.contains("time") || lower.contains("date") || lower.contains("কয়টা") || lower.contains("তারিখ") -> {
                agentCoordinator.executeDirectQuickAction(
                    AgentActionRequest(actionType = AgentActionType.GET_DATE_TIME),
                    isGeminiAudioActive = false
                )
            }
            lower.contains("read screen") || lower.contains("স্ক্রিন পড়") || lower.contains("scan screen") -> {
                agentCoordinator.executeDirectQuickAction(
                    AgentActionRequest(actionType = AgentActionType.READ_SCREEN),
                    isGeminiAudioActive = false
                )
            }
            lower.startsWith("open ") && trimmed.length > 5 -> {
                val appTarget = trimmed.substring(5).trim()
                agentCoordinator.executeDirectQuickAction(
                    AgentActionRequest(
                        actionType = AgentActionType.OPEN_APP,
                        parameters = mapOf("app_name" to appTarget)
                    ),
                    isGeminiAudioActive = false
                )
            }
            else -> {
                createAndRunMultiStageGoal(trimmed)
            }
        }
        return false
    }

    fun updateAgentToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAgentToolsEnabled(enabled)
        }
    }

    fun updateAgentExecutionLimits(maxSteps: Int, maxRetries: Int, timeoutSec: Int) {
        viewModelScope.launch {
            settingsRepository.updateAgentExecutionLimits(maxSteps, maxRetries, timeoutSec)
        }
    }

    fun updateAutoCheckpointRecovery(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoCheckpointRecovery(enabled)
        }
    }

    fun updateBackgroundAutomationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateBackgroundAutomationEnabled(enabled)
            if (!enabled) {
                taskScheduler.cancelAllBackgroundWork()
            }
        }
    }

    fun updateVoiceAudioPreferences(
        voiceInputEnabled: Boolean? = null,
        voiceOutputEnabled: Boolean? = null,
        ttsSpeechRate: Float? = null,
        voiceOutputVolume: Float? = null,
        bargeInInterruptEnabled: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.updateVoiceAudioPreferences(
                voiceInputEnabled = voiceInputEnabled,
                voiceOutputEnabled = voiceOutputEnabled,
                ttsSpeechRate = ttsSpeechRate,
                voiceOutputVolume = voiceOutputVolume,
                bargeInInterruptEnabled = bargeInInterruptEnabled
            )
            voiceInputEnabled?.let { enabled ->
                if (!enabled && !sessionManager.audioCaptureManager.isMuted.value) {
                    sessionManager.toggleMicrophoneMute()
                }
            }
            voiceOutputEnabled?.let { enabled ->
                if (!enabled && !sessionManager.audioPlaybackManager.isAiMuted.value) {
                    sessionManager.toggleAiAudioMute()
                } else if (enabled && sessionManager.audioPlaybackManager.isAiMuted.value) {
                    sessionManager.toggleAiAudioMute()
                }
            }
        }
    }

    fun updateDefaultMemoryRetention(policyName: String) {
        viewModelScope.launch {
            settingsRepository.updateDefaultMemoryRetention(policyName)
        }
    }

    fun updateAutoMemoryProposals(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoMemoryProposals(enabled)
        }
    }

    fun updateAutoSkillProposals(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoSkillProposals(enabled)
        }
    }

    fun updateNotificationPreferences(
        taskNotifications: Boolean? = null,
        completionNotifications: Boolean? = null,
        failureNotifications: Boolean? = null,
        scheduledNotifications: Boolean? = null,
        vibration: Boolean? = null
    ) {
        viewModelScope.launch {
            settingsRepository.updateNotificationPreferences(
                taskNotifications = taskNotifications,
                completionNotifications = completionNotifications,
                failureNotifications = failureNotifications,
                scheduledNotifications = scheduledNotifications,
                vibration = vibration
            )
        }
    }

    fun clearSettingsFeedback() {
        _settingsFeedbackMessage.value = null
    }

    fun refreshStorageSnapshot() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dbFile = appContext.getDatabasePath(AgentCheckpointDatabase.DB_NAME)
            val dbBytes = (dbFile?.length() ?: 0L) +
                (appContext.getDatabasePath("${AgentCheckpointDatabase.DB_NAME}-wal")?.length() ?: 0L)
            val cacheBytes = calculateDirSize(appContext.cacheDir)
            val memCount = runCatching { memoryRepository.getAllMemories().size }.getOrDefault(0)
            val skCount = runCatching { skillRepository.getAllSkills().size }.getOrDefault(0)
            val ltCount = runCatching { checkpointDatabase.longTermTaskDao().getAllTasks().size }.getOrDefault(0)
            val schCount = runCatching { checkpointDatabase.scheduledTaskDao().getAllSchedules().size }.getOrDefault(0)
            val metricCount = runCatching { checkpointDatabase.toolMetricDao().getRecentMetrics(200).size }.getOrDefault(0)
            _storageSnapshot.value = LocalStorageSnapshot(
                databaseSizeBytes = dbBytes,
                cacheSizeBytes = cacheBytes,
                memoryCount = memCount,
                skillCount = skCount,
                taskCount = ltCount,
                scheduleCount = schCount,
                toolMetricCount = metricCount
            )
        }
    }

    fun clearAppCache() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val deleted = runCatching {
                appContext.cacheDir?.listFiles()?.count { it.deleteRecursively() } ?: 0
            }.getOrDefault(0)
            refreshStorageSnapshot()
            _settingsFeedbackMessage.value = "Cleared cache ($deleted item(s) removed)."
        }
    }

    fun deleteTaskHistory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val all = checkpointDatabase.longTermTaskDao().getAllTasks()
            var removed = 0
            all.filter { it.status == "COMPLETED" || it.status == "FAILED" || it.status == "CANCELLED" }
                .forEach {
                    checkpointDatabase.longTermTaskDao().deleteTask(it.taskId)
                    removed++
                }
            agentCoordinator.clearTaskHistory()
            agentCoordinator.taskLogger.clearLogs()
            refreshStorageSnapshot()
            _settingsFeedbackMessage.value = "Deleted $removed completed/failed task record(s) and cleared logs."
        }
    }

    fun resetNonSecretLocalData() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            memoryRepository.clearAllMemories()
            taskScheduler.cancelAllBackgroundWork()
            checkpointDatabase.scheduledTaskDao().getAllSchedules().forEach {
                checkpointDatabase.scheduledTaskDao().deleteSchedule(it.scheduleId)
            }
            checkpointDatabase.longTermTaskDao().getAllTasks().forEach {
                checkpointDatabase.longTermTaskDao().deleteTask(it.taskId)
            }
            agentCoordinator.clearTaskHistory()
            agentCoordinator.taskLogger.clearLogs()
            settingsRepository.resetUiPreferences()
            refreshStorageSnapshot()
            _settingsFeedbackMessage.value = "Local memories, tasks, schedules, and UI preferences were reset. Your encrypted API key was preserved."
        }
    }

    fun buildSanitizedExportText(): String {
        val currentMemories = memories.value
        val currentSkills = skills.value
        val currentSchedules = scheduledTasks.value
        return buildString {
            appendLine("# ScreenSight AI Local Export (Sanitized — No API Keys or Secrets)")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Memories (${currentMemories.size}):")
            currentMemories.forEach { m ->
                appendLine("  - [${m.category.name}] ${m.memoryKey}: ${m.value}")
            }
            appendLine("Skills (${currentSkills.size}):")
            currentSkills.forEach { s ->
                appendLine("  - ${s.name} (v${s.version}, enabled=${s.enabled}, steps=${s.executionSteps.size})")
            }
            appendLine("Schedules (${currentSchedules.size}):")
            currentSchedules.forEach { sc ->
                appendLine("  - ${sc.title} [${sc.recurrence.name} ${"%02d:%02d".format(sc.hourOfDay, sc.minuteOfHour)}]")
            }
        }
    }

    fun buildSanitizedDiagnosticsText(): String {
        val perm = permissionsSnapshot.value
        val cur = settings.value
        val store = storageSnapshot.value
        return buildString {
            appendLine("ScreenSight AI Diagnostics (No Secrets)")
            appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android API: ${Build.VERSION.SDK_INT} (${Build.MODEL})")
            appendLine("Model: ${cur.selectedModelId} | Fallback: ${cur.fallbackModelId}")
            appendLine("API Key Status: ${if (hasApiKey.value) "Configured (${apiKeySource.value.name})" else "Not Configured"}")
            appendLine("Permissions: Mic=${perm.hasMicrophone}, A11y=${perm.isAgentControlReady}, Overlay=${perm.canDrawOverlays}, Notif=${perm.hasNotifications}")
            appendLine("Registered Tools: ${discoveredTools.value.size}")
            appendLine("Local Room v3: ${store.memoryCount} memories, ${store.skillCount} skills, ${store.taskCount} tasks, ${store.scheduleCount} schedules (${store.databaseSizeBytes / 1024} KB)")
        }
    }

    private fun calculateDirSize(dir: java.io.File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // Phase 4: Local Memory & Reusable Skills Controls
    fun updateMemoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateMemoryEnabled(enabled)
            _memoryFeedbackMessage.value = if (enabled) {
                "স্থানীয় স্মৃতি চালু করা হয়েছে (Local memory enabled)."
            } else {
                "স্মৃতি বন্ধ করা হয়েছে — কোনো স্মৃতি পড়া বা সংরক্ষণ হবে না (Memory disabled)."
            }
        }
    }

    fun addLocalMemory(
        category: MemoryCategory,
        key: String,
        value: String,
        expirationPolicy: MemoryExpirationPolicy
    ) {
        viewModelScope.launch {
            when (val res = memoryRepository.saveMemory(
                category = category,
                memoryKey = key,
                value = value,
                confidence = 1.0f,
                source = MemorySource.USER_EXPLICIT,
                expirationPolicy = expirationPolicy,
                userApproved = true
            )) {
                is AgentMemoryRepository.SaveOutcome.Saved -> {
                    _memoryFeedbackMessage.value = "স্মৃতি সংরক্ষিত হয়েছে: ${res.record.memoryKey}"
                }
                is AgentMemoryRepository.SaveOutcome.Rejected -> {
                    _memoryFeedbackMessage.value = res.reasonBn
                }
                AgentMemoryRepository.SaveOutcome.MemoryDisabled -> {
                    _memoryFeedbackMessage.value = "স্মৃতি বন্ধ আছে। নতুন স্মৃতি যোগ করতে প্রথমে স্মৃতি চালু করুন।"
                }
                is AgentMemoryRepository.SaveOutcome.KeptHigherPriority -> {
                    _memoryFeedbackMessage.value = "আগের অনুমোদিত স্মৃতি রাখা হয়েছে।"
                }
            }
        }
    }

    fun editLocalMemory(
        id: String,
        category: MemoryCategory,
        key: String,
        value: String,
        expirationPolicy: MemoryExpirationPolicy
    ) {
        viewModelScope.launch {
            when (val res = memoryRepository.updateMemoryById(
                id = id,
                category = category,
                memoryKey = key,
                value = value,
                expirationPolicy = expirationPolicy
            )) {
                is AgentMemoryRepository.SaveOutcome.Saved -> {
                    _memoryFeedbackMessage.value = "স্মৃতি আপডেট হয়েছে: ${res.record.memoryKey}"
                }
                is AgentMemoryRepository.SaveOutcome.Rejected -> {
                    _memoryFeedbackMessage.value = res.reasonBn
                }
                else -> Unit
            }
        }
    }

    fun deleteLocalMemory(id: String) {
        viewModelScope.launch {
            memoryRepository.deleteMemory(id)
            _memoryFeedbackMessage.value = "স্মৃতিটি মুছে ফেলা হয়েছে।"
        }
    }

    fun clearAllLocalMemories() {
        viewModelScope.launch {
            val count = memoryRepository.clearAllMemories()
            _memoryFeedbackMessage.value = "সব $count টি স্মৃতি মুছে ফেলা হয়েছে।"
        }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        viewModelScope.launch {
            skillRepository.setSkillEnabled(skillId, enabled)
        }
    }

    fun saveOrUpdateSkill(skill: AgentSkill) {
        viewModelScope.launch {
            when (val res = skillRepository.saveSkill(skill)) {
                is AgentSkillRepository.SaveSkillResult.Saved -> {
                    _memoryFeedbackMessage.value = "দক্ষতা সংরক্ষিত হয়েছে: ${res.skill.name}"
                }
                is AgentSkillRepository.SaveSkillResult.Rejected -> {
                    _memoryFeedbackMessage.value = res.reasonBn
                }
            }
        }
    }

    fun deleteSkill(skillId: String) {
        viewModelScope.launch {
            skillRepository.deleteSkill(skillId)
            _memoryFeedbackMessage.value = "দক্ষতাটি মুছে ফেলা হয়েছে।"
        }
    }

    fun runSkill(skillId: String, parameters: Map<String, String> = emptyMap()) {
        agentCoordinator.executeSkillById(skillId, parameters) { outcome ->
            _memoryFeedbackMessage.value = when (outcome) {
                is AgentCoordinator.SkillExecutionOutcome.Completed -> outcome.summaryEn
                is AgentCoordinator.SkillExecutionOutcome.BlockedBySafety -> outcome.reasonBn
                is AgentCoordinator.SkillExecutionOutcome.Cancelled -> "দক্ষতাটি বাতিল করা হয়েছে।"
                is AgentCoordinator.SkillExecutionOutcome.Failed -> outcome.reasonBn
                is AgentCoordinator.SkillExecutionOutcome.DisabledOrUnapproved -> "এই দক্ষতাটি বন্ধ বা অননুমোদিত।"
                is AgentCoordinator.SkillExecutionOutcome.AlreadyRunning -> "আরেকটি কাজ চলছে, অনুগ্রহ করে অপেক্ষা করুন।"
                AgentCoordinator.SkillExecutionOutcome.NotFound -> "দক্ষতাটি পাওয়া যায়নি।"
            }
        }
    }

    fun approvePendingSkillProposal(customName: String? = null) {
        agentCoordinator.approvePendingSkillProposal(customName)
        _memoryFeedbackMessage.value = "নতুন শেখা দক্ষতা অনুমোদন ও সংরক্ষণ করা হয়েছে।"
    }

    fun dismissPendingSkillProposal() {
        agentCoordinator.dismissPendingSkillProposal()
    }

    fun clearMemoryFeedback() {
        _memoryFeedbackMessage.value = null
        agentCoordinator.clearMemoryStatusNote()
    }

    // Phase 5: Autonomous Orchestration, Scheduling, Proactive Assistance, Dynamic Tools & Multi-Agent Actions
    fun refreshDynamicToolsAndMetrics() {
        _discoveredTools.value = dynamicToolManager.discoverTools()
        viewModelScope.launch {
            _toolReliabilityMetrics.value = selfReflectionEngine.computeToolReliabilityMetrics()
        }
    }

    fun updateProactiveSuggestionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateProactiveSuggestionsEnabled(enabled)
            _autonomousFeedbackMessage.value = if (enabled) {
                "স্মার্ট পরামর্শ চালু করা হয়েছে (Proactive suggestions enabled)."
            } else {
                "স্মার্ট পরামর্শ সম্পূর্ণ বন্ধ করা হয়েছে (Proactive suggestions disabled)."
            }
        }
    }

    fun dismissProactiveSuggestion(suggestionId: String) {
        _dismissedSuggestionIds.value = _dismissedSuggestionIds.value + suggestionId
    }

    fun runProactiveSuggestion(suggestion: ProactiveAssistantEngine.ProactiveSuggestion) {
        when (suggestion.actionKind) {
            ProactiveAssistantEngine.SuggestionActionKind.RUN_SKILL -> runSkill(suggestion.targetIdOrValue)
            ProactiveAssistantEngine.SuggestionActionKind.OPEN_APP -> runQuickOpenApp(suggestion.targetIdOrValue)
            ProactiveAssistantEngine.SuggestionActionKind.CHECK_TIME -> runQuickDateTime()
            ProactiveAssistantEngine.SuggestionActionKind.SET_ALARM -> runQuickSetAlarm(6, 0, suggestion.titleEn)
        }
        dismissProactiveSuggestion(suggestion.id)
    }

    fun createAndRunMultiStageGoal(goal: String) {
        viewModelScope.launch {
            val clean = goal.trim()
            if (clean.isEmpty()) return@launch
            val ctx = multiAgentOrchestrator.runControlledWorkflow(
                goal = clean,
                availableSkills = skills.value,
                executeSafePlan = false
            )
            _lastMultiAgentContext.value = ctx
            when (val created = longTermTaskOrchestrator.createTask(clean, ctx.plannedSubtasks)) {
                is LongTermTaskOrchestrator.CreateTaskOutcome.Created -> {
                    val runRes = longTermTaskOrchestrator.runTask(created.task.taskId)
                    _autonomousFeedbackMessage.value = formatLongTermRunOutcome(runRes)
                    refreshDynamicToolsAndMetrics()
                }
                is LongTermTaskOrchestrator.CreateTaskOutcome.InvalidDag -> {
                    _autonomousFeedbackMessage.value = created.reasonBn
                }
            }
        }
    }

    fun resumeLongTermTask(taskId: String, confirmSensitiveSubtaskId: String? = null) {
        viewModelScope.launch {
            val res = longTermTaskOrchestrator.runTask(
                taskId = taskId,
                userConfirmedSensitiveSubtaskId = confirmSensitiveSubtaskId
            )
            _autonomousFeedbackMessage.value = formatLongTermRunOutcome(res)
            refreshDynamicToolsAndMetrics()
        }
    }

    fun pauseLongTermTask(taskId: String) {
        viewModelScope.launch {
            longTermTaskOrchestrator.pauseTask(taskId)
            _autonomousFeedbackMessage.value = "কাজটি বিরতিতে রাখা হয়েছে (Task paused at checkpoint)."
        }
    }

    fun retryLongTermTask(taskId: String) {
        viewModelScope.launch {
            longTermTaskOrchestrator.retryFailedSubtasks(taskId)
            val res = longTermTaskOrchestrator.runTask(taskId)
            _autonomousFeedbackMessage.value = formatLongTermRunOutcome(res)
            refreshDynamicToolsAndMetrics()
        }
    }

    fun cancelLongTermTask(taskId: String) {
        viewModelScope.launch {
            longTermTaskOrchestrator.cancelTask(taskId, "Stopped by user")
            _autonomousFeedbackMessage.value = "কাজটি বাতিল করা হয়েছে (Task cancelled)."
        }
    }

    fun deleteLongTermTask(taskId: String) {
        viewModelScope.launch {
            longTermTaskOrchestrator.deleteTask(taskId)
        }
    }

    fun createSchedule(
        title: String,
        actionType: ScheduledActionType,
        targetAppOrSkillId: String,
        recurrence: ScheduleRecurrence,
        hourOfDay: Int,
        minuteOfHour: Int,
        intervalMinutes: Long,
        userApproved: Boolean,
        requiresAccessibility: Boolean
    ) {
        viewModelScope.launch {
            when (val res = taskScheduler.scheduleTask(
                title = title,
                actionType = actionType,
                targetAppOrSkillId = targetAppOrSkillId,
                recurrence = recurrence,
                hourOfDay = hourOfDay,
                minuteOfHour = minuteOfHour,
                intervalMinutes = intervalMinutes,
                userApproved = userApproved,
                requiresAccessibility = requiresAccessibility
            )) {
                is AgentTaskScheduler.ScheduleOutcome.Scheduled -> {
                    _autonomousFeedbackMessage.value = "সময়সূচী সংরক্ষিত হয়েছে: ${res.task.title}"
                }
                is AgentTaskScheduler.ScheduleOutcome.ApprovalRequired -> {
                    _autonomousFeedbackMessage.value = res.reasonBn
                }
                is AgentTaskScheduler.ScheduleOutcome.RejectedUnsafe -> {
                    _autonomousFeedbackMessage.value = res.reasonBn
                }
            }
        }
    }

    fun toggleScheduleEnabled(scheduleId: String, enabled: Boolean) {
        viewModelScope.launch {
            taskScheduler.setScheduleEnabled(scheduleId, enabled)
            _autonomousFeedbackMessage.value = if (enabled) {
                "সময়সূচী চালু করা হয়েছে।"
            } else {
                "সময়সূচী সাময়িকভাবে বন্ধ করা হয়েছে।"
            }
        }
    }

    fun deleteSchedule(scheduleId: String) {
        viewModelScope.launch {
            taskScheduler.deleteSchedule(scheduleId)
            _autonomousFeedbackMessage.value = "সময়সূচী মুছে ফেলা হয়েছে।"
        }
    }

    fun cancelAllBackgroundWork() {
        taskScheduler.cancelAllBackgroundWork()
        agentCoordinator.cancelCurrentTask("Cancelled all background work")
        _autonomousFeedbackMessage.value = "চলমান ব্যাকগ্রাউন্ড কাজগুলো বন্ধ করা হয়েছে।"
    }

    fun dismissSkillImprovementProposal(proposalId: String) {
        agentCoordinator.dismissImprovementProposal(proposalId)
    }

    fun clearAutonomousFeedback() {
        _autonomousFeedbackMessage.value = null
    }

    private fun formatLongTermRunOutcome(outcome: LongTermTaskOrchestrator.RunOutcome): String {
        return when (outcome) {
            is LongTermTaskOrchestrator.RunOutcome.Completed ->
                "সম্পন্ন হয়েছে: ${outcome.task.goal} (${outcome.task.completedCount}/${outcome.task.totalCount})"
            is LongTermTaskOrchestrator.RunOutcome.Paused ->
                "বিরতিতে আছে: ${outcome.task.goal} (${outcome.reason})"
            is LongTermTaskOrchestrator.RunOutcome.Cancelled ->
                "বাতিল হয়েছে: ${outcome.task.goal}"
            is LongTermTaskOrchestrator.RunOutcome.ConfirmationRequired ->
                "অনুমতি প্রয়োজন (${outcome.subtask.title}): ${outcome.reasonBn}"
            is LongTermTaskOrchestrator.RunOutcome.BlockedBySafety ->
                "নিরাপত্তার কারণে বন্ধ: ${outcome.reasonBn}"
            is LongTermTaskOrchestrator.RunOutcome.Failed ->
                "ব্যর্থ হয়েছে: ${outcome.reasonEn}"
            LongTermTaskOrchestrator.RunOutcome.NotFound ->
                "কাজটি পাওয়া যায়নি।"
        }
    }

    // Trusted Contacts CRUD
    fun addTrustedContact(name: String, phone: String, nickname: String, relationship: String) {
        viewModelScope.launch {
            trustedContactsRepository.addOrUpdateContact(
                name = name,
                phoneNumber = phone,
                nickname = nickname,
                relationship = relationship
            )
        }
    }

    fun removeTrustedContact(contactId: String) {
        viewModelScope.launch {
            trustedContactsRepository.removeContact(contactId)
        }
    }

    fun dialTrustedContact(contact: TrustedContact) {
        appLauncher.openDialer(contact.phoneNumber, contact.name)
    }

    // Agent Task Controls & Quick Actions
    fun approvePendingConfirmation() {
        agentCoordinator.approvePendingConfirmation()
    }

    fun rejectPendingConfirmation() {
        agentCoordinator.rejectPendingConfirmation()
    }

    fun cancelActiveAgentTask() {
        agentCoordinator.cancelCurrentTask()
    }

    fun clearAgentTaskHistory() {
        agentCoordinator.clearTaskHistory()
    }

    fun clearAgentTaskLogs() {
        agentCoordinator.taskLogger.clearLogs()
    }

    // Phase 3: Local Checkpoint Recovery & Diagnostic Harness Controls
    fun resumeRecoverableCheckpoint(taskId: String) {
        agentCoordinator.resumeCheckpointAsync(taskId)
    }

    fun cancelRecoverableCheckpoint(taskId: String) {
        agentCoordinator.cancelRecoverableCheckpoint(taskId)
    }

    fun refreshScreenSnapshot() {
        AgentAccessibilityService.activeInstance?.captureScreenSnapshot()
        refreshPermissions()
    }

    fun selectHarnessScenario(scenario: PhysicalDeviceTestHarness.DeviceTestScenario?) {
        val current = agentCoordinator.testHarness.activeScenario.value
        agentCoordinator.testHarness.setActiveScenario(if (current == scenario) null else scenario)
    }

    fun clearHarnessRecords() {
        agentCoordinator.testHarness.clearRecords()
    }

    fun runQuickOpenApp(appName: String) {
        agentCoordinator.executeDirectQuickAction(
            request = AgentActionRequest(
                actionType = AgentActionType.OPEN_APP,
                parameters = mapOf("app_name" to appName),
                reason = "User tapped quick shortcut for $appName"
            ),
            isGeminiAudioActive = isAiSpeaking.value
        )
    }

    fun runQuickDateTime() {
        agentCoordinator.executeDirectQuickAction(
            request = AgentActionRequest(
                actionType = AgentActionType.GET_DATE_TIME,
                reason = "User asked for current date and time"
            ),
            isGeminiAudioActive = isAiSpeaking.value
        )
    }

    fun runQuickReadScreen() {
        if (sessionState.value.isActive) {
            sendTextMessage("এই স্ক্রিনে কী লেখা আছে সহজ বাংলায় পড়ে শোনাও।")
        } else {
            agentCoordinator.executeDirectQuickAction(
                request = AgentActionRequest(
                    actionType = AgentActionType.READ_SCREEN,
                    reason = "User tapped Read Screen shortcut"
                ),
                isGeminiAudioActive = false
            )
        }
    }

    fun runQuickSetAlarm(hour24: Int, minute: Int, label: String = "ScreenSight Alarm") {
        agentCoordinator.executeDirectQuickAction(
            request = AgentActionRequest(
                actionType = AgentActionType.SET_ALARM,
                parameters = mapOf(
                    "hour_24" to hour24.toString(),
                    "minute" to minute.toString(),
                    "label" to label
                ),
                reason = "User tapped Set Alarm shortcut"
            ),
            isGeminiAudioActive = isAiSpeaking.value
        )
    }

    fun getInstalledApps(): List<AppLauncher.InstalledAppInfo> = appLauncher.getInstalledLauncherApps()

    // Existing Live Session Controls (100% preserved)
    fun requestStartLiveSession(onReadyToNavigateToLive: () -> Unit) {
        if (!settings.value.hasAcceptedPrivacyNotice) {
            _showFirstSessionPrivacyDialog.value = true
            return
        }
        startSessionUseCase()
        onReadyToNavigateToLive()
    }

    fun confirmFirstSessionPrivacyAndStart(onReadyToNavigateToLive: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setPrivacyNoticeAccepted(true)
            _showFirstSessionPrivacyDialog.value = false
            startSessionUseCase()
            onReadyToNavigateToLive()
        }
    }

    fun dismissFirstSessionPrivacyDialog() {
        _showFirstSessionPrivacyDialog.value = false
    }

    fun startLiveSessionDirectly() {
        startSessionUseCase()
    }

    fun stopLiveSession(clearTranscript: Boolean = false) {
        stopSessionUseCase(clearTranscript)
    }

    fun onMicrophonePermissionGranted() {
        refreshPermissions()
        startAudioUseCase()
    }

    fun onMicrophonePermissionDenied() {
        refreshPermissions()
        sessionManager.onScreenCaptureError(ScreenSightError.MicrophonePermissionDenied)
    }

    fun onRequestingScreenCapturePermission() {
        sessionManager.onRequestingScreenPermission()
    }

    fun onScreenCapturePermissionResult(granted: Boolean, resultCode: Int, data: Intent?) {
        sessionManager.onScreenPermissionResult(
            granted = granted,
            resultCode = resultCode,
            data = data,
            autoMinimizeOnStart = true
        )
        refreshPermissions()
    }

    fun togglePauseScreenSharing() {
        sessionManager.togglePauseScreenSharing()
    }

    fun stopScreenSharing() {
        sessionManager.stopScreenSharing()
        refreshPermissions()
    }

    fun toggleMicMute() {
        sessionManager.toggleMicrophoneMute()
    }

    fun toggleAiAudioMute() {
        sessionManager.toggleAiAudioMute()
    }

    fun sendTextMessage(text: String) {
        sendTextMessageUseCase(text)
    }

    fun clearTranscript() {
        sessionManager.clearConversationTranscript()
    }

    fun dismissError() {
        sessionManager.dismissError()
    }

    companion object {
        fun provideFactory(container: AppContainer): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ScreenSightViewModel(
                        appContext = container.appContext,
                        checkpointDatabase = container.checkpointDatabase,
                        apiKeyRepository = container.apiKeyRepository,
                        settingsRepository = container.settingsRepository,
                        geminiLiveRepository = container.geminiLiveRepository,
                        sessionManager = container.sessionManager,
                        startSessionUseCase = container.startSessionUseCase,
                        stopSessionUseCase = container.stopSessionUseCase,
                        startAudioUseCase = container.startAudioUseCase,
                        sendTextMessageUseCase = container.sendTextMessageUseCase,
                        agentCoordinator = container.agentCoordinator,
                        agentPermissionManager = container.agentPermissionManager,
                        trustedContactsRepository = container.trustedContactsRepository,
                        appLauncher = container.appLauncher,
                        memoryRepository = container.memoryRepository,
                        skillRepository = container.skillRepository,
                        longTermTaskOrchestrator = container.longTermTaskOrchestrator,
                        taskScheduler = container.taskScheduler,
                        dynamicToolManager = container.dynamicToolManager,
                        selfReflectionEngine = container.selfReflectionEngine,
                        proactiveAssistantEngine = container.proactiveAssistantEngine,
                        multiAgentOrchestrator = container.multiAgentOrchestrator,
                        safeWebAutomationManager = container.safeWebAutomationManager
                    ) as T
                }
            }
        }
    }
}
