package com.example.data.local.schedule

import com.example.agent.AgentTaskLogger
import com.example.agent.MemoryPrivacyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Strictly local repository for persisting user-approved scheduled and recurring tasks.
 */
class ScheduledTaskRepository(
    private val dao: ScheduledTaskDao
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun observeAllSchedules(): Flow<List<ScheduledTask>> {
        return dao.observeAllSchedules().map { list -> list.map { it.toDomain() } }
    }

    suspend fun getAllSchedules(): List<ScheduledTask> = dao.getAllSchedules().map { it.toDomain() }

    suspend fun getActiveApprovedSchedules(): List<ScheduledTask> {
        return dao.getActiveApprovedSchedules().map { it.toDomain() }
    }

    suspend fun getScheduleById(scheduleId: String): ScheduledTask? {
        return dao.getScheduleById(scheduleId)?.toDomain()
    }

    suspend fun saveSchedule(task: ScheduledTask): ScheduledTask {
        val safePayload = AgentTaskLogger.sanitizeParams(task.payload).filterKeys { k ->
            !MemoryPrivacyGuard.containsSensitiveSecret(k, task.payload[k].orEmpty())
        }
        val clean = task.copy(
            title = MemoryPrivacyGuard.sanitizeSkillText(task.title, maxLen = 100).ifBlank { task.actionType.labelEn },
            targetAppOrSkillId = MemoryPrivacyGuard.sanitizeSkillText(task.targetAppOrSkillId, maxLen = 100),
            payload = safePayload,
            intervalMinutes = task.intervalMinutes.coerceAtLeast(15L),
            lastRunResult = task.lastRunResult?.let { AgentTaskLogger.sanitizeForLog(it).take(180) }
        )
        dao.upsertSchedule(clean.toEntity())
        return clean
    }

    suspend fun setEnabled(scheduleId: String, enabled: Boolean, nowMillis: Long = System.currentTimeMillis()) {
        dao.setEnabled(scheduleId, enabled, nowMillis)
    }

    suspend fun recordRunResult(
        scheduleId: String,
        runAtMillis: Long,
        resultSummary: String,
        nextRunAtMillis: Long,
        stillEnabled: Boolean
    ) {
        dao.recordRunResult(
            scheduleId = scheduleId,
            runAt = runAtMillis,
            result = AgentTaskLogger.sanitizeForLog(resultSummary).take(180),
            nextRunAt = nextRunAtMillis,
            stillEnabled = stillEnabled
        )
    }

    suspend fun deleteSchedule(scheduleId: String) = dao.deleteSchedule(scheduleId)

    suspend fun clearAllSchedules(): Int = dao.clearAllSchedules()

    private fun ScheduledTask.toEntity(): ScheduledTaskEntity {
        return ScheduledTaskEntity(
            scheduleId = scheduleId,
            title = title,
            actionType = actionType.name,
            targetAppOrSkillId = targetAppOrSkillId,
            payloadJson = json.encodeToString(payload),
            recurrence = recurrence.name,
            hourOfDay = hourOfDay.coerceIn(0, 23),
            minuteOfHour = minuteOfHour.coerceIn(0, 59),
            intervalMinutes = intervalMinutes.coerceAtLeast(15L),
            nextRunAtMillis = nextRunAtMillis,
            enabled = enabled,
            userApproved = userApproved,
            requiresAccessibility = requiresAccessibility,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            lastRunAtMillis = lastRunAtMillis,
            lastRunResult = lastRunResult
        )
    }

    private fun ScheduledTaskEntity.toDomain(): ScheduledTask {
        val decodedPayload = runCatching {
            json.decodeFromString<Map<String, String>>(payloadJson)
        }.getOrDefault(emptyMap())
        return ScheduledTask(
            scheduleId = scheduleId,
            title = title,
            actionType = ScheduledActionType.fromStored(actionType),
            targetAppOrSkillId = targetAppOrSkillId,
            payload = decodedPayload,
            recurrence = ScheduleRecurrence.fromStored(recurrence),
            hourOfDay = hourOfDay,
            minuteOfHour = minuteOfHour,
            intervalMinutes = intervalMinutes,
            nextRunAtMillis = nextRunAtMillis,
            enabled = enabled,
            userApproved = userApproved,
            requiresAccessibility = requiresAccessibility,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            lastRunAtMillis = lastRunAtMillis,
            lastRunResult = lastRunResult
        )
    }
}
