package com.example.domain.model

import com.example.agent.AgentMode
import kotlinx.serialization.Serializable

/**
 * Exact session states mandated by the ScreenSight AI specification (Section 9.2).
 */
enum class LiveSessionState(val displayName: String, val isActive: Boolean) {
    IDLE("Idle", false),
    CONNECTING("Connecting to Gemini Live...", true),
    CONNECTED("Connected", true),
    REQUESTING_SCREEN_PERMISSION("Awaiting Screen Permission...", true),
    SHARING_SCREEN("Sharing Screen Live", true),
    LISTENING("Listening...", true),
    THINKING("Thinking...", true),
    SPEAKING("Gemini Speaking...", true),
    RECONNECTING("Reconnecting...", true),
    ERROR("Connection Error", false),
    STOPPED("Session Ended", false)
}

enum class ConnectionQuality(val label: String) {
    EXCELLENT("Optimal (1 FPS)"),
    MODERATE("Backpressure Active"),
    SLOW("Slow Network — Dropping Old Frames"),
    DISCONNECTED("Offline")
}

enum class ThemePreference(val label: String) {
    SYSTEM("System Default"),
    LIGHT("Light Mode"),
    DARK("Dark Mode")
}

@Serializable
data class GeminiLiveModel(
    val id: String,
    val displayName: String,
    val roleBadge: String = "Verified Live Model",
    val quotaSummary: String = "Check Google AI Studio Rate Limits",
    val description: String,
    val supportsNativeAudio: Boolean = true,
    val supportsVideoFrames: Boolean = true,
    val defaultResponseModalities: List<String> = listOf("AUDIO"),
    val inputSampleRateHz: Int = 16000,
    val outputSampleRateHz: Int = 24000,
    val isVerifiedLiveModel: Boolean = true
) {
    val fullResourceName: String
        get() = if (id.startsWith("models/")) id else "models/$id"

    companion object {
        /**
         * Officially verified Google Gemini Live API (`BidiGenerateContent`) model identifiers.
         *
         * Note on Dashboard Display Names vs API Identifiers:
         * Google AI Studio's rate-limit dashboard displays friendly names such as
         * "Gemini 3 Flash Live" or "Gemini 2.5 Flash Native Audio Dialog", whereas the
         * actual `BidiGenerateContent` WebSocket endpoint requires exact `/v1beta/models`
         * resource identifiers (`models/gemini-3.1-flash-live-preview`,
         * `models/gemini-2.5-flash-native-audio-preview-12-2025`, etc.).
         *
         * Unverified dashboard labels are never silently assumed to be valid API model IDs.
         * Users can also run "Validate" in API Settings to query `GET /v1beta/models` and
         * discover the exact `bidiGenerateContent` model IDs enabled on their API key.
         */
        val PRIMARY_MODEL = GeminiLiveModel(
            id = "models/gemini-3.1-flash-live-preview",
            displayName = "Gemini 3 Flash Live",
            roleBadge = "Primary Default • Verified BidiGenerateContent",
            quotaSummary = "Official API ID: models/gemini-3.1-flash-live-preview",
            description = "Main Screen Sharing AI for real-time visual screen understanding and two-way low-latency voice conversation.",
            supportsNativeAudio = true,
            supportsVideoFrames = true,
            defaultResponseModalities = listOf("AUDIO"),
            isVerifiedLiveModel = true
        )

        val NATIVE_AUDIO_2_5_DEC_MODEL = GeminiLiveModel(
            id = "models/gemini-2.5-flash-native-audio-preview-12-2025",
            displayName = "Gemini 2.5 Flash Native Audio Dialog (Dec 2025)",
            roleBadge = "Verified Fallback • Native Audio + Screen",
            quotaSummary = "Official API ID: models/gemini-2.5-flash-native-audio-preview-12-2025",
            description = "Officially documented Gemini Live native audio dialog model supporting real-time voice and 1 FPS video frames.",
            supportsNativeAudio = true,
            supportsVideoFrames = true,
            defaultResponseModalities = listOf("AUDIO"),
            isVerifiedLiveModel = true
        )

        val NATIVE_AUDIO_2_5_SEP_MODEL = GeminiLiveModel(
            id = "models/gemini-2.5-flash-native-audio-preview-09-2025",
            displayName = "Gemini 2.5 Flash Native Audio Dialog (Sep 2025)",
            roleBadge = "Verified Archive Preview • Native Audio + Screen",
            quotaSummary = "Official API ID: models/gemini-2.5-flash-native-audio-preview-09-2025",
            description = "Earlier official Gemini 2.5 Flash Native Audio Live API checkpoint for accounts provisioned on the 09-2025 preview.",
            supportsNativeAudio = true,
            supportsVideoFrames = true,
            defaultResponseModalities = listOf("AUDIO"),
            isVerifiedLiveModel = true
        )

        val FALLBACK_MODEL = NATIVE_AUDIO_2_5_DEC_MODEL

        val VERIFIED_LIVE_MODELS = listOf(
            PRIMARY_MODEL,
            NATIVE_AUDIO_2_5_DEC_MODEL,
            NATIVE_AUDIO_2_5_SEP_MODEL
        )

        val DEFAULT_MODEL = PRIMARY_MODEL

        /**
         * Legacy shut-down or unverified slugified model IDs that should migrate to [DEFAULT_MODEL]
         * on initial settings load so existing installations do not fail on startup.
         */
        private val LEGACY_OR_UNVERIFIED_SLUG_MODEL_IDS = setOf(
            "models/gemini-2.0-flash-live-001",
            "models/gemini-2.0-flash-exp",
            "models/gemini-3.8-live",
            "models/gemini-3.8-live-extended-thinking",
            "models/gemini-3.5-live-translate-preview",
            "models/gemini-3.5-transcribe-live"
        )

        fun isLegacyShutDownModel(modelId: String): Boolean {
            val trimmed = modelId.trim()
            if (trimmed.isEmpty()) return true
            val withPrefix = if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
            return LEGACY_OR_UNVERIFIED_SLUG_MODEL_IDS.any { it.equals(withPrefix, ignoreCase = true) }
        }

        /**
         * Resolves known official aliases or preserves an exact `models/...` API identifier
         * entered by the user without inventing unverified model IDs.
         */
        fun normalizeModelId(rawInput: String): String {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return DEFAULT_MODEL.id
            val lower = trimmed.lowercase().removePrefix("models/")
            return when (lower) {
                "gemini 3 flash live",
                "gemini-3-flash-live",
                "gemini-3.1-flash-live",
                "gemini-3.1-flash-live-preview" -> PRIMARY_MODEL.id

                "gemini 2.5 flash native audio dialog",
                "gemini-2.5-flash-native-audio-dialog",
                "gemini-2.5-flash-preview-native-audio-dialog",
                "gemini-2.5-flash-native-audio-preview-12-2025" -> NATIVE_AUDIO_2_5_DEC_MODEL.id

                "gemini-2.5-flash-native-audio-preview-09-2025" -> NATIVE_AUDIO_2_5_SEP_MODEL.id

                else -> if (trimmed.startsWith("models/")) trimmed else "models/$trimmed"
            }
        }

        fun findByIdOrCustom(modelId: String): GeminiLiveModel {
            val normalized = normalizeModelId(modelId)
            return VERIFIED_LIVE_MODELS.find {
                it.id.equals(normalized, ignoreCase = true)
            } ?: GeminiLiveModel(
                id = normalized,
                displayName = "Custom: ${normalized.removePrefix("models/")}",
                roleBadge = "Unverified / Custom Model ID",
                quotaSummary = "Verify support via Validate button (GET /v1beta/models)",
                description = "User-specified model identifier. Must support bidiGenerateContent in v1beta.",
                supportsNativeAudio = true,
                supportsVideoFrames = true,
                isVerifiedLiveModel = false
            )
        }
    }
}

