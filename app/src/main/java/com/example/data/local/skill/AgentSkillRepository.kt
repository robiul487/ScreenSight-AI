package com.example.data.local.skill

import com.example.agent.AgentToolRegistry
import com.example.agent.MemoryPrivacyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Strictly on-device Skill Registry & Persistence Repository backed by Room (`agent_skills`).
 *
 * Guarantees:
 * - Every skill references ONLY real registered tools in [AgentToolRegistry].
 * - Sanitizes all names, descriptions, trigger phrases, and step parameters via [MemoryPrivacyGuard].
 * - Seeds ready-to-use safe example skills without overwriting user customizations.
 * - Handles corrupted JSON records safely without crashing.
 */
class AgentSkillRepository(
    private val dao: AgentSkillDao,
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    sealed class SaveSkillResult {
        data class Saved(val skill: AgentSkill) : SaveSkillResult()
        data class Rejected(val reasonEn: String, val reasonBn: String) : SaveSkillResult()
    }

    fun observeAllSkills(): Flow<List<AgentSkill>> {
        return dao.observeAllSkills().map { entities ->
            entities.mapNotNull { it.toDomainOrNull() }
        }
    }

    suspend fun getAllSkills(): List<AgentSkill> {
        return runCatching { dao.getAllSkills() }
            .getOrDefault(emptyList())
            .mapNotNull { it.toDomainOrNull() }
    }

    suspend fun getEnabledApprovedSkills(): List<AgentSkill> {
        return runCatching { dao.getEnabledApprovedSkills() }
            .getOrDefault(emptyList())
            .mapNotNull { it.toDomainOrNull() }
    }

    suspend fun getSkillById(skillId: String): AgentSkill? {
        return runCatching { dao.getById(skillId)?.toDomainOrNull() }.getOrNull()
    }

    /**
     * Seeds practical, ready-to-use local skills once if absent (never overwrites edited skills).
     */
    suspend fun seedDefaultSkillsIfNeeded(nowMillis: Long = System.currentTimeMillis()) {
        val existingCount = runCatching { dao.countSkills() }.getOrDefault(0)
        if (existingCount > 0) return
        val defaults = buildDefaultExampleSkills(nowMillis).map { it.toEntity() }
        runCatching { dao.insertIfAbsent(defaults) }
    }

    /**
     * Validates and persists a user-approved skill locally.
     */
    suspend fun saveSkill(
        skill: AgentSkill,
        nowMillis: Long = System.currentTimeMillis()
    ): SaveSkillResult {
        val cleanName = MemoryPrivacyGuard.sanitizeSkillText(skill.name, maxLen = 80)
        val cleanDesc = MemoryPrivacyGuard.sanitizeSkillText(skill.description, maxLen = 220)
        val cleanCriteria = MemoryPrivacyGuard.sanitizeSkillText(skill.successCriteria, maxLen = 160)

        if (cleanName.isBlank()) {
            return SaveSkillResult.Rejected(
                reasonEn = "Skill name cannot be empty.",
                reasonBn = "দক্ষতার নাম খালি রাখা যাবে না।"
            )
        }
        if (skill.executionSteps.isEmpty()) {
            return SaveSkillResult.Rejected(
                reasonEn = "A skill must have at least one valid execution step.",
                reasonBn = "একটি দক্ষতায় অন্তত একটি বৈধ ধাপ থাকতে হবে।"
            )
        }

        // Validate privacy on name, description, trigger phrases, and step parameters
        val allTextToCheck = buildString {
            append(cleanName).append(' ').append(cleanDesc).append(' ')
            skill.triggerPhrases.forEach { append(it).append(' ') }
            skill.executionSteps.forEach { step ->
                append(step.description).append(' ')
                step.parameterTemplates.forEach { (k, v) ->
                    // Placeholders like {{message_text}} are allowed templates, literal secrets are blocked
                    if (!v.trim().startsWith("{{") || !v.trim().endsWith("}}")) {
                        append(k).append('=').append(v).append(' ')
                    }
                }
            }
        }
        if (MemoryPrivacyGuard.containsSensitiveSecret(value = allTextToCheck)) {
            return SaveSkillResult.Rejected(
                reasonEn = "Blocked by Privacy Guard: Skills cannot store passwords, OTPs, PINs, API keys, or private message content.",
                reasonBn = "নিরাপত্তার কারণে পাসওয়ার্ড, ওটিপি, পিন, API Key বা ব্যক্তিগত মেসেজ কোনো দক্ষতায় সংরক্ষণ করা যাবে না।"
            )
        }

        // Verify every step uses a real registered tool in AgentToolRegistry
        val invalidTools = skill.executionSteps
            .map { it.toolName }
            .filter { toolRegistry.getTool(it) == null }
        if (invalidTools.isNotEmpty()) {
            return SaveSkillResult.Rejected(
                reasonEn = "Skill references unsupported tool(s): ${invalidTools.joinToString(", ")}.",
                reasonBn = "দক্ষতাটিতে অসমর্থিত টুল রয়েছে: ${invalidTools.joinToString(", ")}।"
            )
        }

        val allowedSet = (skill.allowedTools + skill.executionSteps.map { it.toolName })
            .map { it.trim() }
            .filter { toolRegistry.getTool(it) != null }
            .distinct()

        val cleanTriggers = skill.triggerPhrases
            .map { MemoryPrivacyGuard.sanitizeSkillText(it, maxLen = 80) }
            .filter { it.isNotBlank() }
            .distinct()

        val sanitizedSteps = skill.executionSteps.mapIndexed { idx, step ->
            step.copy(
                stepIndex = idx,
                description = MemoryPrivacyGuard.sanitizeSkillText(step.description, maxLen = 140),
                expectedOutcome = MemoryPrivacyGuard.sanitizeSkillText(step.expectedOutcome, maxLen = 120)
            )
        }

        val existing = dao.getById(skill.skillId)?.toDomainOrNull()
        val finalSkill = skill.copy(
            skillId = skill.skillId.ifBlank { "skill_${UUID.randomUUID().toString().take(10)}" },
            name = cleanName,
            description = cleanDesc,
            triggerPhrases = cleanTriggers,
            executionSteps = sanitizedSteps,
            allowedTools = allowedSet,
            successCriteria = cleanCriteria,
            version = if (existing != null) existing.version + 1 else skill.version.coerceAtLeast(1),
            createdAtMillis = existing?.createdAtMillis ?: skill.createdAtMillis,
            updatedAtMillis = nowMillis
        )
        dao.upsert(finalSkill.toEntity())
        return SaveSkillResult.Saved(finalSkill)
    }

    suspend fun saveProposalAsApprovedSkill(
        proposal: SkillProposal,
        customName: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): SaveSkillResult {
        val skill = AgentSkill(
            skillId = "skill_${UUID.randomUUID().toString().take(10)}",
            name = customName?.trim().takeIf { !it.isNullOrBlank() } ?: proposal.suggestedName,
            description = proposal.description,
            triggerPhrases = proposal.triggerPhrases,
            requiredParameters = proposal.requiredParameters,
            executionSteps = proposal.executionSteps,
            allowedTools = proposal.allowedTools,
            safetyClassification = proposal.safetyClassification,
            successCriteria = proposal.successCriteria,
            version = 1,
            usageCount = 0,
            successCount = 0,
            usageHistory = emptyList(),
            lastUsedAtMillis = null,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            enabled = true,
            userApproved = true,
            isBuiltIn = false
        )
        return saveSkill(skill, nowMillis)
    }

    suspend fun setSkillEnabled(
        skillId: String,
        enabled: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        return runCatching { dao.setEnabled(skillId, enabled, nowMillis) > 0 }.getOrDefault(false)
    }

    suspend fun deleteSkill(skillId: String): Boolean {
        return runCatching { dao.deleteById(skillId) > 0 }.getOrDefault(false)
    }

    suspend fun recordSkillExecution(
        skillId: String,
        succeeded: Boolean,
        summary: String,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        val current = getSkillById(skillId) ?: return
        val cleanSummary = MemoryPrivacyGuard.sanitizeSkillText(summary, maxLen = 140)
        val entry = SkillUsageRecord(
            timestampMillis = nowMillis,
            succeeded = succeeded,
            summary = cleanSummary
        )
        val updatedHistory = (listOf(entry) + current.usageHistory).take(MAX_USAGE_HISTORY)
        val updated = current.copy(
            usageCount = current.usageCount + 1,
            successCount = if (succeeded) current.successCount + 1 else current.successCount,
            usageHistory = updatedHistory,
            lastUsedAtMillis = nowMillis,
            updatedAtMillis = nowMillis
        )
        runCatching { dao.upsert(updated.toEntity()) }
    }

    /**
     * Matches an incoming user utterance against enabled, user-approved skills by trigger phrases.
     */
    suspend fun findMatchingEnabledSkill(userUtterance: String): AgentSkill? {
        val lower = userUtterance.trim().lowercase()
        if (lower.length < 3) return null
        val enabledSkills = getEnabledApprovedSkills()
        return enabledSkills
            .mapNotNull { skill ->
                val bestTriggerLen = skill.triggerPhrases
                    .map { it.lowercase().trim() }
                    .filter { it.isNotEmpty() && lower.contains(it) }
                    .maxOfOrNull { it.length }
                    ?: if (lower.contains(skill.name.lowercase())) skill.name.length else 0
                if (bestTriggerLen > 0) skill to bestTriggerLen else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun AgentSkill.toEntity(): AgentSkillEntity {
        return AgentSkillEntity(
            skillId = skillId,
            name = name,
            description = description,
            triggerPhrasesJson = json.encodeToString(triggerPhrases),
            requiredParametersJson = json.encodeToString(requiredParameters),
            executionStepsJson = json.encodeToString(executionSteps),
            allowedToolsJson = json.encodeToString(allowedTools),
            safetyClassification = safetyClassification.name,
            successCriteria = successCriteria,
            version = version,
            usageCount = usageCount,
            successCount = successCount,
            usageHistoryJson = json.encodeToString(usageHistory),
            lastUsedAtMillis = lastUsedAtMillis,
            createdAtMillis = createdAtMillis,
            updatedAtMillis = updatedAtMillis,
            enabled = enabled,
            userApproved = userApproved,
            isBuiltIn = isBuiltIn
        )
    }

    private fun AgentSkillEntity.toDomainOrNull(): AgentSkill? {
        return runCatching {
            val steps = json.decodeFromString<List<SkillExecutionStep>>(executionStepsJson)
            if (name.isBlank() || steps.isEmpty()) return null
            val triggers = runCatching { json.decodeFromString<List<String>>(triggerPhrasesJson) }.getOrDefault(emptyList())
            val reqParams = runCatching { json.decodeFromString<List<String>>(requiredParametersJson) }.getOrDefault(emptyList())
            val allowed = runCatching { json.decodeFromString<List<String>>(allowedToolsJson) }.getOrDefault(steps.map { it.toolName }.distinct())
            val history = runCatching { json.decodeFromString<List<SkillUsageRecord>>(usageHistoryJson) }.getOrDefault(emptyList())
            AgentSkill(
                skillId = skillId,
                name = name,
                description = description,
                triggerPhrases = triggers,
                requiredParameters = reqParams,
                executionSteps = steps,
                allowedTools = allowed,
                safetyClassification = SkillSafetyClassification.fromStored(safetyClassification),
                successCriteria = successCriteria,
                version = version.coerceAtLeast(1),
                usageCount = usageCount.coerceAtLeast(0),
                successCount = successCount.coerceAtLeast(0),
                usageHistory = history,
                lastUsedAtMillis = lastUsedAtMillis,
                createdAtMillis = createdAtMillis,
                updatedAtMillis = updatedAtMillis,
                enabled = enabled,
                userApproved = userApproved,
                isBuiltIn = isBuiltIn
            )
        }.getOrNull()
    }

    companion object {
        private const val MAX_USAGE_HISTORY = 15

        fun buildDefaultExampleSkills(nowMillis: Long = System.currentTimeMillis()): List<AgentSkill> {
            return listOf(
                AgentSkill(
                    skillId = "builtin_youtube_play_song",
                    name = "Open YouTube & Play Song",
                    description = "ইউটিউব খুলে পছন্দের গান খুঁজে চালু করে (Opens YouTube, searches for a song, and verifies screen).",
                    triggerPhrases = listOf(
                        "play song on youtube",
                        "open youtube and play",
                        "ইউটিউবে গান চালাও",
                        "ইউটিউবে গান শোনাও"
                    ),
                    requiredParameters = listOf("song_query"),
                    executionSteps = listOf(
                        SkillExecutionStep(
                            stepIndex = 0,
                            description = "Open YouTube app",
                            toolName = "open_app",
                            parameterTemplates = mapOf("app_name" to "YouTube"),
                            expectedOutcome = "YouTube"
                        ),
                        SkillExecutionStep(
                            stepIndex = 1,
                            description = "Search requested song in YouTube",
                            toolName = "search_in_app",
                            parameterTemplates = mapOf("query" to "{{song_query}}", "app_name" to "YouTube"),
                            expectedOutcome = "{{song_query}}"
                        ),
                        SkillExecutionStep(
                            stepIndex = 2,
                            description = "Verify YouTube search results loaded",
                            toolName = "verify_screen",
                            parameterTemplates = mapOf("expected_text" to "YouTube"),
                            expectedOutcome = "YouTube"
                        )
                    ),
                    allowedTools = listOf("open_app", "search_in_app", "verify_screen"),
                    safetyClassification = SkillSafetyClassification.LOW_RISK,
                    successCriteria = "YouTube opened with requested song search ready",
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis,
                    enabled = true,
                    userApproved = true,
                    isBuiltIn = true
                ),
                AgentSkill(
                    skillId = "builtin_set_morning_alarm",
                    name = "Set Morning Alarm (6:00 AM)",
                    description = "সকাল ৬টার অ্যালার্ম সেট করে (Sets a 6:00 AM alarm using Android Clock Intent).",
                    triggerPhrases = listOf(
                        "set morning alarm",
                        "সকালের অ্যালার্ম দাও",
                        "সকাল ৬টার অ্যালার্ম"
                    ),
                    requiredParameters = listOf("hour_24", "minute"),
                    executionSteps = listOf(
                        SkillExecutionStep(
                            stepIndex = 0,
                            description = "Set alarm via Android Clock",
                            toolName = "set_alarm",
                            parameterTemplates = mapOf(
                                "hour_24" to "{{hour_24}}",
                                "minute" to "{{minute}}",
                                "label" to "Morning Alarm"
                            ),
                            expectedOutcome = "Alarm set"
                        )
                    ),
                    allowedTools = listOf("set_alarm"),
                    safetyClassification = SkillSafetyClassification.LOW_RISK,
                    successCriteria = "Clock alarm created for requested time",
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis,
                    enabled = true,
                    userApproved = true,
                    isBuiltIn = true
                ),
                AgentSkill(
                    skillId = "builtin_open_app_navigate",
                    name = "Open Preferred App & Check Screen",
                    description = "পছন্দের অ্যাপ খুলে স্ক্রিন যাচাই করে (Opens a preferred app and reads grounded screen state).",
                    triggerPhrases = listOf(
                        "open preferred app",
                        "পছন্দের অ্যাপ খোলো",
                        "অ্যাপ খুলে দেখাও"
                    ),
                    requiredParameters = listOf("app_name"),
                    executionSteps = listOf(
                        SkillExecutionStep(
                            stepIndex = 0,
                            description = "Launch preferred application",
                            toolName = "open_app",
                            parameterTemplates = mapOf("app_name" to "{{app_name}}"),
                            expectedOutcome = "{{app_name}}"
                        ),
                        SkillExecutionStep(
                            stepIndex = 1,
                            description = "Inspect active app screen state",
                            toolName = "get_screen_state",
                            parameterTemplates = emptyMap(),
                            expectedOutcome = "Screen observed"
                        )
                    ),
                    allowedTools = listOf("open_app", "get_screen_state"),
                    safetyClassification = SkillSafetyClassification.LOW_RISK,
                    successCriteria = "Target app launched and observed safely",
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis,
                    enabled = true,
                    userApproved = true,
                    isBuiltIn = true
                ),
                AgentSkill(
                    skillId = "builtin_prepare_whatsapp_draft",
                    name = "Prepare WhatsApp Message (No Auto-Send)",
                    description = "মেসেজ না পাঠিয়ে শুধু খসড়া তৈরি করে (Drafts a WhatsApp message with mandatory confirmation; never auto-sends).",
                    triggerPhrases = listOf(
                        "prepare whatsapp message",
                        "draft whatsapp message",
                        "হোয়াটসঅ্যাপ মেসেজ তৈরি করো"
                    ),
                    requiredParameters = listOf("recipient_query", "message_text"),
                    executionSteps = listOf(
                        SkillExecutionStep(
                            stepIndex = 0,
                            description = "Prepare message draft (requires confirmation, never auto-sends)",
                            toolName = "prepare_message",
                            parameterTemplates = mapOf(
                                "recipient_query" to "{{recipient_query}}",
                                "message_text" to "{{message_text}}",
                                "channel" to "whatsapp"
                            ),
                            expectedOutcome = "Draft prepared in WhatsApp"
                        )
                    ),
                    allowedTools = listOf("prepare_message"),
                    safetyClassification = SkillSafetyClassification.SENSITIVE_CONFIRMATION,
                    successCriteria = "User confirmed and WhatsApp draft opened without auto-sending",
                    createdAtMillis = nowMillis,
                    updatedAtMillis = nowMillis,
                    enabled = true,
                    userApproved = true,
                    isBuiltIn = true
                )
            )
        }
    }
}
