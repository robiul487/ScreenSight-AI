package com.example.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionType
import com.example.agent.AgentCoordinator
import com.example.agent.AgentMode
import com.example.agent.AgentPermissionManager
import com.example.agent.AgentPermissionsSnapshot
import com.example.agent.AgentTaskStep
import com.example.agent.AppLauncher
import com.example.agent.PendingConfirmation
import com.example.agent.TrustedContact
import com.example.agent.TrustedContactsRepository
import com.example.data.local.ApiKeyRepository
import com.example.data.local.ApiKeySource
import com.example.data.local.ApiKeyStorage
import com.example.data.local.SettingsRepository
import com.example.data.repository.GeminiLiveRepository
import com.example.domain.model.AppSettings
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
import com.example.service.SessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class ApiValidationUiState {
    object Idle : ApiValidationUiState()
    object Validating : ApiValidationUiState()
    data class Valid(val message: String, val discoveredModels: List<String>) : ApiValidationUiState()
    data class Invalid(val error: ScreenSightError) : ApiValidationUiState()
}

class ScreenSightViewModel(
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
    private val appLauncher: AppLauncher
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

    // Android AI Agent State Flows
    val agentTaskSteps: StateFlow<List<AgentTaskStep>> = agentCoordinator.taskSteps
    val isAgentTaskRunning: StateFlow<Boolean> = agentCoordinator.isTaskRunning
    val pendingConfirmation: StateFlow<PendingConfirmation?> = agentCoordinator.pendingConfirmation
    val agentStatusBannerBn: StateFlow<String> = agentCoordinator.statusBannerBn
    val permissionsSnapshot: StateFlow<AgentPermissionsSnapshot> = agentPermissionManager.permissions
    val trustedContacts: StateFlow<List<TrustedContact>> = trustedContactsRepository.trustedContactsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _apiValidationState = MutableStateFlow<ApiValidationUiState>(ApiValidationUiState.Idle)
    val apiValidationState: StateFlow<ApiValidationUiState> = _apiValidationState.asStateFlow()

    private val _showFirstSessionPrivacyDialog = MutableStateFlow(false)
    val showFirstSessionPrivacyDialog: StateFlow<Boolean> = _showFirstSessionPrivacyDialog.asStateFlow()

    init {
        refreshPermissions()
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

    fun updateAgentMode(mode: AgentMode) {
        viewModelScope.launch {
            settingsRepository.updateAgentMode(mode)
        }
    }

    fun updateAgentToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAgentToolsEnabled(enabled)
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
                        appLauncher = container.appLauncher
                    ) as T
                }
            }
        }
    }
}
