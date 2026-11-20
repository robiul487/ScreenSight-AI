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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.agent.AgentPermissionsSnapshot
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.ui.LocalStorageSnapshot
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyState
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
    AGENT_BEHAVIOR("agent_behavior", "Agent Behavior", "Autonomy level, execution limits, checkpoints & safety guardrails"),
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

private data class SettingsHubEntry(
    val groupIndex: Int,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val keywords: String,
    val badgeText: String? = null,
    val badgeTone: StatusTone = StatusTone.NEUTRAL,
    val iconTint: Color? = null,
    val testTag: String,
    val onClick: () -> Unit
)

/**
 * Phase 6+ Professional Settings Hub organizing all 12 categories (A–L) into clean Material 3 groups
 * with instant keyword search across every setting and sub-feature.
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
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val allEntries = listOf(
        // Group 0: Experience & Workspace
        SettingsHubEntry(
            groupIndex = 0,
            icon = Icons.Default.Tune,
            title = "General",
            subtitle = "${settings.themePreference.label} • ${settings.preferredLanguage.substringBefore(" ")}",
            keywords = "theme dark light system dynamic color material you language bangla english haptics sound animations landing home",
            badgeText = settings.themePreference.label.substringBefore(" "),
            badgeTone = StatusTone.INFO,
            testTag = "settings_cat_general",
            onClick = { onOpenCategory(SettingsCategory.GENERAL) }
        ),
        SettingsHubEntry(
            groupIndex = 0,
            icon = Icons.Default.GraphicEq,
            title = "Voice & Audio",
            subtitle = "Voice ${settings.selectedVoiceName} • Speed ${"%.1fx".format(settings.ttsSpeechRate)}",
            keywords = "voice audio microphone mic tts speech speed volume barge-in interrupt aoede puck charon kore fenrir",
            badgeText = if (settings.voiceInputEnabled && settings.voiceOutputEnabled) "On" else "Custom",
            badgeTone = StatusTone.POSITIVE,
            testTag = "settings_cat_voice_audio",
            onClick = { onOpenCategory(SettingsCategory.VOICE_AUDIO) }
        ),
        SettingsHubEntry(
            groupIndex = 0,
            icon = Icons.Default.PresentToAll,
            title = "Screen Awareness",
            subtitle = "${settings.frameRateFps} FPS • ${settings.maxFrameDimensionPx}px • Sensitive screens protected",
            keywords = "screen share capture fps resolution jpeg quality mediaprojection password otp sensitive protection",
            badgeText = if (isSharingScreen) "Live" else "Ready",
            badgeTone = if (isSharingScreen) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            testTag = "settings_cat_screen_awareness",
            onClick = { onOpenCategory(SettingsCategory.SCREEN_AWARENESS) }
        ),
        SettingsHubEntry(
            groupIndex = 0,
            icon = Icons.Default.Notifications,
            title = "Notifications",
            subtitle = if (settings.taskNotificationsEnabled) "Task, completion & schedule notifications active" else "Task notifications paused",
            keywords = "notifications alerts proactive suggestions vibration schedule completion failure",
            badgeText = if (settings.taskNotificationsEnabled) "On" else "Off",
            badgeTone = if (settings.taskNotificationsEnabled) StatusTone.POSITIVE else StatusTone.NEUTRAL,
            testTag = "settings_cat_notifications",
            onClick = { onOpenCategory(SettingsCategory.NOTIFICATIONS) }
        ),
        // Group 1: AI & Autonomous Engine
        SettingsHubEntry(
            groupIndex = 1,
            icon = Icons.Default.Key,
            title = "AI & Model Settings",
            subtitle = "${activeModel.displayName} • Fallback & BYOK key",
            keywords = "api key gemini model live byok fallback custom validate connection",
            badgeText = if (hasApiKey) "Ready" else "Setup",
            badgeTone = if (hasApiKey) StatusTone.POSITIVE else StatusTone.WARNING,
            testTag = "settings_cat_ai_models",
            onClick = onOpenApiSettings
        ),
        SettingsHubEntry(
            groupIndex = 1,
            icon = Icons.Default.SmartToy,
            title = "Agent Behavior",
            subtitle = "${settings.autonomyLevel.shortLabel} • Max ${settings.maxTaskSteps} steps • ${settings.maxActionRetries} retries",
            keywords = "autonomy ask first balanced family easy mode standard agent steps retries timeout checkpoint recovery tool calling",
            badgeText = if (settings.agentToolsEnabled) "Tools On" else "Chat Only",
            badgeTone = if (settings.agentToolsEnabled) StatusTone.INFO else StatusTone.NEUTRAL,
            testTag = "settings_cat_agent_behavior",
            onClick = { onOpenCategory(SettingsCategory.AGENT_BEHAVIOR) }
        ),
        SettingsHubEntry(
            groupIndex = 1,
            icon = Icons.Default.Psychology,
            title = "Memory & Privacy",
            subtitle = if (settings.memoryEnabled) "${storageSnapshot.memoryCount} local memories • Retention: ${settings.defaultMemoryRetention}" else "Local memory disabled",
            keywords = "memory privacy room retention expire proposals clear local personal",
            badgeText = if (settings.memoryEnabled) "On" else "Off",
            badgeTone = if (settings.memoryEnabled) StatusTone.POSITIVE else StatusTone.WARNING,
            testTag = "settings_cat_memory_privacy",
            onClick = { onOpenCategory(SettingsCategory.MEMORY_PRIVACY) }
        ),
        SettingsHubEntry(
            groupIndex = 1,
            icon = Icons.Default.AutoAwesome,
            title = "Skills & Automation",
            subtitle = "${storageSnapshot.skillCount} skills • ${storageSnapshot.scheduleCount} schedules • Background ${if (settings.backgroundAutomationEnabled) "On" else "Off"}",
            keywords = "skills automation workflows proposals workmanager background schedule routines tools reflection",
            badgeText = "${storageSnapshot.skillCount} Skills",
            badgeTone = StatusTone.INFO,
            testTag = "settings_cat_skills_automation",
            onClick = { onOpenCategory(SettingsCategory.SKILLS_AUTOMATION) }
        ),
        // Group 2: System, Data & Trust
        SettingsHubEntry(
            groupIndex = 2,
            icon = Icons.Default.Shield,
            title = "Permissions & Device Access",
            subtitle = "Accessibility, Microphone, Overlay, Notifications & Contacts",
            keywords = "permissions accessibility control overlay floating pill bubble microphone contacts battery optimization",
            badgeText = "${permissions.familyModeReadinessCount}/${permissions.familyModeReadinessTotal}",
            badgeTone = if (permissions.isAgentControlReady && permissions.hasMicrophone) StatusTone.POSITIVE else StatusTone.WARNING,
            iconTint = if (permissions.isAgentControlReady) EmeraldLive else MaterialTheme.colorScheme.primary,
            testTag = "settings_cat_permissions",
            onClick = onOpenPermissions
        ),
        SettingsHubEntry(
            groupIndex = 2,
            icon = Icons.Default.Storage,
            title = "Storage & Data",
            subtitle = "Room DB ${storageSnapshot.databaseSizeBytes / 1024} KB • Cache ${storageSnapshot.cacheSizeBytes / 1024} KB • Export & Cleanup",
            keywords = "storage data room database cache clear export backup reset history",
            testTag = "settings_cat_storage_data",
            onClick = { onOpenCategory(SettingsCategory.STORAGE_DATA) }
        ),
        SettingsHubEntry(
            groupIndex = 2,
            icon = Icons.Default.Lock,
            title = "Security",
            subtitle = "Android Keystore encryption, mandatory confirmations & safety log",
            keywords = "security keystore encryption banking otp payment safety confirmation guardrails audit log",
            badgeText = "Protected",
            badgeTone = StatusTone.POSITIVE,
            testTag = "settings_cat_security",
            onClick = { onOpenCategory(SettingsCategory.SECURITY) }
        ),
        SettingsHubEntry(
            groupIndex = 2,
            icon = Icons.Default.Info,
            title = "About & Support",
            subtitle = "ScreenSight AI v${BuildConfig.VERSION_NAME} • Open-source licenses & diagnostics",
            keywords = "about support version licenses open source privacy policy diagnostics copy",
            testTag = "settings_cat_about_support",
            onClick = { onOpenCategory(SettingsCategory.ABOUT_SUPPORT) }
        )
    )

    val trimmedQuery = searchQuery.trim()
    val filteredEntries = remember(trimmedQuery, allEntries) {
        if (trimmedQuery.isEmpty()) {
            allEntries
        } else {
            allEntries.filter { entry ->
                entry.title.contains(trimmedQuery, ignoreCase = true) ||
                    entry.subtitle.contains(trimmedQuery, ignoreCase = true) ||
                    entry.keywords.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }

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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusSnackbarBanner(
                    message = feedbackMessage,
                    onDismiss = onDismissFeedback,
                    testTag = "settings_hub_feedback_banner"
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_search_input"),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.testTag("settings_search_clear_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    placeholder = { Text("Search settings (e.g., autonomy, voice, battery, cache)...") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )

                if (trimmedQuery.isNotEmpty()) {
                    if (filteredEntries.isEmpty()) {
                        EmptyState(
                            icon = Icons.Default.Search,
                            title = "No matching settings",
                            subtitle = "Try searching for theme, voice, API key, autonomy, overlay, cache, or security."
                        )
                    } else {
                        SettingsGroup(
                            title = "Matching Settings (${filteredEntries.size})",
                            subtitle = "Filtered results for \"$trimmedQuery\""
                        ) {
                            filteredEntries.forEachIndexed { idx, entry ->
                                SettingsItem(
                                    icon = entry.icon,
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    badgeText = entry.badgeText,
                                    badgeTone = entry.badgeTone,
                                    iconTint = entry.iconTint ?: MaterialTheme.colorScheme.primary,
                                    onClick = entry.onClick,
                                    showDivider = idx < filteredEntries.lastIndex,
                                    modifier = Modifier.testTag(entry.testTag)
                                )
                            }
                        }
                    }
                } else {
                    val groups = listOf(
                        Triple(0, "Experience & Workspace", "Interface, voice, screen awareness, and alerts"),
                        Triple(1, "AI & Autonomous Engine", "Model provider, autonomy level, local memory, and skills"),
                        Triple(2, "System, Data & Trust", "Android permissions, storage, security audit, and app info")
                    )
                    groups.forEach { (groupIdx, groupTitle, groupSubtitle) ->
                        val groupEntries = allEntries.filter { it.groupIndex == groupIdx }
                        SettingsGroup(
                            title = groupTitle,
                            subtitle = groupSubtitle
                        ) {
                            groupEntries.forEachIndexed { idx, entry ->
                                SettingsItem(
                                    icon = entry.icon,
                                    title = entry.title,
                                    subtitle = entry.subtitle,
                                    badgeText = entry.badgeText,
                                    badgeTone = entry.badgeTone,
                                    iconTint = entry.iconTint ?: MaterialTheme.colorScheme.primary,
                                    onClick = entry.onClick,
                                    showDivider = idx < groupEntries.lastIndex,
                                    modifier = Modifier.testTag(entry.testTag)
                                )
                            }
                        }
                    }
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
