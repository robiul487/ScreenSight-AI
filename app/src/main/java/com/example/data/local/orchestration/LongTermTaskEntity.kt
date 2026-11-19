package com.example.data.local.orchestration

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Lifecycle status for a persistent multi-stage autonomous task.
 */
enum class LongTermTaskStatus(
    val labelEn: String,
    val labelBn: String,
    val isTerminal: Boolean
) {
    PENDING("Pending", "অপেক্ষমান", false),
    RUNNING("Running", "চলমান", false),
    PAUSED("Paused", "বিরতি", false),
    COMPLETED("Completed", "সম্পন্ন", true),
    FAILED("Failed", "ব্যর্থ", true),
    CANCELLED("Cancelled", "বাতিল", true);

    companion object {
        fun fromStored(raw: String): LongTermTaskStatus {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

/**
 * Execution status for an individual dependency-ordered subtask inside a [LongTermTask].
 */
@Serializable
enum class SubtaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELLED
}

/**
 * A single unit of work inside a long-term task DAG.
 * Supports dependency edges ([dependsOn]), bounded retries, explicit verification, and safe restart recovery.
 */
@Serializable
data class TaskSubtask(
    val subtaskId: String,
    val title: String,
    val description: String = "",
    val toolName: String,
    val parameters: Map<String, String> = emptyMap(),
    val dependsOn: List<String> = emptyList(),
    val expectedOutcome: String = "",
    val isSensitive: Boolean = false,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val retryCount: Int = 0,
    val maxRetries: Int = 1,
    val verifiedResult: String? = null,
    val lastError: String? = null
)

/**
 * Strictly local Room entity storing persistent multi-stage autonomous tasks & checkpoints.
 */
@Entity(
    tableName = "agent_long_term_tasks",
    indices = [
        Index(value = ["status"], name = "idx_long_term_tasks_status"),
        Index(value = ["updatedAtMillis"], name = "idx_long_term_tasks_updated")
    ]
)
data class LongTermTaskEntity(
    @PrimaryKey
    val taskId: String,
    val goal: String,
    val status: String,
    val subtasksJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val completedSubtasks: Int,
    val totalSubtasks: Int,
    val replanCount: Int,
    val lastCheckpointNote: String,
    val lastError: String?
)

/**
 * Decoded domain model for a multi-stage autonomous task with DAG subtasks.
 */
data class LongTermTask(
    val taskId: String,
    val goal: String,
    val status: LongTermTaskStatus,
    val subtasks: List<TaskSubtask>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val replanCount: Int = 0,
    val lastCheckpointNote: String = "",
    val lastError: String? = null
) {
    val completedCount: Int
        get() = subtasks.count { it.status == SubtaskStatus.COMPLETED }

    val totalCount: Int
        get() = subtasks.size

    val progressFraction: Float
        get() = if (subtasks.isEmpty()) 0f else (completedCount.toFloat() / subtasks.size.toFloat()).coerceIn(0f, 1f)

    val hasSensitiveRemainingSubtasks: Boolean
        get() = subtasks.any { it.status == SubtaskStatus.PENDING && it.isSensitive }
}
