package com.example.agent

import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.memory.AgentMemoryRepository
import com.example.data.local.memory.MemoryCategory
import kotlin.math.exp

/**
 * Strictly on-device Intelligent Memory Retriever & Explicit Remember Command Parser.
 *
 * Uses structured local SQLite retrieval + deterministic Bangla/English keyword, category,
 * confidence, recency, and contradiction scoring (no cloud database or external embeddings required).
 *
 * Guarantees:
 * - Returns ONLY relevant memories for the current task (never dumps unrelated personal data to the AI API).
 * - Excludes expired memories automatically.
 * - Detects direct contradictions with the user's current instruction so current user commands ALWAYS override stored preferences.
 * - Returns empty immediately when memory is disabled.
 */
class IntelligentMemoryRetriever(
    private val memoryRepository: AgentMemoryRepository,
    private val isMemoryEnabledProvider: () -> Boolean = { true }
) {

    data class RetrievedMemoryMatch(
        val record: AgentMemoryRecord,
        val relevanceScore: Float
    )

    data class RetrievalResult(
        val selectedMemories: List<AgentMemoryRecord>,
        val promptContext: String,
        val overriddenMemories: List<AgentMemoryRecord> = emptyList()
    )

    data class ExplicitMemoryCandidate(
        val category: MemoryCategory,
        val memoryKey: String,
        val value: String
    )

    /**
     * Retrieves the most relevant non-expired local memories for [userQuery] and optional [activePackageName].
     */
    suspend fun retrieveForQuery(
        userQuery: String,
        activePackageName: String = "",
        maxResults: Int = DEFAULT_MAX_RESULTS,
        nowMillis: Long = System.currentTimeMillis()
    ): RetrievalResult {
        if (!isMemoryEnabledProvider()) {
            return RetrievalResult(emptyList(), "", emptyList())
        }

        val candidates = memoryRepository.getActiveMemoriesForRetrieval(nowMillis)
        if (candidates.isEmpty()) {
            return RetrievalResult(emptyList(), "", emptyList())
        }

        val queryLower = userQuery.trim().lowercase()
        val queryTokens = tokenize(queryLower)
        val packageTokens = tokenize(activePackageName.lowercase().replace('.', ' '))

        val scored = mutableListOf<RetrievedMemoryMatch>()
        val overridden = mutableListOf<AgentMemoryRecord>()

        for (record in candidates) {
            if (record.isExpired(nowMillis)) continue

            if (isOverriddenByCurrentUserInstruction(queryLower, queryTokens, record)) {
                overridden.add(record)
                continue
            }

            val score = computeRelevanceScore(
                queryLower = queryLower,
                queryTokens = queryTokens,
                packageTokens = packageTokens,
                record = record,
                nowMillis = nowMillis
            )

            if (score >= MIN_RELEVANCE_THRESHOLD) {
                scored.add(RetrievedMemoryMatch(record, score))
            }
        }

        val topMatches = scored
            .sortedByDescending { it.relevanceScore }
            .take(maxResults.coerceAtLeast(1))
            .map { it.record }

        if (topMatches.isNotEmpty()) {
            memoryRepository.markMemoriesUsed(topMatches.map { it.id }, nowMillis)
        }

        return RetrievalResult(
            selectedMemories = topMatches,
            promptContext = MemoryPrivacyGuard.formatMemoriesForPrompt(topMatches),
            overriddenMemories = overridden
        )
    }

    /**
     * Retrieves lightweight global user preferences/instructions for session startup
     * (strictly bounded to explicit user-approved preferences/instructions, never dumping task history).
     */
    suspend fun retrieveSessionBaselineContext(
        maxResults: Int = 4,
        nowMillis: Long = System.currentTimeMillis()
    ): RetrievalResult {
        if (!isMemoryEnabledProvider()) {
            return RetrievalResult(emptyList(), "", emptyList())
        }
        val candidates = memoryRepository.getActiveMemoriesForRetrieval(nowMillis)
            .filter {
                it.userApproved && (
                    it.category == MemoryCategory.EXPLICIT_INSTRUCTION ||
                        it.category == MemoryCategory.USER_PREFERENCE ||
                        it.category == MemoryCategory.APP_PREFERENCE ||
                        it.category == MemoryCategory.CONTACT_ALIAS
                    )
            }
            .sortedWith(
                compareByDescending<AgentMemoryRecord> { it.category == MemoryCategory.EXPLICIT_INSTRUCTION }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.lastUsedAtMillis }
            )
            .take(maxResults)

        return RetrievalResult(
            selectedMemories = candidates,
            promptContext = MemoryPrivacyGuard.formatMemoriesForPrompt(candidates)
        )
    }

    private fun computeRelevanceScore(
        queryLower: String,
        queryTokens: Set<String>,
        packageTokens: Set<String>,
        record: AgentMemoryRecord,
        nowMillis: Long
    ): Float {
        val keyTokens = tokenize(record.memoryKey)
        val valueTokens = tokenize(record.value)
        val memoryTokens = keyTokens + valueTokens

        if (queryTokens.isEmpty() && packageTokens.isEmpty()) {
            return if (record.category == MemoryCategory.EXPLICIT_INSTRUCTION && record.userApproved) {
                0.55f * record.confidence
            } else {
                0f
            }
        }

        // 1. Direct keyword & phrase overlap
        val overlappingTokens = queryTokens.intersect(memoryTokens)
        val overlapScore = if (queryTokens.isEmpty() || memoryTokens.isEmpty()) {
            0f
        } else {
            val queryCoverage = overlappingTokens.size.toFloat() / queryTokens.size.toFloat()
            val memoryCoverage = overlappingTokens.size.toFloat() / memoryTokens.size.toFloat()
            (queryCoverage * 0.65f + memoryCoverage * 0.35f) * 0.72f
        }

        // Exact phrase substring boost (handles Bangla/English multi-word names & apps)
        val keyLower = record.memoryKey.lowercase()
        val valLower = record.value.lowercase()
        val phraseBoost = when {
            keyLower.length >= 3 && queryLower.contains(keyLower) -> 0.32f
            valLower.length >= 3 && queryLower.contains(valLower) -> 0.28f
            else -> 0f
        }

        // 2. Category intent alignment
        val categoryBoost = computeCategoryIntentBoost(queryLower, record.category)

        // 3. Active app package match
        val packageBoost = if (packageTokens.isNotEmpty() && packageTokens.intersect(memoryTokens).isNotEmpty()) {
            0.22f
        } else {
            0f
        }

        // Require at least one genuine topical signal (overlap, phrase, category match, or package match)
        // except for globally applicable user-approved communication instructions (e.g. "speak Bangla")
        val isGlobalStyleInstruction = record.category == MemoryCategory.EXPLICIT_INSTRUCTION &&
            record.userApproved &&
            (valLower.contains("bangla") || valLower.contains("bengali") || valLower.contains("বাংলা") ||
                valLower.contains("simple") || valLower.contains("সহজ") || valLower.contains("slow") ||
                valLower.contains("language") || valLower.contains("ভাষা"))

        val topicalSignal = overlapScore + phraseBoost + categoryBoost + packageBoost
        if (topicalSignal < 0.14f && !isGlobalStyleInstruction) {
            return 0f
        }

        // 4. Confidence, user approval & recency decay (30-day half-life)
        val ageDays = ((nowMillis - record.lastUsedAtMillis).coerceAtLeast(0L)).toDouble() / DAY_MILLIS.toDouble()
        val recencyFactor = exp(-ageDays / 45.0).toFloat().coerceIn(0.25f, 1.0f)
        val approvalBonus = if (record.userApproved) 0.12f else 0.0f
        val baseGlobal = if (isGlobalStyleInstruction) 0.34f else 0f

        return (topicalSignal + baseGlobal + approvalBonus) * (0.55f + 0.45f * record.confidence) * (0.75f + 0.25f * recencyFactor)
    }

    private fun computeCategoryIntentBoost(queryLower: String, category: MemoryCategory): Float {
        return when (category) {
            MemoryCategory.CONTACT_ALIAS ->
                if (CALL_OR_CONTACT_HINTS.any { queryLower.contains(it) }) 0.25f else 0f
            MemoryCategory.APP_PREFERENCE ->
                if (APP_OR_MEDIA_HINTS.any { queryLower.contains(it) }) 0.24f else 0f
            MemoryCategory.ROUTINE ->
                if (ROUTINE_HINTS.any { queryLower.contains(it) }) 0.24f else 0f
            MemoryCategory.LEARNED_WORKFLOW ->
                if (WORKFLOW_HINTS.any { queryLower.contains(it) }) 0.18f else 0f
            MemoryCategory.TASK_HISTORY ->
                if (queryLower.contains("again") || queryLower.contains("আবার") || queryLower.contains("last")) 0.20f else 0f
            MemoryCategory.EXPLICIT_INSTRUCTION -> 0.08f
            MemoryCategory.USER_PREFERENCE -> 0.06f
            MemoryCategory.USER_PROFILE -> 0.04f
        }
    }

    /**
     * Detects when the user's immediate request explicitly overrides a stored preference
     * (e.g., stored music app = YouTube, but user asks to open Spotify right now;
     * stored language = Bangla, but user explicitly asks to speak in English).
     */
    private fun isOverriddenByCurrentUserInstruction(
        queryLower: String,
        queryTokens: Set<String>,
        record: AgentMemoryRecord
    ): Boolean {
        val valueLower = record.value.lowercase()
        val keyLower = record.memoryKey.lowercase()

        // App preference override: user names a different specific app right now
        if (record.category == MemoryCategory.APP_PREFERENCE || keyLower.contains("app")) {
            val storedApp = KNOWN_APP_NAMES.firstOrNull { valueLower.contains(it) }
            val requestedApps = KNOWN_APP_NAMES.filter { queryLower.contains(it) }
            if (storedApp != null && requestedApps.isNotEmpty() && storedApp !in requestedApps) {
                return true
            }
        }

        // Language preference override: user explicitly requests a different language right now
        val isLangMemory = keyLower.contains("language") || keyLower.contains("ভাষা") ||
            valueLower.contains("bangla") || valueLower.contains("bengali") || valueLower.contains("বাংলা") ||
            valueLower.contains("english") || valueLower.contains("ইংরেজি")
        if (isLangMemory) {
            val storedBangla = valueLower.contains("bangla") || valueLower.contains("bengali") || valueLower.contains("বাংলা")
            val storedEnglish = valueLower.contains("english") || valueLower.contains("ইংরেজি")
            val asksEnglish = queryLower.contains("in english") || queryLower.contains("speak english") ||
                queryLower.contains("reply in english") || queryLower.contains("ইংরেজিতে")
            val asksBangla = queryLower.contains("in bangla") || queryLower.contains("in bengali") ||
                queryLower.contains("বাংলায়") || queryLower.contains("বাংলায়")
            if (storedBangla && asksEnglish) return true
            if (storedEnglish && asksBangla) return true
        }

        // Explicit negation / override phrasing targeting the stored value
        if (queryLower.contains("instead of $valueLower") ||
            queryLower.contains("not $valueLower") ||
            queryLower.contains("don't use $valueLower") ||
            queryLower.contains("ignore my preference") ||
            queryLower.contains("$valueLower না")
        ) {
            return true
        }

        // Alarm / routine time override when user specifies a different explicit clock time
        if (record.category == MemoryCategory.ROUTINE &&
            (keyLower.contains("alarm") || keyLower.contains("অ্যালার্ম") || valueLower.contains("am") || valueLower.contains("pm"))
        ) {
            val queryTime = TIME_REGEX.find(queryLower)?.value
            val memoryTime = TIME_REGEX.find(valueLower)?.value
            if (queryTime != null && memoryTime != null && queryTime != memoryTime) {
                return true
            }
        }

        return false
    }

    companion object {
        private const val DEFAULT_MAX_RESULTS = 5
        private const val MIN_RELEVANCE_THRESHOLD = 0.20f
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        private val STOP_WORDS = setOf(
            "the", "a", "an", "is", "are", "was", "were", "to", "for", "of", "in", "on", "at",
            "my", "me", "i", "you", "your", "please", "can", "could", "would", "do", "does", "it",
            "this", "that", "and", "or", "with", "from", "by", "about",
            "আমার", "আমি", "তুমি", "একটু", "দয়া", "করে", "দাও", "করো", "করুন", "কি", "কী", "এটা", "ওটা"
        )

        private val CALL_OR_CONTACT_HINTS = listOf(
            "call", "dial", "phone", "message", "whatsapp", "sms", "contact",
            "ফোন", "কল", "মেসেজ", "কথা", "ছেলে", "মেয়ে", "মা", "বাবা", "ভাই", "বোন"
        )

        private val APP_OR_MEDIA_HINTS = listOf(
            "open", "launch", "play", "music", "song", "video", "watch", "listen", "browser", "search",
            "খোলো", "চালু", "গান", "ভিডিও", "শুনতে", "দেখতে", "অ্যাপ", "খুঁজে"
        )

        private val ROUTINE_HINTS = listOf(
            "alarm", "morning", "night", "daily", "every day", "routine", "wake", "medicine", "prayer",
            "অ্যালার্ম", "সকাল", "রাত", "প্রতিদিন", "ওষুধ", "ঘুম", "নামাজ"
        )

        private val WORKFLOW_HINTS = listOf(
            "usual", "same as before", "routine", "workflow", "skill", "আগের মতো", "সবসময়ের মতো"
        )

        private val KNOWN_APP_NAMES = listOf(
            "youtube", "spotify", "whatsapp", "chrome", "facebook", "messenger",
            "telegram", "gmail", "maps", "clock", "settings", "camera", "gallery",
            "ইউটিউব", "হোয়াটসঅ্যাপ", "ফেসবুক", "ক্রোম"
        )

        private val TIME_REGEX = Regex("\\b\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\b")

        private val EXPLICIT_REMEMBER_PREFIXES = listOf(
            "remember that ",
            "remember to ",
            "remember: ",
            "remember ",
            "please remember that ",
            "please remember ",
            "note that ",
            "save to memory:",
            "save in memory:",
            "মনে রেখো যে ",
            "মনে রেখো ",
            "মনে রাখো যে ",
            "মনে রাখো ",
            "মনে রাখবেন "
        )

        /**
         * Parses an explicit user instruction to remember something in English or Bangla.
         * Returns null for normal conversation or commands so regular chats are NOT auto-saved as memory.
         */
        fun parseExplicitRememberInstruction(userText: String): ExplicitMemoryCandidate? {
            val trimmed = userText.trim()
            if (trimmed.length < 8) return null
            val lower = trimmed.lowercase()

            val matchedPrefix = EXPLICIT_REMEMBER_PREFIXES.firstOrNull { lower.startsWith(it) }
                ?: return null

            val payload = trimmed.substring(matchedPrefix.length).trim().trimEnd('.', '।', '!')
            if (payload.length < 3) return null

            val payloadLower = payload.lowercase()

            // Try to extract structured "X is Y" / "X = Y" / "X হলো Y"
            val separatorMatch = Regex("(?i)^(.{2,55}?)\\s+(?:is|are|=|:|হলো|হচ্ছে|মানে)\\s+(.{1,180})$")
                .find(payload)

            val rawKey: String
            val rawValue: String
            if (separatorMatch != null) {
                rawKey = separatorMatch.groupValues[1].trim()
                rawValue = separatorMatch.groupValues[2].trim()
            } else {
                rawKey = deriveDefaultKey(payloadLower)
                rawValue = payload
            }

            val category = inferCategory(rawKey.lowercase(), rawValue.lowercase(), payloadLower)
            return ExplicitMemoryCandidate(
                category = category,
                memoryKey = rawKey,
                value = rawValue
            )
        }

        private fun deriveDefaultKey(payloadLower: String): String {
            return when {
                CALL_OR_CONTACT_HINTS.any { payloadLower.contains(it) } -> "contact_alias_note"
                APP_OR_MEDIA_HINTS.any { payloadLower.contains(it) } -> "preferred_app_setting"
                ROUTINE_HINTS.any { payloadLower.contains(it) } -> "daily_routine"
                payloadLower.contains("bangla") || payloadLower.contains("english") || payloadLower.contains("বাংলা") -> "preferred_language"
                else -> "user_instruction"
            }
        }

        private fun inferCategory(keyLower: String, valueLower: String, fullLower: String): MemoryCategory {
            return when {
                fullLower.contains("always ") || fullLower.contains("never ") || fullLower.contains("সবসময়") ->
                    MemoryCategory.EXPLICIT_INSTRUCTION
                CALL_OR_CONTACT_HINTS.any { keyLower.contains(it) } ->
                    MemoryCategory.CONTACT_ALIAS
                KNOWN_APP_NAMES.any { valueLower.contains(it) } || keyLower.contains("app") || keyLower.contains("অ্যাপ") ->
                    MemoryCategory.APP_PREFERENCE
                ROUTINE_HINTS.any { fullLower.contains(it) } ->
                    MemoryCategory.ROUTINE
                keyLower.contains("name") || keyLower.contains("নাম") || keyLower.contains("age") || keyLower.contains("city") ->
                    MemoryCategory.USER_PROFILE
                keyLower.contains("favorite") || keyLower.contains("prefer") || keyLower.contains("পছন্দ") ->
                    MemoryCategory.USER_PREFERENCE
                else -> MemoryCategory.EXPLICIT_INSTRUCTION
            }
        }

        fun tokenize(raw: String): Set<String> {
            return raw.lowercase()
                .split(Regex("[^a-z0-9\\u0980-\\u09FF]+"))
                .map { it.trim() }
                .filter { it.length >= 2 && it !in STOP_WORDS }
                .toSet()
        }
    }
}
