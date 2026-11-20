package com.example.agent

import com.example.data.local.SettingsRepository
import com.example.data.local.checkpoint.AgentTaskCheckpointRepository
import com.example.data.local.checkpoint.RecoverableTaskCheckpoint
import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.memory.AgentMemoryRepository
import com.example.data.local.memory.MemoryCategory
import com.example.data.local.memory.MemoryExpirationPolicy
import com.example.data.local.memory.MemorySource
import com.example.data.local.skill.AgentSkill
import com.example.data.local.skill.AgentSkillRepository
import com.example.data.local.skill.SkillProposal
import com.example.data.remote.BidiFunctionCall
import com.example.data.remote.BidiFunctionResponse
import com.example.data.repository.GeminiLiveRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Phase 2/3 Autonomous Agent Execution Engine & Coordinator.
 *
 * Implements the full autonomous mobile execution cycle:
 * USER REQUEST -> UNDERSTANDING -> PLANNING -> EXECUTING -> OBSERVING -> EVALUATING -> RECOVERING / COMPLETED / FAILED / CANCELLED
 *
 * Phase 3 Reliability Guarantees:
 * - Natural Bengali & English voice confirmation bound to exact pending action ID with timeout & ambiguity rejection.
 * - Duplicate tool call ID deduplication preventing double execution.
 * - Local Room task checkpoints (strictly sanitized; never persists credentials/OTPs/private messages).
 * - Safe startup inspection & user-controlled resume that NEVER auto-replays sensitive actions.
 * - Observable [lastActionVerification] and [PhysicalDeviceTestHarness] structured telemetry.
 */
