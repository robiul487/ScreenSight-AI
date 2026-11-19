package com.example.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings

/**
 * Dynamic Android App Launcher & Standard Intent Handler (Sections 5, 10, 11, 12).
 *
 * Uses PackageManager launcher discovery (`ACTION_MAIN` + `CATEGORY_LAUNCHER`) combined
 * with Bangla & English alias resolution instead of relying only on hardcoded packages.
 * Always prefers official Android Intents and deep links where supported.
 */
class AppLauncher(private val context: Context) {

    data class InstalledAppInfo(
        val label: String,
        val packageName: String
    )

    sealed class AppResolutionResult {
        data class ExactMatch(val app: InstalledAppInfo) : AppResolutionResult()
        data class Ambiguous(val candidates: List<InstalledAppInfo>) : AppResolutionResult()
        object NotFound : AppResolutionResult()
    }

    /**
     * Queries user-installed launchable apps via Android PackageManager.
     */
    fun getInstalledLauncherApps(): List<InstalledAppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val seenPackages = HashSet<String>()
        val result = mutableListOf<InstalledAppInfo>()
        for (info in resolveInfos) {
            val pkg = info.activityInfo?.packageName ?: continue
            if (seenPackages.add(pkg)) {
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                result.add(InstalledAppInfo(label = label, packageName = pkg))
            }
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /**
     * Resolves an application name in English or Bangla (e.g., "ইউটিউব", "WhatsApp", "ফেসবুক")
     * against the device's actually installed apps.
     */
    fun resolveInstalledApp(rawQuery: String): AppResolutionResult {
        val normalizedQuery = normalizeAppAlias(rawQuery)
        if (normalizedQuery.isEmpty()) return AppResolutionResult.NotFound

        val installed = getInstalledLauncherApps()
        if (installed.isEmpty()) return AppResolutionResult.NotFound

        // 1. Exact label match or exact package match
        val exact = installed.firstOrNull {
            it.label.equals(normalizedQuery, ignoreCase = true) ||
                it.packageName.equals(normalizedQuery, ignoreCase = true)
        }
        if (exact != null) return AppResolutionResult.ExactMatch(exact)

        // 2. Starts-with or contains match
        val matches = installed.filter {
            it.label.lowercase().contains(normalizedQuery) ||
                normalizedQuery.contains(it.label.lowercase()) ||
                it.packageName.lowercase().contains(normalizedQuery.replace(" ", ""))
        }

        return when (matches.size) {
            0 -> AppResolutionResult.NotFound
            1 -> AppResolutionResult.ExactMatch(matches.first())
            else -> {
                // Prefer shortest exact word match if unambiguous (e.g., "YouTube" vs "YouTube Music")
                val wordExact = matches.filter {
                    it.label.lowercase() == normalizedQuery
                }
                if (wordExact.size == 1) {
                    AppResolutionResult.ExactMatch(wordExact.first())
                } else {
                    AppResolutionResult.Ambiguous(matches.take(5))
                }
            }
        }
    }

    fun launchInstalledApp(rawAppName: String): AgentActionResult {
        val normalized = normalizeAppAlias(rawAppName)
        if (normalized == "settings") {
            return openSystemSettings()
        }

        return when (val resolution = resolveInstalledApp(rawAppName)) {
            is AppResolutionResult.ExactMatch -> {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(resolution.app.packageName)
                if (launchIntent == null) {
                    return AgentActionResult(
                        success = false,
                        status = "LAUNCH_FAILED",
                        summaryEn = "${resolution.app.label} was found but cannot be launched directly.",
                        summaryBn = "${resolution.app.label} অ্যাপটি সরাসরি চালু করা যায়নি।"
                    )
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { context.startActivity(launchIntent) }.isSuccess
                if (opened) {
                    AgentActionResult(
                        success = true,
                        status = "APP_OPENED",
                        summaryEn = "Opened ${resolution.app.label}.",
                        summaryBn = "${resolution.app.label} খুলেছি।",
                        details = mapOf(
                            "app_name" to resolution.app.label,
                            "package_name" to resolution.app.packageName
                        )
                    )
                } else {
                    AgentActionResult(
                        success = false,
                        status = "LAUNCH_EXCEPTION",
                        summaryEn = "Android blocked opening ${resolution.app.label} from the current state.",
                        summaryBn = "${resolution.app.label} অ্যাপটি খুলতে সমস্যা হয়েছে।"
                    )
                }
            }

            is AppResolutionResult.Ambiguous -> {
                val names = resolution.candidates.joinToString(", ") { it.label }
                AgentActionResult(
                    success = false,
                    status = "AMBIGUOUS_APP",
                    summaryEn = "Multiple matching apps found: $names. Please clarify which app to open.",
                    summaryBn = "একাধিক অ্যাপ পাওয়া গেছে: $names। আপনি কোনটি খুলতে চান?",
                    details = mapOf("candidates" to names)
                )
            }

            is AppResolutionResult.NotFound -> {
                AgentActionResult(
                    success = false,
                    status = "APP_NOT_INSTALLED",
                    summaryEn = "Could not find an installed app named \"$rawAppName\" on this phone.",
                    summaryBn = "আপনার ফোনে \"$rawAppName\" অ্যাপটি পাওয়া যায়নি।"
                )
            }
        }
    }

    fun openSystemSettings(section: String = ""): AgentActionResult {
        val action = when (section.trim().lowercase()) {
            "wifi", "wi-fi", "ওয়াইফাই" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth", "ব্লুটুথ" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness", "ডিসপ্লে" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume", "সাউন্ড" -> Settings.ACTION_SOUND_SETTINGS
            "battery", "ব্যাটারি" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility", "অ্যাক্সেসিবিলিটি" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "apps", "অ্যাপস" -> Settings.ACTION_APPLICATION_SETTINGS
            "date", "time", "সময়" -> Settings.ACTION_DATE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return if (launched) {
            AgentActionResult(
                success = true,
                status = "SETTINGS_OPENED",
                summaryEn = "Opened Android Settings.",
                summaryBn = "ফোনের সেটিংস খুলেছি।"
            )
        } else {
            AgentActionResult(
                success = false,
                status = "SETTINGS_FAILED",
                summaryEn = "Could not open the requested Settings screen.",
                summaryBn = "সেটিংস খোলা যায়নি।"
            )
        }
    }

    /**
     * Opens a supported web URL or YouTube search deep link via official Android VIEW intent.
     */
    fun openUrl(rawUrl: String): AgentActionResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return AgentActionResult(
                success = false,
                status = "INVALID_URL",
                summaryEn = "No URL was provided.",
                summaryBn = "কোনো ওয়েব লিংক দেওয়া হয়নি।"
            )
        }
        val safeUrl = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return if (launched) {
            AgentActionResult(
                success = true,
                status = "URL_OPENED",
                summaryEn = "Opened $safeUrl.",
                summaryBn = "ওয়েব পেজটি খুলেছি।"
            )
        } else {
            AgentActionResult(
                success = false,
                status = "URL_FAILED",
                summaryEn = "No browser or app could open $safeUrl.",
                summaryBn = "এই লিংকটি খোলার মতো কোনো ব্রাউজার পাওয়া যায়নি।"
            )
        }
    }

    /**
     * Opens YouTube search directly using official YouTube search URI, which works reliably
     * on devices with YouTube installed and gracefully falls back to browser.
     */
    fun openYouTubeSearch(query: String): AgentActionResult {
        val clean = query.trim()
        if (clean.isEmpty()) return launchInstalledApp("YouTube")
        val encoded = Uri.encode(clean)
        val uri = Uri.parse("https://www.youtube.com/results?search_query=$encoded")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Prefer YouTube app directly if installed
        if (isPackageInstalled("com.google.android.youtube")) {
            intent.setPackage("com.google.android.youtube")
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return if (launched) {
            AgentActionResult(
                success = true,
                status = "YOUTUBE_SEARCH_OPENED",
                summaryEn = "Opened YouTube search results for \"$clean\".",
                summaryBn = "YouTube-এ \"$clean\" খুঁজে দিয়েছি।"
            )
        } else {
            openUrl("https://www.youtube.com/results?search_query=$encoded")
        }
    }

    /**
     * Opens the system phone dialer (`ACTION_DIAL`) pre-filled with the number so the user stays in control.
     */
    fun openDialer(phoneNumber: String, displayName: String = ""): AgentActionResult {
        val cleanPhone = phoneNumber.replace(Regex("[^+0-9*#]"), "")
        if (cleanPhone.isBlank()) {
            return AgentActionResult(
                success = false,
                status = "INVALID_NUMBER",
                summaryEn = "No valid phone number was provided.",
                summaryBn = "সঠিক ফোন নম্বর পাওয়া যায়নি।"
            )
        }
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        val label = displayName.ifBlank { cleanPhone }
        return if (launched) {
            AgentActionResult(
                success = true,
                status = "DIALER_OPENED",
                summaryEn = "Opened the phone dialer for $label. Tap the call button to place the call.",
                summaryBn = "$label-এর নম্বর ডায়ালারে খুলে দিয়েছি। কল করতে সবুজ বাটনে চাপ দিন।"
            )
        } else {
            AgentActionResult(
                success = false,
                status = "DIALER_UNAVAILABLE",
                summaryEn = "No phone dialer app is available on this device.",
                summaryBn = "এই ডিভাইসে ফোন ডায়ালার অ্যাপ পাওয়া যায়নি।"
            )
        }
    }

    /**
     * Prepares an SMS or WhatsApp message via official Android Intents/deep links without silently sending.
     */
    fun openPreparedMessage(
        phoneNumber: String,
        messageText: String,
        channel: String,
        recipientName: String = ""
    ): AgentActionResult {
        val cleanPhone = phoneNumber.replace(Regex("[^+0-9]"), "")
        if (cleanPhone.isBlank()) {
            return AgentActionResult(
                success = false,
                status = "INVALID_RECIPIENT",
                summaryEn = "No valid phone number found for recipient.",
                summaryBn = "প্রাপকের সঠিক ফোন নম্বর পাওয়া যায়নি।"
            )
        }

        val useWhatsApp = channel.equals("whatsapp", ignoreCase = true) ||
            channel.contains("হোয়াটসঅ্যাপ") || channel.contains("হোয়াটসঅ্যাপ")

        if (useWhatsApp && (isPackageInstalled("com.whatsapp") || isPackageInstalled("com.whatsapp.w4b"))) {
            val waDigits = cleanPhone.removePrefix("+")
            val waUri = Uri.parse("https://wa.me/$waDigits?text=${Uri.encode(messageText)}")
            val intent = Intent(Intent.ACTION_VIEW, waUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (isPackageInstalled("com.whatsapp")) {
                    setPackage("com.whatsapp")
                }
            }
            val launched = runCatching { context.startActivity(intent) }.isSuccess
            if (launched) {
                val targetLabel = recipientName.ifBlank { cleanPhone }
                return AgentActionResult(
                    success = true,
                    status = "WHATSAPP_MESSAGE_PREPARED",
                    summaryEn = "Opened WhatsApp with your drafted message to $targetLabel. Review and tap Send.",
                    summaryBn = "$targetLabel-এর জন্য WhatsApp-এ মেসেজটি লিখে খুলে দিয়েছি। দেখে নিয়ে Send চাপুন।"
                )
            }
        }

        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$cleanPhone")).apply {
            putExtra("sms_body", messageText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(smsIntent) }.isSuccess
        val targetLabel = recipientName.ifBlank { cleanPhone }
        return if (launched) {
            AgentActionResult(
                success = true,
                status = "SMS_MESSAGE_PREPARED",
                summaryEn = "Opened SMS app with your drafted message to $targetLabel. Review and tap Send.",
                summaryBn = "$targetLabel-এর জন্য SMS অ্যাপে মেসেজটি লিখে খুলে দিয়েছি। দেখে নিয়ে Send চাপুন।"
            )
        } else {
            AgentActionResult(
                success = false,
                status = "MESSAGING_UNAVAILABLE",
                summaryEn = "Could not open WhatsApp or SMS app on this device.",
                summaryBn = "মেসেজ পাঠানোর অ্যাপ খোলা যায়নি।"
            )
        }
    }

    /**
     * Creates an alarm using the official Android AlarmClock Intent.
     */
    fun setAlarm(hour24: Int, minute: Int, label: String = ""): AgentActionResult {
        if (hour24 !in 0..23 || minute !in 0..59) {
            return AgentActionResult(
                success = false,
                status = "INVALID_TIME",
                summaryEn = "Invalid alarm time ($hour24:$minute). Please clarify the exact hour and minute.",
                summaryBn = "অ্যালার্মের সময়টি সঠিক নয়। অনুগ্রহ করে ঠিক কয়টায় অ্যালার্ম দেবেন তা বলুন।"
            )
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour24)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            }
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hasHandler = intent.resolveActivity(context.packageManager) != null
        if (!hasHandler) {
            return AgentActionResult(
                success = false,
                status = "ALARM_APP_UNAVAILABLE",
                summaryEn = "No compatible Clock / Alarm app found on this device.",
                summaryBn = "এই ফোনে অ্যালার্ম সেট করার ঘড়ি অ্যাপ পাওয়া যায়নি।"
            )
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        val formattedTime = String.format("%02d:%02d", hour24, minute)
        return if (launched) {
            AgentActionResult(
                success = true,
                status = "ALARM_REQUEST_SENT",
                summaryEn = "Sent alarm request for $formattedTime to the Clock app.",
                summaryBn = "$formattedTime-এর জন্য অ্যালার্ম ঘড়ি অ্যাপে সেট করা হয়েছে।"
            )
        } else {
            AgentActionResult(
                success = false,
                status = "ALARM_FAILED",
                summaryEn = "Failed to open the Clock app to set the alarm.",
                summaryBn = "অ্যালার্ম সেট করতে সমস্যা হয়েছে।"
            )
        }
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private val BANGLA_AND_COMMON_APP_ALIASES = mapOf(
            "ইউটিউব" to "youtube",
            "ইউটিউবটা" to "youtube",
            "youtube" to "youtube",
            "ফেসবুক" to "facebook",
            "ফেসবুকটা" to "facebook",
            "fb" to "facebook",
            "হোয়াটসঅ্যাপ" to "whatsapp",
            "হোয়াটসঅ্যাপ" to "whatsapp",
            "ওয়াটসঅ্যাপ" to "whatsapp",
            "ওয়াটসঅ্যাপ" to "whatsapp",
            "হোয়াটস্যাপ" to "whatsapp",
            "ক্রোম" to "chrome",
            "গুগল ক্রোম" to "chrome",
            "গুগল" to "google",
            "ম্যাপস" to "maps",
            "গুগল ম্যাপস" to "maps",
            "সেটিংস" to "settings",
            "সেটিং" to "settings",
            "ফোন" to "phone",
            "ডায়ালার" to "phone",
            "মেসেজ" to "messages",
            "মেসেঞ্জার" to "messenger",
            "ক্যামেরা" to "camera",
            "গ্যালারি" to "gallery",
            "ফটো" to "photos",
            "ক্যালকুলেটর" to "calculator",
            "ঘড়ি" to "clock",
            "ঘড়ি" to "clock",
            "অ্যালার্ম" to "clock",
            "ইমো" to "imo",
            "টেলিগ্রাম" to "telegram",
            "জিমেইল" to "gmail"
        )

        fun normalizeAppAlias(raw: String): String {
            val clean = raw.trim().lowercase()
            return BANGLA_AND_COMMON_APP_ALIASES[clean] ?: clean
        }
    }
}
