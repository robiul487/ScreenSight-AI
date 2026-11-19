package com.example.agent

/**
 * Local Action Confirmation & Safety Engine (Section 15).
 *
 * Separates every action into:
 * - Group A (LOW_RISK): Runs immediately after user voice instruction.
 * - Group B (SENSITIVE): Requires explicit user confirmation before executing.
 * - Group C (HIGH_RISK_BLOCKED): Unconditionally blocked from autonomous execution
 *   (financial transfers/payments, purchases, account/file deletion, passwords/OTPs, security changes).
 *
 * Also sanitizes sensitive screen text and guards against prompt injection from screen content.
 */
object AgentSafetyManager {

    data class SafetyEvaluation(
        val riskLevel: ActionRiskLevel,
        val requiresConfirmation: Boolean,
        val isBlocked: Boolean,
        val reasonEn: String,
        val reasonBn: String
    )

    private val HIGH_RISK_KEYWORDS = listOf(
        // Financial / Payments / Bangladesh MFS & Banking
        "pay now", "confirm payment", "send money", "cash out", "transfer money",
        "bank transfer", "bkash", "nagad", "rocket", "upay", "checkout", "buy now",
        "place order", "purchase", "credit card", "debit card", "cvv", "pin number",
        "টাকা পাঠা", "পেমেন্ট", "ক্যাশ আউট", "বিকাশ", "নগদ",
        // Account / File deletion & Security changes
        "delete account", "remove account", "factory reset", "erase all data",
        "delete permanently", "format", "uninstall", "disable screen lock",
        "change password", "reset password", "recovery code", "seed phrase",
        "two-factor", "2fa", "allow all permissions", "device admin",
        "অ্যাকাউন্ট মুছ", "ডিলিট কর", "পাসওয়ার্ড", "পাসওয়ার্ড", "ওটিপি", "otp"
    )

    private val SENSITIVE_MESSAGE_SEND_KEYWORDS = listOf(
        "send", "পাঠান", "পাঠাও", "submit order", "post", "publish", "share now"
    )

