package com.example.agent

/**
 * Phase 3 Bengali & English Natural Voice Confirmation Classifier (Section 4).
 *
 * Strictly interprets spoken or typed replies while the agent is in [AgentExecutionState.WAITING_FOR_USER]:
 * - Clear Bangla/English approvals -> [ConfirmationIntent.APPROVE]
 * - Clear Bangla/English cancellations -> [ConfirmationIntent.REJECT]
 * - Contradictory, uncertain, or hedging utterances -> [ConfirmationIntent.AMBIGUOUS] (NEVER approves)
 * - Unrelated new commands -> [ConfirmationIntent.NEW_INSTRUCTION] (cancels/suspends pending action)
 */
object VoiceConfirmationParser {

    enum class ConfirmationIntent {
        APPROVE,
        REJECT,
        AMBIGUOUS,
        NEW_INSTRUCTION
    }

    private val APPROVE_EXACT = setOf(
        // Bengali approvals
        "হ্যাঁ", "হাঁ", "হ্যা", "জি", "জী", "জি হ্যাঁ", "জী হ্যাঁ",
        "ঠিক আছে", "ঠিকআছে", "আচ্ছা ঠিক আছে",
        "করো", "করুন", "কর", "করে দাও", "করে দিন",
        "হ্যাঁ করো", "হ্যাঁ করুন", "হ্যাঁ করে দাও", "হ্যাঁ করে দিন",
        "হ্যাঁ ঠিক আছে", "চালিয়ে যাও", "চালিয়ে যাও", "সম্মতি", "অনুমতি দিলাম",
        // Romanized Bangla
        "ha", "haa", "haan", "ji", "thik ache", "thik ase", "koro", "korun",
        // English approvals
        "yes", "yeah", "yep", "yup", "confirm", "confirmed", "approve", "approved",
        "ok", "okay", "sure", "go ahead", "do it", "proceed", "yes please", "yes confirm", "yes do it"
    )

    private val REJECT_EXACT = setOf(
        // Bengali rejections
        "না", "নাহ", "নাহ্", "বাতিল", "বাতিল করো", "বাতিল করুন",
        "থামো", "থামুন", "থাম", "বন্ধ করো", "বন্ধ করুন",
        "করো না", "করুন না", "কোরো না", "দরকার নেই", "লাগবে না", "থাক", "বাদ দাও", "বাদ দিন",
        // Romanized Bangla
        "na", "nah", "batil", "thamo", "koro na", "lagbe na",
        // English rejections
        "no", "nope", "nah", "cancel", "stop", "reject", "abort", "never mind", "nevermind",
        "don't", "do not", "don't do it", "no thanks", "no cancel", "cancel it"
    )

    private val AMBIGUOUS_MARKERS = setOf(
        "হয়তো", "হয়তো", "জানি না", "বুঝছি না", "দাঁড়াও", "দাঁড়াও", "একটু দাঁড়াও", "অপেক্ষা করো",
        "পরে", "দেখি", "মনে হয়", "আচ্ছা দেখি", "কী বললে", "আবার বলো",
        "maybe", "perhaps", "not sure", "unsure", "wait", "hold on", "hmm", "hm", "uh", "um",
        "later", "idk", "i don't know", "let me think", "what", "repeat", "say again"
    )

    private val NEW_COMMAND_HINTS = listOf(
        "open ", "search ", "play ", "call ", "send ", "set alarm", "volume", "settings", "youtube",
        "whatsapp", "chrome", "facebook", "google", "read screen", "what time",
        "খোলো", "খুলুন", "খোঁজো", "চালাও", "কল দাও", "ফোন দাও", "অ্যালার্ম", "মেসেজ", "সময়", "ইউটিউব"
    )

    private val PUNCTUATION_REGEX = Regex("[\\p{Punct}।,?!\"'“”‘’]+")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun classify(rawUtterance: String): ConfirmationIntent {
        val normalized = rawUtterance
            .trim()
            .lowercase()
            .replace(PUNCTUATION_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        if (normalized.isEmpty()) return ConfirmationIntent.AMBIGUOUS

        // 1. Check direct exact matches first
        if (normalized in APPROVE_EXACT) return ConfirmationIntent.APPROVE
        if (normalized in REJECT_EXACT) return ConfirmationIntent.REJECT
        if (normalized in AMBIGUOUS_MARKERS) return ConfirmationIntent.AMBIGUOUS

        val tokens = normalized.split(' ').filter { it.isNotBlank() }

        // 2. Reject mixed / contradictory speech immediately (e.g., "হ্যাঁ না", "yes no", "yes wait")
        val hasApproveWord = tokens.any { it in setOf("হ্যাঁ", "হাঁ", "হ্যা", "করো", "করুন", "yes", "confirm", "approve", "ok", "okay") }
        val hasRejectWord = tokens.any { it in setOf("না", "নাহ", "বাতিল", "থামো", "থামুন", "no", "cancel", "stop", "don't", "abort", "reject") }
        val hasUncertainWord = AMBIGUOUS_MARKERS.any { marker -> normalized.contains(marker) }

        if (hasUncertainWord) return ConfirmationIntent.AMBIGUOUS
        if (hasApproveWord && hasRejectWord) return ConfirmationIntent.AMBIGUOUS

        // 3. Detect a completely new user instruction that should cancel/suspend the pending action
        if (NEW_COMMAND_HINTS.any { hint -> normalized.contains(hint) }) {
            return ConfirmationIntent.NEW_INSTRUCTION
        }

        // 4. Short unambiguous rejection phrase (up to 4 words, no approval conflict)
        if (hasRejectWord && tokens.size <= 4) {
            return ConfirmationIntent.REJECT
        }

        // 5. Short unambiguous approval phrase (up to 3 words, e.g., "হ্যাঁ ঠিক আছে", "yes go ahead")
        if (hasApproveWord && tokens.size <= 3 && !normalized.contains("কিন্তু") && !normalized.contains("but") && !normalized.contains("if")) {
            return ConfirmationIntent.APPROVE
        }

        // 6. Long unrelated sentence (> 4 words without confirm/cancel keywords) -> treat as new instruction
        if (tokens.size >= 5) {
            return ConfirmationIntent.NEW_INSTRUCTION
        }

        // Default safe stance: ambiguous utterances NEVER approve a sensitive action
        return ConfirmationIntent.AMBIGUOUS
    }
}
