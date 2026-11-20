package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.agent.AgentPermissionsSnapshot
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.ui.LocalStorageSnapshot
import com.example.ui.components.AppTopBar
import com.example.ui.components.SettingsGroup
import com.example.ui.components.SettingsItem
import com.example.ui.components.StatusSnackbarBanner
import com.example.ui.components.StatusTone
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.ScreenSightSpacing

/**
 * Complete 12-Category Settings Architecture (Phase 6, Categories A–L).
 */
enum class SettingsCategory(
    val routeArg: String,
    val title: String,
    val subtitle: String
) {
    GENERAL("general", "General", "Language, theme, haptics, animations & landing screen"),
    VOICE_AUDIO("voice_audio", "Voice & Audio", "Voice I/O, Gemini voice profile, speech speed & barge-in"),
    SCREEN_AWARENESS("screen_awareness", "Screen Awareness", "Screen sharing, capture quality & sensitive-screen protection"),
    AGENT_BEHAVIOR("agent_behavior", "Agent Behavior", "Operating mode, execution limits, checkpoints & safety guardrails"),
    MEMORY_PRIVACY("memory_privacy", "Memory & Privacy", "On-device memory master toggle, retention policy & cleanup"),
    SKILLS_AUTOMATION("skills_automation", "Skills & Automation", "Skill proposals, background routines, schedules & tools"),
    NOTIFICATIONS("notifications", "Notifications", "Task completion, failure alerts, schedules & vibration"),
    STORAGE_DATA("storage_data", "Storage & Data", "Local database stats, cache cleanup, export & reset"),
    SECURITY("security", "Security", "API key storage protection, safety confirmations & event log"),
    ABOUT_SUPPORT("about_support", "About & Support", "Version info, open-source licenses, privacy & diagnostics");

    companion object {
        fun fromRouteArg(arg: String?): SettingsCategory =
            entries.firstOrNull { it.routeArg.equals(arg, ignoreCase = true) } ?: GENERAL
    }
}

/**
 * Phase 6 Professional Settings Hub organizing all 12 categories (A–L) into clean Material 3 groups.
 */