    private val PRIVATE_MESSAGING_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.facebook.orca",
        "org.telegram.messenger",
        "com.viber.voip"
    )

    private val OTP_OR_PIN_REGEX = Regex("\\b\\d{4,8}\\b")
    private val CARD_NUMBER_REGEX = Regex("\\b(?:\\d[ -]*?){13,19}\\b")

    /**
     * Evaluates an [AgentActionRequest] against mandatory Group A / B / C safety rules.
     */
    fun evaluate(
        request: AgentActionRequest,
        activePackageName: String = ""
    ): SafetyEvaluation {
        val combinedParams = buildString {
            append(request.reason).append(' ')
            request.parameters.values.forEach { append(it).append(' ') }
        }.lowercase()

        // Group C: Unconditionally block financial, irreversible deletion, security, or credential actions
        if (containsHighRiskIntent(request, combinedParams)) {
            return SafetyEvaluation(
                riskLevel = ActionRiskLevel.HIGH_RISK_BLOCKED,
                requiresConfirmation = false,
                isBlocked = true,
                reasonEn = "Blocked by Safety Engine: ScreenSight AI cannot autonomously perform payments, transfers, purchases, deletions, security changes, or enter passwords/OTPs. Please complete this step manually.",
                reasonBn = "নিরাপত্তার কারণে এই কাজটি স্বয়ংক্রিয়ভাবে করা যাবে না (পেমেন্ট, টাকা পাঠানো, ফাইল বা অ্যাকাউন্ট মোছা, পাসওয়ার্ড বা ওটিপি)। অনুগ্রহ করে এটি নিজে সম্পন্ন করুন।"
            )
        }

        // Group B: Sensitive actions requiring explicit user confirmation
        when (request.actionType) {
            AgentActionType.CALL_TRUSTED_CONTACT,
            AgentActionType.OPEN_DIALER -> {
                return SafetyEvaluation(
                    riskLevel = ActionRiskLevel.SENSITIVE,
                    requiresConfirmation = true,
                    isBlocked = false,
                    reasonEn = "Confirm before opening the phone dialer for this contact.",
                    reasonBn = "কল করার আগে আপনার অনুমতি প্রয়োজন।"
                )
            }

            AgentActionType.PREPARE_MESSAGE -> {
                return SafetyEvaluation(
                    riskLevel = ActionRiskLevel.SENSITIVE,
                    requiresConfirmation = true,
                    isBlocked = false,
                    reasonEn = "Confirm the recipient and message text before opening the messaging app.",
                    reasonBn = "মেসেজটি তৈরি ও খোলার আগে আপনার অনুমতি প্রয়োজন।"
                )
            }

            AgentActionType.ASK_CONFIRMATION -> {
                return SafetyEvaluation(
                    riskLevel = ActionRiskLevel.SENSITIVE,
                    requiresConfirmation = true,
                    isBlocked = false,
                    reasonEn = request.reason.ifBlank { "User confirmation requested." },
                    reasonBn = "এই কাজটি করার আগে আপনার অনুমতি প্রয়োজন।"
                )
            }

            AgentActionType.CLICK_ELEMENT -> {
                val target = (request.parameters["target_text"] ?: request.parameters["text"] ?: "").lowercase()
                val isMessagingApp = PRIVATE_MESSAGING_PACKAGES.any { activePackageName.equals(it, ignoreCase = true) }
                if (isMessagingApp && SENSITIVE_MESSAGE_SEND_KEYWORDS.any { target == it || target.contains(it) }) {
                    return SafetyEvaluation(
                        riskLevel = ActionRiskLevel.SENSITIVE,
                        requiresConfirmation = true,
                        isBlocked = false,
                        reasonEn = "Confirm before tapping Send in a messaging app.",
                        reasonBn = "এই মেসেজটি পাঠাব? পাঠানোর আগে আপনার অনুমতি প্রয়োজন।"
                    )
                }
            }

            AgentActionType.READ_SCREEN -> {
                val isMessagingApp = PRIVATE_MESSAGING_PACKAGES.any { activePackageName.equals(it, ignoreCase = true) }
                val explicitUserAuth = request.parameters["user_authorized_private_read"]?.equals("true", ignoreCase = true) == true
                if (isMessagingApp && !explicitUserAuth && request.requiresConfirmation) {
                    return SafetyEvaluation(
                        riskLevel = ActionRiskLevel.SENSITIVE,
                        requiresConfirmation = true,
                        isBlocked = false,
                        reasonEn = "Private messaging app detected. Confirm before reading messages aloud.",
                        reasonBn = "এখানে ব্যক্তিগত মেসেজ রয়েছে। মেসেজগুলো পড়ে শোনাব?"
                    )
                }
            }

            else -> Unit
        }

        // Honor explicit confirmation flag from AI planner if set
        if (request.requiresConfirmation) {
            return SafetyEvaluation(
                riskLevel = ActionRiskLevel.SENSITIVE,
                requiresConfirmation = true,
                isBlocked = false,
                reasonEn = request.reason.ifBlank { "Please confirm this action before continuing." },
                reasonBn = "এই কাজটি করার আগে আপনার অনুমতি প্রয়োজন।"
            )
        }

        // Group A: Low-risk actions
        return SafetyEvaluation(
            riskLevel = ActionRiskLevel.LOW_RISK,
            requiresConfirmation = false,
            isBlocked = false,
            reasonEn = "Low-risk action approved.",
            reasonBn = "নিরাপদ কাজ অনুমোদিত।"
        )
    }

    private fun containsHighRiskIntent(request: AgentActionRequest, combinedLower: String): Boolean {
        // Block entering passwords/OTPs or clicking payment/delete buttons
        if (request.actionType == AgentActionType.TYPE_TEXT) {
            val fieldHint = (request.parameters["field_hint"] ?: "").lowercase()
            if (fieldHint.contains("password") ||
                fieldHint.contains("pin") ||
                fieldHint.contains("otp") ||
                fieldHint.contains("passcode") ||
                fieldHint.contains("cvv") ||
                fieldHint.contains("পাসওয়ার্ড") ||
                fieldHint.contains("পিন")
            ) {
                return true
            }
        }
        if (request.actionType == AgentActionType.OPEN_SETTINGS) {
            val section = (request.parameters["section"] ?: "").lowercase()
            if (section.contains("security") || section.contains("lock") || section.contains("reset") || section.contains("biometric")) {
                return true
            }
        }
        if (request.actionType == AgentActionType.CLICK_ELEMENT ||
            request.actionType == AgentActionType.LONG_PRESS ||
            request.actionType == AgentActionType.TYPE_TEXT ||
            request.actionType == AgentActionType.OPEN_URL
        ) {
            return HIGH_RISK_KEYWORDS.any { keyword -> combinedLower.contains(keyword) }
        }
        return false
    }

    /**
     * Checks whether a UI node text or view hint represents sensitive credentials/OTPs
     * that must never be read aloud or transmitted in accessibility snapshots.
     */
    fun isSensitiveTextOrField(text: String?, viewIdOrHint: String? = null, isPasswordNode: Boolean = false): Boolean {
        if (isPasswordNode) return true
        val hintLower = viewIdOrHint?.lowercase().orEmpty()
        if (hintLower.contains("password") ||
            hintLower.contains("passwd") ||
            hintLower.contains("pin_entry") ||
            hintLower.contains("otp") ||
            hintLower.contains("passcode") ||
            hintLower.contains("cvv") ||
            hintLower.contains("secret") ||
            hintLower.contains("recovery")
        ) {
            return true
        }
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return false
        val valueLower = value.lowercase()
        if ((valueLower.contains("otp") || valueLower.contains("verification code") ||
                valueLower.contains("security code") || valueLower.contains("ভেরিফিকেশন কোড")) &&
            OTP_OR_PIN_REGEX.containsMatchIn(value)
        ) {
            return true
        }
        if (CARD_NUMBER_REGEX.containsMatchIn(value)) {
            return true
        }
        return false
    }

    /**
     * Redacts sensitive patterns if they ever appear inside visible text strings.
     */
    fun redactSensitiveContent(raw: String): String {
        var sanitized = raw
        if (CARD_NUMBER_REGEX.containsMatchIn(sanitized)) {
            sanitized = CARD_NUMBER_REGEX.replace(sanitized, "[REDACTED_CARD]")
        }
        val lower = sanitized.lowercase()
        if (lower.contains("otp") || lower.contains("verification code") || lower.contains("one-time") || lower.contains("পিন")) {
            sanitized = OTP_OR_PIN_REGEX.replace(sanitized, "[REDACTED_CODE]")
        }
        return sanitized
    }
}
