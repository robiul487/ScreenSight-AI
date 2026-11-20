package com.example

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.agent.AgentActionExecutor
import com.example.agent.AgentCoordinator
import com.example.agent.AgentPermissionManager
import com.example.agent.AgentToolRegistry
import com.example.agent.AppLauncher
import com.example.agent.BanglaVoiceFeedbackManager
import com.example.agent.DynamicToolManager
import com.example.agent.IntelligentMemoryRetriever
import com.example.agent.LongTermTaskOrchestrator
import com.example.agent.MultiAgentOrchestrator
import com.example.agent.PhysicalDeviceTestHarness
import com.example.agent.ProactiveAssistantEngine
import com.example.agent.SafeWebAutomationManager
import com.example.agent.SelfReflectionEngine
import com.example.agent.SkillLearningEngine
import com.example.agent.TrustedContactsRepository
import com.example.data.local.ApiKeyRepository
import com.example.data.local.ApiKeyStorage
import com.example.data.local.SettingsRepository
import com.example.data.local.checkpoint.AgentCheckpointDatabase
import com.example.data.local.checkpoint.AgentTaskCheckpointRepository
import com.example.data.local.memory.AgentMemoryRepository
import com.example.data.local.orchestration.LongTermTaskRepository
import com.example.data.local.schedule.ScheduledTaskRepository
import com.example.data.local.skill.AgentSkillRepository
import com.example.data.remote.GeminiWebSocketClient
import com.example.data.repository.GeminiLiveRepository
import com.example.domain.usecase.SendScreenFrameUseCase
import com.example.domain.usecase.SendTextMessageUseCase
import com.example.domain.usecase.StartAudioUseCase
import com.example.domain.usecase.StartSessionUseCase
import com.example.domain.usecase.StopSessionUseCase
import com.example.service.AgentTaskScheduler
import com.example.service.AudioCaptureManager
import com.example.service.AudioPlaybackManager
import com.example.service.FrameProcessor
import com.example.service.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val apiKeyRepository: ApiKeyStorage = ApiKeyRepository(context)
    private val settingsDataStore = PreferenceDataStoreFactory.create(
        scope = appScope,
        produceFile = { context.applicationContext.preferencesDataStoreFile("screensight_settings") }
    )
    val settingsRepository = SettingsRepository(settingsDataStore)
    val webSocketClient = GeminiWebSocketClient()
    val geminiLiveRepository = GeminiLiveRepository(webSocketClient)
    val audioCaptureManager = AudioCaptureManager(context)
    val audioPlaybackManager = AudioPlaybackManager(context)
    val frameProcessor = FrameProcessor()

    private val memoryEnabledState = AtomicBoolean(true)
    private val proactiveEnabledState = AtomicBoolean(true)

    // Android AI Agent Core Engine (reuses existing Gemini Live WebSocket & Audio pipeline)
    val appLauncher = AppLauncher(context)
    val trustedContactsRepository = TrustedContactsRepository(context)
    val agentPermissionManager = AgentPermissionManager(context)
    val banglaVoiceFeedbackManager = BanglaVoiceFeedbackManager(context)
    val checkpointDatabase = AgentCheckpointDatabase.getInstance(context)
    val checkpointRepository = AgentTaskCheckpointRepository(checkpointDatabase.checkpointDao())
    val physicalDeviceTestHarness = PhysicalDeviceTestHarness()

    // Phase 4: Strictly On-Device Long-Term Memory & Reusable Skills System
    val memoryRepository = AgentMemoryRepository(
        dao = checkpointDatabase.memoryDao(),
        isMemoryEnabledProvider = { memoryEnabledState.get() }
    )
    val memoryRetriever = IntelligentMemoryRetriever(
        memoryRepository = memoryRepository,
        isMemoryEnabledProvider = { memoryEnabledState.get() }
    )
    val skillRepository = AgentSkillRepository(
        dao = checkpointDatabase.skillDao()
    )

    // Phase 5: Local Orchestration, Dynamic Tool Discovery, Scheduling, Reflection & Safe Web
    val longTermTaskRepository = LongTermTaskRepository(checkpointDatabase.longTermTaskDao())
    val scheduledTaskRepository = ScheduledTaskRepository(checkpointDatabase.scheduledTaskDao())
    val toolMetricDao = checkpointDatabase.toolMetricDao()

    val agentActionExecutor = AgentActionExecutor(
        context = context,
        appLauncher = appLauncher,
        trustedContactsRepository = trustedContactsRepository
    )

    val sharedToolRegistry = AgentToolRegistry(
        defaultActionExecutor = { req, confirmed -> agentActionExecutor.execute(req, confirmed) }
    )
    val dynamicToolManager = DynamicToolManager(
        toolRegistry = sharedToolRegistry,
        metricDao = toolMetricDao,
        hasContactsPermissionProvider = { agentPermissionManager.permissions.value.hasContacts }
    )
    val selfReflectionEngine = SelfReflectionEngine(metricDao = toolMetricDao)
    val skillLearningEngine = SkillLearningEngine(sharedToolRegistry)

    val agentCoordinator = AgentCoordinator(
        geminiRepository = geminiLiveRepository,
        actionExecutor = agentActionExecutor,
        settingsRepository = settingsRepository,
        voiceFeedback = banglaVoiceFeedbackManager,
        toolRegistry = sharedToolRegistry,
        checkpointRepository = checkpointRepository,
        testHarness = physicalDeviceTestHarness,
        memoryRepository = memoryRepository,
        memoryRetriever = memoryRetriever,
        skillRepository = skillRepository,
        skillLearningEngine = skillLearningEngine,
        isMemoryEnabledProvider = { memoryEnabledState.get() },
        dynamicToolManager = dynamicToolManager,
        selfReflectionEngine = selfReflectionEngine
    ).also { coordinator ->
        // Inspect local incomplete tasks on startup without ever auto-replaying sensitive actions
        coordinator.launchStartupCheckpointInspection()
    }

    val longTermTaskOrchestrator = LongTermTaskOrchestrator(
        repository = longTermTaskRepository,
        dynamicToolManager = dynamicToolManager,
        toolExecutor = { req, confirmed -> agentActionExecutor.execute(req, confirmed) }
    )

    val taskScheduler = AgentTaskScheduler(
        context = context,
        repository = scheduledTaskRepository
    )

    val proactiveAssistantEngine = ProactiveAssistantEngine(
        isProactiveEnabledProvider = { proactiveEnabledState.get() }
    )

    val multiAgentOrchestrator = MultiAgentOrchestrator(
        dynamicToolManager = dynamicToolManager,
        memoryRetriever = memoryRetriever,
        skillLearningEngine = skillLearningEngine,
        toolExecutor = { req, confirmed -> agentActionExecutor.execute(req, confirmed) }
    )

    val appContext: Context = context.applicationContext

    val safeWebAutomationManager = SafeWebAutomationManager()

    init {
        appScope.launch {
            settingsRepository.settingsFlow.collectLatest { settings ->
                memoryEnabledState.set(settings.memoryEnabled)
                proactiveEnabledState.set(settings.proactiveSuggestionsEnabled)
                banglaVoiceFeedbackManager.setSpeechRate(settings.ttsSpeechRate)
                banglaVoiceFeedbackManager.setVoiceOutputEnabled(settings.voiceOutputEnabled)
                audioPlaybackManager.setOutputVolume(settings.voiceOutputVolume)
                agentCoordinator.updateAutonomyLevel(settings.autonomyLevel)
                agentCoordinator.updateExecutionConfig(
                    com.example.agent.AgentExecutionConfig(
                        maxStepsPerTask = settings.maxTaskSteps,
                        maxRetriesPerAction = settings.maxActionRetries,
                        defaultToolTimeoutMs = settings.agentToolTimeoutSec * 1_000L
                    )
                )
            }
        }
        appScope.launch {
            runCatching { skillRepository.seedDefaultSkillsIfNeeded() }
            runCatching { memoryRepository.purgeExpiredMemories() }
            runCatching { longTermTaskOrchestrator.inspectAndPrepareUnfinishedTasksOnStartup() }
        }
    }

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

    fun shutdown() {
        appScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

class ScreenSightApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }

    override fun onTerminate() {
        if (::container.isInitialized) {
            container.shutdown()
        }
        super.onTerminate()
    }
}
