package com.example.data.local.checkpoint

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.agent.AgentExecutionState
import com.example.agent.AgentStepStatus
import kotlinx.serialization.Serializable

/**
 * Room Entity storing strictly sanitized, local-only autonomous task checkpoints (Phase 3, Section 5).
 *
 * Never persists passwords, OTPs, PINs, API keys, or private message bodies.
 * Enables safe inspection and user-controlled resume/cancel after app process restart.
 */
@Entity(tableName = "agent_task_checkpoints")
data class AgentTaskCheckpointEntity(
    @PrimaryKey val taskId: String,
    val sanitizedRequest: String,
    val stateName: String,
    val planJson: String,
    val completedStepsCount: Int,
    val totalStepsCount: Int,
    val retryCount: Int,
    val containsSensitiveAction: Boolean,
    val safeMetadataJson: String,
    val sanitizedLastError: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Serializable
data class PersistedPlanStep(
    val stepIndex: Int,
    val description: String,
    val toolName: String,
    val statusName: String,
    val isSensitive: Boolean = false,
    val retryCount: Int = 0,
    val expectedOutcome: String = "",
    val executionResult: String = ""
)

@Serializable
data class PersistedTaskPlan(
    val taskDescription: String,
    val currentStepIndex: Int,
    val totalToolStepsExecuted: Int,
    val maxStepsAllowed: Int,
    val steps: List<PersistedPlanStep> = emptyList()
)

/**
 * Domain projection of an incomplete checkpoint presented to the user on app restart.
 */
data class RecoverableTaskCheckpoint(
    val taskId: String,
    val sanitizedRequest: String,
    val state: AgentExecutionState,
    val plan: PersistedTaskPlan?,
    val completedStepsCount: Int,
    val totalStepsCount: Int,
    val retryCount: Int,
    val containsSensitiveAction: Boolean,
    val sanitizedLastError: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    val remainingSafeSteps: List<PersistedPlanStep>
        get() = plan?.steps.orEmpty().filter { step ->
            val st = runCatching { AgentStepStatus.valueOf(step.statusName) }.getOrDefault(AgentStepStatus.PENDING)
            (st == AgentStepStatus.PENDING || st == AgentStepStatus.RUNNING || st == AgentStepStatus.RECOVERING) &&
                !step.isSensitive
        }

    val skippedSensitiveStepsCount: Int
        get() = plan?.steps.orEmpty().count { step ->
            val st = runCatching { AgentStepStatus.valueOf(step.statusName) }.getOrDefault(AgentStepStatus.PENDING)
            (st == AgentStepStatus.PENDING || st == AgentStepStatus.RUNNING || st == AgentStepStatus.WAITING_CONFIRMATION) &&
                step.isSensitive
        }
}
