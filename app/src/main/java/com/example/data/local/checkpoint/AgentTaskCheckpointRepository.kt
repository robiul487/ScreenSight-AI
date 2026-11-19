package com.example.data.local.checkpoint

import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionType
import com.example.agent.AgentExecutionState
import com.example.agent.AgentSafetyManager
import com.example.agent.AgentTaskLogger
import com.example.agent.AgentTaskPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Repository abstracting local Room persistence for Phase 3 task checkpoints (Section 5).
 *
 * Guarantees:
 * - Every string & metadata field is scrubbed via [AgentTaskLogger.sanitizeForLog] before SQLite writes.
 * - Passwords, OTPs, PINs, API keys, and private message bodies are NEVER stored.
 * - Flags any task containing Group B sensitive or Group C high-risk steps (`containsSensitiveAction = true`)
 *   so sensitive actions are NEVER automatically replayed after app restart.
 */
class AgentTaskCheckpointRepository(
    private val dao: AgentTaskCheckpointDao
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val recentCheckpointsFlow: Flow<List<RecoverableTaskCheckpoint>> =
        dao.observeRecentCheckpoints(limit = 30).map { list -> list.map { toDomain(it) } }

    val incompleteCheckpointsFlow: Flow<List<RecoverableTaskCheckpoint>> =
        dao.observeIncompleteCheckpoints().map { list -> list.map { toDomain(it) } }

    suspend fun saveCheckpoint(
        taskId: String,
        rawUserRequest: String,
        state: AgentExecutionState,
        plan: AgentTaskPlan?,
        retryCount: Int,
        safeMetadata: Map<String, String> = emptyMap(),
        lastError: String? = null
    ) {
        val now = System.currentTimeMillis()
        val existing = dao.getById(taskId)
        val sanitizedRequest = AgentTaskLogger.sanitizeForLog(rawUserRequest)
        val sanitizedError = lastError?.takeIf { it.isNotBlank() }?.let { AgentTaskLogger.sanitizeForLog(it) }
        val sanitizedMetadata = AgentTaskLogger.sanitizeParams(safeMetadata)

        var containsSensitive = existing?.containsSensitiveAction == true
        val persistedPlan = plan?.let { p ->
            val mappedSteps = p.steps.map { step ->
                val actionType = AgentActionType.fromToolName(step.toolName)
                val stepSensitive = isStepSensitiveOrBlocked(actionType, step.description)
                if (stepSensitive) containsSensitive = true
                PersistedPlanStep(
                    stepIndex = step.stepIndex,
                    description = AgentTaskLogger.sanitizeForLog(step.description),
                    toolName = step.toolName,
                    statusName = step.status.name,
                    isSensitive = stepSensitive,
                    expectedOutcome = AgentTaskLogger.sanitizeForLog(step.expectedOutcome),
                    executionResult = AgentTaskLogger.sanitizeForLog(step.executionResult)
                )
            }
            PersistedTaskPlan(
                taskDescription = AgentTaskLogger.sanitizeForLog(p.taskDescription),
                currentStepIndex = p.currentStepIndex,
                totalToolStepsExecuted = p.totalToolStepsExecuted,
                maxStepsAllowed = p.maxStepsAllowed,
                steps = mappedSteps
            )
        }

        if (state == AgentExecutionState.WAITING_FOR_USER) {
            containsSensitive = true
        }

        val entity = AgentTaskCheckpointEntity(
            taskId = taskId,
            sanitizedRequest = sanitizedRequest.ifBlank { existing?.sanitizedRequest ?: "Agent task" },
            stateName = state.name,
            planJson = persistedPlan?.let { json.encodeToString(it) } ?: existing?.planJson.orEmpty(),
            completedStepsCount = plan?.completedStepsCount ?: existing?.completedStepsCount ?: 0,
            totalStepsCount = plan?.steps?.size ?: existing?.totalStepsCount ?: 0,
            retryCount = maxOf(retryCount, existing?.retryCount ?: 0),
            containsSensitiveAction = containsSensitive,
            safeMetadataJson = json.encodeToString(sanitizedMetadata),
            sanitizedLastError = sanitizedError ?: existing?.sanitizedLastError,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now
        )
        dao.upsert(entity)
    }

    /**
     * Called on app startup / process restart to inspect incomplete tasks BEFORE offering resume.
     * Does NOT execute any action automatically.
     */
    suspend fun inspectIncompleteTasksOnStartup(): List<RecoverableTaskCheckpoint> {
        return dao.getIncompleteCheckpoints().map { toDomain(it) }
    }

    suspend fun getCheckpoint(taskId: String): RecoverableTaskCheckpoint? {
        return dao.getById(taskId)?.let { toDomain(it) }
    }

    suspend fun markCancelled(taskId: String, reason: String = "Cancelled by user") {
        dao.updateState(
            taskId = taskId,
            stateName = AgentExecutionState.CANCELLED.name,
            reason = AgentTaskLogger.sanitizeForLog(reason),
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun clearAllCheckpoints() {
        dao.deleteAll()
    }

    private fun isStepSensitiveOrBlocked(actionType: AgentActionType?, description: String): Boolean {
        if (actionType == null) return false
        if (actionType == AgentActionType.CALL_TRUSTED_CONTACT ||
            actionType == AgentActionType.OPEN_DIALER ||
            actionType == AgentActionType.PREPARE_MESSAGE ||
            actionType == AgentActionType.ASK_CONFIRMATION
        ) {
            return true
        }
        val eval = AgentSafetyManager.evaluate(
            AgentActionRequest(
                actionType = actionType,
                parameters = mapOf("target_text" to description),
                reason = description
            )
        )
        return eval.requiresConfirmation || eval.isBlocked
    }

    private fun toDomain(entity: AgentTaskCheckpointEntity): RecoverableTaskCheckpoint {
        val parsedState = runCatching { AgentExecutionState.valueOf(entity.stateName) }
            .getOrDefault(AgentExecutionState.FAILED)
        val parsedPlan = if (entity.planJson.isNotBlank()) {
            runCatching { json.decodeFromString<PersistedTaskPlan>(entity.planJson) }.getOrNull()
        } else {
            null
        }
        return RecoverableTaskCheckpoint(
            taskId = entity.taskId,
            sanitizedRequest = entity.sanitizedRequest,
            state = parsedState,
            plan = parsedPlan,
            completedStepsCount = entity.completedStepsCount,
            totalStepsCount = entity.totalStepsCount,
            retryCount = entity.retryCount,
            containsSensitiveAction = entity.containsSensitiveAction,
            sanitizedLastError = entity.sanitizedLastError,
            createdAtMillis = entity.createdAtMillis,
            updatedAtMillis = entity.updatedAtMillis
        )
    }
}
