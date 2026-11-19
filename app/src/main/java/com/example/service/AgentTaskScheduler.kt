package com.example.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.agent.MemoryPrivacyGuard
import com.example.data.local.schedule.ScheduleRecurrence
import com.example.data.local.schedule.ScheduledActionType
import com.example.data.local.schedule.ScheduledTask
import com.example.data.local.schedule.ScheduledTaskRepository
import kotlinx.coroutines.flow.Flow
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase 5: Local Android-Compliant Scheduled & Recurring Task Manager.
 *
 * Uses WorkManager exclusively (avoiding restricted SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM
 * permissions and respecting Android Doze & battery optimization).
 *
 * Strict Safety Guarantees:
 * - Requires explicit [userApproved] = true before scheduling ANY action whose
 *   [ScheduledActionType.modifiesDeviceState] is true (e.g., OPEN_APP, RUN_APPROVED_SKILL).
 * - Blocks scheduling any sensitive/financial/credential/destructive workflow text.
 * - Restores all enabled approved schedules cleanly after reboot or device time changes.
 */
class AgentTaskScheduler(
    private val context: Context,
    private val repository: ScheduledTaskRepository
) {

    sealed class ScheduleOutcome {
        data class Scheduled(val task: ScheduledTask) : ScheduleOutcome()
        data class ApprovalRequired(val reasonEn: String, val reasonBn: String) : ScheduleOutcome()
        data class RejectedUnsafe(val reasonEn: String, val reasonBn: String) : ScheduleOutcome()
    }

    fun observeSchedules(): Flow<List<ScheduledTask>> = repository.observeAllSchedules()

    suspend fun getAllSchedules(): List<ScheduledTask> = repository.getAllSchedules()

    /**
     * Creates or updates a scheduled task and registers its WorkManager request.
     * Device-state-modifying schedules MUST have [userApproved] == true.
     */
    suspend fun scheduleTask(
        title: String,
        actionType: ScheduledActionType,
        targetAppOrSkillId: String = "",
        payload: Map<String, String> = emptyMap(),
        recurrence: ScheduleRecurrence = ScheduleRecurrence.ONE_TIME,
        hourOfDay: Int = 8,
        minuteOfHour: Int = 0,
        intervalMinutes: Long = 60L,
        userApproved: Boolean = false,
        requiresAccessibility: Boolean = false,
        existingScheduleId: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): ScheduleOutcome {
        val combinedText = "$title $targetAppOrSkillId ${payload.values.joinToString(" ")}"
        if (MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText(combinedText)) {
            return ScheduleOutcome.RejectedUnsafe(
                reasonEn = "Blocked: payments, purchases, account deletions, and credential actions cannot be scheduled.",
                reasonBn = "নিরাপত্তার কারণে পেমেন্ট, কেনাকাটা, মুছে ফেলা বা পাসওয়ার্ড-সংক্রান্ত কাজ শিডিউল করা যাবে না।"
            )
        }

        if (actionType.modifiesDeviceState && !userApproved) {
            return ScheduleOutcome.ApprovalRequired(
                reasonEn = "Explicit user approval is required before scheduling '${actionType.labelEn}'.",
                reasonBn = "'${actionType.labelBn}' শিডিউল করার আগে আপনার স্পষ্ট অনুমতি প্রয়োজন।"
            )
        }

        val clampedHour = hourOfDay.coerceIn(0, 23)
        val clampedMinute = minuteOfHour.coerceIn(0, 59)
        val clampedInterval = intervalMinutes.coerceAtLeast(15L)

        val draft = ScheduledTask(
            scheduleId = existingScheduleId ?: "sch_${UUID.randomUUID().toString().take(10)}",
            title = title.ifBlank { actionType.labelEn },
            actionType = actionType,
            targetAppOrSkillId = targetAppOrSkillId,
            payload = payload,
            recurrence = recurrence,
            hourOfDay = clampedHour,
            minuteOfHour = clampedMinute,
            intervalMinutes = clampedInterval,
            nextRunAtMillis = nowMillis,
            enabled = true,
            userApproved = if (actionType.modifiesDeviceState) true else true,
            requiresAccessibility = requiresAccessibility,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
        val computedNext = computeNextRunAtMillis(draft, nowMillis)
        val saved = repository.saveSchedule(draft.copy(nextRunAtMillis = computedNext))
        enqueueWorkForTask(saved, nowMillis)
        return ScheduleOutcome.Scheduled(saved)
    }

    /**
     * Pauses or resumes a saved schedule and updates WorkManager accordingly.
     */
    suspend fun setScheduleEnabled(
        scheduleId: String,
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): ScheduledTask? {
        val existing = repository.getScheduleById(scheduleId) ?: return null
        if (enabled && existing.actionType.modifiesDeviceState && !existing.userApproved) {
            return existing
        }
        val nextRun = if (enabled) computeNextRunAtMillis(existing, nowMillis) else existing.nextRunAtMillis
        val updated = repository.saveSchedule(
            existing.copy(
                enabled = enabled,
                nextRunAtMillis = nextRun,
                updatedAtMillis = nowMillis
            )
        )
        if (enabled) {
            enqueueWorkForTask(updated, nowMillis)
        } else {
            cancelWorkForTask(scheduleId)
        }
        return updated
    }

    /**
     * Permanently deletes a scheduled task from Room and cancels its pending WorkManager job.
     */
    suspend fun deleteSchedule(scheduleId: String) {
        cancelWorkForTask(scheduleId)
        repository.deleteSchedule(scheduleId)
    }

    /**
     * Restores all enabled, user-approved schedules after device reboot, package update,
     * or system clock/timezone change.
     */
    suspend fun restoreAllSchedulesAfterReboot(nowMillis: Long = System.currentTimeMillis()): Int {
        val active = repository.getActiveApprovedSchedules()
        var restoredCount = 0
        for (task in active) {
            val nextRun = if (task.nextRunAtMillis <= nowMillis) {
                if (task.recurrence == ScheduleRecurrence.ONE_TIME) {
                    // Missed one-time schedule: reschedule shortly (1 min) only if within 12 hours, else disable cleanly
                    if (nowMillis - task.nextRunAtMillis <= 12L * 60L * 60L * 1000L) {
                        nowMillis + 60_000L
                    } else {
                        repository.setEnabled(task.scheduleId, false, nowMillis)
                        continue
                    }
                } else {
                    computeNextRunAtMillis(task, nowMillis)
                }
            } else {
                task.nextRunAtMillis
            }
            val updated = repository.saveSchedule(
                task.copy(nextRunAtMillis = nextRun, updatedAtMillis = nowMillis)
            )
            enqueueWorkForTask(updated, nowMillis)
            restoredCount++
        }
        return restoredCount
    }

    /**
     * Enqueues a battery-friendly WorkManager request for an approved, enabled [ScheduledTask].
     */
    fun enqueueWorkForTask(task: ScheduledTask, nowMillis: Long = System.currentTimeMillis()) {
        if (!task.enabled || !task.userApproved) return
        val wm = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return

        val delayMs = (task.nextRunAtMillis - nowMillis).coerceAtLeast(0L)
        val inputData = Data.Builder()
            .putString(AgentBackgroundWorker.KEY_SCHEDULE_ID, task.scheduleId)
            .putString(AgentBackgroundWorker.KEY_ACTION_TYPE, task.actionType.name)
            .putString(AgentBackgroundWorker.KEY_TITLE, task.title)
            .build()

        // Respect battery optimization: avoid running non-critical device-modifying work when battery is critically low
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(task.actionType.modifiesDeviceState)
            .build()

        val uniqueName = uniqueWorkName(task.scheduleId)
        if (task.recurrence == ScheduleRecurrence.INTERVAL_MINUTES) {
            val repeatMin = task.intervalMinutes.coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<AgentBackgroundWorker>(repeatMin, TimeUnit.MINUTES)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag(TAG_BACKGROUND_AGENT)
                .addTag(uniqueName)
                .build()
            wm.enqueueUniquePeriodicWork(
                uniqueName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            val request = OneTimeWorkRequestBuilder<AgentBackgroundWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setConstraints(constraints)
                .addTag(TAG_BACKGROUND_AGENT)
                .addTag(uniqueName)
                .build()
            wm.enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun cancelWorkForTask(scheduleId: String) {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueWorkName(scheduleId))
        }
    }

    fun cancelAllBackgroundWork() {
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .cancelAllWorkByTag(TAG_BACKGROUND_AGENT)
        }
    }

    companion object {
        const val TAG_BACKGROUND_AGENT = "screensight_background_agent"

        fun uniqueWorkName(scheduleId: String): String = "screensight_schedule_$scheduleId"

        fun computeNextRunAtMillis(
            task: ScheduledTask,
            nowMillis: Long = System.currentTimeMillis()
        ): Long {
            return when (task.recurrence) {
                ScheduleRecurrence.INTERVAL_MINUTES -> {
                    val intervalMs = task.intervalMinutes.coerceAtLeast(15L) * 60_000L
                    nowMillis + intervalMs
                }
                ScheduleRecurrence.ONE_TIME,
                ScheduleRecurrence.DAILY -> {
                    val cal = Calendar.getInstance().apply {
                        timeInMillis = nowMillis
                        set(Calendar.HOUR_OF_DAY, task.hourOfDay.coerceIn(0, 23))
                        set(Calendar.MINUTE, task.minuteOfHour.coerceIn(0, 59))
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    if (cal.timeInMillis <= nowMillis) {
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                    cal.timeInMillis
                }
            }
        }
    }
}