enum class GeminiVoice(val voiceName: String, val description: String) {
    PUCK("Puck", "Upbeat, clear conversational assistant"),
    CHARON("Charon", "Calm, steady analytical tone"),
    KORE("Kore", "Warm, natural instructional voice"),
    FENRIR("Fenrir", "Deep, crisp technical voice"),
    AOEDE("Aoede", "Expressive, friendly companion")
}

data class AppSettings(
    val selectedModelId: String = GeminiLiveModel.DEFAULT_MODEL.id,
    val fallbackModelId: String = GeminiLiveModel.FALLBACK_MODEL.id,
    val selectedVoiceName: String = GeminiVoice.AOEDE.voiceName,
    val responseModalities: List<String> = listOf("AUDIO"),
    val preferredLanguage: String = "Auto-detect (Match user language)",
    val customSystemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,
    val frameRateFps: Int = 1,
    val maxFrameDimensionPx: Int = 1280,
    val jpegQuality: Int = 70,
    val themePreference: ThemePreference = ThemePreference.DARK,
    val dynamicColorEnabled: Boolean = false,
    val hapticFeedbackEnabled: Boolean = true,
    val soundEffectsEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val defaultLandingScreen: String = "home",
    val hasAcceptedPrivacyNotice: Boolean = false,
    val lastSessionSummary: String = "No session recorded yet",
    val agentMode: AgentMode = AgentMode.STANDARD_AGENT,
    val agentToolsEnabled: Boolean = true,
    val maxTaskSteps: Int = 12,
    val maxActionRetries: Int = 2,
    val agentToolTimeoutSec: Int = 12,
    val autoCheckpointRecoveryEnabled: Boolean = true,
    val backgroundAutomationEnabled: Boolean = true,
    val voiceInputEnabled: Boolean = true,
    val voiceOutputEnabled: Boolean = true,
    val ttsSpeechRate: Float = 0.95f,
    val voiceOutputVolume: Float = 1.0f,
    val bargeInInterruptEnabled: Boolean = true,
    val lastAgentTaskSummary: String = "No agent task recorded yet",
    val memoryEnabled: Boolean = true,
    val defaultMemoryRetention: String = "NEVER",
    val autoMemoryProposalsEnabled: Boolean = true,
    val autoSkillProposalsEnabled: Boolean = true,
    val proactiveSuggestionsEnabled: Boolean = true,
    val taskNotificationsEnabled: Boolean = true,
    val completionNotificationsEnabled: Boolean = true,
    val failureNotificationsEnabled: Boolean = true,
    val scheduledTaskNotificationsEnabled: Boolean = true,
    val notificationVibrationEnabled: Boolean = true
) {
    companion object {
        const val DEFAULT_SYSTEM_INSTRUCTION = """You are ScreenSight AI, a real-time visual assistant.

You can receive screenshots or video frames of the user's shared Android screen and audio or text messages from the user.

Your responsibilities:

1. Understand the visible screen.
2. Answer questions based on what is actually visible.
3. Explain app interfaces.
4. Help users navigate settings.
5. Troubleshoot visible errors.
6. Explain buttons, menus, and UI elements.
7. Provide clear step-by-step instructions.
8. Remember relevant context within the current session.
9. Speak naturally and concisely.
10. Ask for clarification when the screen is unclear.

Important rules:

- Never pretend to see something that is not visible.
- Do not invent buttons, settings, or app content.
- Clearly distinguish observations from assumptions.
- If a screen changes, use the latest available visual context.
- Never claim that you clicked or changed something unless a supported action actually occurred.
- Do not request passwords, OTPs, recovery codes, or other secrets.
- Warn users when sensitive information is visible.
- Never expose private information unnecessarily.
- Respond in the language used by the user.
- Use simple, natural spoken language.
- Prefer concise instructions over lengthy explanations."""
    }
}

