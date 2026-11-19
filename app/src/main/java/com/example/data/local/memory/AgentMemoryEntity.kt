package com.example.data.local.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Structured categories for ScreenSight AI's strictly on-device Long-Term Memory system.
 */
enum class MemoryCategory(
    val displayNameEn: String,
    val displayNameBn: String
) {
    USER_PREFERENCE("User Preference", "ব্যবহারকারীর পছন্দ"),
    USER_PROFILE("User Profile", "ব্যক্তিগত তথ্য"),
    ROUTINE("Routine", "দৈনন্দিন অভ্যাস"),
    CONTACT_ALIAS("Contact Alias", "যোগাযোগের ডাকনাম"),
    APP_PREFERENCE("App Preference", "পছন্দের অ্যাপ"),
    TASK_HISTORY("Task History", "কাজের ইতিহাস"),
    LEARNED_WORKFLOW("Learned Workflow", "শেখা কাজের ধাপ"),
    EXPLICIT_INSTRUCTION("Explicit Instruction", "বিশেষ নির্দেশনা");

    companion object {
        fun fromStored(raw: String): MemoryCategory {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: USER_PREFERENCE
        }
    }
}

enum class MemorySource(val labelEn: String) {
    USER_EXPLICIT("User Requested"),
    USER_EDITED("User Edited"),
    TASK_OUTCOME("Completed Task"),
    SKILL_LEARNED("Learned Workflow");

    companion object {
        fun fromStored(raw: String): MemorySource {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: USER_EXPLICIT
        }
    }
}

enum class MemoryExpirationPolicy(
    val labelEn: String,
    val labelBn: String,
    val durationMillis: Long?
) {
    NEVER("Never expires", "কখনো মুছবে না", null),
    SEVEN_DAYS("7 days", "৭ দিন", 7L * 24L * 60L * 60L * 1000L),
    THIRTY_DAYS("30 days", "৩০ দিন", 30L * 24L * 60L * 60L * 1000L),
    NINETY_DAYS("90 days", "৯০ দিন", 90L * 24L * 60L * 60L * 1000L),
    CUSTOM("Custom expiry", "নির্দিষ্ট সময়", null);

    fun computeExpiresAt(fromMillis: Long, customExpiresAt: Long? = null): Long? {
        return when (this) {
            NEVER -> null
            CUSTOM -> customExpiresAt
            else -> durationMillis?.let { fromMillis + it }
        }
    }

    companion object {
        fun fromStored(raw: String): MemoryExpirationPolicy {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: NEVER
        }
    }
}

/**
 * Room entity for a sanitized, strictly on-device long-term memory record.
 * Never stores passwords, OTPs, PINs, API keys, authentication tokens, or private message bodies.
 */
@Entity(
    tableName = "agent_memories",
    indices = [
        Index(value = ["category"], name = "idx_agent_memories_category"),
        Index(value = ["normalizedKey"], name = "idx_agent_memories_normalized_key"),
        Index(value = ["expiresAtMillis"], name = "idx_agent_memories_expires_at")
    ]
)
data class AgentMemoryEntity(
    @PrimaryKey
    val id: String,
    val category: String,
    val memoryKey: String,
    val normalizedKey: String,
    val value: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastUsedAtMillis: Long,
    val confidence: Float,
    val source: String,
    val expirationPolicy: String,
    val expiresAtMillis: Long?,
    val userApproved: Boolean
)

/**
 * Domain model representing a decoded, validated local memory record.
 */
data class AgentMemoryRecord(
    val id: String,
    val category: MemoryCategory,
    val memoryKey: String,
    val value: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastUsedAtMillis: Long,
    val confidence: Float,
    val source: MemorySource,
    val expirationPolicy: MemoryExpirationPolicy,
    val expiresAtMillis: Long?,
    val userApproved: Boolean
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean {
        val exp = expiresAtMillis ?: return false
        return nowMillis >= exp
    }
}
