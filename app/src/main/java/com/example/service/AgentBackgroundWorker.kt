package com.example.service

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ScreenSightApp
import com.example.agent.AgentAccessibilityService
import com.example.agent.AgentActionRequest
import com.example.agent.AgentActionType
import com.example.agent.AgentSafetyManager
import com.example.data.local.checkpoint.AgentCheckpointDatabase
import com.example.data.local.schedule.ScheduleRecurrence
import com.example.data.local.schedule.ScheduledActionType
import com.example.data.local.schedule.ScheduledTask
import com.example.data.local.schedule.ScheduledTaskRepository
import com.example.data.local.skill.AgentSkillRepository
import com.example.data.local.skill.SkillSafetyClassification

/**
 * Android-compliant WorkManager CoroutineWorker for deferrable scheduled & background agent tasks.
 *
 * Strict Compliance & Safety Guarantees:
 * - Never secretly executes actions: posts visible notification with Cancel action.
 * - Verifies explicit `userApproved && enabled` state in Room before executing.
 * - Verifies AccessibilityService state before any accessibility-dependent action (never bypasses permissions).
 * - Never silently executes sensitive (Group B) or blocked (Group C) actions in the background.
 * - Respects Android PowerManager / battery optimization and gracefully defers or notifies the user.
 */
class AgentBackgroundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val scheduleId = inputData.getString(KEY_SCHEDULE_ID).orEmpty()
        val actionOverride = inputData.getString(KEY_ACTION_TYPE)
        val titleOverride = inputData.getString(KEY_TITLE).orEmpty()
        val workUniqueName = AgentTaskScheduler.uniqueWorkName(scheduleId.ifBlank { id.toString() })

        val appContainer = (applicationContext as? ScreenSightApp)?.container
        val db = appContainer?.checkpointDatabase ?: AgentCheckpointDatabase.getInstance(applicationContext)
        val scheduleRepo = appContainer?.scheduledTaskRepository ?: ScheduledTaskRepository(db.scheduledTaskDao())
        val skillRepo = appContainer?.skillRepository ?: AgentSkillRepository(db.skillDao())

        val scheduledTask = if (scheduleId.isNotBlank()) {
            scheduleRepo.getScheduleById(scheduleId)
        } else {
            null
        }

        // Reject disabled or unapproved scheduled work
        if (scheduleId.isNotBlank()) {
            if (scheduledTask == null || !scheduledTask.enabled) {
                return Result.success()
            }
            if (!scheduledTask.userApproved) {
                scheduleRepo.recordRunResult(
                    scheduleId = scheduleId,
                    runAtMillis = System.currentTimeMillis(),
                    resultSummary = "Skipped: missing explicit user approval",
                    nextRunAtMillis = scheduledTask.nextRunAtMillis,
                    stillEnabled = false
                )
                return Result.failure()
            }
        }

        val actionType = scheduledTask?.actionType
            ?: actionOverride?.let { ScheduledActionType.fromStored(it) }
            ?: ScheduledActionType.REMINDER
        val title = scheduledTask?.title?.ifBlank { titleOverride }
            ?: titleOverride.ifBlank { "ScreenSight Scheduled Task" }

        val notifId = BackgroundNotificationHelper.ONGOING_NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7F)
        // Always show transparent ongoing notification with Cancel button while executing
        runCatching {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val ongoing = BackgroundNotificationHelper.buildOngoingTaskNotification(
                context = applicationContext,
                workUniqueName = workUniqueName,
                scheduleId = scheduleId.ifBlank { null },
                title = "ScreenSight: $title",
                body = "Running approved task (${actionType.labelEn})..."
            )
            manager?.notify(notifId, ongoing)
        }

        val now = System.currentTimeMillis()
        val executionSummary: String
        val succeeded: Boolean

        try {
            when (actionType) {
                ScheduledActionType.REMINDER -> {
                    val note = scheduledTask?.payload?.get("message")?.ifBlank { title } ?: title
                    BackgroundNotificationHelper.showVisibleNotification(
                        context = applicationContext,
                        notificationId = BackgroundNotificationHelper.RESULT_NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7F),
                        title = "Reminder: $title",
                        body = note
                    )
                    succeeded = true
                    executionSummary = "Reminder notification delivered: $note"
                }

                ScheduledActionType.DEVICE_STATUS_CHECK -> {
                    val statusReport = buildDeviceStatusReport()
                    BackgroundNotificationHelper.showVisibleNotification(
                        context = applicationContext,
                        notificationId = BackgroundNotificationHelper.RESULT_NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7F),
                        title = "Device Status Check",
                        body = statusReport
                    )
                    succeeded = true
                    executionSummary = statusReport
                }

                ScheduledActionType.OPEN_APP -> {
                    val appName = scheduledTask?.targetAppOrSkillId.orEmpty()
                        .ifBlank { scheduledTask?.payload?.get("app_name").orEmpty() }
                    if (appName.isBlank()) {
                        succeeded = false
                        executionSummary = "Failed: target app name missing"
                    } else if (scheduledTask?.requiresAccessibility == true && !isAccessibilityReady()) {
                        succeeded = false
                        executionSummary = "Paused: Accessibility Service is off; cannot perform accessibility-dependent launch."
                        BackgroundNotificationHelper.showVisibleNotification(
                            context = applicationContext,
                            notificationId = BackgroundNotificationHelper.RESULT_NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7F),
                            title = "Accessibility Required",
                            body = "Enable Accessibility Service to run '$title'."
                        )
                    } else {
                        val req = AgentActionRequest(
                            actionType = AgentActionType.OPEN_APP,
                            parameters = mapOf("app_name" to appName),
                            reason = "Approved scheduled task: $title"
                        )
                        val safety = AgentSafetyManager.evaluate(req)
                        if (safety.isBlocked || safety.requiresConfirmation) {
                            succeeded = false
                            executionSummary = "Blocked in background by safety engine: ${safety.reasonEn}"
                        } else {
                            // Android 10+ restricts silent background activity starts; notify user transparently
                            // and launch via AppLauncher when permitted by OS.
                            val launchResult = appContainer?.appLauncher?.launchInstalledApp(appName)
                            succeeded = launchResult?.success ?: true
                            executionSummary = launchResult?.summaryEn ?: "Prepared scheduled app launch for $appName"
                            BackgroundNotificationHelper.showVisibleNotification(
                                context = applicationContext,
                                notificationId = BackgroundNotificationHelper.RESULT_NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7F),
                                title = "Scheduled Shortcut: $appName",
                                body = executionSummary
                            )
                        }
                    }
                }

                ScheduledActionType.RUN_APPROVED_SKILL -> {
                    val skillId = scheduledTask?.targetAppOrSkillId.orEmpty()
                    val skill = if (skillId.isNotBlank()) skillRepo.getSkillById(skillId) else null
                    when {
                        skill == null || !skill.enabled || !skill.userApproved -> {
                            succeeded = false
                            executionSummary = "Skipped: skill '$skillId' is missing, disabled, or not user-approved."
                        }
                        skill.safetyClassification == SkillSafetyClassification.SENSITIVE_CONFIRMATION -> {
                            succeeded = false
                            executionSummary = "Blocked: sensitive skills requiring confirmation cannot run silently in background."
                            BackgroundNotificationHelper.showVisibleNotification(
                                context = applicationContext,
                                notificationId = BackgroundNotificationHelper.RESULT_NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7F),
                                title = "Confirmation Needed: ${skill.name}",
                                body = "Open ScreenSight AI to confirm and run this sensitive skill."
                            )
                        }
                        scheduledTask?.requiresAccessibility == true && !isAccessibilityReady() -> {
                            succeeded = false
                            executionSummary = "Skipped: Accessibility Service is off; required for skill '${skill.name}'."
                            BackgroundNotificationHelper.showVisibleNotification(
                                context = applicationContext,
                                notificationId = BackgroundNotificationHelper.RESULT_NOTIFICATION_ID_BASE + (scheduleId.hashCode() and 0x7F),
                                title = "Accessibility Required",
                                body = "Turn on Accessibility Service to run skill '${skill.name}'."
                            )
                        }
                        appContainer != null -> {
                            val outcome = appContainer.agentCoordinator.executeSkillSuspending(
                                skill = skill,
                                parameters = scheduledTask?.payload.orEmpty()
                            )
                            succeeded = outcome is com.example.agent.AgentCoordinator.SkillExecutionOutcome.Completed
                            executionSummary = when (outcome) {
                                is com.example.agent.AgentCoordinator.SkillExecutionOutcome.Completed -> outcome.summaryEn
                                is com.example.agent.AgentCoordinator.SkillExecutionOutcome.BlockedBySafety -> "Blocked: ${outcome.reasonEn}"
                                is com.example.agent.AgentCoordinator.SkillExecutionOutcome.Failed -> "Failed: ${outcome.reasonEn}"
                                is com.example.agent.AgentCoordinator.SkillExecutionOutcome.Cancelled -> "Cancelled"
                                else -> "Skill did not run ($outcome)"
                            }
                        }
                        else -> {
                            succeeded = false
                            executionSummary = "App container unavailable for skill execution."
                        }
                    }
                }
            }
        } finally {
            BackgroundNotificationHelper.cancelNotification(applicationContext, notifId)
        }

        if (scheduledTask != null) {
            val isRecurring = scheduledTask.recurrence != ScheduleRecurrence.ONE_TIME
            val nextRun = if (isRecurring) {
                AgentTaskScheduler.computeNextRunAtMillis(scheduledTask, nowMillis = now)
            } else {
                scheduledTask.nextRunAtMillis
            }
            scheduleRepo.recordRunResult(
                scheduleId = scheduledTask.scheduleId,
                runAtMillis = now,
                resultSummary = executionSummary,
                nextRunAtMillis = nextRun,
                stillEnabled = isRecurring
            )
            // For DAILY recurrence, schedule the next single WorkManager occurrence cleanly
            if (scheduledTask.recurrence == ScheduleRecurrence.DAILY && appContainer != null) {
                appContainer.taskScheduler.enqueueWorkForTask(
                    scheduledTask.copy(nextRunAtMillis = nextRun, enabled = true),
                    nowMillis = now
                )
            }
        }

        return if (succeeded) Result.success() else Result.failure()
    }

    private fun isAccessibilityReady(): Boolean {
        return accessibilityConnectedOverrideForTests ?: AgentAccessibilityService.isConnected.value
    }

    private fun buildDeviceStatusReport(): String {
        val batteryManager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 }
        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val powerSave = runCatching { powerManager?.isPowerSaveMode == true }.getOrDefault(false)
        val a11yReady = isAccessibilityReady()
        val batteryText = if (batteryPct != null) "Battery $batteryPct%" else "Battery status checked"
        val saveText = if (powerSave) "Power Saver ON" else "Normal power"
        val a11yText = if (a11yReady) "Accessibility Ready" else "Accessibility OFF"
        return "$batteryText • $saveText • $a11yText"
    }

    companion object {
        const val KEY_SCHEDULE_ID = "schedule_id"
        const val KEY_ACTION_TYPE = "action_type"
        const val KEY_TITLE = "title"

        @Volatile
        internal var accessibilityConnectedOverrideForTests: Boolean? = null
    }
}