enum class MessageSender {
    USER,
    GEMINI,
    SYSTEM
}

data class TranscriptMessage(
    val id: String,
    val sender: MessageSender,
    val text: String,
    val timestampMillis: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val hasScreenContext: Boolean = false
)

/**
 * Centralized error hierarchy covering all API, Android, and Network scenarios (Section 12).
 */
sealed class ScreenSightError(
    val title: String,
    val userMessage: String,
    val isRecoverable: Boolean = true
) {
    // API Errors
    object MissingApiKey : ScreenSightError(
        title = "Missing API Key",
        userMessage = "Please enter your Google Gemini API key in API Settings before starting a live session."
    )

    object InvalidApiKey : ScreenSightError(
        title = "Invalid API Key",
        userMessage = "Your Gemini API key is invalid or unauthorized. Please check your key in API Settings."
    )

    object ApiPermissionError : ScreenSightError(
        title = "API Permission Denied",
        userMessage = "Your API key does not have permission to access the selected Gemini Live model."
    )

    data class UnsupportedModel(val modelId: String) : ScreenSightError(
        title = "Unsupported Model",
        userMessage = "Your selected model ($modelId) was not found or does not support Gemini Live API (BidiGenerateContent). Open API Settings and tap Validate to view supported models on your key."
    )

    object QuotaExceeded : ScreenSightError(
        title = "API Quota Reached",
        userMessage = "Your API quota has been reached (RESOURCE_EXHAUSTED). Check your Google AI Studio rate limits or wait before starting a new session."
    )

    object RateLimitExceeded : ScreenSightError(
        title = "Rate Limit Exceeded",
        userMessage = "Too many requests or tokens sent in a short window. Please wait a moment and try again."
    )

    object BillingRestriction : ScreenSightError(
        title = "Billing or Tier Restriction",
        userMessage = "This model requires an eligible Google AI Studio billing tier or region."
    )

    object ServerUnavailable : ScreenSightError(
        title = "Gemini Server Unavailable",
        userMessage = "Google Gemini Live service is temporarily unavailable. Please try again shortly."
    )

    data class WebSocketFailure(val detail: String) : ScreenSightError(
        title = "Live Connection Error",
        userMessage = detail
    )

    // Android Errors
    object MicrophonePermissionDenied : ScreenSightError(
        title = "Microphone Permission Denied",
        userMessage = "Microphone access is required for two-way voice conversation with Gemini Live."
    )

    object ScreenCapturePermissionDenied : ScreenSightError(
        title = "Screen Sharing Cancelled",
        userMessage = "Screen capture permission was not granted. You can continue with voice or tap Share Screen again."
    )

    object MediaProjectionRevoked : ScreenSightError(
        title = "Screen Sharing Stopped",
        userMessage = "Screen sharing was stopped by Android or revoked from the system status bar."
    )

    data class ForegroundServiceFailure(val reason: String) : ScreenSightError(
        title = "Foreground Service Error",
        userMessage = "Unable to start the screen capture foreground service: $reason"
    )

    object AudioRecordFailure : ScreenSightError(
        title = "Microphone Initialization Failed",
        userMessage = "Could not initialize Android AudioRecord (16 kHz PCM). Another app may be using the microphone."
    )

    object AudioTrackFailure : ScreenSightError(
        title = "Audio Output Failed",
        userMessage = "Could not initialize Android AudioTrack (24 kHz PCM) for AI voice output."
    )

    object BackgroundRestriction : ScreenSightError(
        title = "Background Restriction Active",
        userMessage = "Android restricted background operation. Keep ScreenSight AI foreground notification active."
    )

    // Network Errors
    object InternetUnavailable : ScreenSightError(
        title = "No Internet Connection",
        userMessage = "No active internet connection detected. Please check your Wi-Fi or mobile data."
    )

    object ConnectionTimeout : ScreenSightError(
        title = "Connection Timed Out",
        userMessage = "Timed out waiting for Gemini Live setupComplete handshake. Check your network connection."
    )

    object ConnectionLost : ScreenSightError(
        title = "Connection Lost",
        userMessage = "The live connection to Gemini was interrupted and could not be restored automatically."
    )

    data class ServerClosedSession(val reason: String) : ScreenSightError(
        title = "Session Closed by Server",
        userMessage = if (reason.isNotBlank()) "Gemini closed the live session: $reason" else "Gemini closed the live session."
    )

    object SessionExpired : ScreenSightError(
        title = "Session Expired",
        userMessage = "Maximum Gemini Live session duration reached. Start a new session to continue."
    )

    companion object {
        fun fromHttpOrWebSocketError(code: Int?, message: String?, selectedModel: String): ScreenSightError {
            val rawMessage = message?.trim().orEmpty()
            val msg = rawMessage.lowercase()
            return when {
                msg.contains("api_key_invalid") || msg.contains("api key not valid") || msg.contains("unauthenticated") || code == 401 ->
                    InvalidApiKey
                msg.contains("not found") || msg.contains("not supported for bidigeneratecontent") || msg.contains("unsupported model") || msg.contains("is not found for api version") || code == 404 ->
                    UnsupportedModel(selectedModel)
                msg.contains("resource_exhausted") || msg.contains("quota") || msg.contains("rate limit") || msg.contains("too many requests") || code == 429 ->
                    if (msg.contains("rate") || msg.contains("too many requests")) RateLimitExceeded else QuotaExceeded
                code == 403 || msg.contains("permission_denied") || msg.contains("forbidden") || msg.contains("caller does not have permission") ->
                    if (msg.contains("billing")) BillingRestriction else ApiPermissionError
                code == 400 && msg.contains("invalid argument") && !msg.contains("model") ->
                    WebSocketFailure(rawMessage.ifBlank { "Invalid configuration argument rejected by Gemini Live API." })
                code in 500..599 || code == 1011 && (msg.contains("internal") || msg.contains("unavailable")) || msg.contains("service unavailable") ->
                    ServerUnavailable
                msg.contains("timeout") || msg.contains("timed out") ->
                    ConnectionTimeout
                msg.contains("unable to resolve host") || msg.contains("network is unreachable") || msg.contains("no address associated") || msg.contains("failed to connect to") ->
                    InternetUnavailable
                else -> WebSocketFailure(
                    rawMessage.ifBlank { "Failed to establish WebSocket session with Gemini Live API." }
                )
            }
        }
    }
}
