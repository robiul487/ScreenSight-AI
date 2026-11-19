package com.example.agent

/**
 * Post-Action Observation & Outcome Evaluator (Phase 2, Section 6).
 *
 * Evaluates whether an executed tool action truly achieved its intended outcome by comparing
 * pre-action and post-action [ScreenUiSnapshot]s, checking foreground package changes,
 * verifying expected screen indicators, and classifying failures as recoverable vs terminal.
 */
object AgentOutcomeEvaluator {

    data class EvaluationOutcome(
        val verifiedSuccess: Boolean,
        val screenChanged: Boolean,
        val shouldRetry: Boolean,
        val evaluationNoteEn: String,
        val evaluationNoteBn: String,
        val recoveryHint: String = ""
    )

    private val NON_RECOVERABLE_STATUSES = setOf(
        "BLOCKED_HIGH_RISK",
        "ACCESSIBILITY_DISABLED",
        "USER_CANCELLED",
        "CANCELLED",
        "APP_NOT_INSTALLED",
        "AMBIGUOUS_APP",
        "AMBIGUOUS_CONTACT",
        "CONTACT_NOT_FOUND",
        "INVALID_ARGUMENTS",
        "INVALID_TIME",
        "INVALID_NUMBER",
        "INVALID_URL",
        "EMPTY_MESSAGE",
        "EMPTY_QUERY",
        "UNSUPPORTED_ACTION",
        "ALARM_APP_UNAVAILABLE",
        "DIALER_UNAVAILABLE",
        "MESSAGING_UNAVAILABLE"
    )

    private val RECOVERABLE_STATUSES = setOf(
        "ELEMENT_NOT_FOUND",
        "UI_WAIT_TIMEOUT",
        "SCROLL_FAILED",
        "SWIPE_FAILED",
        "TYPE_FAILED",
        "SEARCH_ELEMENT_NOT_FOUND",
        "SEARCH_INPUT_FAILED",
        "SCREEN_UNREADABLE",
        "ACTION_TIMEOUT"
    )

