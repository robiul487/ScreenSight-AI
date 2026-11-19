package com.example.agent

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Android Action Executor (Section 3C, 4, 5, 10, 11, 12).
 *
 * Receives validated [AgentActionRequest]s, enforces [AgentSafetyManager] checks,
 * executes supported Android Intents or [AgentAccessibilityService] operations,
 * verifies outcomes via fresh UI snapshots where available, and never falsely
 * reports success.
 */
class AgentActionExecutor(
    private val context: Context,
    private val appLauncher: AppLauncher,
    private val trustedContactsRepository: TrustedContactsRepository
) {
    /**
     * Resolves a sensitive call/message request before asking user confirmation so the
     * confirmation prompt displays the exact resolved person and phone number.
     */
    suspend fun buildConfirmationPreview(request: AgentActionRequest): Pair<PendingConfirmation?, AgentActionResult?> {
        return when (request.actionType) {
            AgentActionType.CALL_TRUSTED_CONTACT -> {
                val query = request.parameters["contact_query"]?.trim().orEmpty()
                when (val lookup = trustedContactsRepository.resolveContact(query)) {
                    is TrustedContactsRepository.ContactLookupResult.SingleMatch -> {
                        val c = lookup.contact
                        val enrichedReq = request.copy(
                            parameters = request.parameters + mapOf(
                                "resolved_name" to c.name,
                                "resolved_phone" to c.phoneNumber
                            )
                        )
                        PendingConfirmation(
                            callId = request.callId,
                            actionRequest = enrichedReq,
                            promptEn = "Open phone dialer for ${c.name} (${c.phoneNumber})?",
                            promptBn = "${c.name} (${c.phoneNumber})-কে ফোন করার জন্য ডায়ালার খুলব?",
                            previewDetail = "Call: ${c.name} • ${c.phoneNumber}"
                        ) to null
                    }
                    is TrustedContactsRepository.ContactLookupResult.Ambiguous -> {
                        val names = lookup.candidates.joinToString(", ") {
                            if (it.nickname.isNotBlank()) "${it.name} (${it.nickname})" else it.name
                        }
                        null to AgentActionResult(
                            success = false,
                            status = "AMBIGUOUS_CONTACT",
                            summaryEn = "Multiple matching contacts found: $names. Ask the user which one to call.",
                            summaryBn = "একাধিক কন্টাক্ট পাওয়া গেছে: $names। আপনি কাকে ফোন করতে চান?"
                        )
                    }
                    is TrustedContactsRepository.ContactLookupResult.NotFound -> {
                        null to AgentActionResult(
                            success = false,
                            status = "CONTACT_NOT_FOUND",
                            summaryEn = "No trusted or phone contact matched \"$query\". Tell the user to add them in Trusted Family Contacts or say the phone number.",
                            summaryBn = "\"$query\" নামে কোনো কন্টাক্ট পাওয়া যায়নি। Trusted Family Contacts-এ নম্বরটি যুক্ত করুন অথবা নম্বরটি বলুন।"
                        )
                    }
                }
            }

            AgentActionType.OPEN_DIALER -> {
                val phone = request.parameters["phone_number"]?.trim().orEmpty()
                val name = request.parameters["display_name"]?.trim().orEmpty()
                if (phone.isEmpty()) {
                    return null to AgentActionResult(
                        success = false,
                        status = "INVALID_NUMBER",
                        summaryEn = "No phone number was provided.",
                        summaryBn = "কোনো ফোন নম্বর পাওয়া যায়নি।"
                    )
                }
                val label = name.ifBlank { phone }
                PendingConfirmation(
                    callId = request.callId,
                    actionRequest = request,
                    promptEn = "Open phone dialer for $label?",
                    promptBn = "$label নম্বরে ফোন করার জন্য ডায়ালার খুলব?",
                    previewDetail = "Dialer: $label"
                ) to null
            }

            AgentActionType.PREPARE_MESSAGE -> {
                val recipientQuery = request.parameters["recipient_query"]?.trim().orEmpty()
                val msgText = request.parameters["message_text"]?.trim().orEmpty()
                val channel = request.parameters["channel"]?.trim().orEmpty().ifBlank { "whatsapp" }
                if (msgText.isEmpty()) {
                    return null to AgentActionResult(
                        success = false,
                        status = "EMPTY_MESSAGE",
                        summaryEn = "Message text is empty. Ask the user what message to send.",
                        summaryBn = "মেসেজে কী লিখতে হবে তা বলা হয়নি।"
                    )
                }
                // Allow either a direct phone number or a trusted contact lookup
                val digitCount = recipientQuery.count { it.isDigit() }
                if (digitCount >= 6) {
                    val enriched = request.copy(
                        parameters = request.parameters + mapOf(
                            "resolved_name" to recipientQuery,
                            "resolved_phone" to recipientQuery
                        )
                    )
                    PendingConfirmation(
                        callId = request.callId,
                        actionRequest = enriched,
                        promptEn = "Prepare $channel message to $recipientQuery: \"$msgText\"?",
                        promptBn = "$recipientQuery-কে মেসেজ পাঠাব: \"$msgText\"?",
                        previewDetail = "${channel.uppercase()} to $recipientQuery: $msgText"
                    ) to null
                } else {
                    when (val lookup = trustedContactsRepository.resolveContact(recipientQuery)) {
                        is TrustedContactsRepository.ContactLookupResult.SingleMatch -> {
                            val c = lookup.contact
                            val enriched = request.copy(
                                parameters = request.parameters + mapOf(
                                    "resolved_name" to c.name,
                                    "resolved_phone" to c.phoneNumber
                                )
                            )
                            PendingConfirmation(
                                callId = request.callId,
                                actionRequest = enriched,
                                promptEn = "Prepare $channel message to ${c.name}: \"$msgText\"?",
                                promptBn = "${c.name}-কে এই মেসেজটি তৈরি করে খুলব: \"$msgText\"?",
                                previewDetail = "${channel.uppercase()} to ${c.name}: $msgText"
                            ) to null
                        }
                        is TrustedContactsRepository.ContactLookupResult.Ambiguous -> {
                            val names = lookup.candidates.joinToString(", ") { it.name }
                            null to AgentActionResult(
                                success = false,
                                status = "AMBIGUOUS_CONTACT",
                                summaryEn = "Multiple contacts match \"$recipientQuery\": $names. Please clarify.",
                                summaryBn = "একাধিক কন্টাক্ট পাওয়া গেছে: $names। কাকে মেসেজ পাঠাব?"
                            )
                        }
                        is TrustedContactsRepository.ContactLookupResult.NotFound -> {
                            null to AgentActionResult(
                                success = false,
                                status = "CONTACT_NOT_FOUND",
                                summaryEn = "Could not find contact \"$recipientQuery\" in Trusted Contacts.",
                                summaryBn = "\"$recipientQuery\" নামে কোনো কন্টাক্ট পাওয়া যায়নি।"
                            )
                        }
                    }
                }
            }

            else -> {
                val target = request.parameters["target_text"] ?: request.reason.ifBlank { request.actionType.displayLabelEn }
                PendingConfirmation(
                    callId = request.callId,
                    actionRequest = request,
                    promptEn = "Confirm action: $target?",
                    promptBn = "এই কাজটি করার আগে আপনার অনুমতি প্রয়োজন: $target। কাজটি করব?",
                    previewDetail = target
                ) to null
            }
        }
    }

    /**
     * Executes a validated [AgentActionRequest] (after confirmation if Group B).
     */
    suspend fun execute(
        request: AgentActionRequest,
        userConfirmed: Boolean = false
    ): AgentActionResult {
        val activePackage = AgentAccessibilityService.activePackageName.value
        val evaluation = AgentSafetyManager.evaluate(request, activePackage)

        if (evaluation.isBlocked) {
            return AgentActionResult(
                success = false,
                status = "BLOCKED_HIGH_RISK",
                summaryEn = evaluation.reasonEn,
                summaryBn = evaluation.reasonBn,
                blockedBySafety = true
            )
        }

        if (evaluation.requiresConfirmation && !userConfirmed) {
            return AgentActionResult(
                success = false,
                status = "REQUIRES_CONFIRMATION",
                summaryEn = evaluation.reasonEn,
                summaryBn = evaluation.reasonBn,
                needsConfirmation = true
            )
        }

        return when (request.actionType) {
            AgentActionType.OPEN_APP -> {
                val appName = request.parameters["app_name"].orEmpty()
                val res = appLauncher.launchInstalledApp(appName)
                if (res.success) {
                    delay(650L)
                    attachFreshSnapshot(res)
                } else {
                    res
                }
            }

            AgentActionType.OPEN_SETTINGS -> {
                val section = request.parameters["section"].orEmpty()
                val res = appLauncher.openSystemSettings(section)
                if (res.success) {
                    delay(500L)
                    attachFreshSnapshot(res)
                } else {
                    res
                }
            }

            AgentActionType.OPEN_URL -> {
                val url = request.parameters["url"].orEmpty()
                appLauncher.openUrl(url)
            }

            AgentActionType.GET_DATE_TIME -> executeGetDateTime()

            AgentActionType.SET_VOLUME -> executeSetVolume(
                direction = request.parameters["direction"].orEmpty(),
                percentStr = request.parameters["percent"]
            )

            AgentActionType.SET_ALARM -> {
                val hour = request.parameters["hour_24"]?.toIntOrNull() ?: -1
                val minute = request.parameters["minute"]?.toIntOrNull() ?: 0
                val label = request.parameters["label"].orEmpty()
                appLauncher.setAlarm(hour, minute, label)
            }

            AgentActionType.CALL_TRUSTED_CONTACT -> {
                val resolvedPhone = request.parameters["resolved_phone"].orEmpty()
                val resolvedName = request.parameters["resolved_name"].orEmpty()
                if (resolvedPhone.isNotBlank()) {
                    appLauncher.openDialer(resolvedPhone, resolvedName)
                } else {
                    val query = request.parameters["contact_query"].orEmpty()
                    when (val lookup = trustedContactsRepository.resolveContact(query)) {
                        is TrustedContactsRepository.ContactLookupResult.SingleMatch ->
                            appLauncher.openDialer(lookup.contact.phoneNumber, lookup.contact.name)
                        is TrustedContactsRepository.ContactLookupResult.Ambiguous ->
                            AgentActionResult(
                                success = false,
                                status = "AMBIGUOUS_CONTACT",
                                summaryEn = "Multiple contacts match \"$query\". Please clarify.",
                                summaryBn = "একাধিক কন্টাক্ট পাওয়া গেছে। কার নম্বরে কল করব?"
                            )
                        is TrustedContactsRepository.ContactLookupResult.NotFound ->
                            AgentActionResult(
                                success = false,
                                status = "CONTACT_NOT_FOUND",
                                summaryEn = "No contact found for \"$query\".",
                                summaryBn = "\"$query\" নামে কোনো কন্টাক্ট পাওয়া যায়নি।"
                            )
                    }
                }
            }

            AgentActionType.OPEN_DIALER -> {
                val phone = request.parameters["phone_number"].orEmpty()
                val name = request.parameters["display_name"].orEmpty()
                appLauncher.openDialer(phone, name)
            }

            AgentActionType.PREPARE_MESSAGE -> {
                val phone = request.parameters["resolved_phone"] ?: request.parameters["recipient_query"].orEmpty()
                val name = request.parameters["resolved_name"].orEmpty()
                val message = request.parameters["message_text"].orEmpty()
                val channel = request.parameters["channel"] ?: "whatsapp"
                appLauncher.openPreparedMessage(phone, message, channel, name)
            }

            AgentActionType.SEARCH_TEXT -> executeSearchText(
                query = request.parameters["query"].orEmpty(),
                appName = request.parameters["app_name"].orEmpty()
            )

            AgentActionType.NAVIGATE_BACK -> withAccessibility { service ->
                val ok = service.navigateBack()
                delay(350L)
                val snap = service.captureScreenSnapshot()
                AgentActionResult(
                    success = ok,
                    status = if (ok) "NAVIGATED_BACK" else "BACK_FAILED",
                    summaryEn = if (ok) "Navigated back to the previous page." else "Could not navigate back.",
                    summaryBn = if (ok) "আগের পেজে ফিরে গেছি।" else "আগের পেজে যাওয়া যায়নি।",
                    screenSummary = snap?.toSafePromptSummary()
                )
            }

            AgentActionType.NAVIGATE_HOME -> withAccessibility { service ->
                val ok = service.navigateHome()
                delay(350L)
                AgentActionResult(
                    success = ok,
                    status = if (ok) "NAVIGATED_HOME" else "HOME_FAILED",
                    summaryEn = if (ok) "Returned to the Home screen." else "Could not return to Home.",
                    summaryBn = if (ok) "হোম স্ক্রিনে ফিরে গেছি।" else "হোম স্ক্রিনে যাওয়া যায়নি।"
                )
            }

            AgentActionType.READ_SCREEN,
            AgentActionType.VERIFY_SCREEN -> withAccessibility { service ->
                val snap = service.captureScreenSnapshot()
                if (snap == null) {
                    AgentActionResult(
                        success = false,
                        status = "SCREEN_UNREADABLE",
                        summaryEn = "The current window content is not accessible (it may be a protected or custom-rendered screen).",
                        summaryBn = "এই স্ক্রিনের লেখা অ্যাক্সেস করা যাচ্ছে না। স্ক্রিন শেয়ার চালু থাকলে আমি দেখে বলতে পারব।"
                    )
                } else {
                    AgentActionResult(
                        success = true,
                        status = "SCREEN_CAPTURED",
                        summaryEn = "Read ${snap.visibleTexts.size} visible text items on ${snap.packageName}.",
                        summaryBn = "স্ক্রিনের তথ্য পড়ে নিয়েছি।",
                        screenSummary = snap.toSafePromptSummary()
                    )
                }
            }

            AgentActionType.CLICK_ELEMENT -> withAccessibility { service ->
                val target = request.parameters["target_text"] ?: request.parameters["text"].orEmpty()
                val idx = request.parameters["occurrence_index"]?.toIntOrNull() ?: 0
                val (ok, msg) = service.clickElementByText(target, idx)
                delay(450L)
                val snap = service.captureScreenSnapshot()
                AgentActionResult(
                    success = ok,
                    status = if (ok) "ELEMENT_CLICKED" else "ELEMENT_NOT_FOUND",
                    summaryEn = msg,
                    summaryBn = if (ok) "\"$target\"-এ ক্লিক করেছি।" else "\"$target\" বাটনটি স্ক্রিনে পাওয়া যায়নি।",
                    screenSummary = snap?.toSafePromptSummary()
                )
            }

            AgentActionType.LONG_PRESS -> withAccessibility { service ->
                val target = request.parameters["target_text"].orEmpty()
                val (ok, msg) = service.longPressElementByText(target)
                delay(400L)
                val snap = service.captureScreenSnapshot()
                AgentActionResult(
                    success = ok,
                    status = if (ok) "ELEMENT_LONG_PRESSED" else "LONG_PRESS_FAILED",
                    summaryEn = msg,
                    summaryBn = if (ok) "\"$target\" চেপে ধরেছি।" else "\"$target\" চেপে ধরা যায়নি।",
                    screenSummary = snap?.toSafePromptSummary()
                )
            }

            AgentActionType.SCROLL -> withAccessibility { service ->
                val dir = request.parameters["direction"] ?: "down"
                val (ok, msg) = service.scrollScreen(dir)
                delay(350L)
                val snap = service.captureScreenSnapshot()
                AgentActionResult(
                    success = ok,
                    status = if (ok) "SCROLLED" else "SCROLL_FAILED",
                    summaryEn = msg,
                    summaryBn = if (ok) "স্ক্রিন স্ক্রল করেছি।" else "এই স্ক্রিনটি স্ক্রল করা যাচ্ছে না।",
                    screenSummary = snap?.toSafePromptSummary()
                )
            }

            AgentActionType.TYPE_TEXT -> withAccessibility { service ->
                val text = request.parameters["text"].orEmpty()
                val hint = request.parameters["field_hint"].orEmpty()
                val submit = request.parameters["submit"]?.equals("true", ignoreCase = true) == true
                val (ok, msg) = service.typeTextIntoField(text, hint, submit)
                delay(400L)
                val snap = service.captureScreenSnapshot()
                AgentActionResult(
                    success = ok,
                    status = if (ok) "TEXT_TYPED" else "TYPE_FAILED",
                    summaryEn = msg,
                    summaryBn = if (ok) "টেক্সট লিখে দিয়েছি।" else "লেখার ঘরটি স্ক্রিনে পাওয়া যায়নি।",
                    screenSummary = snap?.toSafePromptSummary()
                )
            }

            AgentActionType.WAIT_FOR_UI -> withAccessibility {
                val expected = request.parameters["expected_text"].orEmpty()
                val timeoutMs = request.parameters["timeout_ms"]?.toLongOrNull() ?: 3000L
                val snap = AgentAccessibilityService.waitForUi(expected, timeoutMs)
                if (snap != null) {
                    AgentActionResult(
                        success = true,
                        status = "UI_READY",
                        summaryEn = "UI snapshot ready on ${snap.packageName}.",
                        summaryBn = "স্ক্রিন লোড হয়েছে।",
                        screenSummary = snap.toSafePromptSummary()
                    )
                } else {
                    AgentActionResult(
                        success = false,
                        status = "UI_WAIT_TIMEOUT",
                        summaryEn = "Timed out waiting for accessible UI.",
                        summaryBn = "স্ক্রিন লোড হতে বেশি সময় লাগছে।"
                    )
                }
            }

            AgentActionType.ASK_CONFIRMATION -> AgentActionResult(
                success = true,
                status = "USER_CONFIRMED",
                summaryEn = "User confirmed the action.",
                summaryBn = "ব্যবহারকারী অনুমতি দিয়েছেন।"
            )

            AgentActionType.STOP_ACTION -> AgentActionResult(
                success = true,
                status = "STOPPED",
                summaryEn = "Stopped active agent task.",
                summaryBn = "চলমান কাজটি থামানো হয়েছে।"
            )
        }
    }

    private suspend fun executeSearchText(query: String, appName: String): AgentActionResult {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return AgentActionResult(
                success = false,
                status = "EMPTY_QUERY",
                summaryEn = "No search query was provided.",
                summaryBn = "কী খুঁজতে হবে তা বলা হয়নি।"
            )
        }

        val normalizedApp = AppLauncher.normalizeAppAlias(appName)
        if (normalizedApp == "youtube" || appName.contains("youtube", ignoreCase = true) || appName.contains("ইউটিউব")) {
            val ytResult = appLauncher.openYouTubeSearch(cleanQuery)
            if (ytResult.success) {
                delay(1100L)
                return attachFreshSnapshot(ytResult)
            }
            return ytResult
        }

        if (normalizedApp == "chrome" || normalizedApp == "google") {
            val url = "https://www.google.com/search?q=${android.net.Uri.encode(cleanQuery)}"
            return appLauncher.openUrl(url)
        }

        // For any currently open app, use AccessibilityService to click Search & type
        return withAccessibility { service ->
            val (typedDirectly, _) = service.typeTextIntoField(cleanQuery, fieldHint = "search", submitAfterTyping = true)
            if (typedDirectly) {
                delay(650L)
                val snap = service.captureScreenSnapshot()
                return@withAccessibility AgentActionResult(
                    success = true,
                    status = "SEARCH_SUBMITTED",
                    summaryEn = "Searched for \"$cleanQuery\".",
                    summaryBn = "\"$cleanQuery\" খুঁজে দিয়েছি।",
                    screenSummary = snap?.toSafePromptSummary()
                )
            }
            // Try clicking a Search icon first
            val (clickedSearch, _) = service.clickElementByText("Search")
            if (clickedSearch) {
                delay(450L)
                val (typed, msg) = service.typeTextIntoField(cleanQuery, fieldHint = "search", submitAfterTyping = true)
                delay(650L)
                val snap = service.captureScreenSnapshot()
                return@withAccessibility AgentActionResult(
                    success = typed,
                    status = if (typed) "SEARCH_SUBMITTED" else "SEARCH_INPUT_FAILED",
                    summaryEn = if (typed) "Searched for \"$cleanQuery\"." else msg,
                    summaryBn = if (typed) "\"$cleanQuery\" খুঁজে দিয়েছি।" else "খোঁজার ঘরটি পাওয়া যায়নি।",
                    screenSummary = snap?.toSafePromptSummary()
                )
            }
            AgentActionResult(
                success = false,
                status = "SEARCH_ELEMENT_NOT_FOUND",
                summaryEn = "Could not locate a search button or input field on the current screen.",
                summaryBn = "এই স্ক্রিনে খোঁজার (Search) ঘর পাওয়া যায়নি।",
                screenSummary = service.captureScreenSnapshot()?.toSafePromptSummary()
            )
        }
    }

    private fun executeGetDateTime(): AgentActionResult {
        val now = Date()
        val enFormat = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH)
        val bnLocale = Locale("bn", "BD")
        val bnDateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", bnLocale)
        val bnTimeFormat = SimpleDateFormat("h:mm a", bnLocale)
        val enStr = enFormat.format(now)
        val bnStr = "আজ ${bnDateFormat.format(now)}, এখন সময় ${bnTimeFormat.format(now)}।"
        return AgentActionResult(
            success = true,
            status = "DATE_TIME_OK",
            summaryEn = "Current date and time: $enStr",
            summaryBn = bnStr,
            details = mapOf("en" to enStr, "bn" to bnStr)
        )
    }

    private fun executeSetVolume(direction: String, percentStr: String?): AgentActionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AgentActionResult(
                success = false,
                status = "AUDIO_SERVICE_UNAVAILABLE",
                summaryEn = "AudioManager is unavailable on this device.",
                summaryBn = "ভলিউম নিয়ন্ত্রণ করা যায়নি।"
            )

        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val newVol = when (direction.trim().lowercase()) {
            "up", "increase", "higher", "বাড়াও", "বাড়াও" -> (curVol + (maxVol / 5).coerceAtLeast(1)).coerceAtMost(maxVol)
            "down", "decrease", "lower", "কমাও" -> (curVol - (maxVol / 5).coerceAtLeast(1)).coerceAtLeast(0)
            "mute", "silent", "বন্ধ" -> 0
            "max", "full" -> maxVol
            "set" -> {
                val pct = percentStr?.toIntOrNull()?.coerceIn(0, 100) ?: 50
                ((pct / 100f) * maxVol).toInt().coerceIn(0, maxVol)
            }
            else -> curVol
        }

        val setOk = runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
        }.isSuccess
        val pctNow = ((newVol * 100f) / maxVol).toInt()
        return if (setOk) {
            AgentActionResult(
                success = true,
                status = "VOLUME_UPDATED",
                summaryEn = "Media volume set to $pctNow%.",
                summaryBn = "সাউন্ড এখন $pctNow শতাংশে সেট করা হয়েছে।"
            )
        } else {
            AgentActionResult(
                success = false,
                status = "VOLUME_FAILED",
                summaryEn = "Android restricted changing media volume right now.",
                summaryBn = "ভলিউম পরিবর্তন করা যায়নি।"
            )
        }
    }

    private fun attachFreshSnapshot(result: AgentActionResult): AgentActionResult {
        val snap = AgentAccessibilityService.activeInstance?.captureScreenSnapshot()
        return if (snap != null) {
            result.copy(screenSummary = snap.toSafePromptSummary())
        } else {
            result
        }
    }

    private suspend fun withAccessibility(
        block: suspend (AgentAccessibilityService) -> AgentActionResult
    ): AgentActionResult {
        val service = AgentAccessibilityService.activeInstance
        if (service == null || !AgentAccessibilityService.isConnected.value) {
            return AgentActionResult(
                success = false,
                status = "ACCESSIBILITY_DISABLED",
                summaryEn = "Android Accessibility Service (ScreenSight AI Agent Control) is not enabled. Ask the user to enable it in Agent Permissions & Setup.",
                summaryBn = "ফোন নিয়ন্ত্রণের জন্য ScreenSight AI Accessibility অনুমতি চালু করা নেই। অনুগ্রহ করে Agent Permissions থেকে এটি চালু করুন।",
                needsAccessibilityPermission = true
            )
        }
        return block(service)
    }
}