class AgentCoordinator(
    private val geminiRepository: GeminiLiveRepository?,
    private val actionExecutor: AgentActionExecutor?,
    private val settingsRepository: SettingsRepository?,
    private val voiceFeedback: BanglaVoiceFeedbackManager?,
    val toolRegistry: AgentToolRegistry = AgentToolRegistry(
        defaultActionExecutor = actionExecutor?.let { exec ->
            { req, confirmed -> exec.execute(req, confirmed) }
        }
    ),
    val taskLogger: AgentTaskLogger = AgentTaskLogger(),
    @Volatile
    var config: AgentExecutionConfig = AgentExecutionConfig(),
    private val screenSnapshotProvider: () -> ScreenUiSnapshot? = {
        AgentAccessibilityService.activeInstance?.captureScreenSnapshot()
            ?: AgentAccessibilityService.latestSnapshot.value
    },
    private val activePackageProvider: () -> String = {
        AgentAccessibilityService.activePackageName.value
    },
    private val toolResponseSender: (List<BidiFunctionResponse>) -> Unit = { responses ->
        if (geminiRepository != null && geminiRepository.isSessionActive()) {
            geminiRepository.sendToolResponses(responses)
        }
    },
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val checkpointRepository: AgentTaskCheckpointRepository? = null,
    val testHarness: PhysicalDeviceTestHarness = PhysicalDeviceTestHarness(),
    private val memoryRepository: AgentMemoryRepository? = null,
    private val memoryRetriever: IntelligentMemoryRetriever? = null,
    private val skillRepository: AgentSkillRepository? = null,
    private val skillLearningEngine: SkillLearningEngine = SkillLearningEngine(toolRegistry),
    private val isMemoryEnabledProvider: () -> Boolean = { true },
    private val dynamicToolManager: DynamicToolManager? = null,
    private val selfReflectionEngine: SelfReflectionEngine? = null
) {
    @Volatile
    var autonomyLevel: com.example.domain.model.AutonomyLevel = com.example.domain.model.AutonomyLevel.BALANCED
        private set

    fun updateExecutionConfig(newConfig: AgentExecutionConfig) {
        config = newConfig
    }

    fun updateAutonomyLevel(level: com.example.domain.model.AutonomyLevel) {
        autonomyLevel = level
    }

    private fun requiresExtraAutonomyConfirmation(request: AgentActionRequest): Boolean {
        if (autonomyLevel != com.example.domain.model.AutonomyLevel.ASK_FIRST) return false
        return when (request.actionType) {
            AgentActionType.GET_DATE_TIME,
            AgentActionType.READ_SCREEN,
            AgentActionType.GET_SCREEN_STATE,
            AgentActionType.GET_CURRENT_APP,
            AgentActionType.VERIFY_SCREEN,
            AgentActionType.WAIT_FOR_UI -> false
            else -> true
        }
    }
    enum class VoiceConfirmationOutcome {
        NO_PENDING_CONFIRMATION,
        APPROVED,
        REJECTED,
        EXPIRED,
        AMBIGUOUS_KEPT_WAITING,
        CANCELLED_FOR_NEW_INSTRUCTION
    }

    sealed class ResumeCheckpointOutcome {
        data class Resumed(
            val plan: AgentTaskPlan,
            val skippedSensitiveCount: Int
        ) : ResumeCheckpointOutcome()

        data class BlockedSensitiveOnly(val reasonEn: String, val reasonBn: String) : ResumeCheckpointOutcome()
        data object NotFound : ResumeCheckpointOutcome()
    }

    sealed class SkillExecutionOutcome {
        data class Completed(val skillId: String, val stepsExecuted: Int, val summaryEn: String) : SkillExecutionOutcome()
        data class BlockedBySafety(val skillId: String, val reasonEn: String, val reasonBn: String) : SkillExecutionOutcome()
        data class Cancelled(val skillId: String, val reasonEn: String) : SkillExecutionOutcome()
        data class Failed(val skillId: String, val reasonEn: String, val reasonBn: String) : SkillExecutionOutcome()
        data class DisabledOrUnapproved(val skillId: String) : SkillExecutionOutcome()
        data class AlreadyRunning(val activeSkillId: String?) : SkillExecutionOutcome()
        data object NotFound : SkillExecutionOutcome()
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val executionMutex = Mutex()

    private val _executionState = MutableStateFlow(AgentExecutionState.IDLE)
    val executionState: StateFlow<AgentExecutionState> = _executionState.asStateFlow()

    private val _activePlan = MutableStateFlow<AgentTaskPlan?>(null)
    val activePlan: StateFlow<AgentTaskPlan?> = _activePlan.asStateFlow()

    private val _taskSteps = MutableStateFlow<List<AgentTaskStep>>(emptyList())
    val taskSteps: StateFlow<List<AgentTaskStep>> = _taskSteps.asStateFlow()

    private val _isTaskRunning = MutableStateFlow(false)
    val isTaskRunning: StateFlow<Boolean> = _isTaskRunning.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _statusBannerBn = MutableStateFlow("আমি কী করতে পারি, বলুন?")
    val statusBannerBn: StateFlow<String> = _statusBannerBn.asStateFlow()

    private val _lastActionVerification = MutableStateFlow<LastActionVerification?>(null)
    val lastActionVerification: StateFlow<LastActionVerification?> = _lastActionVerification.asStateFlow()

    private val _recoverableCheckpoints = MutableStateFlow<List<RecoverableTaskCheckpoint>>(emptyList())
    val recoverableCheckpoints: StateFlow<List<RecoverableTaskCheckpoint>> = _recoverableCheckpoints.asStateFlow()

    // Phase 4: Local Long-Term Memory & Reusable Skills StateFlows
    private val _pendingSkillProposal = MutableStateFlow<SkillProposal?>(null)
    val pendingSkillProposal: StateFlow<SkillProposal?> = _pendingSkillProposal.asStateFlow()

    private val _activeSkillId = MutableStateFlow<String?>(null)
    val activeSkillId: StateFlow<String?> = _activeSkillId.asStateFlow()

    private val _lastRetrievedMemories = MutableStateFlow<List<AgentMemoryRecord>>(emptyList())
    val lastRetrievedMemories: StateFlow<List<AgentMemoryRecord>> = _lastRetrievedMemories.asStateFlow()

    private val _lastMemoryStatusNote = MutableStateFlow<String?>(null)
    val lastMemoryStatusNote: StateFlow<String?> = _lastMemoryStatusNote.asStateFlow()

    // Phase 5: Post-Task Self-Reflection & Skill Improvement Proposals
    private val _latestReflectionReport = MutableStateFlow<SelfReflectionEngine.TaskReflectionReport?>(null)
    val latestReflectionReport: StateFlow<SelfReflectionEngine.TaskReflectionReport?> = _latestReflectionReport.asStateFlow()

    private val _skillImprovementProposals = MutableStateFlow<List<SelfReflectionEngine.SkillImprovementProposal>>(emptyList())
    val skillImprovementProposals: StateFlow<List<SelfReflectionEngine.SkillImprovementProposal>> = _skillImprovementProposals.asStateFlow()

    private var activeTaskJob: Job? = null
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private val cancelledCallIds = HashSet<String>()
    private val executedCallIds = LinkedHashSet<String>()
    private var currentTaskId: String? = null
    private var latestUserRequestText: String = ""
    private var currentTaskRetryTotal: Int = 0

    /**
     * Called when the user speaks or types a new request.
     * - If awaiting confirmation ([AgentExecutionState.WAITING_FOR_USER]), interprets Bengali/English
     *   confirmation naturally via [VoiceConfirmationParser], bound strictly to the active pending ID.
     * - Otherwise records the request, begins a fresh task context if idle, and enters UNDERSTANDING.
     */
    fun onUserRequestReceived(userText: String) {
        val clean = userText.trim()
        if (clean.isEmpty()) return

        if (_pendingConfirmation.value != null) {
            val outcome = handleSpokenConfirmationUtterance(clean)
            if (outcome != VoiceConfirmationOutcome.CANCELLED_FOR_NEW_INSTRUCTION &&
                outcome != VoiceConfirmationOutcome.EXPIRED
            ) {
                return
            }
        }

        latestUserRequestText = clean
        if (memoryRepository != null && isMemoryEnabledProvider()) {
            val explicitCandidate = IntelligentMemoryRetriever.parseExplicitRememberInstruction(clean)
            if (explicitCandidate != null) {
                scope.launch(Dispatchers.IO) {
                    handleExplicitRememberSuspending(clean)
                }
            }
        }
        if (!_isTaskRunning.value) {
            val newTaskId = UUID.randomUUID().toString().take(8)
            currentTaskId = newTaskId
            currentTaskRetryTotal = 0
            taskLogger.startTask(newTaskId, clean)
            transitionState(AgentExecutionState.UNDERSTANDING, "User request received")
            _statusBannerBn.value = "বুঝছি..."
        }
    }

    /**
     * Interprets a Bengali or English spoken/typed utterance while a sensitive action awaits confirmation.
     * Strictly bound to the exact current pending confirmation ID, rejects ambiguous replies,
     * honors timeout expiration, and cancels the pending confirmation if the user gives a new command.
     */
    fun handleSpokenConfirmationUtterance(
        utterance: String,
        nowMillis: Long = System.currentTimeMillis()
    ): VoiceConfirmationOutcome {
        val pending = _pendingConfirmation.value ?: return VoiceConfirmationOutcome.NO_PENDING_CONFIRMATION

        if (pending.isExpired(nowMillis)) {
            rejectPendingConfirmation(expectedId = pending.id)
            _statusBannerBn.value = "অনুমতির সময় শেষ হয়ে গেছে। কাজটি বাতিল করা হয়েছে।"
            currentTaskId?.let { tid ->
                taskLogger.logError(tid, "Pending confirmation ${pending.id} expired before user reply")
            }
            return VoiceConfirmationOutcome.EXPIRED
        }

        return when (VoiceConfirmationParser.classify(utterance)) {
            VoiceConfirmationParser.ConfirmationIntent.APPROVE -> {
                val ok = approvePendingConfirmation(expectedId = pending.id, nowMillis = nowMillis)
                if (ok) {
                    _statusBannerBn.value = "অনুমতি পাওয়া গেছে, কাজটি করছি..."
                    VoiceConfirmationOutcome.APPROVED
                } else {
                    VoiceConfirmationOutcome.EXPIRED
                }
            }
            VoiceConfirmationParser.ConfirmationIntent.REJECT -> {
                rejectPendingConfirmation(expectedId = pending.id)
                _statusBannerBn.value = "আপনার অনুরোধে কাজটি বাতিল করা হয়েছে।"
                VoiceConfirmationOutcome.REJECTED
            }
            VoiceConfirmationParser.ConfirmationIntent.AMBIGUOUS -> {
                _statusBannerBn.value = "স্পষ্ট বোঝা যায়নি — অনুমতি দিতে 'হ্যাঁ' অথবা বাতিল করতে 'না' বলুন।"
                currentTaskId?.let { tid ->
                    taskLogger.logError(tid, "Ambiguous confirmation reply rejected (kept waiting for action ${pending.id})")
                }
                VoiceConfirmationOutcome.AMBIGUOUS_KEPT_WAITING
            }
            VoiceConfirmationParser.ConfirmationIntent.NEW_INSTRUCTION -> {
                rejectPendingConfirmation(expectedId = pending.id)
                _statusBannerBn.value = "নতুন নির্দেশনা পাওয়ায় আগের অনুমতি বাতিল করা হয়েছে।"
                currentTaskId?.let { tid ->
                    taskLogger.logStateTransition(
                        tid,
                        AgentExecutionState.WAITING_FOR_USER,
                        AgentExecutionState.CANCELLED,
                        "Pending confirmation ${pending.id} cancelled by new user instruction"
                    )
                }
                VoiceConfirmationOutcome.CANCELLED_FOR_NEW_INSTRUCTION
            }
        }
    }

    /**
     * Called when the user barges in via voice while an autonomous task is running or awaiting input.
     * When [preservePendingConfirmation] is true (e.g., VAD onset while awaiting spoken "হ্যাঁ"/"Yes"),
     * the pending confirmation is kept intact so [VoiceConfirmationParser] can evaluate the transcript.
     */
    fun onUserInterruption(preservePendingConfirmation: Boolean = false) {
        if (preservePendingConfirmation && _pendingConfirmation.value != null) {
            voiceFeedback?.stopSpeaking()
            return
        }
        if (_isTaskRunning.value || _pendingConfirmation.value != null) {
            cancelCurrentTask(reason = "Interrupted by new user voice command")
        }
    }

    /**
     * Handles a batch of structured tool calls received from Gemini Live over the active WebSocket.
     * Runs sequentially with concurrency protection so parallel batches never conflict.
     */
    fun onGeminiToolCalls(
        functionCalls: List<BidiFunctionCall>,
        onSystemNote: (String) -> Unit = {}
    ) {
        if (functionCalls.isEmpty()) return

        activeTaskJob = scope.launch {
            executionMutex.withLock {
                processGeminiToolCallsLocked(functionCalls, onSystemNote)
            }
        }
    }

    /**
     * Suspend entry point for deterministic unit & integration testing (avoids arbitrary sleeps).
     */
    suspend fun processToolCallsSuspending(
        functionCalls: List<BidiFunctionCall>,
        onSystemNote: (String) -> Unit = {}
    ): List<BidiFunctionResponse> {
        return executionMutex.withLock {
            processGeminiToolCallsLocked(functionCalls, onSystemNote)
        }
    }

    private suspend fun processGeminiToolCallsLocked(
        functionCalls: List<BidiFunctionCall>,
        onSystemNote: (String) -> Unit
    ): List<BidiFunctionResponse> {
        val taskId = ensureActiveTaskId(latestUserRequestText.ifBlank { "Voice agent task" })
        _isTaskRunning.value = true

        val responses = mutableListOf<BidiFunctionResponse>()

        for (index in functionCalls.indices) {
            val call = functionCalls[index]

            if (call.id != null && cancelledCallIds.remove(call.id)) {
                responses.add(buildCancelledResponse(call))
                continue
            }

            // Duplicate tool-call execution protection (Phase 3, Section 7)
            if (!call.id.isNullOrBlank() && !recordExecutedCallId(call.id)) {
                val duplicateResult = AgentActionResult(
                    success = false,
                    status = "DUPLICATE_CALL_IGNORED",
                    summaryEn = "Duplicate tool call ID \"${call.id}\" was ignored to prevent double execution.",
                    summaryBn = "একই কাজ দ্বিতীয়বার চালানো থেকে বিরত রাখা হয়েছে।"
                )
                taskLogger.logError(taskId, duplicateResult.summaryEn)
                responses.add(toFunctionResponse(call, duplicateResult, null))
                continue
            }

            val params = extractParams(call)
            taskLogger.logToolCall(taskId, call.name, params)

            // 1. Centralized Tool Registry lookup & local argument validation
            val (registeredTool, validation) = toolRegistry.validateCall(call.name, params)
            if (!validation.isValid || registeredTool == null) {
                val invalidResult = AgentActionResult(
                    success = false,
                    status = validation.errorCode.ifBlank { "INVALID_ARGUMENTS" },
                    summaryEn = validation.errorMessageEn,
                    summaryBn = validation.errorMessageBn
                )
                taskLogger.logToolResult(taskId, call.name, invalidResult)
                responses.add(toFunctionResponse(call, invalidResult, null))
                continue
            }

            // 2. Planning & Task Lifecycle Tools (do not count against device action step limit)
            when (registeredTool.actionType) {
                AgentActionType.CREATE_PLAN -> {
                    transitionState(AgentExecutionState.PLANNING, "Generating multi-step task plan")
                    val plan = buildTaskPlanFromParams(taskId, params)
                    _activePlan.value = plan
                    taskLogger.logPlan(taskId, plan, isUpdate = false)
                    saveCheckpointAsync(taskId, AgentExecutionState.PLANNING, plan)
                    _statusBannerBn.value = "পরিকল্পনা করছি..."
                    val planRes = AgentActionResult(
                        success = true,
                        status = "PLAN_CREATED",
                        summaryEn = "Created ${plan.steps.size}-step plan for \"${plan.taskDescription}\". Proceed to execute step 1.",
                        summaryBn = "কাজের পরিকল্পনা তৈরি করেছি।",
                        details = mapOf("task_id" to taskId, "step_count" to plan.steps.size.toString())
                    )
                    taskLogger.logToolResult(taskId, call.name, planRes)
                    responses.add(toFunctionResponse(call, planRes, null))
                    continue
                }

                AgentActionType.UPDATE_PLAN -> {
                    transitionState(AgentExecutionState.PLANNING, params["reason"].orEmpty())
                    val updated = updateActivePlanFromParams(taskId, params)
                    _activePlan.value = updated
                    taskLogger.logPlan(taskId, updated, isUpdate = true)
                    saveCheckpointAsync(taskId, AgentExecutionState.PLANNING, updated)
                    val updateRes = AgentActionResult(
                        success = true,
                        status = "PLAN_UPDATED",
                        summaryEn = "Updated remaining plan (${updated.steps.size} steps). Continue with next step.",
                        summaryBn = "কাজের পরিকল্পনা পরিবর্তন করেছি।"
                    )
                    taskLogger.logToolResult(taskId, call.name, updateRes)
                    responses.add(toFunctionResponse(call, updateRes, null))
                    continue
                }

                AgentActionType.COMPLETE_TASK -> {
                    val statusParam = params["status"]?.trim()?.lowercase().orEmpty()
                    val isSuccess = statusParam != "failed"
                    val summaryEn = params["summary_en"].orEmpty().ifBlank {
                        if (isSuccess) "Task completed." else "Task could not be completed."
                    }
                    val summaryBn = params["summary_bn"].orEmpty().ifBlank {
                        if (isSuccess) "কাজটি সম্পন্ন হয়েছে।" else "কাজটি সম্পন্ন করা যায়নি।"
                    }
                    val finalState = if (isSuccess) AgentExecutionState.COMPLETED else AgentExecutionState.FAILED
                    transitionState(finalState, summaryEn)
                    taskLogger.logFinished(taskId, finalState, summaryEn)
                    _statusBannerBn.value = summaryBn
                    _activePlan.update { plan ->
                        plan?.copy(
                            steps = plan.steps.map { step ->
                                if (isSuccess && step.status == AgentStepStatus.RUNNING) {
                                    step.copy(status = AgentStepStatus.COMPLETED)
                                } else {
                                    step
                                }
                            }
                        )
                    }
                    val completedPlanSnapshot = _activePlan.value
                    val completedStepsSnapshot = _taskSteps.value
                    val completedRequestSnapshot = latestUserRequestText
                    saveCheckpointAsync(
                        taskId = taskId,
                        state = finalState,
                        plan = completedPlanSnapshot,
                        lastError = if (isSuccess) null else summaryEn
                    )
                    if (isSuccess) {
                        recordCompletedTaskLearningAndHistory(
                            taskId = taskId,
                            taskRequest = completedRequestSnapshot.ifBlank { completedPlanSnapshot?.taskDescription.orEmpty() },
                            summaryEn = summaryEn,
                            plan = completedPlanSnapshot,
                            executedSteps = completedStepsSnapshot
                        )
                    }
                    val completeRes = AgentActionResult(
                        success = isSuccess,
                        status = if (isSuccess) "TASK_COMPLETED" else "TASK_FAILED",
                        summaryEn = summaryEn,
                        summaryBn = summaryBn
                    )
                    responses.add(toFunctionResponse(call, completeRes, null))
                    persistTaskSummary("Task ${finalState.labelEn}")
                    currentTaskId = null
                    continue
                }

                else -> Unit
            }

            // 3. Enforce Max Steps Per Task Guardrail (prevents infinite autonomous loops)
            val planNow = ensurePlanExists(taskId, registeredTool.actionType, params)
            if (planNow.totalToolStepsExecuted >= config.maxStepsPerTask) {
                val limitResult = AgentActionResult(
                    success = false,
                    status = "MAX_STEPS_EXCEEDED",
                    summaryEn = "Stopped autonomous execution after reaching the maximum limit of ${config.maxStepsPerTask} steps to prevent an infinite loop. Explain current status to the user.",
                    summaryBn = "নিরাপত্তার জন্য সর্বোচ্চ ধাপের সীমা পূর্ণ হওয়ায় কাজটি থামানো হয়েছে।"
                )
                transitionState(AgentExecutionState.FAILED, "Max steps (${config.maxStepsPerTask}) reached")
                taskLogger.logError(taskId, limitResult.summaryEn)
                taskLogger.logFinished(taskId, AgentExecutionState.FAILED, limitResult.summaryEn)
                responses.add(toFunctionResponse(call, limitResult, null))
                _isTaskRunning.value = false
                currentTaskId = null
                break
            }

            // 4. Execute action with Safety Check -> Confirmation -> Observation -> Evaluation -> Bounded Retry
            val request = AgentActionRequest(
                callId = call.id,
                actionType = registeredTool.actionType,
                parameters = params,
                reason = params["reason"].orEmpty()
            )
            val stepId = UUID.randomUUID().toString()
            addOrUpdateStep(
                AgentTaskStep(
                    id = stepId,
                    callId = call.id,
                    actionType = registeredTool.actionType,
                    titleEn = buildStepTitleEn(registeredTool.actionType, params),
                    titleBn = buildStepTitleBn(registeredTool.actionType, params),
                    status = AgentStepStatus.RUNNING
                )
            )
            incrementPlanStepCounter(registeredTool.actionType, params)

            val (finalResult, evaluation) = executeWithObservationAndRecovery(
                taskId = taskId,
                stepId = stepId,
                tool = registeredTool,
                request = request,
                expectedOutcome = findExpectedOutcomeForAction(registeredTool.actionType),
                onSystemNote = onSystemNote
            )

            val stepStatus = when {
                finalResult.status == "USER_CANCELLED" || finalResult.status == "CANCELLED" -> AgentStepStatus.CANCELLED
                finalResult.success -> AgentStepStatus.COMPLETED
                else -> AgentStepStatus.FAILED
            }
            updateStepStatus(stepId, stepStatus, evaluation?.evaluationNoteEn ?: finalResult.summaryEn)
            updatePlanStepResult(registeredTool.actionType, stepStatus, finalResult.summaryEn)
            taskLogger.logToolResult(taskId, call.name, finalResult)

            _statusBannerBn.value = finalResult.summaryBn.ifBlank {
                if (finalResult.success) "কাজটি সম্পন্ন হয়েছে।" else "কাজটি করা যায়নি।"
            }
            persistTaskSummary("${registeredTool.actionType.displayLabelEn}: ${stepStatus.labelEn}")

            responses.add(toFunctionResponse(call, finalResult, evaluation))

            // Transition to WAITING_FOR_ACTION when waiting for Gemini's next step in a multi-step flow,
            // or FAILED/CANCELLED if halted by safety/cancellation.
            if (!finalResult.success) {
                if (finalResult.status == "USER_CANCELLED" || finalResult.status == "CANCELLED") {
                    transitionState(AgentExecutionState.CANCELLED, finalResult.summaryEn)
                    saveCheckpointAsync(taskId, AgentExecutionState.CANCELLED, _activePlan.value, lastError = finalResult.summaryEn)
                } else if (finalResult.blockedBySafety || finalResult.needsAccessibilityPermission) {
                    transitionState(AgentExecutionState.FAILED, finalResult.summaryEn)
                    saveCheckpointAsync(taskId, AgentExecutionState.FAILED, _activePlan.value, lastError = finalResult.summaryEn)
                } else {
                    transitionState(AgentExecutionState.WAITING_FOR_ACTION, "Awaiting Gemini replanning after failure")
                    saveCheckpointAsync(taskId, AgentExecutionState.WAITING_FOR_ACTION, _activePlan.value, lastError = finalResult.summaryEn)
                }
                // Stop dependent actions in the same batch so stale actions aren't run blindly
                for (j in (index + 1) until functionCalls.size) {
                    responses.add(buildCancelledResponse(functionCalls[j]))
                }
                break
            } else {
                transitionState(AgentExecutionState.WAITING_FOR_ACTION, "Step succeeded; awaiting next step or completion")
                saveCheckpointAsync(
                    taskId = taskId,
                    state = AgentExecutionState.WAITING_FOR_ACTION,
                    plan = _activePlan.value,
                    safeMetadata = mapOf("last_tool" to call.name, "last_status" to finalResult.status)
                )
            }
        }

        if (responses.isNotEmpty()) {
            toolResponseSender(responses)
        }
        _isTaskRunning.value = false
        return responses
    }

    /**
     * Handles official Gemini Live `toolCallCancellation` when user interrupts mid-task.
     */
    fun onGeminiToolCallsCancelled(ids: List<String>) {
        cancelledCallIds.addAll(ids)
        val pending = _pendingConfirmation.value
        if (pending != null && (ids.isEmpty() || pending.callId in ids)) {
            rejectPendingConfirmation()
        }
        _taskSteps.update { list ->
            list.map { step ->
                if ((ids.isEmpty() || step.callId in ids) &&
                    (step.status == AgentStepStatus.PENDING ||
                        step.status == AgentStepStatus.RUNNING ||
                        step.status == AgentStepStatus.RECOVERING ||
                        step.status == AgentStepStatus.WAITING_CONFIRMATION)
                ) {
                    step.copy(status = AgentStepStatus.CANCELLED, detail = "Cancelled by user interruption")
                } else {
                    step
                }
            }
        }
        activeTaskJob?.cancel()
        _isTaskRunning.value = false
        currentTaskId?.let { tid ->
            taskLogger.logFinished(tid, AgentExecutionState.CANCELLED, "Cancelled by Gemini toolCallCancellation")
        }
        transitionState(AgentExecutionState.CANCELLED, "Interrupted by user")
        currentTaskId = null
    }

    /**
     * Executes a direct deterministic shortcut from Dashboard or Family Easy Mode
     * using the same unified tool registry, safety engine, and logger.
     */
    fun executeDirectQuickAction(
        request: AgentActionRequest,
        isGeminiAudioActive: Boolean = false,
        onResult: ((AgentActionResult) -> Unit)? = null
    ) {
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            executionMutex.withLock {
                val taskId = UUID.randomUUID().toString().take(8)
                currentTaskId = taskId
                taskLogger.startTask(taskId, request.reason.ifBlank { request.actionType.displayLabelEn })
                _isTaskRunning.value = true
                _statusBannerBn.value = "একটু অপেক্ষা করুন, কাজটি করছি।"

                val tool = toolRegistry.getTool(request.actionType.toolName)
                val stepId = UUID.randomUUID().toString()
                addOrUpdateStep(
                    AgentTaskStep(
                        id = stepId,
                        actionType = request.actionType,
                        titleEn = buildStepTitleEn(request.actionType, request.parameters),
                        titleBn = buildStepTitleBn(request.actionType, request.parameters),
                        status = AgentStepStatus.RUNNING
                    )
                )

                val result = if (tool != null) {
                    executeWithObservationAndRecovery(
                        taskId = taskId,
                        stepId = stepId,
                        tool = tool,
                        request = request,
                        expectedOutcome = "",
                        onSystemNote = {}
                    ).first
                } else {
                    AgentActionResult(
                        success = false,
                        status = "UNSUPPORTED_ACTION",
                        summaryEn = "Unsupported action: ${request.actionType.toolName}",
                        summaryBn = "এই কাজটি সাপোর্টেড নয়।"
                    )
                }

                val finalStatus = when {
                    result.status == "USER_CANCELLED" -> AgentStepStatus.CANCELLED
                    result.success -> AgentStepStatus.COMPLETED
                    else -> AgentStepStatus.FAILED
                }
                val finalExecState = when (finalStatus) {
                    AgentStepStatus.COMPLETED -> AgentExecutionState.COMPLETED
                    AgentStepStatus.CANCELLED -> AgentExecutionState.CANCELLED
                    else -> AgentExecutionState.FAILED
                }
                updateStepStatus(stepId, finalStatus, result.summaryEn)
                transitionState(finalExecState, result.summaryEn)
                taskLogger.logToolResult(taskId, request.actionType.toolName, result)
                taskLogger.logFinished(taskId, finalExecState, result.summaryEn)

                _statusBannerBn.value = result.summaryBn.ifBlank {
                    if (result.success) "কাজটি সম্পন্ন হয়েছে।" else "কাজটি করা যায়নি।"
                }

                if (result.summaryBn.isNotBlank()) {
                    voiceFeedback?.speakIfIdle(result.summaryBn, isGeminiLiveAudioActive = isGeminiAudioActive)
                }

                persistTaskSummary("${request.actionType.displayLabelEn}: ${finalStatus.labelEn}")
                _isTaskRunning.value = false
                currentTaskId = null
                onResult?.invoke(result)
            }
        }
    }

    /**
     * Core Phase 2 execution pipeline for a single action:
     * Safety Evaluation -> Confirmation Gate -> Pre-Snapshot -> Execute (with Timeout) ->
     * Post-Snapshot Observation -> Outcome Evaluation -> Bounded Recovery Retries.
     */
    private suspend fun executeWithObservationAndRecovery(
        taskId: String,
        stepId: String,
        tool: AgentToolRegistry.RegisteredTool,
        request: AgentActionRequest,
        expectedOutcome: String,
        onSystemNote: (String) -> Unit
    ): Pair<AgentActionResult, AgentOutcomeEvaluator.EvaluationOutcome?> {
        val activePkg = activePackageProvider()
        val safety = AgentSafetyManager.evaluate(request, activePkg)

        if (safety.isBlocked) {
            onSystemNote(safety.reasonEn)
            taskLogger.logError(taskId, "Blocked high-risk action: ${request.actionType.toolName}")
            return AgentActionResult(
                success = false,
                status = "BLOCKED_HIGH_RISK",
                summaryEn = safety.reasonEn,
                summaryBn = safety.reasonBn,
                blockedBySafety = true
            ) to null
        }

        var effectiveRequest = request
        var userConfirmed = false

        if (safety.requiresConfirmation || requiresExtraAutonomyConfirmation(request)) {
            val confirmReasonEn = if (safety.requiresConfirmation) {
                safety.reasonEn
            } else {
                "Ask-First autonomy mode: please confirm before running ${request.actionType.displayLabelEn}."
            }
            val confirmReasonBn = if (safety.requiresConfirmation) {
                safety.reasonBn
            } else {
                "Ask-First মোড চালু আছে: ${request.actionType.displayLabelEn} চালানোর আগে অনুমতি দিন।"
            }
            val previewPair = actionExecutor?.buildConfirmationPreview(request)
                ?: (PendingConfirmation(
                    callId = request.callId,
                    actionRequest = request,
                    promptEn = confirmReasonEn,
                    promptBn = confirmReasonBn,
                    previewDetail = request.actionType.displayLabelEn
                ) to null)
            val (preview, earlyFailure) = previewPair
            if (earlyFailure != null) {
                return earlyFailure to null
            }
            if (preview != null) {
                val nowMs = System.currentTimeMillis()
                val timedPreview = preview.copy(
                    createdAtMillis = nowMs,
                    expiresAtMillis = nowMs + config.confirmationTimeoutMs
                )
                transitionState(AgentExecutionState.WAITING_FOR_USER, timedPreview.promptEn)
                updateStepStatus(stepId, AgentStepStatus.WAITING_CONFIRMATION, timedPreview.promptEn)
                _statusBannerBn.value = timedPreview.promptBn
                _pendingConfirmation.value = timedPreview
                saveCheckpointAsync(
                    taskId = taskId,
                    state = AgentExecutionState.WAITING_FOR_USER,
                    plan = _activePlan.value,
                    safeMetadata = mapOf("pending_action" to request.actionType.toolName)
                )
                voiceFeedback?.speakIfIdle(
                    timedPreview.promptBn,
                    isGeminiLiveAudioActive = geminiRepository?.isSessionActive() == true
                )

                val deferred = CompletableDeferred<Boolean>()
                confirmationDeferred = deferred
                val approved = withTimeoutOrNull(config.confirmationTimeoutMs) {
                    deferred.await()
                } ?: false

                _pendingConfirmation.value = null
                confirmationDeferred = null

                if (!approved) {
                    return AgentActionResult(
                        success = false,
                        status = "USER_CANCELLED",
                        summaryEn = "Action cancelled because the user did not approve confirmation.",
                        summaryBn = "আপনার অনুরোধে কাজটি বাতিল করা হয়েছে।"
                    ) to null
                }

                effectiveRequest = timedPreview.actionRequest
                userConfirmed = true
            }
        }

        val maxAttempts = if (tool.supportsRetry) 1 + config.maxRetriesPerAction.coerceAtLeast(0) else 1
        val timeoutMs = tool.timeoutMs.coerceAtMost(config.defaultToolTimeoutMs)
        var lastResult: AgentActionResult? = null
        var lastEvaluation: AgentOutcomeEvaluator.EvaluationOutcome? = null
        var retriesPerformed = 0
        val actionStartMs = System.currentTimeMillis()
        var firstBeforeSnapshot: ScreenUiSnapshot? = null
        var latestAfterSnapshot: ScreenUiSnapshot? = null

        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                retriesPerformed = attempt - 1
                currentTaskRetryTotal++
                transitionState(AgentExecutionState.RECOVERING, "Retrying ${tool.name} ($attempt/$maxAttempts)")
                updateStepStatus(
                    stepId = stepId,
                    status = AgentStepStatus.RECOVERING,
                    detail = "Retry ${attempt - 1}/${config.maxRetriesPerAction}...",
                    retryCount = attempt - 1
                )
                _statusBannerBn.value = "আমি এই ধাপে আটকে গেছি, আবার চেষ্টা করছি।"
                taskLogger.logRetry(
                    taskId = taskId,
                    toolName = tool.name,
                    attempt = attempt - 1,
                    maxRetries = config.maxRetriesPerAction,
                    reason = lastEvaluation?.evaluationNoteEn ?: lastResult?.summaryEn.orEmpty()
                )
                delay(config.recoveryBackoffMs)
            } else {
                transitionState(AgentExecutionState.EXECUTING, "Executing ${tool.name}")
                updateStepStatus(stepId, AgentStepStatus.RUNNING, "Running ${tool.name}...")
                _statusBannerBn.value = buildStepTitleBn(request.actionType, request.parameters)
            }

            val beforeSnapshot = screenSnapshotProvider()
            if (firstBeforeSnapshot == null) firstBeforeSnapshot = beforeSnapshot

            val rawResult = withTimeoutOrNull(timeoutMs) {
                toolRegistry.executeRegisteredTool(tool, effectiveRequest, userConfirmed)
            } ?: AgentActionResult(
                success = false,
                status = "ACTION_TIMEOUT",
                summaryEn = "Tool ${tool.name} timed out after ${timeoutMs}ms.",
                summaryBn = "কাজটি করতে নির্ধারিত সময়ের বেশি সময় লেগেছে।",
                recoverable = true
            )

            transitionState(AgentExecutionState.OBSERVING, "Observing result of ${tool.name}")
            val afterSnapshot = screenSnapshotProvider()
            latestAfterSnapshot = afterSnapshot

            val evaluation = AgentOutcomeEvaluator.evaluate(
                request = effectiveRequest,
                result = rawResult,
                beforeSnapshot = beforeSnapshot,
                afterSnapshot = afterSnapshot,
                expectedOutcome = expectedOutcome
            )

            val enrichedResult = rawResult.copy(
                success = evaluation.verifiedSuccess,
                summaryEn = evaluation.evaluationNoteEn,
                summaryBn = evaluation.evaluationNoteBn,
                screenChanged = evaluation.screenChanged,
                screenSummary = rawResult.screenSummary ?: afterSnapshot?.toSafePromptSummary()
            )

            lastResult = enrichedResult
            lastEvaluation = evaluation

            if (evaluation.verifiedSuccess || !evaluation.shouldRetry) {
                val durationMs = (System.currentTimeMillis() - actionStartMs).coerceAtLeast(0L)
                recordActionTelemetry(
                    taskId = taskId,
                    toolName = tool.name,
                    result = enrichedResult,
                    evaluation = evaluation,
                    beforeSnapshot = firstBeforeSnapshot,
                    afterSnapshot = latestAfterSnapshot,
                    retryCount = retriesPerformed,
                    durationMs = durationMs
                )
                return enrichedResult to evaluation
            }
        }

        val exhausted = (lastResult ?: AgentActionResult(
            success = false,
            status = "RECOVERY_EXHAUSTED",
            summaryEn = "Action failed after retries.",
            summaryBn = "কয়েকবার চেষ্টা করেও কাজটি করা যায়নি।"
        ))
        val durationMs = (System.currentTimeMillis() - actionStartMs).coerceAtLeast(0L)
        recordActionTelemetry(
            taskId = taskId,
            toolName = tool.name,
            result = exhausted,
            evaluation = lastEvaluation,
            beforeSnapshot = firstBeforeSnapshot,
            afterSnapshot = latestAfterSnapshot,
            retryCount = retriesPerformed,
            durationMs = durationMs
        )
        return exhausted to lastEvaluation
    }

    private fun recordActionTelemetry(
        taskId: String,
        toolName: String,
        result: AgentActionResult,
        evaluation: AgentOutcomeEvaluator.EvaluationOutcome?,
        beforeSnapshot: ScreenUiSnapshot?,
        afterSnapshot: ScreenUiSnapshot?,
        retryCount: Int,
        durationMs: Long
    ) {
        val verified = evaluation?.verifiedSuccess ?: result.success
        val screenChanged = evaluation?.screenChanged ?: (result.screenChanged == true)
        _lastActionVerification.value = LastActionVerification(
            toolName = toolName,
            verifiedSuccess = verified,
            screenChanged = screenChanged,
            status = result.status,
            summary = AgentTaskLogger.sanitizeForLog(result.summaryEn),
            retryCount = retryCount,
            durationMs = durationMs
        )
        val activeScenario = testHarness.activeScenario.value
        if (activeScenario != null) {
            testHarness.recordRun(
                scenario = activeScenario,
                taskId = taskId,
                agentState = _executionState.value,
                toolName = toolName,
                accessibilityResult = result.details["interaction_tier"] ?: result.status,
                preActionObservation = beforeSnapshot,
                postActionObservation = afterSnapshot,
                verificationResult = if (verified) "VERIFIED_SUCCESS (screenChanged=$screenChanged)" else "VERIFICATION_FAILED (${result.status})",
                retryCount = retryCount,
                executionDurationMs = durationMs,
                failureReason = if (verified) null else result.summaryEn,
                passed = verified,
                executedOnPhysicalDevice = AgentAccessibilityService.isConnected.value
            )
        }
        val dtm = dynamicToolManager
        if (dtm != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    dtm.recordExecution(
                        taskId = taskId,
                        toolName = toolName,
                        succeeded = verified,
                        blockedBySafety = result.blockedBySafety,
                        retryCount = retryCount,
                        durationMs = durationMs,
                        errorCode = if (verified) null else result.status,
                        summary = result.summaryEn
                    )
                }
            }
        }
    }

    /**
     * Approves the active pending confirmation. If [expectedId] is supplied, strictly validates
     * it matches the active pending action ID and has not expired.
     */
    fun approvePendingConfirmation(
        expectedId: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val pending = _pendingConfirmation.value ?: return false
        if (expectedId != null && pending.id != expectedId) {
            return false
        }
        if (pending.isExpired(nowMillis)) {
            confirmationDeferred?.complete(false)
            _pendingConfirmation.value = null
            return false
        }
        confirmationDeferred?.complete(true)
        return true
    }

    /**
     * Rejects the active pending confirmation. If [expectedId] is supplied, strictly validates ID match.
     */
    fun rejectPendingConfirmation(expectedId: String? = null): Boolean {
        val pending = _pendingConfirmation.value
        if (expectedId != null && pending != null && pending.id != expectedId) {
            return false
        }
        confirmationDeferred?.complete(false)
        _pendingConfirmation.value = null
        return true
    }

    /**
     * Inspects local Room checkpoints on startup/restart and populates [recoverableCheckpoints]
     * WITHOUT automatically executing or replaying any action.
     */
    suspend fun inspectRecoverableCheckpoints(): List<RecoverableTaskCheckpoint> {
        val repo = checkpointRepository ?: return emptyList()
        val incomplete = runCatching { repo.inspectIncompleteTasksOnStartup() }.getOrDefault(emptyList())
        _recoverableCheckpoints.value = incomplete
        return incomplete
    }

    fun launchStartupCheckpointInspection() {
        if (checkpointRepository == null) return
        scope.launch(Dispatchers.IO) {
            inspectRecoverableCheckpoints()
        }
    }

    /**
     * Safely resumes a persisted incomplete task checkpoint ONLY after explicit user action.
     * Sensitive actions (calls, messages, confirmations) are stripped and NEVER auto-replayed.
     */
    suspend fun resumeRecoverableCheckpoint(taskId: String): ResumeCheckpointOutcome {
        val repo = checkpointRepository ?: return ResumeCheckpointOutcome.NotFound
        val checkpoint = repo.getCheckpoint(taskId) ?: return ResumeCheckpointOutcome.NotFound

        val safeRemaining = checkpoint.remainingSafeSteps
        val skippedSensitive = checkpoint.skippedSensitiveStepsCount

        if (safeRemaining.isEmpty()) {
            repo.markCancelled(taskId, "Skipped automatic replay of sensitive or completed steps after restart")
            _recoverableCheckpoints.update { list -> list.filterNot { it.taskId == taskId } }
            val reasonEn = if (checkpoint.containsSensitiveAction || skippedSensitive > 0) {
                "Task \"${checkpoint.sanitizedRequest}\" only had sensitive steps remaining. For safety, sensitive actions are never auto-replayed after restart — please request the action again."
            } else {
                "No safe unfinished steps remain for task \"${checkpoint.sanitizedRequest}\"."
            }
            val reasonBn = if (checkpoint.containsSensitiveAction || skippedSensitive > 0) {
                "নিরাপত্তার জন্য কল বা মেসেজের মতো সংবেদনশীল কাজ রিস্টার্টের পর স্বয়ংক্রিয়ভাবে চালানো হয় না। অনুগ্রহ করে আবার বলুন।"
            } else {
                "পুনরায় শুরু করার মতো কোনো বাকি ধাপ পাওয়া যায়নি।"
            }
            _statusBannerBn.value = reasonBn
            return ResumeCheckpointOutcome.BlockedSensitiveOnly(reasonEn, reasonBn)
        }

        val restoredSteps = safeRemaining.mapIndexed { idx, persisted ->
            AgentPlanStep(
                stepIndex = idx,
                description = persisted.description,
                toolName = persisted.toolName,
                expectedOutcome = persisted.expectedOutcome,
                status = AgentStepStatus.PENDING,
                retryCount = persisted.retryCount
            )
        }
        val restoredPlan = AgentTaskPlan(
            taskId = checkpoint.taskId,
            taskDescription = checkpoint.plan?.taskDescription ?: checkpoint.sanitizedRequest,
            steps = restoredSteps,
            currentStepIndex = 0,
            totalToolStepsExecuted = checkpoint.plan?.totalToolStepsExecuted ?: checkpoint.completedStepsCount,
            maxStepsAllowed = config.maxStepsPerTask
        )
        currentTaskId = checkpoint.taskId
        latestUserRequestText = checkpoint.sanitizedRequest
        _activePlan.value = restoredPlan
        _recoverableCheckpoints.update { list -> list.filterNot { it.taskId == taskId } }
        taskLogger.startTask(checkpoint.taskId, "Resumed: ${checkpoint.sanitizedRequest}")
        transitionState(AgentExecutionState.WAITING_FOR_ACTION, "Resumed ${restoredSteps.size} safe steps from local checkpoint")
        _statusBannerBn.value = if (skippedSensitive > 0) {
            "আগের কাজটি পুনরায় প্রস্তুত করা হয়েছে ($skippedSensitive টি সংবেদনশীল ধাপ নিরাপত্তার জন্য বাদ দেওয়া হয়েছে)।"
        } else {
            "আগের অসমাপ্ত কাজটি পুনরায় প্রস্তুত করা হয়েছে।"
        }
        repo.saveCheckpoint(
            taskId = checkpoint.taskId,
            rawUserRequest = checkpoint.sanitizedRequest,
            state = AgentExecutionState.WAITING_FOR_ACTION,
            plan = restoredPlan,
            retryCount = checkpoint.retryCount,
            safeMetadata = mapOf("resumed_after_restart" to "true", "skipped_sensitive" to skippedSensitive.toString())
        )
        return ResumeCheckpointOutcome.Resumed(restoredPlan, skippedSensitive)
    }

    fun resumeCheckpointAsync(taskId: String) {
        scope.launch {
            resumeRecoverableCheckpoint(taskId)
        }
    }

    suspend fun cancelRecoverableCheckpointSuspending(taskId: String) {
        checkpointRepository?.markCancelled(taskId, "Cancelled by user from startup recovery prompt")
        _recoverableCheckpoints.update { list -> list.filterNot { it.taskId == taskId } }
    }

    fun cancelRecoverableCheckpoint(taskId: String) {
        scope.launch {
            cancelRecoverableCheckpointSuspending(taskId)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 4: On-Device Long-Term Memory Retrieval, Reusable Skill Execution & Skill Learning
    // ---------------------------------------------------------------------------------------------

    /**
     * Retrieves relevant, sanitized, non-expired local memories for [query] while respecting
     * user-instruction overrides and the global memory toggle.
     */
    suspend fun retrieveRelevantMemoriesForRequest(
        query: String,
        activePackageName: String = activePackageProvider(),
        nowMillis: Long = System.currentTimeMillis()
    ): IntelligentMemoryRetriever.RetrievalResult {
        val retriever = memoryRetriever
        if (!isMemoryEnabledProvider() || retriever == null) {
            _lastRetrievedMemories.value = emptyList()
            return IntelligentMemoryRetriever.RetrievalResult(emptyList(), "", emptyList())
        }
        val result = runCatching {
            retriever.retrieveForQuery(
                userQuery = query,
                activePackageName = activePackageName,
                nowMillis = nowMillis
            )
        }.getOrDefault(IntelligentMemoryRetriever.RetrievalResult(emptyList(), "", emptyList()))
        _lastRetrievedMemories.value = result.selectedMemories
        return result
    }

    suspend fun retrieveSessionBaselineMemoryContext(
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val retriever = memoryRetriever
        if (!isMemoryEnabledProvider() || retriever == null) return ""
        val res = runCatching { retriever.retrieveSessionBaselineContext(nowMillis = nowMillis) }
            .getOrNull() ?: return ""
        _lastRetrievedMemories.value = res.selectedMemories
        return res.promptContext
    }

    suspend fun getEnabledSkillSummaries(): List<String> {
        val repo = skillRepository ?: return emptyList()
        return runCatching {
            repo.getEnabledApprovedSkills().take(8).map { skill ->
                val triggers = skill.triggerPhrases.take(2).joinToString(" / ")
                val tools = skill.allowedTools.joinToString(" -> ")
                "${skill.name} (triggers: $triggers | steps: $tools | safety: ${skill.safetyClassification.name})"
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Handles explicit user "Remember..." / "মনে রেখো..." instructions when memory is enabled.
     * Never persists secrets, OTPs, PINs, API keys, or private message bodies.
     */
    suspend fun handleExplicitRememberSuspending(
        userText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): AgentMemoryRepository.SaveOutcome? {
        val repo = memoryRepository ?: return null
        if (!isMemoryEnabledProvider()) return AgentMemoryRepository.SaveOutcome.MemoryDisabled
        val candidate = IntelligentMemoryRetriever.parseExplicitRememberInstruction(userText) ?: return null
        val outcome = repo.saveMemory(
            category = candidate.category,
            memoryKey = candidate.memoryKey,
            value = candidate.value,
            confidence = 0.96f,
            source = MemorySource.USER_EXPLICIT,
            expirationPolicy = MemoryExpirationPolicy.NEVER,
            userApproved = true,
            nowMillis = nowMillis
        )
        when (outcome) {
            is AgentMemoryRepository.SaveOutcome.Saved -> {
                _lastMemoryStatusNote.value = "মনে রাখা হয়েছে: ${outcome.record.memoryKey} = ${outcome.record.value}"
            }
            is AgentMemoryRepository.SaveOutcome.Rejected -> {
                _lastMemoryStatusNote.value = outcome.reasonBn
            }
            else -> Unit
        }
        return outcome
    }

    fun clearMemoryStatusNote() {
        _lastMemoryStatusNote.value = null
    }

    /**
     * Runs a stored, enabled, user-approved local skill asynchronously via [AgentCoordinator].
     */
    fun executeSkillById(
        skillId: String,
        parameters: Map<String, String> = emptyMap(),
        onComplete: ((SkillExecutionOutcome) -> Unit)? = null
    ) {
        if (_activeSkillId.value != null || _isTaskRunning.value) {
            val outcome = SkillExecutionOutcome.AlreadyRunning(_activeSkillId.value)
            _statusBannerBn.value = "আরেকটি কাজ চলছে, অনুগ্রহ করে একটু অপেক্ষা করুন।"
            onComplete?.invoke(outcome)
            return
        }
        activeTaskJob = scope.launch {
            val skill = skillRepository?.getSkillById(skillId)
            val outcome = if (skill == null) {
                SkillExecutionOutcome.NotFound
            } else {
                executeSkillSuspending(skill, parameters)
            }
            onComplete?.invoke(outcome)
        }
    }

    /**
     * Executes a reusable [AgentSkill] through the EXISTING unified execution pipeline:
     * - Enforces `skill.enabled && skill.userApproved`.
     * - Prevents duplicate concurrent skill execution.
     * - Reuses existing [AgentToolRegistry] tools & [executeWithObservationAndRecovery].
     * - Validates EVERY step through [AgentSafetyManager] — skills NEVER bypass confirmation or high-risk blocks.
     * - Supports cancellation at any point via [cancelCurrentTask].
     */
    suspend fun executeSkillSuspending(
        skill: AgentSkill,
        parameters: Map<String, String> = emptyMap(),
        onSystemNote: (String) -> Unit = {}
    ): SkillExecutionOutcome {
        if (!skill.enabled || !skill.userApproved) {
            return SkillExecutionOutcome.DisabledOrUnapproved(skill.skillId)
        }
        if (_activeSkillId.value != null || _isTaskRunning.value) {
            return SkillExecutionOutcome.AlreadyRunning(_activeSkillId.value)
        }

        return executionMutex.withLock {
            if (_activeSkillId.value != null || _isTaskRunning.value) {
                return@withLock SkillExecutionOutcome.AlreadyRunning(_activeSkillId.value)
            }

            val taskId = "sk_${UUID.randomUUID().toString().take(8)}"
            currentTaskId = taskId
            currentTaskRetryTotal = 0
            latestUserRequestText = "Skill: ${skill.name}"
            _activeSkillId.value = skill.skillId
            _isTaskRunning.value = true

            taskLogger.startTask(taskId, "Skill execution: ${skill.name}")
            transitionState(AgentExecutionState.PLANNING, "Preparing skill ${skill.name}")
            _statusBannerBn.value = "দক্ষতা চালু হচ্ছে: ${skill.name}"

            val resolvedPlanSteps = skill.executionSteps.mapIndexed { idx, step ->
                val resolvedParams = resolveSkillStepParams(step.parameterTemplates, parameters)
                AgentPlanStep(
                    stepIndex = idx,
                    description = step.description,
                    toolName = step.toolName,
                    arguments = AgentTaskLogger.sanitizeParams(resolvedParams),
                    expectedOutcome = step.expectedOutcome,
                    status = AgentStepStatus.PENDING
                )
            }

            _activePlan.value = AgentTaskPlan(
                taskId = taskId,
                taskDescription = "Skill: ${skill.name}",
                steps = resolvedPlanSteps,
                currentStepIndex = 0,
                totalToolStepsExecuted = 0,
                maxStepsAllowed = config.maxStepsPerTask
            )

            var executedCount = 0
            var outcome: SkillExecutionOutcome? = null

            try {
                for ((idx, step) in skill.executionSteps.withIndex()) {
                    if (!currentCoroutineContext().isActive || _activeSkillId.value != skill.skillId) {
                        outcome = SkillExecutionOutcome.Cancelled(skill.skillId, "Skill cancelled by user.")
                        break
                    }

                    if (executedCount >= config.maxStepsPerTask) {
                        outcome = SkillExecutionOutcome.Failed(
                            skillId = skill.skillId,
                            reasonEn = "Skill exceeded max steps limit (${config.maxStepsPerTask}).",
                            reasonBn = "সর্বোচ্চ ধাপের সীমা পূর্ণ হওয়ায় দক্ষতাটি থামানো হয়েছে।"
                        )
                        break
                    }

                    // Enforce allowedTools whitelist declared by the skill
                    if (skill.allowedTools.isNotEmpty() && step.toolName !in skill.allowedTools) {
                        outcome = SkillExecutionOutcome.BlockedBySafety(
                            skillId = skill.skillId,
                            reasonEn = "Tool \"${step.toolName}\" is not in skill's allowedTools list.",
                            reasonBn = "নিরাপত্তার কারণে অননুমোদিত টুল \"${step.toolName}\" বন্ধ করা হয়েছে।"
                        )
                        break
                    }

                    val resolvedParams = resolveSkillStepParams(step.parameterTemplates, parameters)
                    val unresolvedPlaceholder = resolvedParams.values.any { it.contains("{{") && it.contains("}}") }
                    if (unresolvedPlaceholder) {
                        outcome = SkillExecutionOutcome.Failed(
                            skillId = skill.skillId,
                            reasonEn = "Missing required parameter for step ${idx + 1} (${step.toolName}).",
                            reasonBn = "এই দক্ষতাটি চালানোর জন্য প্রয়োজনীয় তথ্য (প্যারামিটার) দেওয়া হয়নি।"
                        )
                        break
                    }

                    val (registeredTool, validation) = toolRegistry.validateCall(step.toolName, resolvedParams)
                    if (!validation.isValid || registeredTool == null) {
                        outcome = SkillExecutionOutcome.Failed(
                            skillId = skill.skillId,
                            reasonEn = validation.errorMessageEn,
                            reasonBn = validation.errorMessageBn
                        )
                        break
                    }

                    // Phase 5: Optional step condition check (e.g., "param_present:query" or "accessibility_ready")
                    if (!evaluateSkillStepCondition(step.condition, parameters)) {
                        updatePlanStepResult(registeredTool.actionType, AgentStepStatus.COMPLETED, "Skipped by condition (${step.condition})")
                        continue
                    }

                    val request = AgentActionRequest(
                        callId = "${taskId}_step_$idx",
                        actionType = registeredTool.actionType,
                        parameters = resolvedParams,
                        reason = "Skill ${skill.name}: ${step.description}",
                        requiresConfirmation = skill.safetyClassification == com.example.data.local.skill.SkillSafetyClassification.SENSITIVE_CONFIRMATION
                    )

                    val stepId = UUID.randomUUID().toString()
                    addOrUpdateStep(
                        AgentTaskStep(
                            id = stepId,
                            callId = request.callId,
                            actionType = registeredTool.actionType,
                            titleEn = buildStepTitleEn(registeredTool.actionType, resolvedParams),
                            titleBn = buildStepTitleBn(registeredTool.actionType, resolvedParams),
                            status = AgentStepStatus.RUNNING
                        )
                    )
                    incrementPlanStepCounter(registeredTool.actionType, resolvedParams)
                    executedCount++

                    var (result, evaluation) = executeWithObservationAndRecovery(
                        taskId = taskId,
                        stepId = stepId,
                        tool = registeredTool,
                        request = request,
                        expectedOutcome = step.expectedOutcome.ifBlank { step.verificationCheck },
                        onSystemNote = onSystemNote
                    )

                    // Phase 5: Safe step-level recovery fallback (always routed through AgentSafetyManager!)
                    val recoveryToolName = step.recoveryToolName?.trim().orEmpty()
                    if (!result.success &&
                        !result.blockedBySafety &&
                        result.status != "USER_CANCELLED" &&
                        result.status != "CANCELLED" &&
                        recoveryToolName.isNotEmpty()
                    ) {
                        val (recTool, recVal) = toolRegistry.validateCall(recoveryToolName, step.recoveryParameters)
                        if (recVal.isValid && recTool != null) {
                            val recReq = AgentActionRequest(
                                callId = "${taskId}_rec_$idx",
                                actionType = recTool.actionType,
                                parameters = step.recoveryParameters,
                                reason = "Skill recovery before retrying ${step.toolName}"
                            )
                            val recStepId = UUID.randomUUID().toString()
                            addOrUpdateStep(
                                AgentTaskStep(
                                    id = recStepId,
                                    callId = recReq.callId,
                                    actionType = recTool.actionType,
                                    titleEn = "Recovery: ${recTool.actionType.displayLabelEn}",
                                    titleBn = "পুনরুদ্ধার: ${recTool.actionType.displayLabelBn}",
                                    status = AgentStepStatus.RECOVERING
                                )
                            )
                            val (recRes, _) = executeWithObservationAndRecovery(
                                taskId = taskId,
                                stepId = recStepId,
                                tool = recTool,
                                request = recReq,
                                expectedOutcome = "",
                                onSystemNote = onSystemNote
                            )
                            updateStepStatus(
                                recStepId,
                                if (recRes.success) AgentStepStatus.COMPLETED else AgentStepStatus.FAILED,
                                recRes.summaryEn
                            )
                            if (recRes.success) {
                                val retryPair = executeWithObservationAndRecovery(
                                    taskId = taskId,
                                    stepId = stepId,
                                    tool = registeredTool,
                                    request = request,
                                    expectedOutcome = step.expectedOutcome.ifBlank { step.verificationCheck },
                                    onSystemNote = onSystemNote
                                )
                                result = retryPair.first
                                evaluation = retryPair.second
                            }
                        }
                    }

                    val stepStatus = when {
                        result.status == "USER_CANCELLED" || result.status == "CANCELLED" -> AgentStepStatus.CANCELLED
                        result.success -> AgentStepStatus.COMPLETED
                        else -> AgentStepStatus.FAILED
                    }
                    updateStepStatus(stepId, stepStatus, evaluation?.evaluationNoteEn ?: result.summaryEn)
                    updatePlanStepResult(registeredTool.actionType, stepStatus, result.summaryEn)
                    taskLogger.logToolResult(taskId, step.toolName, result)

                    if (!result.success) {
                        outcome = when {
                            result.blockedBySafety -> SkillExecutionOutcome.BlockedBySafety(
                                skillId = skill.skillId,
                                reasonEn = result.summaryEn,
                                reasonBn = result.summaryBn
                            )
                            result.status == "USER_CANCELLED" || result.status == "CANCELLED" ->
                                SkillExecutionOutcome.Cancelled(skill.skillId, result.summaryEn)
                            else -> SkillExecutionOutcome.Failed(
                                skillId = skill.skillId,
                                reasonEn = result.summaryEn,
                                reasonBn = result.summaryBn
                            )
                        }
                        break
                    }
                }

                val finalOutcome = outcome ?: SkillExecutionOutcome.Completed(
                    skillId = skill.skillId,
                    stepsExecuted = executedCount,
                    summaryEn = "Skill \"${skill.name}\" completed ($executedCount steps)."
                )

                when (finalOutcome) {
                    is SkillExecutionOutcome.Completed -> {
                        transitionState(AgentExecutionState.COMPLETED, finalOutcome.summaryEn)
                        taskLogger.logFinished(taskId, AgentExecutionState.COMPLETED, finalOutcome.summaryEn)
                        _statusBannerBn.value = "\"${skill.name}\" দক্ষতা সফলভাবে সম্পন্ন হয়েছে।"
                        skillRepository?.recordSkillExecution(skill.skillId, true, finalOutcome.summaryEn)
                        persistTaskSummary("Skill ${skill.name}: Completed")
                    }
                    is SkillExecutionOutcome.BlockedBySafety -> {
                        transitionState(AgentExecutionState.FAILED, finalOutcome.reasonEn)
                        taskLogger.logFinished(taskId, AgentExecutionState.FAILED, finalOutcome.reasonEn)
                        _statusBannerBn.value = finalOutcome.reasonBn
                        skillRepository?.recordSkillExecution(skill.skillId, false, "Blocked by safety: ${finalOutcome.reasonEn}")
                    }
                    is SkillExecutionOutcome.Cancelled -> {
                        transitionState(AgentExecutionState.CANCELLED, finalOutcome.reasonEn)
                        taskLogger.logFinished(taskId, AgentExecutionState.CANCELLED, finalOutcome.reasonEn)
                        _statusBannerBn.value = "দক্ষতাটি বাতিল করা হয়েছে।"
                        skillRepository?.recordSkillExecution(skill.skillId, false, "Cancelled")
                    }
                    is SkillExecutionOutcome.Failed -> {
                        transitionState(AgentExecutionState.FAILED, finalOutcome.reasonEn)
                        taskLogger.logFinished(taskId, AgentExecutionState.FAILED, finalOutcome.reasonEn)
                        _statusBannerBn.value = finalOutcome.reasonBn.ifBlank { "দক্ষতাটি সম্পন্ন করা যায়নি।" }
                        skillRepository?.recordSkillExecution(skill.skillId, false, finalOutcome.reasonEn)
                    }
                    else -> Unit
                }

                finalOutcome
            } finally {
                _activeSkillId.value = null
                _isTaskRunning.value = false
                currentTaskId = null
            }
        }
    }

    /**
     * Approves the currently proposed learned skill and saves it locally to Room.
     */
    suspend fun approvePendingSkillProposalSuspending(
        customName: String? = null
    ): AgentSkillRepository.SaveSkillResult? {
        val proposal = _pendingSkillProposal.value ?: return null
        val repo = skillRepository ?: return null
        val saved = repo.saveProposalAsApprovedSkill(proposal, customName)
        if (saved is AgentSkillRepository.SaveSkillResult.Saved) {
            _pendingSkillProposal.value = null
            _statusBannerBn.value = "নতুন দক্ষতা সংরক্ষণ করা হয়েছে: ${saved.skill.name}"
            if (isMemoryEnabledProvider()) {
                memoryRepository?.saveMemory(
                    category = MemoryCategory.LEARNED_WORKFLOW,
                    memoryKey = "skill_${saved.skill.name}",
                    value = "Approved reusable skill with ${saved.skill.executionSteps.size} steps (${saved.skill.allowedTools.joinToString(", ")})",
                    confidence = 0.92f,
                    source = MemorySource.SKILL_LEARNED,
                    expirationPolicy = MemoryExpirationPolicy.NEVER,
                    userApproved = true
                )
            }
        }
        return saved
    }

    fun approvePendingSkillProposal(customName: String? = null) {
        scope.launch(Dispatchers.IO) {
            approvePendingSkillProposalSuspending(customName)
        }
    }

    fun dismissPendingSkillProposal() {
        _pendingSkillProposal.value = null
    }

    private suspend fun recordCompletedTaskLearningAndHistory(
        taskId: String,
        taskRequest: String,
        summaryEn: String,
        plan: AgentTaskPlan?,
        executedSteps: List<AgentTaskStep>
    ) {
        // 1. Save concise sanitized TASK_HISTORY memory only if memory is enabled and non-sensitive
        val memRepo = memoryRepository
        if (isMemoryEnabledProvider() && memRepo != null && taskRequest.isNotBlank()) {
            val toolsSummary = executedSteps
                .filter { it.status == AgentStepStatus.COMPLETED }
                .map { it.actionType.toolName }
                .distinct()
                .joinToString(" -> ")
            val safeValue = if (toolsSummary.isNotBlank()) {
                "$summaryEn (tools: $toolsSummary)"
            } else {
                summaryEn
            }
            if (!MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText(taskRequest) &&
                !MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText(safeValue)
            ) {
                runCatching {
                    memRepo.saveMemory(
                        category = MemoryCategory.TASK_HISTORY,
                        memoryKey = taskRequest.take(70),
                        value = safeValue.take(200),
                        confidence = 0.72f,
                        source = MemorySource.TASK_OUTCOME,
                        expirationPolicy = MemoryExpirationPolicy.THIRTY_DAYS,
                        userApproved = false
                    )
                }
            }
        }

        // 2. Propose a reusable skill ONLY for safe multi-step workflows (NEVER auto-saves!)
        val skRepo = skillRepository
        val existingSkills = if (skRepo != null) {
            runCatching { skRepo.getAllSkills() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (skRepo != null) {
            val proposal = skillLearningEngine.proposeSkillFromCompletedTask(
                taskId = taskId,
                taskDescription = taskRequest.ifBlank { plan?.taskDescription.orEmpty() },
                plan = plan,
                executedSteps = executedSteps,
                existingSkills = existingSkills
            ) ?: skillLearningEngine.detectRepeatableWorkflowProposal(
                historyRecords = taskLogger.records.value,
                existingSkills = existingSkills
            )
            if (proposal != null) {
                _pendingSkillProposal.value = proposal
            }
        }

        // 3. Phase 5: Post-task self-reflection (NEVER modifies safety rules or code)
        val reflection = selfReflectionEngine
        if (reflection != null) {
            runCatching {
                val report = reflection.evaluateTask(
                    taskId = taskId,
                    goal = taskRequest.ifBlank { plan?.taskDescription.orEmpty() },
                    outcomeAchieved = true,
                    steps = executedSteps,
                    existingSkills = existingSkills
                )
                _latestReflectionReport.value = report
                if (report.improvementProposals.isNotEmpty()) {
                    _skillImprovementProposals.value = report.improvementProposals
                }
            }
        }
    }

    fun dismissImprovementProposal(proposalId: String) {
        _skillImprovementProposals.update { list -> list.filterNot { it.proposalId == proposalId } }
    }

    private fun evaluateSkillStepCondition(condition: String, parameters: Map<String, String>): Boolean {
        val clean = condition.trim()
        if (clean.isEmpty()) return true
        if (clean.startsWith("param_present:", ignoreCase = true)) {
            val key = clean.substringAfter(':').trim()
            return !parameters[key].isNullOrBlank()
        }
        if (clean.equals("accessibility_ready", ignoreCase = true)) {
            return AgentAccessibilityService.isConnected.value
        }
        return true
    }

    private fun resolveSkillStepParams(
        templates: Map<String, String>,
        runtimeParameters: Map<String, String>
    ): Map<String, String> {
        return templates.mapValues { (_, templateValue) ->
            var resolved = templateValue
            runtimeParameters.forEach { (paramKey, paramValue) ->
                resolved = resolved.replace("{{$paramKey}}", paramValue)
            }
            resolved
        }
    }

    /**
     * Immediately stops any running agent task, clears pending confirmations, and cleans up state.
     */
    fun cancelCurrentTask(reason: String = "Stopped by user") {
        confirmationDeferred?.complete(false)
        confirmationDeferred = null
        _pendingConfirmation.value = null
        _activeSkillId.value = null
        activeTaskJob?.cancel()
        activeTaskJob = null
        _isTaskRunning.value = false
        voiceFeedback?.stopSpeaking()

        _taskSteps.update { steps ->
            steps.map { step ->
                if (step.status == AgentStepStatus.RUNNING ||
                    step.status == AgentStepStatus.PENDING ||
                    step.status == AgentStepStatus.RECOVERING ||
                    step.status == AgentStepStatus.WAITING_CONFIRMATION
                ) {
                    step.copy(status = AgentStepStatus.CANCELLED, detail = reason)
                } else {
                    step
                }
            }
        }
        _activePlan.update { plan ->
            plan?.copy(
                steps = plan.steps.map { s ->
                    if (s.status == AgentStepStatus.PENDING || s.status == AgentStepStatus.RUNNING || s.status == AgentStepStatus.RECOVERING) {
                        s.copy(status = AgentStepStatus.CANCELLED)
                    } else {
                        s
                    }
                }
            )
        }
        currentTaskId?.let { tid ->
            taskLogger.logFinished(tid, AgentExecutionState.CANCELLED, reason)
            saveCheckpointAsync(tid, AgentExecutionState.CANCELLED, _activePlan.value, lastError = reason)
        }
        transitionState(AgentExecutionState.CANCELLED, reason)
        currentTaskId = null
        _statusBannerBn.value = "কাজটি থামানো হয়েছে। আমি কী করতে পারি, বলুন?"
    }

    fun clearTaskHistory() {
        _taskSteps.value = emptyList()
        _activePlan.value = null
        _lastActionVerification.value = null
        taskLogger.clearLogs()
        if (!_isTaskRunning.value) {
            _executionState.value = AgentExecutionState.IDLE
        }
    }

    fun stopLocalVoiceFeedback() {
        voiceFeedback?.stopSpeaking()
    }

    private fun recordExecutedCallId(callId: String): Boolean {
        if (!executedCallIds.add(callId)) {
            return false
        }
        if (executedCallIds.size > MAX_TRACKED_CALL_IDS) {
            val iterator = executedCallIds.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
        return true
    }

    private fun saveCheckpointAsync(
        taskId: String,
        state: AgentExecutionState,
        plan: AgentTaskPlan?,
        safeMetadata: Map<String, String> = emptyMap(),
        lastError: String? = null
    ) {
        val repo = checkpointRepository ?: return
        val reqText = latestUserRequestText.ifBlank { plan?.taskDescription ?: "Agent task" }
        val retries = currentTaskRetryTotal
        scope.launch(Dispatchers.IO) {
            runCatching {
                repo.saveCheckpoint(
                    taskId = taskId,
                    rawUserRequest = reqText,
                    state = state,
                    plan = plan,
                    retryCount = retries,
                    safeMetadata = safeMetadata,
                    lastError = lastError
                )
            }
        }
    }

    private fun transitionState(next: AgentExecutionState, reason: String = "") {
        val previous = _executionState.value
        if (previous != next) {
            _executionState.value = next
            currentTaskId?.let { tid ->
                taskLogger.logStateTransition(tid, previous, next, reason)
            }
        }
    }

    private fun ensureActiveTaskId(defaultRequest: String): String {
        val existing = currentTaskId
        if (existing != null) return existing
        val created = UUID.randomUUID().toString().take(8)
        currentTaskId = created
        taskLogger.startTask(created, defaultRequest)
        return created
    }

    private fun ensurePlanExists(
        taskId: String,
        actionType: AgentActionType,
        params: Map<String, String>
    ): AgentTaskPlan {
        val existing = _activePlan.value
        if (existing != null && existing.taskId == taskId) {
            return existing
        }
        val synthesized = AgentTaskPlan(
            taskId = taskId,
            taskDescription = latestUserRequestText.ifBlank { buildStepTitleEn(actionType, params) },
            steps = listOf(
                AgentPlanStep(
                    stepIndex = 0,
                    description = buildStepTitleEn(actionType, params),
                    toolName = actionType.toolName,
                    arguments = AgentTaskLogger.sanitizeParams(params),
                    status = AgentStepStatus.RUNNING
                )
            ),
            maxStepsAllowed = config.maxStepsPerTask
        )
        _activePlan.value = synthesized
        return synthesized
    }

    private fun incrementPlanStepCounter(actionType: AgentActionType, params: Map<String, String>) {
        _activePlan.update { plan ->
            if (plan == null) return@update null
            val nextExecuted = plan.totalToolStepsExecuted + 1
            val matchIdx = plan.steps.indexOfFirst {
                it.status == AgentStepStatus.PENDING &&
                    (it.toolName.equals(actionType.toolName, ignoreCase = true) ||
                        AgentActionType.fromToolName(it.toolName) == actionType)
            }
            val updatedSteps = if (matchIdx >= 0) {
                plan.steps.mapIndexed { i, step ->
                    if (i == matchIdx) {
                        step.copy(
                            status = AgentStepStatus.RUNNING,
                            arguments = AgentTaskLogger.sanitizeParams(params)
                        )
                    } else {
                        step
                    }
                }
            } else {
                plan.steps + AgentPlanStep(
                    stepIndex = plan.steps.size,
                    description = buildStepTitleEn(actionType, params),
                    toolName = actionType.toolName,
                    arguments = AgentTaskLogger.sanitizeParams(params),
                    status = AgentStepStatus.RUNNING
                )
            }
            plan.copy(
                steps = updatedSteps,
                currentStepIndex = if (matchIdx >= 0) matchIdx else updatedSteps.lastIndex,
                totalToolStepsExecuted = nextExecuted
            )
        }
    }

    private fun updatePlanStepResult(
        actionType: AgentActionType,
        status: AgentStepStatus,
        resultSummary: String
    ) {
        _activePlan.update { plan ->
            if (plan == null) return@update null
            val runningIdx = plan.steps.indexOfLast {
                it.status == AgentStepStatus.RUNNING || it.status == AgentStepStatus.RECOVERING
            }
            if (runningIdx < 0) return@update plan
            plan.copy(
                steps = plan.steps.mapIndexed { idx, step ->
                    if (idx == runningIdx) {
                        step.copy(status = status, executionResult = AgentTaskLogger.sanitizeForLog(resultSummary))
                    } else {
                        step
                    }
                }
            )
        }
    }

    private fun findExpectedOutcomeForAction(actionType: AgentActionType): String {
        val plan = _activePlan.value ?: return ""
        return plan.steps.firstOrNull {
            (it.status == AgentStepStatus.RUNNING || it.status == AgentStepStatus.PENDING) &&
                (it.toolName.equals(actionType.toolName, ignoreCase = true) ||
                    AgentActionType.fromToolName(it.toolName) == actionType)
        }?.expectedOutcome.orEmpty()
    }

    private fun buildTaskPlanFromParams(taskId: String, params: Map<String, String>): AgentTaskPlan {
        val desc = params["task_description"].orEmpty().ifBlank { latestUserRequestText.ifBlank { "Multi-step task" } }
        val expected = params["expected_outcome"].orEmpty()
        val steps = parsePlanSteps(params["steps"].orEmpty(), expected)
        return AgentTaskPlan(
            taskId = taskId,
            taskDescription = desc,
            steps = steps,
            currentStepIndex = 0,
            totalToolStepsExecuted = _activePlan.value?.takeIf { it.taskId == taskId }?.totalToolStepsExecuted ?: 0,
            maxStepsAllowed = config.maxStepsPerTask
        )
    }

    private fun updateActivePlanFromParams(taskId: String, params: Map<String, String>): AgentTaskPlan {
        val existing = _activePlan.value
        val completedPrefix = existing?.takeIf { it.taskId == taskId }?.steps?.filter {
            it.status == AgentStepStatus.COMPLETED
        }.orEmpty()
        val newRemaining = parsePlanSteps(params["remaining_steps"].orEmpty(), expectedOutcome = "")
            .mapIndexed { idx, step -> step.copy(stepIndex = completedPrefix.size + idx) }
        return AgentTaskPlan(
            taskId = taskId,
            taskDescription = existing?.taskDescription ?: latestUserRequestText.ifBlank { "Adapted task" },
            steps = completedPrefix + newRemaining,
            currentStepIndex = completedPrefix.size,
            totalToolStepsExecuted = existing?.totalToolStepsExecuted ?: 0,
            maxStepsAllowed = config.maxStepsPerTask
        )
    }

    private fun persistTaskSummary(summary: String) {
        if (settingsRepository == null) return
        scope.launch {
            settingsRepository.updateLastAgentTaskSummary(summary)
        }
    }

    private fun addOrUpdateStep(step: AgentTaskStep) {
        _taskSteps.update { current ->
            (current + step).takeLast(MAX_TASK_STEPS)
        }
    }

    private fun updateStepStatus(
        stepId: String,
        status: AgentStepStatus,
        detail: String,
        retryCount: Int? = null
    ) {
        _taskSteps.update { current ->
            current.map { step ->
                if (step.id == stepId) {
                    step.copy(
                        status = status,
                        detail = detail,
                        retryCount = retryCount ?: step.retryCount
                    )
                } else {
                    step
                }
            }
        }
    }

    private fun extractParams(call: BidiFunctionCall): Map<String, String> {
        return call.args?.entries?.associate { (k, v) ->
            k to (runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: v.toString())
        } ?: emptyMap()
    }

    private fun toFunctionResponse(
        call: BidiFunctionCall,
        result: AgentActionResult,
        evaluation: AgentOutcomeEvaluator.EvaluationOutcome?
    ): BidiFunctionResponse {
        val responseObj = buildJsonObject {
            put("success", JsonPrimitive(result.success))
            put("verified_success", JsonPrimitive(evaluation?.verifiedSuccess ?: result.success))
            put("status", JsonPrimitive(result.status))
            put("message_en", JsonPrimitive(result.summaryEn))
            put("message_bn", JsonPrimitive(result.summaryBn))
            put("agent_state", JsonPrimitive(_executionState.value.name))
            result.screenChanged?.let { changed ->
                put("screen_changed", JsonPrimitive(changed))
            }
            if (!evaluation?.recoveryHint.isNullOrBlank()) {
                put("recovery_hint", JsonPrimitive(evaluation!!.recoveryHint))
            }
            if (result.blockedBySafety) {
                put("blocked_by_safety_policy", JsonPrimitive(true))
            }
            if (result.needsAccessibilityPermission) {
                put("needs_accessibility_permission", JsonPrimitive(true))
            }
            result.screenSummary?.let { snap ->
                put("screen_ui_snapshot", JsonPrimitive(snap))
            }
            result.details.forEach { (k, v) ->
                put(k, JsonPrimitive(v))
            }
        }
        return BidiFunctionResponse(
            id = call.id,
            name = call.name,
            response = responseObj
        )
    }

    private fun buildCancelledResponse(call: BidiFunctionCall): BidiFunctionResponse {
        return BidiFunctionResponse(
            id = call.id,
            name = call.name,
            response = buildJsonObject {
                put("success", JsonPrimitive(false))
                put("status", JsonPrimitive("CANCELLED"))
                put("message", JsonPrimitive("Task was cancelled or halted after a previous step failed."))
            }
        )
    }

    private fun buildStepTitleEn(type: AgentActionType, params: Map<String, String>): String {
        return when (type) {
            AgentActionType.OPEN_APP -> "Open ${params["app_name"].orEmpty().ifBlank { "App" }}"
            AgentActionType.CLICK_ELEMENT -> "Tap \"${params["target_text"].orEmpty().ifBlank { "screen" }}\""
            AgentActionType.SEARCH_TEXT -> "Search \"${params["query"].orEmpty()}\""
            AgentActionType.TYPE_TEXT -> "Type text into field"
            AgentActionType.SCROLL -> "Scroll ${params["direction"] ?: "down"}"
            AgentActionType.SWIPE -> "Swipe ${params["direction"] ?: "up"}"
            AgentActionType.SET_ALARM -> "Set alarm (${params["hour_24"]}:${params["minute"]})"
            AgentActionType.CALL_TRUSTED_CONTACT -> "Call ${params["contact_query"].orEmpty()}"
            AgentActionType.PREPARE_MESSAGE -> "Prepare message for ${params["recipient_query"].orEmpty()}"
            else -> type.displayLabelEn
        }
    }

    private fun buildStepTitleBn(type: AgentActionType, params: Map<String, String>): String {
        return when (type) {
            AgentActionType.OPEN_APP -> "ঠিক আছে, আমি ${params["app_name"].orEmpty().ifBlank { "অ্যাপ" }} খুলছি।"
            AgentActionType.CLICK_ELEMENT -> "\"${params["target_text"].orEmpty().ifBlank { "বাটন" }}\"-এ ক্লিক করছি।"
            AgentActionType.SEARCH_TEXT -> "আমি \"${params["query"].orEmpty()}\" খুঁজছি।"
            AgentActionType.TYPE_TEXT -> "টেক্সট লিখছি।"
            AgentActionType.SCROLL -> "স্ক্রিন স্ক্রল করছি।"
            AgentActionType.SWIPE -> "স্ক্রিন সোয়াইপ করছি।"
            AgentActionType.SET_ALARM -> "অ্যালার্ম সেট করছি।"
            AgentActionType.CALL_TRUSTED_CONTACT -> "${params["contact_query"].orEmpty()}-কে কল করার প্রস্তুতি নিচ্ছি।"
            AgentActionType.PREPARE_MESSAGE -> "${params["recipient_query"].orEmpty()}-এর জন্য মেসেজ তৈরি করছি।"
            else -> type.displayLabelBn
        }
    }

    companion object {
        private const val MAX_TASK_STEPS = 40
        private const val MAX_TRACKED_CALL_IDS = 64

        /**
         * Parses structured pipe-separated or newline-separated plan steps such as:
         * `open_app:YouTube | search_in_app:Bangla music | click_element:Play | verify_screen:Pause`
         */
        fun parsePlanSteps(rawSteps: String, expectedOutcome: String = ""): List<AgentPlanStep> {
            val rawParts = rawSteps
                .split('|', '\n', ';')
                .map { it.trim().removePrefix("-").replace(Regex("^\\d+[.)]\\s*"), "").trim() }
                .filter { it.isNotEmpty() }

            return rawParts.mapIndexed { idx, part ->
                val colonIndex = part.indexOf(':')
                val parsedTool = if (colonIndex > 0) {
                    val candidate = part.substring(0, colonIndex).trim()
                    AgentActionType.fromToolName(candidate)?.toolName ?: candidate
                } else {
                    AgentActionType.fromToolName(part)?.toolName ?: "custom_step"
                }
                val description = if (colonIndex > 0) {
                    part.substring(colonIndex + 1).trim().ifBlank { part }
                } else {
                    part
                }
                AgentPlanStep(
                    stepIndex = idx,
                    description = description,
                    toolName = parsedTool,
                    expectedOutcome = if (idx == rawParts.lastIndex) expectedOutcome else ""
                )
            }
        }
    }
}