    /**
     * Evaluates an executed [AgentActionResult] against pre/post UI snapshots and an optional
     * [expectedOutcome] string from the active [AgentTaskPlan].
     */
    fun evaluate(
        request: AgentActionRequest,
        result: AgentActionResult,
        beforeSnapshot: ScreenUiSnapshot?,
        afterSnapshot: ScreenUiSnapshot?,
        expectedOutcome: String = ""
    ): EvaluationOutcome {
        val screenChanged = when {
            beforeSnapshot != null && afterSnapshot != null ->
                beforeSnapshot.contentHash != afterSnapshot.contentHash ||
                    beforeSnapshot.packageName != afterSnapshot.packageName
            beforeSnapshot == null && afterSnapshot != null -> true
            else -> false
        }

        // 1. Terminal safety / permission / user cancellation failures must NEVER be retried
        if (!result.success) {
            val isNonRecoverable = result.blockedBySafety ||
                result.needsAccessibilityPermission ||
                result.status in NON_RECOVERABLE_STATUSES
            val canRetry = !isNonRecoverable && (result.recoverable || result.status in RECOVERABLE_STATUSES)
            val hint = buildRecoveryHint(request, result, afterSnapshot)
            return EvaluationOutcome(
                verifiedSuccess = false,
                screenChanged = screenChanged,
                shouldRetry = canRetry,
                evaluationNoteEn = result.summaryEn,
                evaluationNoteBn = result.summaryBn,
                recoveryHint = hint
            )
        }

        // 2. Verify app foregrounding after OPEN_APP when Accessibility snapshot is available
        if (request.actionType == AgentActionType.OPEN_APP && afterSnapshot != null) {
            val expectedPkg = result.details["package_name"].orEmpty()
                .ifBlank { result.details["package"].orEmpty() }
            val expectedApp = request.parameters["app_name"].orEmpty()
            val pkgMatches = expectedPkg.isNotBlank() &&
                afterSnapshot.packageName.equals(expectedPkg, ignoreCase = true)
            val labelMatches = expectedApp.isNotBlank() &&
                afterSnapshot.packageName.contains(AppLauncher.normalizeAppAlias(expectedApp), ignoreCase = true)

            if (expectedPkg.isNotBlank() && afterSnapshot.packageName.isNotBlank() && !pkgMatches && !labelMatches) {
                return EvaluationOutcome(
                    verifiedSuccess = false,
                    screenChanged = screenChanged,
                    shouldRetry = true,
                    evaluationNoteEn = "Launch intent was sent for $expectedApp ($expectedPkg), but the active foreground app is ${afterSnapshot.packageName}.",
                    evaluationNoteBn = "$expectedApp খোলার চেষ্টা করেছি, কিন্তু অন্য অ্যাপ স্ক্রিনে আছে। আবার চেষ্টা করছি।",
                    recoveryHint = "Wait briefly or re-launch $expectedApp and verify foreground package."
                )
            }
        }

        // 3. Verify UI change after clicking an element if both snapshots exist
        if (request.actionType == AgentActionType.CLICK_ELEMENT &&
            beforeSnapshot != null &&
            afterSnapshot != null &&
            !screenChanged
        ) {
            return EvaluationOutcome(
                verifiedSuccess = true,
                screenChanged = false,
                shouldRetry = false,
                evaluationNoteEn = "${result.summaryEn} (Note: screen text did not change immediately — inspect UI or wait if loading.)",
                evaluationNoteBn = result.summaryBn,
                recoveryHint = "If the expected page did not open, call get_screen_state or try scrolling/tapping an alternative item."
            )
        }

        // 4. Optional expected outcome verification against visible screen text
        val cleanExpected = expectedOutcome.trim().lowercase()
        if (cleanExpected.isNotEmpty() && afterSnapshot != null) {
            val haystack = (afterSnapshot.visibleTexts + afterSnapshot.clickableItems + afterSnapshot.packageName)
                .joinToString(" ")
                .lowercase()
            if (haystack.contains(cleanExpected)) {
                return EvaluationOutcome(
                    verifiedSuccess = true,
                    screenChanged = screenChanged,
                    shouldRetry = false,
                    evaluationNoteEn = "${result.summaryEn} Verified expected screen indicator: \"$expectedOutcome\".",
                    evaluationNoteBn = result.summaryBn
                )
            }
        }

        return EvaluationOutcome(
            verifiedSuccess = true,
            screenChanged = screenChanged,
            shouldRetry = false,
            evaluationNoteEn = result.summaryEn,
            evaluationNoteBn = result.summaryBn
        )
    }

    private fun buildRecoveryHint(
        request: AgentActionRequest,
        result: AgentActionResult,
        afterSnapshot: ScreenUiSnapshot?
    ): String {
        return when (result.status) {
            "ELEMENT_NOT_FOUND" -> {
                val target = request.parameters["target_text"].orEmpty()
                if (afterSnapshot?.hasScrollableContainer == true) {
                    "Element \"$target\" was not visible. Try scroll_screen(direction='down') or inspect get_screen_state for exact button labels."
                } else {
                    "Element \"$target\" was not found. Call get_screen_state to inspect actual visible labels or tap by coordinates."
                }
            }
            "SEARCH_ELEMENT_NOT_FOUND", "SEARCH_INPUT_FAILED" ->
                "Could not find a search box on this screen. Inspect get_screen_state to find the Search icon first, or use search_in_app with app_name='YouTube'."
            "TYPE_FAILED" ->
                "No focused text field accepted input. Tap the text field first using click_element, then call type_text."
            "UI_WAIT_TIMEOUT" ->
                "Screen took longer than expected to load. Re-check get_screen_state or inform the user."
            else -> ""
        }
    }
}
