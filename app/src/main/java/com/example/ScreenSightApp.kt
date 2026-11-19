package com.example

import android.app.Application
import android.content.Context
import com.example.agent.AgentActionExecutor
import com.example.agent.AgentCoordinator
import com.example.agent.AgentPermissionManager
import com.example.agent.AppLauncher
import com.example.agent.BanglaVoiceFeedbackManager
import com.example.agent.TrustedContactsRepository
import com.example.data.local.ApiKeyRepository
import com.example.data.local.ApiKeyStorage
import com.example.data.local.SettingsRepository
import com.example.data.remote.GeminiWebSocketClient
import com.example.data.repository.GeminiLiveRepository
import com.example.domain.usecase.SendScreenFrameUseCase
import com.example.domain.usecase.SendTextMessageUseCase
import com.example.domain.usecase.StartAudioUseCase
import com.example.domain.usecase.StartSessionUseCase
import com.example.domain.usecase.StopSessionUseCase
import com.example.service.AudioCaptureManager
import com.example.service.AudioPlaybackManager
import com.example.service.FrameProcessor
import com.example.service.SessionManager

class AppContainer(context: Context) {
    val apiKeyRepository: ApiKeyStorage = ApiKeyRepository(context)
    val settingsRepository = SettingsRepository(context)
    val webSocketClient = GeminiWebSocketClient()
    val geminiLiveRepository = GeminiLiveRepository(webSocketClient)
    val audioCaptureManager = AudioCaptureManager(context)
    val audioPlaybackManager = AudioPlaybackManager(context)
    val frameProcessor = FrameProcessor()

    // Android AI Agent Core Engine (reuses existing Gemini Live WebSocket & Audio pipeline)
    val appLauncher = AppLauncher(context)
    val trustedContactsRepository = TrustedContactsRepository(context)
    val agentPermissionManager = AgentPermissionManager(context)
    val banglaVoiceFeedbackManager = BanglaVoiceFeedbackManager(context)
    val agentActionExecutor = AgentActionExecutor(
        context = context,
        appLauncher = appLauncher,
        trustedContactsRepository = trustedContactsRepository
    )
    val agentCoordinator = AgentCoordinator(
        geminiRepository = geminiLiveRepository,
        actionExecutor = agentActionExecutor,
        settingsRepository = settingsRepository,
        voiceFeedback = banglaVoiceFeedbackManager
    )

    val sessionManager = SessionManager(
        appContext = context,
        geminiRepository = geminiLiveRepository,
        apiKeyStorage = apiKeyRepository,
        settingsRepository = settingsRepository,
        audioCaptureManager = audioCaptureManager,
        audioPlaybackManager = audioPlaybackManager,
        frameProcessor = frameProcessor,
        agentCoordinator = agentCoordinator,
        trustedContactsRepository = trustedContactsRepository
    )

    val startSessionUseCase = StartSessionUseCase(sessionManager)
    val stopSessionUseCase = StopSessionUseCase(sessionManager)
    val sendScreenFrameUseCase = SendScreenFrameUseCase(sessionManager)
    val startAudioUseCase = StartAudioUseCase(sessionManager)
    val sendTextMessageUseCase = SendTextMessageUseCase(sessionManager)
}

class ScreenSightApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}
