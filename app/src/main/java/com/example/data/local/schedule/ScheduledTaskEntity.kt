package com.example.data.local.schedule

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Permitted Android-compliant scheduled action types.
 * High-risk actions (calls, messages, payments, deletions) are NEVER schedulable.
 */
enum class ScheduledActionType(
    val labelEn: String,
    val labelBn: String,
    val modifiesDeviceState: Boolean
) {
    REMINDER("Notification Reminder", "রিমাইন্ডার নোটিফিকেশন", false),
    DEVICE_STATUS_CHECK("Device Status Check", "ডিভাইস স্ট্যাটাস চেক", false),
    OPEN_APP("Open App Shortcut", "অ্যাপ খোলার শর্টকাট", true),
    RUN_APPROVED_SKILL("Run Approved Skill", "অনুমোদিত দক্ষতা চালান", true);

    companion object {
        fun fromStored(raw: String): ScheduledActionType {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: REMINDER
        }
    }
}

enum class ScheduleRecurrence(
    val labelEn: String,
    val labelBn: String
) {
    ONE_TIME("One-Time", "একবার"),
    DAILY("Daily", "প্রতিদিন"),
    INTERVAL_MINUTES("Every N Minutes", "নির্দিষ্ট সময় পরপর");

    companion object {
        fun fromStored(raw: String): ScheduleRecurrence {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: ONE_TIME
        }
    }
}

/**
 * Strictly local Room entity storing user-approved scheduled and recurring agent tasks.
 */
@Entity(
    tableName = "agent_scheduled_tasks",
    indices = [
        Index(value = ["enabled"], name = "idx_scheduled_tasks_enabled"),
        Index(value = ["nextRunAtMillis"], name = "idx_scheduled_tasks_next_run")
    ]
)
data class ScheduledTaskEntity(
    @PrimaryKey
    val scheduleId: String,
    val title: String,
    val actionType: String,
    val targetAppOrSkillId: String,
    val payloadJson: String,
    val recurrence: String,
    val hourOfDay: Int,
    val minuteOfHour: Int,
    val intervalMinutes: Long,
    val nextRunAtMillis: Long,
    val enabled: Boolean,
    val userApproved: Boolean,
    val requiresAccessibility: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastRunAtMillis: Long?,
    val lastRunResult: String?
)

/**
 * Decoded domain model for a local scheduled task.
 */
data class ScheduledTask(
    val scheduleId: String,
    val title: String,
    val actionType: ScheduledActionType,
    val targetAppOrSkillId: String = "",
    val payload: Map<String, String> = emptyMap(),
    val recurrence: ScheduleRecurrence = ScheduleRecurrence.ONE_TIME,
    val hourOfDay: Int = 8,
    val minuteOfHour: Int = 0,
    val intervalMinutes: Long = 60L,
    val nextRunAtMillis: Long,
    val enabled: Boolean = true,
    val userApproved: Boolean = false,
    val requiresAccessibility: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val lastRunAtMillis: Long? = null,
    val lastRunResult: String? = null
)
