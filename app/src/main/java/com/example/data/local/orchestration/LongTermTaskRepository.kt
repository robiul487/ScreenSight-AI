package com.example.data.local.orchestration

import com.example.agent.AgentTaskLogger
import com.example.agent.MemoryPrivacyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Strictly local repository for persisting multi-stage autonomous tasks and subtask checkpoints.
 * Sanitizes all stored goal text, parameters, and checkpoint notes so secrets are never written to disk.
 */
class LongTermTaskRepository(
    private val dao: LongTermTaskDao
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun observeAllTasks(): Flow<List<LongTermTask>> {
        return dao.observeAllTasks().map { list -> list.map { it.toDomain() } }
    }

    suspend fun getAllTasks(): List<LongTermTask> = dao.getAllTasks().map { it.toDomain() }

    suspend fun getTaskById(taskId: String): LongTermTask? = dao.getTaskById(taskId)?.toDomain()

    suspend fun getUnfinishedTasks(): List<LongTermTask> = dao.getUnfinishedTasks().map { it.toDomain() }

    suspend fun saveTask(task: LongTermTask): LongTermTask {
        val sanitizedSubtasks = task.subtasks.map { sub ->
            val safeParams = AgentTaskLogger.sanitizeParams(sub.parameters).filterKeys { k ->
                !MemoryPrivacyGuard.containsSensitiveSecret(k, sub.parameters[k].orEmpty())
            }
            sub.copy(
                title = MemoryPrivacyGuard.sanitizeSkillText(sub.title, maxLen = 120),
                description = MemoryPrivacyGuard.sanitizeSkillText(sub.description, maxLen = 180),
                parameters = safeParams,
                expectedOutcome = MemoryPrivacyGuard.sanitizeSkillText(sub.expectedOutcome, maxLen = 120),
                verifiedResult = sub.verifiedResult?.let { AgentTaskLogger.sanitizeForLog(it).take(200) },
                lastError = sub.lastError?.let { AgentTaskLogger.sanitizeForLog(it).take(200) }
            )
        }
        val cleanTask = task.copy(
            goal = MemoryPrivacyGuard.sanitizeSkillText(task.goal, maxLen = 180).ifBlank { "Autonomous Task" },
            subtasks = sanitizedSubtasks,
            lastCheckpointNote = AgentTaskLogger.sanitizeForLog(task.lastCheckpointNote).take(200),
            lastError = task.lastError?.let { AgentTaskLogger.sanitizeForLog(it).take(200) }
        )
        dao.upsertTask(cleanTask.toEntity())
        return cleanTask
    }

    suspend fun deleteTask(taskId: String) = dao.deleteTask(taskId)

    suspend fun clearAllTasks(): Int = dao.clearAllTasks()

    private fun LongTermTask.toEntity(): LongTermTaskEntity {
        return LongTermTaskEntity(
            taskId = taskId,
            goal = goal,
            status = status.name,
            subtasksJson = json.encodeToString(subtasks),
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            completedSubtasks = completedCount,
            totalSubtasks = totalCount,
            replanCount = replanCount,
            lastCheckpointNote = lastCheckpointNote,
            lastError = lastError
        )
    }

    private fun LongTermTaskEntity.toDomain(): LongTermTask {
        val decodedSubtasks = runCatching {
            json.decodeFromString<List<TaskSubtask>>(subtasksJson)
        }.getOrDefault(emptyList())
        return LongTermTask(
            taskId = taskId,
            goal = goal,
            status = LongTermTaskStatus.fromStored(status),
            subtasks = decodedSubtasks,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            replanCount = replanCount,
            lastCheckpointNote = lastCheckpointNote,
            lastError = lastError
        )
    }
}