@Composable
fun SettingsHubScreen(
    settings: AppSettings,
    hasApiKey: Boolean,
    isSharingScreen: Boolean,
    permissions: AgentPermissionsSnapshot,
    storageSnapshot: LocalStorageSnapshot,
    feedbackMessage: String?,
    onDismissFeedback: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenApiSettings: () -> Unit,
    onOpenPermissions: () -> Unit
) {
    val activeModel = GeminiLiveModel.findByIdOrCustom(settings.selectedModelId)

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            AppTopBar(
                title = "Settings",
                subtitle = "12 categories • On-device controls",
                onOpenDrawer = onOpenDrawer
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = ScreenSightSpacing.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("settings_hub_screen"),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                StatusSnackbarBanner(
                    message = feedbackMessage,
                    onDismiss = onDismissFeedback,
                    testTag = "settings_hub_feedback_banner"
                )

                // Group 1: Experience & Workspace (A, C, D, I)
                SettingsGroup(
                    title = "Experience & Workspace",
                    subtitle = "Interface, voice, screen awareness, and alerts"
                ) {
                    SettingsItem(
                        icon = Icons.Default.Tune,
                        title = "General",
                        subtitle = "${settings.themePreference.label} • ${settings.preferredLanguage.substringBefore(" ")}",
                        badgeText = settings.themePreference.label.substringBefore(" "),
                        badgeTone = StatusTone.INFO,
                        onClick = { onOpenCategory(SettingsCategory.GENERAL) },
                        modifier = Modifier.testTag("settings_cat_general")
                    )
                    SettingsItem(
                        icon = Icons.Default.GraphicEq,
                        title = "Voice & Audio",
                        subtitle = "Voice ${settings.selectedVoiceName} • Speed ${"%.1fx".format(settings.ttsSpeechRate)}",
                        badgeText = if (settings.voiceInputEnabled && settings.voiceOutputEnabled) "On" else "Custom",
                        badgeTone = StatusTone.POSITIVE,
                        onClick = { onOpenCategory(SettingsCategory.VOICE_AUDIO) },
                        modifier = Modifier.testTag("settings_cat_voice_audio")
                    )
                    SettingsItem(
                        icon = Icons.Default.PresentToAll,
                        title = "Screen Awareness",
                        subtitle = "${settings.frameRateFps} FPS • ${settings.maxFrameDimensionPx}px • Sensitive screens protected",
                        badgeText = if (isSharingScreen) "Live" else "Ready",
                        badgeTone = if (isSharingScreen) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                        onClick = { onOpenCategory(SettingsCategory.SCREEN_AWARENESS) },
                        modifier = Modifier.testTag("settings_cat_screen_awareness")
                    )
                    SettingsItem(
                        icon = Icons.Default.Notifications,
                        title = "Notifications",
                        subtitle = if (settings.taskNotificationsEnabled) "Task, completion & schedule notifications active" else "Task notifications paused",
                        badgeText = if (settings.taskNotificationsEnabled) "On" else "Off",
                        badgeTone = if (settings.taskNotificationsEnabled) StatusTone.POSITIVE else StatusTone.NEUTRAL,
                        onClick = { onOpenCategory(SettingsCategory.NOTIFICATIONS) },
                        showDivider = false,
                        modifier = Modifier.testTag("settings_cat_notifications")
                    )
                }

                // Group 2: AI & Autonomous Engine (B, E, F, G)
                SettingsGroup(
                    title = "AI & Autonomous Engine",
                    subtitle = "Model provider, execution limits, local memory, and skills"
                ) {
                    SettingsItem(
                        icon = Icons.Default.Key,
                        title = "AI & Model Settings",
                        subtitle = "${activeModel.displayName} • Fallback & BYOK key",
                        badgeText = if (hasApiKey) "Ready" else "Setup",
                        badgeTone = if (hasApiKey) StatusTone.POSITIVE else StatusTone.WARNING,
                        onClick = onOpenApiSettings,
                        modifier = Modifier.testTag("settings_cat_ai_models")
                    )
                    SettingsItem(
                        icon = Icons.Default.SmartToy,
                        title = "Agent Behavior",
                        subtitle = "${settings.agentMode.labelEn} • Max ${settings.maxTaskSteps} steps • ${settings.maxActionRetries} retries",
                        badgeText = if (settings.agentToolsEnabled) "Tools On" else "Chat Only",
                        badgeTone = if (settings.agentToolsEnabled) StatusTone.INFO else StatusTone.NEUTRAL,
                        onClick = { onOpenCategory(SettingsCategory.AGENT_BEHAVIOR) },
                        modifier = Modifier.testTag("settings_cat_agent_behavior")
                    )
                    SettingsItem(
                        icon = Icons.Default.Psychology,
                        title = "Memory & Privacy",
                        subtitle = if (settings.memoryEnabled) "${storageSnapshot.memoryCount} local memories • Retention: ${settings.defaultMemoryRetention}" else "Local memory disabled",
                        badgeText = if (settings.memoryEnabled) "On" else "Off",
                        badgeTone = if (settings.memoryEnabled) StatusTone.POSITIVE else StatusTone.WARNING,
                        onClick = { onOpenCategory(SettingsCategory.MEMORY_PRIVACY) },
                        modifier = Modifier.testTag("settings_cat_memory_privacy")
                    )
                    SettingsItem(
                        icon = Icons.Default.AutoAwesome,
                        title = "Skills & Automation",
                        subtitle = "${storageSnapshot.skillCount} skills • ${storageSnapshot.scheduleCount} schedules • Background ${if (settings.backgroundAutomationEnabled) "On" else "Off"}",
                        badgeText = "${storageSnapshot.skillCount} Skills",
                        badgeTone = StatusTone.INFO,
                        onClick = { onOpenCategory(SettingsCategory.SKILLS_AUTOMATION) },
                        showDivider = false,
                        modifier = Modifier.testTag("settings_cat_skills_automation")
                    )
                }

                // Group 3: Permissions, Data, Security & Info (H, J, K, L)
                SettingsGroup(
                    title = "System, Data & Trust",
                    subtitle = "Android permissions, storage, security audit, and app info"
                ) {
                    SettingsItem(
                        icon = Icons.Default.Shield,
                        title = "Permissions & Device Access",
                        subtitle = "Accessibility, Microphone, Overlay, Notifications & Contacts",
                        badgeText = "${permissions.familyModeReadinessCount}/${permissions.familyModeReadinessTotal}",
                        badgeTone = if (permissions.isAgentControlReady && permissions.hasMicrophone) StatusTone.POSITIVE else StatusTone.WARNING,
                        iconTint = if (permissions.isAgentControlReady) EmeraldLive else MaterialTheme.colorScheme.primary,
                        onClick = onOpenPermissions,
                        modifier = Modifier.testTag("settings_cat_permissions")
                    )
                    SettingsItem(
                        icon = Icons.Default.Storage,
                        title = "Storage & Data",
                        subtitle = "Room DB ${storageSnapshot.databaseSizeBytes / 1024} KB • Cache ${storageSnapshot.cacheSizeBytes / 1024} KB • Export & Cleanup",
                        onClick = { onOpenCategory(SettingsCategory.STORAGE_DATA) },
                        modifier = Modifier.testTag("settings_cat_storage_data")
                    )
                    SettingsItem(
                        icon = Icons.Default.Lock,
                        title = "Security",
                        subtitle = "Android Keystore encryption, mandatory confirmations & safety log",
                        badgeText = "Protected",
                        badgeTone = StatusTone.POSITIVE,
                        onClick = { onOpenCategory(SettingsCategory.SECURITY) },
                        modifier = Modifier.testTag("settings_cat_security")
                    )
                    SettingsItem(
                        icon = Icons.Default.Info,
                        title = "About & Support",
                        subtitle = "ScreenSight AI v${BuildConfig.VERSION_NAME} • Open-source licenses & diagnostics",
                        onClick = { onOpenCategory(SettingsCategory.ABOUT_SUPPORT) },
                        showDivider = false,
                        modifier = Modifier.testTag("settings_cat_about_support")
                    )
                }

                Text(
                    text = "All preferences are saved locally on device. Safety guardrails for passwords, banking, and payments cannot be bypassed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
