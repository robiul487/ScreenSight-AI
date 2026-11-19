package com.example.agent

import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.memory.MemoryCategory
import com.example.data.local.skill.AgentSkill
import com.example.data.local.skill.SkillSafetyClassification

/**
 * Phase 5: Privacy-First Proactive Assistance Engine.
 *
 * Generates helpful, non-intrusive, contextual suggestions based EXCLUSIVELY on:
 * - Local approved user memories (routines, app preferences, task history)
 * - Local approved LOW_RISK reusable skills
 * - Current local time of day
 *
 * Strict Privacy & Safety Guarantees:
 * - Immediately returns an empty list when user disables proactive assistance.
 * - Zero hidden background monitoring, zero external telemetry.
 * - NEVER suggests or auto-executes calls, messages, emails, payments, purchases, deletions, or security changes.
 */
class ProactiveAssistantEngine(
    private val isProactiveEnabledProvider: () -> Boolean = { true }
) {

    enum class SuggestionActionKind {
        RUN_SKILL,
        OPEN_APP,
        CHECK_TIME,
        SET_ALARM
    }

    data class ProactiveSuggestion(
        val id: String,
        val titleEn: String,
        val titleBn: String,
        val reasonEn: String,
        val reasonBn: String,
        val actionKind: SuggestionActionKind,
        val targetIdOrValue: String,
        val confidence: Float
    )

    /**
     * Generates up to [maxSuggestions] relevant, low-risk local suggestions.
     * Strictly returns empty list when disabled by the user.
     */
    fun generateSuggestions(
        memories: List<AgentMemoryRecord>,
        skills: List<AgentSkill>,
        hourOfDay: Int,
        dismissedIds: Set<String> = emptySet(),
        maxSuggestions: Int = 3
    ): List<ProactiveSuggestion> {
        if (!isProactiveEnabledProvider()) return emptyList()

        val suggestions = mutableListOf<ProactiveSuggestion>()
        val safeMemories = memories.filter { mem ->
            !MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText("${mem.memoryKey} ${mem.value}")
        }

        // 1. Time-relevant user routine memories (e.g., morning/evening/night routine)
        val timeKeywords = when (hourOfDay) {
            in 5..10 -> listOf("morning", "সকাল", "alarm", "অ্যালার্ম", "news", "খবর")
            in 11..16 -> listOf("afternoon", "দুপুর", "lunch", "work")
            in 17..20 -> listOf("evening", "সন্ধ্যা", "বিকাল", "news")
            else -> listOf("night", "রাত", "sleep", "alarm", "অ্যালার্ম")
        }

        val routineMatch = safeMemories.firstOrNull { mem ->
            mem.category == MemoryCategory.ROUTINE &&
                (mem.userApproved || mem.confidence >= 0.7f) &&
                timeKeywords.any { kw ->
                    mem.memoryKey.lowercase().contains(kw) || mem.value.lowercase().contains(kw)
                }
        }
        if (routineMatch != null) {
            val isAlarm = routineMatch.value.lowercase().contains("alarm") || routineMatch.value.contains("অ্যালার্ম")
            val id = "sug_routine_${routineMatch.id}"
            if (id !in dismissedIds) {
                suggestions.add(
                    ProactiveSuggestion(
                        id = id,
                        titleEn = if (isAlarm) "Set your routine alarm" else "Run routine: ${routineMatch.memoryKey}",
                        titleBn = if (isAlarm) "রুটিন অ্যালার্ম সেট করুন" else "রুটিন: ${routineMatch.memoryKey}",
                        reasonEn = "Matches your saved routine (${routineMatch.value.take(60)})",
                        reasonBn = "আপনার সংরক্ষিত রুটিনের সাথে মিলেছে",
                        actionKind = if (isAlarm) SuggestionActionKind.SET_ALARM else SuggestionActionKind.CHECK_TIME,
                        targetIdOrValue = routineMatch.value,
                        confidence = routineMatch.confidence.coerceIn(0.6f, 1.0f)
                    )
                )
            }
        }

        // 2. Favorite / preferred app memory shortcut
        val appPref = safeMemories.firstOrNull { mem ->
            mem.category == MemoryCategory.APP_PREFERENCE &&
                (mem.userApproved || mem.confidence >= 0.75f)
        }
        if (appPref != null) {
            val appName = extractSafeAppName(appPref.value)
            if (appName != null) {
                val id = "sug_app_${appName.lowercase()}"
                if (id !in dismissedIds) {
                    suggestions.add(
                        ProactiveSuggestion(
                            id = id,
                            titleEn = "Open $appName",
                            titleBn = "$appName খুলুন",
                            reasonEn = "Based on your saved app preference",
                            reasonBn = "আপনার পছন্দের অ্যাপ স্মৃতি থেকে",
                            actionKind = SuggestionActionKind.OPEN_APP,
                            targetIdOrValue = appName,
                            confidence = 0.82f
                        )
                    )
                }
            }
        }

        // 3. Frequently used, user-approved, LOW_RISK reusable skill
        val topSafeSkill = skills
            .filter { skill ->
                skill.enabled &&
                    skill.userApproved &&
                    skill.safetyClassification == SkillSafetyClassification.LOW_RISK &&
                    skill.usageCount > 0 &&
                    !MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText("${skill.name} ${skill.description}")
            }
            .maxByOrNull { it.successCount * 2 + it.usageCount }

        if (topSafeSkill != null) {
            val id = "sug_skill_${topSafeSkill.skillId}"
            if (id !in dismissedIds) {
                suggestions.add(
                    ProactiveSuggestion(
                        id = id,
                        titleEn = "Run '${topSafeSkill.name}'",
                        titleBn = "'${topSafeSkill.name}' চালান",
                        reasonEn = "Used ${topSafeSkill.usageCount}x with ${topSafeSkill.successCount} verified runs",
                        reasonBn = "আগে ${topSafeSkill.usageCount} বার সফলভাবে ব্যবহৃত হয়েছে",
                        actionKind = SuggestionActionKind.RUN_SKILL,
                        targetIdOrValue = topSafeSkill.skillId,
                        confidence = 0.86f
                    )
                )
            }
        }

        return suggestions
            .sortedByDescending { it.confidence }
            .take(maxSuggestions.coerceAtLeast(1))
    }

    private fun extractSafeAppName(rawValue: String): String? {
        val known = listOf("YouTube", "Chrome", "Maps", "Clock", "Calendar", "Calculator", "Spotify", "Weather")
        val lower = rawValue.lowercase()
        return known.firstOrNull { lower.contains(it.lowercase()) }
    }
}
