package com.example.agent

import com.example.data.local.SettingsRepository
import com.example.data.remote.BidiFunctionCall
import com.example.data.remote.BidiFunctionResponse
import com.example.data.repository.GeminiLiveRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Sequential Multi-Step Agent Coordinator (Sections 3, 6, 15, 19, 20).
 *
 * Bridges Gemini Live `ToolCall` / `ToolCallCancellation` events with the local
 * [AgentActionExecutor] and [AgentSafetyManager]:
 * - Executes multi-step tool calls sequentially (never firing conflicting UI actions together).
 * - Pauses on Group B Sensitive Actions and awaits explicit user approval via [PendingConfirmation].
 * - Sends structured [BidiFunctionResponse] back to the SAME Gemini Live WebSocket.
 * - Immediately cancels running/pending tasks if the user taps Stop or Gemini sends `ToolCallCancellation`.
 * - Updates concise, privacy-safe [taskSteps] for the Agent Activity Console and Family Easy Mode status.
 */
class AgentCoordinator(
    private val geminiRepository: GeminiLiveRepository,
    private val actionExecutor: AgentActionExecutor,
    private val settingsRepository: SettingsRepository,
    private val voiceFeedback: BanglaVoiceFeedbackManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _taskSteps = MutableStateFlow<List<AgentTaskStep>>(emptyList())
    val taskSteps: StateFlow<List<AgentTaskStep>> = _taskSteps.asStateFlow()

    private val _isTaskRunning = MutableStateFlow(false)
    val isTaskRunning: StateFlow<Boolean> = _isTaskRunning.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _statusBannerBn = MutableStateFlow("আমি কী করতে পারি, বলুন?")
    val statusBannerBn: StateFlow<String> = _statusBannerBn.asStateFlow()

    private var activeTaskJob: Job? = null
    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private val cancelledCallIds = HashSet<String>()

    /**
     * Handles a batch of tool calls received from Gemini Live over the active WebSocket.
     */
    fun onGeminiToolCalls(
        functionCalls: List<BidiFunctionCall>,
        onSystemNote: (String) -> Unit
    ) {
        if (functionCalls.isEmpty()) return

        // Execute sequentially on a dedicated cancellable task job
        val previousJob = activeTaskJob
        activeTaskJob = scope.launch {
            // Wait briefly for any prior single-step action or replace stale execution
            previousJob?.cancel()
            _isTaskRunning.value = true
            _statusBannerBn.value = "একটু অপেক্ষা করুন, কাজটি করছি।"

            val responses = mutableListOf<BidiFunctionResponse>()

            for (call in functionCalls) {
                if (call.id != null && cancelledCallIds.remove(call.id)) {
                    responses.add(buildCancelledResponse(call))
                    continue
                }

                val actionType = AgentActionType.fromToolName(call.name)
                if (actionType == null) {
                    responses.add(
                        BidiFunctionResponse(
                            id = call.id,
                            name = call.name,
                            response = buildJsonObject {
                                put("success", JsonPrimitive(false))
                                put("status", JsonPrimitive("UNSUPPORTED_ACTION"))
                                put("message", JsonPrimitive("Unknown or unsupported Android tool: ${call.name}"))
                            }
                        )
                    )
                    continue
                }

                val params = call.args?.entries?.associate { (k, v) ->
                    k to (runCatching { v.jsonPrimitive.contentOrNull }.getOrNull() ?: v.toString())
                } ?: emptyMap()

                val request = AgentActionRequest(
                    callId = call.id,
                    actionType = actionType,
                    parameters = params,
                    reason = params["reason"].orEmpty()
                )

                val stepId = UUID.randomUUID().toString()
                val stepTitleEn = buildStepTitleEn(actionType, params)
                val stepTitleBn = buildStepTitleBn(actionType, params)

                addOrUpdateStep(
                    AgentTaskStep(
                        id = stepId,
                        callId = call.id,
                        actionType = actionType,
                        titleEn = stepTitleEn,
                        titleBn = stepTitleBn,
                        status = AgentStepStatus.RUNNING
                    )
                )

                val result = executeWithSafetyAndConfirmation(
                    stepId = stepId,
                    request = request,
                    onSystemNote = onSystemNote
                )

                val finalStatus = when {
                    result.status == "USER_CANCELLED" || result.status == "CANCELLED" -> AgentStepStatus.CANCELLED
                    result.success -> AgentStepStatus.COMPLETED
                    else -> AgentStepStatus.FAILED
                }

                updateStepStatus(stepId, finalStatus, result.summaryEn)
                _statusBannerBn.value = if (result.success) {
                    result.summaryBn.ifBlank { "কাজটি সম্পন্ন হয়েছে।" }
                } else {
                    result.summaryBn.ifBlank { "কাজটি করা যায়নি।" }
                }

                // Record concise non-sensitive summary in DataStore
                scope.launch {
                    val safeSummary = "${actionType.displayLabelEn}: ${finalStatus.labelEn}"
                    settingsRepository.updateLastAgentTaskSummary(safeSummary)
                }

                responses.add(toFunctionResponse(call, result))

                // If a step failed or was blocked, stop dependent steps in this batch
                if (!result.success) {
                    val remainingIndex = functionCalls.indexOf(call) + 1
                    for (j in remainingIndex until functionCalls.size) {
                        responses.add(buildCancelledResponse(functionCalls[j]))
                    }
                    break
                }
            }

            if (responses.isNotEmpty() && geminiRepository.isSessionActive()) {
                geminiRepository.sendToolResponses(responses)
            }
            _isTaskRunning.value = false
        }
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
                if (step.callId in ids &&
                    (step.status == AgentStepStatus.PENDING ||
                        step.status == AgentStepStatus.RUNNING ||
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
    }

    /**
     * Executes a direct deterministic shortcut from Dashboard or Family Easy Mode
     * (e.g., Open YouTube, Check Date & Time, Read Screen, Set Alarm) and provides
     * local Bangla TTS feedback if Gemini Live audio isn't actively speaking.
     */
    fun executeDirectQuickAction(
        request: AgentActionRequest,
        isGeminiAudioActive: Boolean = false,
        onResult: ((AgentActionResult) -> Unit)? = null
    ) {
        activeTaskJob?.cancel()
        activeTaskJob = scope.launch {
            _isTaskRunning.value = true
            _statusBannerBn.value = "একটু অপেক্ষা করুন, কাজটি করছি।"

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

            val result = executeWithSafetyAndConfirmation(
                stepId = stepId,
                request = request,
                onSystemNote = {}
            )

            val finalStatus = when {
                result.status == "USER_CANCELLED" -> AgentStepStatus.CANCELLED
                result.success -> AgentStepStatus.COMPLETED
                else -> AgentStepStatus.FAILED
            }
            updateStepStatus(stepId, finalStatus, result.summaryEn)
            _statusBannerBn.value = result.summaryBn.ifBlank {
                if (result.success) "কাজটি সম্পন্ন হয়েছে।" else "কাজটি করা যায়নি।"
            }

            if (result.summaryBn.isNotBlank()) {
                voiceFeedback.speakIfIdle(result.summaryBn, isGeminiLiveAudioActive = isGeminiAudioActive)
            }

            settingsRepository.updateLastAgentTaskSummary("${request.actionType.displayLabelEn}: ${finalStatus.labelEn}")
            _isTaskRunning.value = false
            onResult?.invoke(result)
        }
    }

    private suspend fun executeWithSafetyAndConfirmation(
        stepId: String,
        request: AgentActionRequest,
        onSystemNote: (String) -> Unit
    ): AgentActionResult {
        val activePkg = AgentAccessibilityService.activePackageName.value
        val safety = AgentSafetyManager.evaluate(request, activePkg)

        if (safety.isBlocked) {
            onSystemNote(safety.reasonEn)
            return AgentActionResult(
                success = false,
                status = "BLOCKED_HIGH_RISK",
                summaryEn = safety.reasonEn,
                summaryBn = safety.reasonBn,
                blockedBySafety = true
            )
        }

        if (safety.requiresConfirmation) {
            val (preview, earlyFailure) = actionExecutor.buildConfirmationPreview(request)
            if (earlyFailure != null) {
                return earlyFailure
            }
            if (preview != null) {
                updateStepStatus(stepId, AgentStepStatus.WAITING_CONFIRMATION, preview.promptEn)
                _statusBannerBn.value = preview.promptBn
                _pendingConfirmation.value = preview
                voiceFeedback.speakIfIdle(preview.promptBn, isGeminiLiveAudioActive = geminiRepository.isSessionActive())

                val deferred = CompletableDeferred<Boolean>()
                confirmationDeferred = deferred
                val approved = withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) {
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
                    )
                }

                updateStepStatus(stepId, AgentStepStatus.RUNNING, "Confirmed — executing...")
                return withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                    actionExecutor.execute(preview.actionRequest, userConfirmed = true)
                } ?: AgentActionResult(
                    success = false,
                    status = "ACTION_TIMEOUT",
                    summaryEn = "Action timed out before completing.",
                    summaryBn = "কাজটি করতে নির্ধারিত সময়ের বেশি সময় লেগেছে।"
                )
            }
        }

        return withTimeoutOrNull(ACTION_TIMEOUT_MS) {
            actionExecutor.execute(request, userConfirmed = false)
        } ?: AgentActionResult(
            success = false,
            status = "ACTION_TIMEOUT",
            summaryEn = "Action timed out before completing.",
            summaryBn = "কাজটি করতে নির্ধারিত সময়ের বেশি সময় লেগেছে।"
        )
    }

    fun approvePendingConfirmation() {
        confirmationDeferred?.complete(true)
    }

    fun rejectPendingConfirmation() {
        confirmationDeferred?.complete(false)
        _pendingConfirmation.value = null
    }

    /**
     * Immediately stops any running agent task and clears pending confirmations.
     */
    fun cancelCurrentTask() {
        confirmationDeferred?.complete(false)
        confirmationDeferred = null
        _pendingConfirmation.value = null
        activeTaskJob?.cancel()
        activeTaskJob = null
        _isTaskRunning.value = false
        voiceFeedback.stopSpeaking()

        _taskSteps.update { steps ->
            steps.map { step ->
                if (step.status == AgentStepStatus.RUNNING ||
                    step.status == AgentStepStatus.PENDING ||
                    step.status == AgentStepStatus.WAITING_CONFIRMATION
                ) {
                    step.copy(status = AgentStepStatus.CANCELLED, detail = "Stopped by user")
                } else {
                    step
                }
            }
        }
        _statusBannerBn.value = "কাজটি থামানো হয়েছে। আমি কী করতে পারি, বলুন?"
    }

    fun clearTaskHistory() {
        _taskSteps.value = emptyList()
    }

    fun stopLocalVoiceFeedback() {
        voiceFeedback.stopSpeaking()
    }

    private fun addOrUpdateStep(step: AgentTaskStep) {
        _taskSteps.update { current ->
            (current + step).takeLast(MAX_TASK_STEPS)
        }
    }

    private fun updateStepStatus(stepId: String, status: AgentStepStatus, detail: String) {
        _taskSteps.update { current ->
            current.map { step ->
                if (step.id == stepId) step.copy(status = status, detail = detail) else step
            }
        }
    }

    private fun toFunctionResponse(call: BidiFunctionCall, result: AgentActionResult): BidiFunctionResponse {
        val responseObj = buildJsonObject {
            put("success", JsonPrimitive(result.success))
            put("status", JsonPrimitive(result.status))
            put("message_en", JsonPrimitive(result.summaryEn))
            put("message_bn", JsonPrimitive(result.summaryBn))
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
            AgentActionType.CLICK_ELEMENT -> "Tap \"${params["target_text"].orEmpty()}\""
            AgentActionType.SEARCH_TEXT -> "Search \"${params["query"].orEmpty()}\""
            AgentActionType.TYPE_TEXT -> "Type text into field"
            AgentActionType.SCROLL -> "Scroll ${params["direction"] ?: "down"}"
            AgentActionType.SET_ALARM -> "Set alarm (${params["hour_24"]}:${params["minute"]})"
            AgentActionType.CALL_TRUSTED_CONTACT -> "Call ${params["contact_query"].orEmpty()}"
            AgentActionType.PREPARE_MESSAGE -> "Prepare message for ${params["recipient_query"].orEmpty()}"
            else -> type.displayLabelEn
        }
    }

    private fun buildStepTitleBn(type: AgentActionType, params: Map<String, String>): String {
        return when (type) {
            AgentActionType.OPEN_APP -> "${params["app_name"].orEmpty().ifBlank { "অ্যাপ" }} খুলছি"
            AgentActionType.CLICK_ELEMENT -> "\"${params["target_text"].orEmpty()}\"-এ ক্লিক করছি"
            AgentActionType.SEARCH_TEXT -> "\"${params["query"].orEmpty()}\" খুঁজছি"
            AgentActionType.TYPE_TEXT -> "টেক্সট লিখছি"
            AgentActionType.SCROLL -> "স্ক্রিন স্ক্রল করছি"
            AgentActionType.SET_ALARM -> "অ্যালার্ম সেট করছি"
            AgentActionType.CALL_TRUSTED_CONTACT -> "${params["contact_query"].orEmpty()}-কে কল করার প্রস্তুতি"
            AgentActionType.PREPARE_MESSAGE -> "${params["recipient_query"].orEmpty()}-এর জন্য মেসেজ তৈরি"
            else -> type.displayLabelBn
        }
    }

    companion object {
        private const val ACTION_TIMEOUT_MS = 12_000L
        private const val CONFIRMATION_TIMEOUT_MS = 60_000L
        private const val MAX_TASK_STEPS = 40
    }
}
