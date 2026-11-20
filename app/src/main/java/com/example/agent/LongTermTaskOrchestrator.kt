package com.example.agent

import com.example.data.local.orchestration.LongTermTask
import com.example.data.local.orchestration.LongTermTaskRepository
import com.example.data.local.orchestration.LongTermTaskStatus
import com.example.data.local.orchestration.SubtaskStatus
import com.example.data.local.orchestration.TaskSubtask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Phase 5: Long-Term Autonomous Task Orchestrator.
 *
 * Breaks complex goals into dependency-ordered subtasks (DAG), persists local Room checkpoints
 * after every step, supports safe restart recovery without repeating completed actions,
 * dynamic re-planning, pause/resume/cancel/retry, and verified completion only.
 *
 * Safety & Reliability Invariants:
 * - Rejects cyclic or malformed dependency graphs before execution.
 * - NEVER re-executes subtasks already marked [SubtaskStatus.COMPLETED].
 * - NEVER silently executes sensitive subtasks; surfaces [StepExecutionOutcome.ConfirmationRequired].
 * - Routes every subtask exclusively through central [AgentToolRegistry] + [AgentSafetyManager].
 */
class LongTermTaskOrchestrator(
    private val repository: LongTermTaskRepository,
    private val dynamicToolManager: DynamicToolManager,
    private val gemmaReasoningEngine: GemmaAgentReasoningEngine? = null,
    private val toolExecutor: suspend (AgentActionRequest, Boolean) -> AgentActionResult = { req, confirmed ->
        val tool = dynamicToolManager.toolRegistry.getTool(req.actionType.toolName)
        if (tool != null) {
            dynamicToolManager.toolRegistry.executeRegisteredTool(tool, req, confirmed)
        } else {
            AgentActionResult(
                success = false,
                status = "UNSUPPORTED_ACTION",
                summaryEn = "Tool ${req.actionType.toolName} is not registered.",
                summaryBn = "এই টুলটি নিবন্ধিত নয়।"
            )
        }
    }
) {

    sealed class CreateTaskOutcome {
        data class Created(val task: LongTermTask) : CreateTaskOutcome()
        data class InvalidDag(val reasonEn: String, val reasonBn: String) : CreateTaskOutcome()
    }

    sealed class RunOutcome {
        data class Completed(val task: LongTermTask, val executedSubtasksCount: Int) : RunOutcome()
        data class Paused(val task: LongTermTask, val executedSubtasksCount: Int, val reason: String) : RunOutcome()
        data class Cancelled(val task: LongTermTask) : RunOutcome()
        data class ConfirmationRequired(
            val task: LongTermTask,
            val subtask: TaskSubtask,
            val reasonEn: String,
            val reasonBn: String
        ) : RunOutcome()
        data class BlockedBySafety(
            val task: LongTermTask,
            val subtask: TaskSubtask,
            val reasonEn: String,
            val reasonBn: String
        ) : RunOutcome()
        data class Failed(val task: LongTermTask, val reasonEn: String) : RunOutcome()
        data object NotFound : RunOutcome()
    }

    private val executionMutex = Mutex()
    private val pauseRequestedTaskIds = HashSet<String>()
    private val cancelRequestedTaskIds = HashSet<String>()

    fun observeAllTasks(): Flow<List<LongTermTask>> = repository.observeAllTasks()

    suspend fun getAllTasks(): List<LongTermTask> = repository.getAllTasks()

    suspend fun getTaskById(taskId: String): LongTermTask? = repository.getTaskById(taskId)

    /**
     * Validates DAG dependencies (no missing refs, no cycles, non-empty subtasks) and persists the task.
     */
    suspend fun createTask(
        goal: String,
        subtasks: List<TaskSubtask>,
        nowMillis: Long = System.currentTimeMillis(),
        modelUsageNote: String? = null
    ): CreateTaskOutcome {
        val cleanGoal = MemoryPrivacyGuard.sanitizeSkillText(goal, maxLen = 180)
        if (cleanGoal.isBlank()) {
            return CreateTaskOutcome.InvalidDag(
                reasonEn = "Task goal cannot be empty.",
                reasonBn = "কাজের লক্ষ্য খালি রাখা যাবে না।"
            )
        }
        val dagError = validateDag(subtasks)
        if (dagError != null) {
            return CreateTaskOutcome.InvalidDag(
                reasonEn = dagError,
                reasonBn = "কাজের ধাপগুলোর ক্রম সঠিক নয় ($dagError)।"
            )
        }

        val checkpointPrefix = "Created with ${subtasks.size} dependency-ordered subtasks"
        val note = if (!modelUsageNote.isNullOrBlank()) {
            "$checkpointPrefix • ${AgentTaskLogger.sanitizeForLog(modelUsageNote)}"
        } else {
            checkpointPrefix
        }

        val task = LongTermTask(
            taskId = "lt_${UUID.randomUUID().toString().take(10)}",
            goal = cleanGoal,
            status = LongTermTaskStatus.PENDING,
            subtasks = subtasks,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            lastCheckpointNote = note
        )
        val saved = repository.saveTask(task)
        return CreateTaskOutcome.Created(saved)
    }

    /**
     * Inspects unfinished tasks after app restart and resets any interrupted RUNNING subtasks
     * back to PENDING (preserving already COMPLETED subtasks so they are NEVER re-executed).
     */
    suspend fun inspectAndPrepareUnfinishedTasksOnStartup(
        nowMillis: Long = System.currentTimeMillis()
    ): List<LongTermTask> {
        val unfinished = repository.getUnfinishedTasks()
        return unfinished.map { task ->
            val hadInterruptedRunning = task.status == LongTermTaskStatus.RUNNING ||
                task.subtasks.any { it.status == SubtaskStatus.RUNNING }
            if (!hadInterruptedRunning) {
                task
            } else {
                val recoveredSubtasks = task.subtasks.map { sub ->
                    if (sub.status == SubtaskStatus.RUNNING) {
                        sub.copy(status = SubtaskStatus.PENDING, lastError = "Recovered after process restart")
                    } else {
                        sub
                    }
                }
                val recovered = task.copy(
                    status = LongTermTaskStatus.PAUSED,
                    subtasks = recoveredSubtasks,
                    updatedAtMillis = nowMillis,
                    lastCheckpointNote = "Restored at checkpoint (${recoveredSubtasks.count { it.status == SubtaskStatus.COMPLETED }}/${recoveredSubtasks.size} completed). Tap Resume to continue safely."
                )
                repository.saveTask(recovered)
            }
        }
    }

    /**
     * Executes ready dependency-satisfied subtasks sequentially, persisting a Room checkpoint after
     * every subtask. Already COMPLETED subtasks are strictly skipped to prevent duplicate execution.
     */
    suspend fun runTask(
        taskId: String,
        userConfirmedSensitiveSubtaskId: String? = null,
        maxSubtasksThisPass: Int = Int.MAX_VALUE
    ): RunOutcome = executionMutex.withLock {
        pauseRequestedTaskIds.remove(taskId)
        cancelRequestedTaskIds.remove(taskId)

        var current = repository.getTaskById(taskId) ?: return@withLock RunOutcome.NotFound
        if (current.status == LongTermTaskStatus.COMPLETED) {
            return@withLock RunOutcome.Completed(current, 0)
        }
        if (current.status == LongTermTaskStatus.CANCELLED) {
            return@withLock RunOutcome.Cancelled(current)
        }

        val dagError = validateDag(current.subtasks)
        if (dagError != null) {
            val failed = repository.saveTask(
                current.copy(
                    status = LongTermTaskStatus.FAILED,
                    updatedAtMillis = System.currentTimeMillis(),
                    lastError = dagError,
                    lastCheckpointNote = "Halted: invalid dependency graph"
                )
            )
            return@withLock RunOutcome.Failed(failed, dagError)
        }

        current = repository.saveTask(
            current.copy(
                status = LongTermTaskStatus.RUNNING,
                updatedAtMillis = System.currentTimeMillis(),
                lastCheckpointNote = "Running (${current.completedCount}/${current.totalCount} completed)"
            )
        )

        var executedCount = 0

        while (executedCount < maxSubtasksThisPass) {
            if (cancelRequestedTaskIds.remove(taskId)) {
                val cancelled = cancelTaskInternal(current, "Cancelled by user")
                return@withLock RunOutcome.Cancelled(cancelled)
            }
            if (pauseRequestedTaskIds.remove(taskId)) {
                val paused = repository.saveTask(
                    current.copy(
                        status = LongTermTaskStatus.PAUSED,
                        updatedAtMillis = System.currentTimeMillis(),
                        lastCheckpointNote = "Paused by user at ${current.completedCount}/${current.totalCount}"
                    )
                )
                return@withLock RunOutcome.Paused(paused, executedCount, "Paused by user")
            }

            // Pick next PENDING subtask whose dependencies are ALL COMPLETED (never re-run COMPLETED!)
            val completedIds = current.subtasks
                .filter { it.status == SubtaskStatus.COMPLETED }
                .map { it.subtaskId }
                .toSet()

            val nextReady = current.subtasks.firstOrNull { sub ->
                sub.status == SubtaskStatus.PENDING && sub.dependsOn.all { dep -> dep in completedIds }
            }

            if (nextReady == null) {
                val allDone = current.subtasks.all {
                    it.status == SubtaskStatus.COMPLETED || it.status == SubtaskStatus.SKIPPED
                }
                return@withLock if (allDone && current.subtasks.any { it.status == SubtaskStatus.COMPLETED }) {
                    val done = repository.saveTask(
                        current.copy(
                            status = LongTermTaskStatus.COMPLETED,
                            updatedAtMillis = System.currentTimeMillis(),
                            lastError = null,
                            lastCheckpointNote = "Verified completion (${current.completedCount}/${current.totalCount} subtasks)"
                        )
                    )
                    RunOutcome.Completed(done, executedCount)
                } else {
                    val reason = "No runnable subtasks remain (unsatisfied dependencies or prior failure)."
                    val failed = repository.saveTask(
                        current.copy(
                            status = LongTermTaskStatus.FAILED,
                            updatedAtMillis = System.currentTimeMillis(),
                            lastError = reason,
                            lastCheckpointNote = reason
                        )
                    )
                    RunOutcome.Failed(failed, reason)
                }
            }

            // Validate tool call against central schema
            val (registeredTool, validation) = dynamicToolManager.validateCall(nextReady.toolName, nextReady.parameters)
            if (!validation.isValid || registeredTool == null) {
                val failedSub = nextReady.copy(
                    status = SubtaskStatus.FAILED,
                    lastError = validation.errorMessageEn
                )
                current = repository.saveTask(
                    current.copy(
                        status = LongTermTaskStatus.FAILED,
                        subtasks = replaceSubtask(current.subtasks, failedSub),
                        updatedAtMillis = System.currentTimeMillis(),
                        lastError = validation.errorMessageEn,
                        lastCheckpointNote = "Failed validation on ${nextReady.title}"
                    )
                )
                return@withLock RunOutcome.Failed(current, validation.errorMessageEn)
            }

            val req = AgentActionRequest(
                actionType = registeredTool.actionType,
                parameters = nextReady.parameters,
                reason = "Long-term task (${current.goal}): ${nextReady.title}",
                requiresConfirmation = nextReady.isSensitive
            )

            // Mandatory central AgentSafetyManager check
            val safety = AgentSafetyManager.evaluate(req)
            if (safety.isBlocked) {
                val failedSub = nextReady.copy(
                    status = SubtaskStatus.FAILED,
                    lastError = safety.reasonEn
                )
                current = repository.saveTask(
                    current.copy(
                        status = LongTermTaskStatus.FAILED,
                        subtasks = replaceSubtask(current.subtasks, failedSub),
                        updatedAtMillis = System.currentTimeMillis(),
                        lastError = safety.reasonEn,
                        lastCheckpointNote = "Blocked by AgentSafetyManager on ${nextReady.title}"
                    )
                )
                dynamicToolManager.recordExecution(
                    taskId = taskId,
                    toolName = registeredTool.name,
                    succeeded = false,
                    blockedBySafety = true,
                    retryCount = 0,
                    durationMs = 0L,
                    errorCode = "BLOCKED_HIGH_RISK",
                    summary = safety.reasonEn
                )
                return@withLock RunOutcome.BlockedBySafety(current, failedSub, safety.reasonEn, safety.reasonBn)
            }

            val isConfirmedThisSubtask = userConfirmedSensitiveSubtaskId == nextReady.subtaskId
            if (safety.requiresConfirmation && !isConfirmedThisSubtask) {
                val paused = repository.saveTask(
                    current.copy(
                        status = LongTermTaskStatus.PAUSED,
                        updatedAtMillis = System.currentTimeMillis(),
                        lastCheckpointNote = "Paused for explicit user confirmation on ${nextReady.title}"
                    )
                )
                return@withLock RunOutcome.ConfirmationRequired(paused, nextReady, safety.reasonEn, safety.reasonBn)
            }

            // Persist RUNNING checkpoint before executing
            val runningSub = nextReady.copy(status = SubtaskStatus.RUNNING)
            current = repository.saveTask(
                current.copy(
                    subtasks = replaceSubtask(current.subtasks, runningSub),
                    updatedAtMillis = System.currentTimeMillis(),
                    lastCheckpointNote = "Executing subtask: ${nextReady.title}"
                )
            )

            val startMs = System.currentTimeMillis()
            val maxAttempts = 1 + nextReady.maxRetries.coerceIn(0, 2)
            var result: AgentActionResult? = null
            var attemptsUsed = 0

            for (attempt in 1..maxAttempts) {
                attemptsUsed = attempt
                val attemptResult = toolExecutor(req, isConfirmedThisSubtask)
                result = attemptResult
                if (attemptResult.success || !attemptResult.recoverable || attemptResult.blockedBySafety) {
                    break
                }
            }

            val finalResult = result ?: AgentActionResult(
                success = false,
                status = "EXECUTION_ERROR",
                summaryEn = "Subtask returned no result.",
                summaryBn = "ধাপটি কোনো ফলাফল দেয়নি।"
            )
            val durationMs = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
            val retriesDone = (attemptsUsed - 1).coerceAtLeast(0)

            dynamicToolManager.recordExecution(
                taskId = taskId,
                toolName = registeredTool.name,
                succeeded = finalResult.success,
                blockedBySafety = finalResult.blockedBySafety,
                retryCount = retriesDone,
                durationMs = durationMs,
                errorCode = if (finalResult.success) null else finalResult.status,
                summary = finalResult.summaryEn
            )

            if (finalResult.success) {
                executedCount++
                val completedSub = nextReady.copy(
                    status = SubtaskStatus.COMPLETED,
                    retryCount = nextReady.retryCount + retriesDone,
                    verifiedResult = finalResult.summaryEn,
                    lastError = null
                )
                current = repository.saveTask(
                    current.copy(
                        subtasks = replaceSubtask(current.subtasks, completedSub),
                        updatedAtMillis = System.currentTimeMillis(),
                        lastError = null,
                        lastCheckpointNote = "Verified: ${nextReady.title} (${current.completedCount + 1}/${current.totalCount})"
                    )
                )
            } else {
                val failedSub = nextReady.copy(
                    status = SubtaskStatus.FAILED,
                    retryCount = nextReady.retryCount + retriesDone,
                    lastError = finalResult.summaryEn
                )
                current = repository.saveTask(
                    current.copy(
                        status = LongTermTaskStatus.FAILED,
                        subtasks = replaceSubtask(current.subtasks, failedSub),
                        updatedAtMillis = System.currentTimeMillis(),
                        lastError = finalResult.summaryEn,
                        lastCheckpointNote = "Failed at ${nextReady.title}: ${finalResult.status}"
                    )
                )
                return@withLock RunOutcome.Failed(current, finalResult.summaryEn)
            }
        }

        // Hit maxSubtasksThisPass budget -> pause cleanly at checkpoint
        val paused = repository.saveTask(
            current.copy(
                status = LongTermTaskStatus.PAUSED,
                updatedAtMillis = System.currentTimeMillis(),
                lastCheckpointNote = "Checkpoint saved (${current.completedCount}/${current.totalCount} completed)"
            )
        )
        RunOutcome.Paused(paused, executedCount, "Checkpoint pass complete")
    }

    /**
     * Pauses an active or pending task safely at its current checkpoint.
     */
    suspend fun pauseTask(taskId: String): LongTermTask? {
        pauseRequestedTaskIds.add(taskId)
        val task = repository.getTaskById(taskId) ?: return null
        if (task.status.isTerminal) return task
        return repository.saveTask(
            task.copy(
                status = LongTermTaskStatus.PAUSED,
                updatedAtMillis = System.currentTimeMillis(),
                lastCheckpointNote = "Paused by user (${task.completedCount}/${task.totalCount} completed)"
            )
        )
    }

    /**
     * Immediately marks a task and all non-completed subtasks as CANCELLED.
     */
    suspend fun cancelTask(taskId: String, reason: String = "Stopped by user"): LongTermTask? {
        cancelRequestedTaskIds.add(taskId)
        val task = repository.getTaskById(taskId) ?: return null
        return cancelTaskInternal(task, reason)
    }

    /**
     * Resets failed subtasks back to PENDING so the task can resume without repeating already completed steps.
     */
    suspend fun retryFailedSubtasks(taskId: String): LongTermTask? {
        val task = repository.getTaskById(taskId) ?: return null
        val resetSubtasks = task.subtasks.map { sub ->
            if (sub.status == SubtaskStatus.FAILED) {
                sub.copy(status = SubtaskStatus.PENDING, lastError = null)
            } else {
                sub
            }
        }
        return repository.saveTask(
            task.copy(
                status = LongTermTaskStatus.PAUSED,
                subtasks = resetSubtasks,
                updatedAtMillis = System.currentTimeMillis(),
                lastError = null,
                lastCheckpointNote = "Failed subtasks reset for safe retry"
            )
        )
    }

    /**
     * Dynamically replaces uncompleted subtasks with a revised plan while strictly preserving
     * all already-COMPLETED subtasks so completed actions are never duplicated.
     */
    suspend fun replanRemainingSubtasks(
        taskId: String,
        newRemainingSubtasks: List<TaskSubtask>,
        reason: String
    ): CreateTaskOutcome {
        val task = repository.getTaskById(taskId)
            ?: return CreateTaskOutcome.InvalidDag("Task $taskId not found.", "কাজটি পাওয়া যায়নি।")

        val completedPrefix = task.subtasks.filter { it.status == SubtaskStatus.COMPLETED }
        val combined = completedPrefix + newRemainingSubtasks.filter { newSub ->
            completedPrefix.none { it.subtaskId == newSub.subtaskId }
        }
        val dagError = validateDag(combined)
        if (dagError != null) {
            return CreateTaskOutcome.InvalidDag(dagError, "পুনর্পরিকল্পনার ধাপগুলো সঠিক নয়।")
        }

        val updated = repository.saveTask(
            task.copy(
                status = LongTermTaskStatus.PAUSED,
                subtasks = combined,
                replanCount = task.replanCount + 1,
                updatedAtMillis = System.currentTimeMillis(),
                lastError = null,
                lastCheckpointNote = "Replanned (${task.replanCount + 1}): ${AgentTaskLogger.sanitizeForLog(reason)}"
            )
        )
        return CreateTaskOutcome.Created(updated)
    }

    /**
     * Uses Gemma 4 reasoning (when configured) to propose an alternative safe recovery plan
     * after a subtask failure without repeating already completed or failed action signatures.
     */
    suspend fun attemptGuidedRecovery(taskId: String): CreateTaskOutcome? {
        val engine = gemmaReasoningEngine ?: return null
        val task = repository.getTaskById(taskId) ?: return null
        val failedSub = task.subtasks.firstOrNull { it.status == SubtaskStatus.FAILED } ?: return null
        val completedIds = task.subtasks.filter { it.status == SubtaskStatus.COMPLETED }.map { it.subtaskId }.toSet()
        val triedFailedSignatures = task.subtasks
            .filter { it.status == SubtaskStatus.FAILED }
            .map { GemmaAgentReasoningEngine.actionSignature(it.toolName, it.parameters) }
            .toSet()

        val proposal = engine.proposeRecoveryPlan(
            goal = task.goal,
            failedSubtask = failedSub,
            failureReason = failedSub.lastError ?: task.lastError.orEmpty(),
            completedSubtaskIds = completedIds,
            triedActionSignatures = triedFailedSignatures,
            taskIdForTelemetry = taskId
        )
        if (!proposal.hasRecoveryPlan || proposal.replacementSubtasks.isEmpty()) {
            return null
        }

        val lastCompletedId = task.subtasks.lastOrNull { it.status == SubtaskStatus.COMPLETED }?.subtaskId
        val rebased = proposal.replacementSubtasks.mapIndexed { idx, sub ->
            val uniqueId = "rec_${task.replanCount + 1}_${idx + 1}"
            val dep = if (idx == 0) listOfNotNull(lastCompletedId) else listOf("rec_${task.replanCount + 1}_$idx")
            sub.copy(subtaskId = uniqueId, dependsOn = dep)
        }
        val usageNote = proposal.modelUsage?.toCompactCheckpointTag()?.let { " • $it" }.orEmpty()
        return replanRemainingSubtasks(
            taskId = taskId,
            newRemainingSubtasks = rebased,
            reason = "${proposal.explanation}$usageNote"
        )
    }

    suspend fun deleteTask(taskId: String) = repository.deleteTask(taskId)

    private suspend fun cancelTaskInternal(task: LongTermTask, reason: String): LongTermTask {
        val updatedSubtasks = task.subtasks.map { sub ->
            if (sub.status == SubtaskStatus.PENDING || sub.status == SubtaskStatus.RUNNING) {
                sub.copy(status = SubtaskStatus.CANCELLED, lastError = reason)
            } else {
                sub
            }
        }
        return repository.saveTask(
            task.copy(
                status = LongTermTaskStatus.CANCELLED,
                subtasks = updatedSubtasks,
                updatedAtMillis = System.currentTimeMillis(),
                lastError = reason,
                lastCheckpointNote = "Cancelled: $reason"
            )
        )
    }

    private fun replaceSubtask(list: List<TaskSubtask>, replacement: TaskSubtask): List<TaskSubtask> {
        return list.map { if (it.subtaskId == replacement.subtaskId) replacement else it }
    }

    companion object {
        /**
         * Validates that subtasks form a valid acyclic DAG with unique IDs and valid dependency edges.
         * Returns null if valid, or a descriptive error string if invalid.
         */
        fun validateDag(subtasks: List<TaskSubtask>): String? {
            if (subtasks.isEmpty()) return "Task must contain at least one subtask."
            val ids = subtasks.map { it.subtaskId }
            if (ids.any { it.isBlank() }) return "Every subtask must have a non-blank subtaskId."
            if (ids.size != ids.toSet().size) return "Duplicate subtaskId detected."

            val idSet = ids.toSet()
            val depsById = subtasks.associate { it.subtaskId to it.dependsOn }
            for ((id, deps) in depsById) {
                for (dep in deps) {
                    if (dep == id) return "Subtask '$id' cannot depend on itself."
                    if (dep !in idSet) return "Subtask '$id' depends on unknown subtask '$dep'."
                }
            }

            // Kahn's topological sort cycle detection
            val inDegree = HashMap<String, Int>()
            val dependents = HashMap<String, MutableList<String>>()
            ids.forEach { inDegree[it] = 0 }
            for ((id, deps) in depsById) {
                inDegree[id] = deps.size
                deps.forEach { dep ->
                    dependents.getOrPut(dep) { mutableListOf() }.add(id)
                }
            }

            val queue = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
            var visited = 0
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                visited++
                dependents[node].orEmpty().forEach { next ->
                    val nextDeg = (inDegree[next] ?: 1) - 1
                    inDegree[next] = nextDeg
                    if (nextDeg == 0) queue.addLast(next)
                }
            }

            return if (visited != subtasks.size) {
                "Cyclic dependency detected in subtask graph."
            } else {
                null
            }
        }
    }
}
