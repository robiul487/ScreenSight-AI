package com.example.assistant

/**
 * Real Android system state of the Digital Assistant role (`RoleManager.ROLE_ASSISTANT` /
 * active `VoiceInteractionService`). Never inferred from a button tap alone.
 */
enum class DefaultAssistantRoleStatus(
    val label: String,
    val summary: String,
    val isActive: Boolean
) {
    ACTIVE(
        label = "Active Default Assistant",
        summary = "Android reports Neya (ScreenSight AI) as the active system digital assistant.",
        isActive = true
    ),
    NOT_SELECTED(
        label = "Not Selected",
        summary = "Neya is registered as a system assistant candidate, but another assistant or None is currently selected.",
        isActive = false
    ),
    UNSUPPORTED_DEVICE_OR_API(
        label = "Role / API Unsupported",
        summary = "This Android build or OEM profile does not expose the Digital Assistant role chooser directly. Use system Default Digital Assistant settings.",
        isActive = false
    ),
    SELECTION_CANCELLED(
        label = "Selection Cancelled",
        summary = "The system assistant chooser was dismissed without selecting Neya.",
        isActive = false
    ),
    UNVERIFIED(
        label = "Could Not Verify",
        summary = "Android did not return a verifiable assistant role state on this device.",
        isActive = false
    )
}

/**
 * Wake detection architecture mode for "Hey Neya".
 * Manual invocation is always supported; hardware DSP and software wake-word modes
 * are honestly capability-gated rather than simulated.
 */
enum class WakeDetectionMode(
    val label: String,
    val shortLabel: String,
    val description: String
) {
    MANUAL_ONLY(
        label = "Manual System Assistant Only",
        shortLabel = "Manual",
        description = "Invoke Neya via power/home long-press, corner swipe, assistant shortcut, or in-app controls. Zero background battery or microphone drain."
    ),
    DSP_HARDWARE(
        label = "Hardware DSP Hotword (OEM Privileged)",
        shortLabel = "DSP Hardware",
        description = "Low-power AlwaysOnHotwordDetector / SoundTrigger DSP. Restricted by Android to privileged preinstalled system assistants."
    ),
    SOFTWARE_FALLBACK(
        label = "On-Device Software Wake Model",
        shortLabel = "On-Device Model",
        description = "Requires a bundled, verified offline neural wake-word model for \"Hey Neya\" (not bundled in this build; no cloud streaming)."
    )
}

enum class WakeCapabilityStatus(val shortLabel: String) {
    AVAILABLE("Available"),
    REQUIRES_PRIVILEGED_DSP("Unavailable (OEM Privileged Only)"),
    MODEL_NOT_BUNDLED("Unavailable (No Bundled Wake Model)"),
    PERMISSION_REQUIRED("Microphone Permission Required"),
    MIC_PRIVACY_BLOCKED("Blocked by Android Mic Privacy"),
    UNSUPPORTED("Unsupported")
}

/**
 * Real runtime audit of "Hey Neya" wake-word feasibility on the current device.
 */
data class WakeWordCapabilityReport(
    val androidSdkInt: Int = android.os.Build.VERSION.SDK_INT,
    val isRoleManagerSupported: Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
    val isDefaultAssistantHeld: Boolean = false,
    val isVoiceInteractionServiceActive: Boolean = false,
    val hasRecordAudioPermission: Boolean = false,
    val isMicHardwarePrivacyBlocked: Boolean = false,
    val hasPrivilegedHotwordPermission: Boolean = false,
    val dspStatus: WakeCapabilityStatus = WakeCapabilityStatus.REQUIRES_PRIVILEGED_DSP,
    val dspReason: String = "Android restricts AlwaysOnHotwordDetector / SoundTrigger DSP (CAPTURE_AUDIO_HOTWORD, MANAGE_VOICE_KEYPHRASES) to privileged preinstalled system apps.",
    val softwareFallbackStatus: WakeCapabilityStatus = WakeCapabilityStatus.MODEL_NOT_BUNDLED,
    val softwareFallbackReason: String = "No offline neural \"Hey Neya\" wake-word model is bundled in this APK. Continuous cloud audio streaming and permanent background mic services are disabled by policy.",
    val effectiveActiveMode: WakeDetectionMode = WakeDetectionMode.MANUAL_ONLY,
    val isRealWakeDetectorAvailable: Boolean = false,
    val isCurrentlyListeningForWakeWord: Boolean = false,
    val batteryPrivacySummary: String = "Zero background microphone polling. Neya never streams audio to cloud APIs before you explicitly invoke it."
)

enum class AssistantInvocationSource(val label: String) {
    VOICE_INTERACTION_SESSION("System Voice Interaction"),
    ASSIST_INTENT("Android Assist Intent"),
    MANUAL_IN_APP("Manual In-App Trigger"),
    FLOATING_OVERLAY("Floating Agent Overlay")
}

sealed class AssistantCommandResult {
    data class SentToLiveSession(val command: String) : AssistantCommandResult()
    data class AnsweredDirectly(val answer: String, val modelBadge: String) : AssistantCommandResult()
    data class ExecutedTask(val taskId: String, val summary: String) : AssistantCommandResult()
    data class StartedQuickAction(val summary: String) : AssistantCommandResult()
    data class BlockedOrFailed(val reason: String) : AssistantCommandResult()
    object EmptyInput : AssistantCommandResult()
}

data class NeyaAssistantRuntimeState(
    val roleStatus: DefaultAssistantRoleStatus = DefaultAssistantRoleStatus.NOT_SELECTED,
    val isVoiceInteractionServiceConnected: Boolean = false,
    val isSessionShowing: Boolean = false,
    val lastInvocationSource: AssistantInvocationSource? = null,
    val lastInvocationTimeMillis: Long = 0L,
    val lastStatusNote: String = "Ready for system or manual assistant invocation.",
    val lastCommandSummary: String? = null,
    val capabilityReport: WakeWordCapabilityReport = WakeWordCapabilityReport()
)
