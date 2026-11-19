package com.example.ui.screens

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.agent.AgentMode
import com.example.agent.AgentTaskLogger
import com.example.data.local.ApiKeySource
import com.example.data.local.memory.MemoryExpirationPolicy
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiVoice
import com.example.domain.model.ThemePreference
import com.example.ui.LocalStorageSnapshot
import com.example.ui.components.AppConfirmationDialog
import com.example.ui.components.AppTopBar
import com.example.ui.components.FeatureCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.PrimaryNavDestination
import com.example.ui.components.SecondaryButton
import com.example.ui.components.SettingsGroup
import com.example.ui.components.SettingsItem
import com.example.ui.components.SettingsToggleItem
import com.example.ui.components.StatusIndicator
import com.example.ui.components.StatusSnackbarBanner
import com.example.ui.components.StatusTone
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ScreenSightSpacing
import kotlin.math.roundToInt

/**
 * Dedicated Material 3 detail screens for Settings Categories A, C, D, E, F, G, I, J, K, L.
 * (Category B uses ApiSettingsScreen; Category H uses AgentPermissionsScreen.)
 * Every single control is wired directly to real DataStore, Room, and runtime controllers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    settings: AppSettings,
    hasApiKey: Boolean,
    apiKeySource: ApiKeySource,
    isSharingScreen: Boolean,
    storageSnapshot: LocalStorageSnapshot,
    agentTaskLogs: List<AgentTaskLogger.TaskLogEntry>,
    feedbackMessage: String?,
    onDismissFeedback: () -> Unit,
    onBack: () -> Unit,
    // General (A)
    onSelectTheme: (ThemePreference) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleHapticFeedback: (Boolean) -> Unit,
    onToggleSoundEffects: (Boolean) -> Unit,
    onToggleAnimations: (Boolean) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectDefaultLanding: (String) -> Unit,
    onResetUiPreferences: () -> Unit,
    // Voice & Audio (C)
    onSelectVoice: (String) -> Unit,
    onUpdateVoiceAudio: (
        voiceInputEnabled: Boolean?,
        voiceOutputEnabled: Boolean?,
        ttsSpeechRate: Float?,
        voiceOutputVolume: Float?,
        bargeInInterruptEnabled: Boolean?
    ) -> Unit,
    // Screen Awareness (D)
    onUpdateFrameConfig: (fps: Int, maxDimPx: Int, jpegQuality: Int) -> Unit,
    onStartOrInspectScreenShare: () -> Unit,
    onStopScreenShare: () -> Unit,
    // Agent Behavior (E)
    onSelectAgentMode: (AgentMode) -> Unit,
    onToggleAgentTools: (Boolean) -> Unit,
    onUpdateAgentLimits: (maxSteps: Int, maxRetries: Int, timeoutSec: Int) -> Unit,
    onToggleAutoCheckpointRecovery: (Boolean) -> Unit,
    onToggleBackgroundAutomation: (Boolean) -> Unit,
    // Memory & Privacy (F)
    onToggleMemoryEnabled: (Boolean) -> Unit,
    onSelectMemoryRetention: (String) -> Unit,
    onToggleAutoMemoryProposals: (Boolean) -> Unit,
    onClearAllMemories: () -> Unit,
    onOpenLibrary: () -> Unit,
    // Skills & Automation (G)
    onToggleAutoSkillProposals: (Boolean) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenAutonomousHub: () -> Unit,
    // Notifications (I)
    onUpdateNotifications: (
        taskNotifications: Boolean?,
        completionNotifications: Boolean?,
        failureNotifications: Boolean?,
        scheduledNotifications: Boolean?,
        vibration: Boolean?
    ) -> Unit,
    onToggleProactiveSuggestions: (Boolean) -> Unit,
    // Storage & Data (J)
    onRefreshStorage: () -> Unit,
    onClearAppCache: () -> Unit,
    onDeleteTaskHistory: () -> Unit,
    onResetNonSecretLocalData: () -> Unit,
    onBuildExportText: () -> String,
    // Security (K) & About (L)
    onOpenPrivacyPolicy: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onBuildDiagnosticsText: () -> String
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showResetUiConfirm by rememberSaveable { mutableStateOf(false) }
    var showClearMemoriesConfirm by rememberSaveable { mutableStateOf(false) }
    var showDeleteHistoryConfirm by rememberSaveable { mutableStateOf(false) }
    var showResetLocalDataConfirm by rememberSaveable { mutableStateOf(false) }
    var showLicensesDialog by rememberSaveable { mutableStateOf(false) }
    var copiedDiagnosticsNotice by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(category) {
        if (category == SettingsCategory.STORAGE_DATA || category == SettingsCategory.ABOUT_SUPPORT) {
            onRefreshStorage()
        }
    }

    if (showResetUiConfirm) {
        AppConfirmationDialog(
            title = "Reset UI Preferences?",
            message = "Restores theme, dynamic color, haptics, sound effects, animations, and default landing tab to defaults. Your API key, memories, and skills are untouched.",
            confirmText = "Reset UI",
            destructive = false,
            onConfirm = {
                showResetUiConfirm = false
                onResetUiPreferences()
            },
            onDismiss = { showResetUiConfirm = false },
            confirmTestTag = "confirm_reset_ui_button"
        )
    }

    if (showClearMemoriesConfirm) {
        AppConfirmationDialog(
            title = "Clear All Local Memories?",
            message = "Permanently deletes all on-device memory records from Room storage. This cannot be undone.",
            confirmText = "Clear Memories",
            destructive = true,
            onConfirm = {
                showClearMemoriesConfirm = false
                onClearAllMemories()
            },
            onDismiss = { showClearMemoriesConfirm = false },
            confirmTestTag = "confirm_clear_memories_settings_button"
        )
    }

    if (showDeleteHistoryConfirm) {
        AppConfirmationDialog(
            title = "Delete Task History?",
            message = "Deletes completed, failed, and cancelled task records and clears in-memory execution logs.",
            confirmText = "Delete History",
            destructive = true,
            onConfirm = {
                showDeleteHistoryConfirm = false
                onDeleteTaskHistory()
            },
            onDismiss = { showDeleteHistoryConfirm = false },
            confirmTestTag = "confirm_delete_task_history_button"
        )
    }

    if (showResetLocalDataConfirm) {
        AppConfirmationDialog(
            title = "Reset Non-Secret Local Data?",
            message = "Clears all local memories, tasks, schedules, execution logs, and UI preferences. Your encrypted Gemini API key in Android Keystore is preserved.",
            confirmText = "Reset Local Data",
            destructive = true,
            onConfirm = {
                showResetLocalDataConfirm = false
                onResetNonSecretLocalData()
            },
            onDismiss = { showResetLocalDataConfirm = false },
            confirmTestTag = "confirm_reset_local_data_button"
        )
    }

    if (showLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AppTopBar(
                title = category.title,
                subtitle = category.subtitle,
                onBack = onBack,
                backTestTag = "settings_category_back_button"
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
                    .testTag("settings_category_${category.routeArg}"),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusSnackbarBanner(
                    message = if (copiedDiagnosticsNotice) "Diagnostics summary copied to clipboard (no secrets included)." else feedbackMessage,
                    onDismiss = {
                        copiedDiagnosticsNotice = false
                        onDismissFeedback()
                    },
                    testTag = "settings_category_feedback_banner"
                )

                when (category) {
                    SettingsCategory.GENERAL -> {
                        SettingsGroup(title = "Theme & Display") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("App Theme", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ThemePreference.entries.forEach { pref ->
                                        FilterChip(
                                            selected = settings.themePreference == pref,
                                            onClick = { onSelectTheme(pref) },
                                            label = { Text(pref.displayName) },
                                            modifier = Modifier.testTag("settings_theme_${pref.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            SettingsToggleItem(
                                icon = Icons.Default.Palette,
                                title = "Dynamic Color (Material You)",
                                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    "Match wallpaper accents on Android 12+"
                                } else {
                                    "Requires Android 12+ (uses custom ScreenSight palette)"
                                },
                                checked = settings.dynamicColorEnabled,
                                onCheckedChange = onToggleDynamicColor,
                                switchTestTag = "settings_dynamic_color_switch",
                                showDivider = false
                            )
                        }

                        SettingsGroup(title = "Language & Default Workspace") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Preferred Language", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                val languages = listOf(
                                    "Auto (English / Bangla)",
                                    "English",
                                    "বাংলা (Bangla)"
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    languages.forEach { lang ->
                                        FilterChip(
                                            selected = settings.preferredLanguage == lang,
                                            onClick = { onSelectLanguage(lang) },
                                            label = { Text(lang) }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Default Landing Screen", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    PrimaryNavDestination.entries.take(4).forEach { dest ->
                                        FilterChip(
                                            selected = settings.defaultLandingScreen == dest.route,
                                            onClick = { onSelectDefaultLanding(dest.route) },
                                            label = { Text(dest.label) },
                                            modifier = Modifier.testTag("settings_landing_${dest.route}")
                                        )
                                    }
                                }
                            }
                        }

                        SettingsGroup(title = "Feedback & Motion") {
                            SettingsToggleItem(
                                icon = Icons.Default.Vibration,
                                title = "Haptic Feedback",
                                subtitle = "Subtle tactile confirmation on primary actions and tab switches",
                                checked = settings.hapticFeedbackEnabled,
                                onCheckedChange = onToggleHapticFeedback,
                                switchTestTag = "settings_haptics_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.VolumeUp,
                                title = "Sound Effects",
                                subtitle = "Play gentle state cues when sessions start or complete",
                                checked = settings.soundEffectsEnabled,
                                onCheckedChange = onToggleSoundEffects,
                                switchTestTag = "settings_sound_effects_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Animation,
                                title = "Interface Animations",
                                subtitle = "Respects Android reduced-motion accessibility setting",
                                checked = settings.animationsEnabled,
                                onCheckedChange = onToggleAnimations,
                                switchTestTag = "settings_animations_switch",
                                showDivider = false
                            )
                        }

                        SecondaryButton(
                            text = "Reset UI Preferences",
                            icon = Icons.Default.RestartAlt,
                            onClick = { showResetUiConfirm = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_reset_ui_button")
                        )
                    }

                    SettingsCategory.VOICE_AUDIO -> {
                        SettingsGroup(title = "Voice Input & Output") {
                            SettingsToggleItem(
                                icon = Icons.Default.Mic,
                                title = "Voice Input (Microphone)",
                                subtitle = "Stream voice instructions during Gemini Live sessions",
                                checked = settings.voiceInputEnabled,
                                onCheckedChange = { onUpdateVoiceAudio(it, null, null, null, null) },
                                switchTestTag = "settings_voice_input_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.VolumeUp,
                                title = "Voice Output (Spoken Responses)",
                                subtitle = "Play Gemini Live audio and on-device Bangla/English voice feedback",
                                checked = settings.voiceOutputEnabled,
                                onCheckedChange = { onUpdateVoiceAudio(null, it, null, null, null) },
                                switchTestTag = "settings_voice_output_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.RecordVoiceOver,
                                title = "Interrupt AI Speaking (Barge-In)",
                                subtitle = "Pause AI speech automatically when you start talking",
                                checked = settings.bargeInInterruptEnabled,
                                onCheckedChange = { onUpdateVoiceAudio(null, null, null, null, it) },
                                switchTestTag = "settings_barge_in_switch",
                                showDivider = false
                            )
                        }

                        SettingsGroup(title = "Gemini Live Voice Profile") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    GeminiVoice.entries.forEach { voice ->
                                        FilterChip(
                                            selected = settings.selectedVoiceName.equals(voice.apiName, ignoreCase = true),
                                            onClick = { onSelectVoice(voice.apiName) },
                                            label = { Text("${voice.apiName} (${voice.tone})") }
                                        )
                                    }
                                }
                            }
                        }

                        SettingsGroup(title = "Speech Speed & Output Volume") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("TTS Speech Speed", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = "${"%.2f".format(settings.ttsSpeechRate)}x",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = settings.ttsSpeechRate,
                                    onValueChange = { onUpdateVoiceAudio(null, null, it, null, null) },
                                    valueRange = 0.6f..1.5f,
                                    steps = 8,
                                    modifier = Modifier.testTag("settings_tts_speed_slider")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Voice Output Volume", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = "${(settings.voiceOutputVolume * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Slider(
                                    value = settings.voiceOutputVolume,
                                    onValueChange = { onUpdateVoiceAudio(null, null, null, it, null) },
                                    valueRange = 0.1f..1.0f,
                                    steps = 8,
                                    modifier = Modifier.testTag("settings_voice_volume_slider")
                                )
                            }
                        }
                    }

                    SettingsCategory.SCREEN_AWARENESS -> {
                        FeatureCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Live Screen Sharing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        text = if (isSharingScreen) {
                                            "Streaming ${settings.frameCaptureFps} FPS frames to Gemini Live with active notification."
                                        } else {
                                            "Inactive. Android requires explicit MediaProjection consent each session."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                StatusIndicator(
                                    label = if (isSharingScreen) "Sharing" else "Off",
                                    tone = if (isSharingScreen) StatusTone.POSITIVE else StatusTone.NEUTRAL
                                )
                            }
                            if (isSharingScreen) {
                                SecondaryButton(
                                    text = "Stop Screen Sharing",
                                    onClick = onStopScreenShare,
                                    contentColor = CrimsonAlert,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                PrimaryButton(
                                    text = "Open Agent to Share Screen",
                                    icon = Icons.Default.PresentToAll,
                                    onClick = onStartOrInspectScreenShare,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        SettingsGroup(title = "Frame Capture Quality & Bandwidth") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Capture Frame Rate", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(1, 2).forEach { fps ->
                                        FilterChip(
                                            selected = settings.frameCaptureFps == fps,
                                            onClick = { onUpdateFrameConfig(fps, settings.frameMaxDimensionPx, settings.frameJpegQuality) },
                                            label = { Text("$fps FPS") }
                                        )
                                    }
                                }

                                Text("Max Resolution", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(720, 1024, 1280).forEach { dim ->
                                        FilterChip(
                                            selected = settings.frameMaxDimensionPx == dim,
                                            onClick = { onUpdateFrameConfig(settings.frameCaptureFps, dim, settings.frameJpegQuality) },
                                            label = { Text("${dim}px") }
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("JPEG Quality", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text("${settings.frameJpegQuality}%", color = MaterialTheme.colorScheme.primary)
                                }
                                Slider(
                                    value = settings.frameJpegQuality.toFloat(),
                                    onValueChange = {
                                        onUpdateFrameConfig(settings.frameCaptureFps, settings.frameMaxDimensionPx, it.roundToInt())
                                    },
                                    valueRange = 50f..90f,
                                    steps = 7
                                )
                            }
                        }

                        SettingsGroup(title = "Mandatory Screen Privacy") {
                            SettingsToggleItem(
                                icon = Icons.Default.Shield,
                                title = "Sensitive Screen & Password Protection",
                                subtitle = "Always ON: Password inputs, OTPs, banking, and payment screens are masked from Accessibility reading and auto-blocked.",
                                checked = true,
                                onCheckedChange = {},
                                enabled = false,
                                showDivider = false
                            )
                        }
                    }

                    SettingsCategory.AGENT_BEHAVIOR -> {
                        SettingsGroup(title = "Operating Mode & Capabilities") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Assistant Mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AgentMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = settings.agentMode == mode,
                                            onClick = { onSelectAgentMode(mode) },
                                            label = { Text(mode.displayNameEn) },
                                            modifier = Modifier.testTag("settings_mode_${mode.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            SettingsToggleItem(
                                icon = Icons.Default.SmartToy,
                                title = "Autonomous Tool Calling",
                                subtitle = "Allow Gemini Live to execute validated local Android actions",
                                checked = settings.agentToolsEnabled,
                                onCheckedChange = onToggleAgentTools,
                                switchTestTag = "settings_agent_tools_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.History,
                                title = "Auto Checkpoint Recovery",
                                subtitle = "Persist task checkpoints to Room so interrupted tasks can resume safely",
                                checked = settings.autoCheckpointRecoveryEnabled,
                                onCheckedChange = onToggleAutoCheckpointRecovery,
                                switchTestTag = "settings_auto_checkpoint_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Schedule,
                                title = "Background Scheduled Automation",
                                subtitle = "Allow approved WorkManager routines when device conditions permit",
                                checked = settings.backgroundAutomationEnabled,
                                onCheckedChange = onToggleBackgroundAutomation,
                                switchTestTag = "settings_background_auto_switch",
                                showDivider = false
                            )
                        }

                        SettingsGroup(title = "Execution Limits (Applied Live to AgentCoordinator)") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Max Steps Per Task", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text("${settings.maxTaskSteps} steps", color = MaterialTheme.colorScheme.primary)
                                }
                                Slider(
                                    value = settings.maxTaskSteps.toFloat(),
                                    onValueChange = {
                                        onUpdateAgentLimits(it.roundToInt(), settings.maxActionRetries, settings.agentToolTimeoutSec)
                                    },
                                    valueRange = 4f..30f,
                                    steps = 25,
                                    modifier = Modifier.testTag("settings_max_steps_slider")
                                )

                                Text("Retry Limit Per Action", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0, 1, 2, 3, 5).forEach { retries ->
                                        FilterChip(
                                            selected = settings.maxActionRetries == retries,
                                            onClick = { onUpdateAgentLimits(settings.maxTaskSteps, retries, settings.agentToolTimeoutSec) },
                                            label = { Text("$retries") },
                                            modifier = Modifier.testTag("settings_retries_$retries")
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Tool Timeout", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text("${settings.agentToolTimeoutSec}s", color = MaterialTheme.colorScheme.primary)
                                }
                                Slider(
                                    value = settings.agentToolTimeoutSec.toFloat(),
                                    onValueChange = {
                                        onUpdateAgentLimits(settings.maxTaskSteps, settings.maxActionRetries, it.roundToInt())
                                    },
                                    valueRange = 5f..30f,
                                    steps = 24
                                )
                            }
                        }

                        SettingsGroup(title = "Mandatory Safety Guardrails") {
                            SettingsToggleItem(
                                icon = Icons.Default.Lock,
                                title = "Require Confirmation for Sensitive Actions",
                                subtitle = "Always ON: Calls, messages, form submits, purchases, and deletions require explicit approval.",
                                checked = true,
                                onCheckedChange = {},
                                enabled = false,
                                showDivider = false
                            )
                        }
                    }

                    SettingsCategory.MEMORY_PRIVACY -> {
                        SettingsGroup(title = "Local Memory Controls") {
                            SettingsToggleItem(
                                icon = Icons.Default.Psychology,
                                title = "On-Device Long-Term Memory",
                                subtitle = "Store approved preferences, routines, and contact aliases locally in Room",
                                checked = settings.memoryEnabled,
                                onCheckedChange = onToggleMemoryEnabled,
                                switchTestTag = "settings_memory_master_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.AutoAwesome,
                                title = "Propose Useful Memories",
                                subtitle = "Suggest remembering repeated preferences (never auto-saves secrets)",
                                checked = settings.autoMemoryProposalsEnabled,
                                onCheckedChange = onToggleAutoMemoryProposals,
                                switchTestTag = "settings_auto_memory_proposals_switch",
                                showDivider = false
                            )
                        }

                        SettingsGroup(title = "Default Retention Policy") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    MemoryExpirationPolicy.entries.forEach { policy ->
                                        FilterChip(
                                            selected = settings.defaultMemoryRetention == policy.name,
                                            onClick = { onSelectMemoryRetention(policy.name) },
                                            label = { Text(policy.labelEn) },
                                            modifier = Modifier.testTag("settings_retention_${policy.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        }

                        SettingsGroup(title = "Memory Actions") {
                            SettingsItem(
                                icon = Icons.Default.Psychology,
                                title = "Manage Memories in Library",
                                subtitle = "Inspect, search, edit, or delete ${storageSnapshot.memoryCount} stored record(s)",
                                onClick = onOpenLibrary
                            )
                            SettingsItem(
                                icon = Icons.Default.DeleteForever,
                                title = "Clear All Local Memories",
                                subtitle = "Permanently wipe all memories from Room storage",
                                iconTint = CrimsonAlert,
                                onClick = { showClearMemoriesConfirm = true },
                                showDivider = false,
                                modifier = Modifier.testTag("settings_clear_memories_item")
                            )
                        }
                    }

                    SettingsCategory.SKILLS_AUTOMATION -> {
                        SettingsGroup(title = "Reusable Skills & Automation") {
                            SettingsToggleItem(
                                icon = Icons.Default.AutoAwesome,
                                title = "Propose Reusable Skills",
                                subtitle = "Suggest saving repeatable safe multi-step workflows for your approval",
                                checked = settings.autoSkillProposalsEnabled,
                                onCheckedChange = onToggleAutoSkillProposals,
                                switchTestTag = "settings_auto_skill_proposals_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Schedule,
                                title = "Background Scheduled Execution",
                                subtitle = "Allow approved WorkManager routines to execute on schedule",
                                checked = settings.backgroundAutomationEnabled,
                                onCheckedChange = onToggleBackgroundAutomation,
                                switchTestTag = "settings_skills_background_switch",
                                showDivider = false
                            )
                        }

                        SettingsGroup(title = "Manage Workflows & Tools") {
                            SettingsItem(
                                icon = Icons.Default.Psychology,
                                title = "Open Skills Library",
                                subtitle = "${storageSnapshot.skillCount} built-in and approved skills",
                                onClick = onOpenLibrary
                            )
                            SettingsItem(
                                icon = Icons.Default.Schedule,
                                title = "Open Tasks & Schedules",
                                subtitle = "${storageSnapshot.taskCount} tasks • ${storageSnapshot.scheduleCount} schedules",
                                onClick = onOpenTasks
                            )
                            SettingsItem(
                                icon = Icons.Default.SmartToy,
                                title = "Dynamic Tools, Reflection & Multi-Agent Diagnostics",
                                subtitle = "Inspect 25 registered tools, reliability scores, and proactive rules",
                                onClick = onOpenAutonomousHub,
                                showDivider = false
                            )
                        }
                    }

                    SettingsCategory.NOTIFICATIONS -> {
                        SettingsGroup(title = "Task & Assistant Alerts") {
                            SettingsToggleItem(
                                icon = Icons.Default.Notifications,
                                title = "Task Notifications",
                                subtitle = "Show status updates for long-running autonomous tasks",
                                checked = settings.taskNotificationsEnabled,
                                onCheckedChange = { onUpdateNotifications(it, null, null, null, null) },
                                switchTestTag = "settings_notif_tasks_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.CheckCircle,
                                title = "Completion Notifications",
                                subtitle = "Notify when a multi-stage goal or skill completes",
                                checked = settings.completionNotificationsEnabled,
                                onCheckedChange = { onUpdateNotifications(null, it, null, null, null) },
                                switchTestTag = "settings_notif_completion_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Shield,
                                title = "Failure & Safety Alerts",
                                subtitle = "Alert when a task needs confirmation or encounters an error",
                                checked = settings.failureNotificationsEnabled,
                                onCheckedChange = { onUpdateNotifications(null, null, it, null, null) },
                                switchTestTag = "settings_notif_failure_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Schedule,
                                title = "Scheduled Task Notifications",
                                subtitle = "Notify when scheduled reminders or safe routines run",
                                checked = settings.scheduledTaskNotificationsEnabled,
                                onCheckedChange = { onUpdateNotifications(null, null, null, it, null) },
                                switchTestTag = "settings_notif_scheduled_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.AutoAwesome,
                                title = "Proactive Suggestions",
                                subtitle = "Generate gentle context-aware workflow tips on device",
                                checked = settings.proactiveSuggestionsEnabled,
                                onCheckedChange = onToggleProactiveSuggestions,
                                switchTestTag = "settings_notif_proactive_switch"
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Vibration,
                                title = "Notification Vibration",
                                subtitle = "Vibrate alongside task notifications when device allows",
                                checked = settings.notificationVibrationEnabled,
                                onCheckedChange = { onUpdateNotifications(null, null, null, null, it) },
                                switchTestTag = "settings_notif_vibration_switch",
                                showDivider = false
                            )
                        }
                    }

                    SettingsCategory.STORAGE_DATA -> {
                        FeatureCard(modifier = Modifier.testTag("storage_metrics_card")) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("On-Device Storage Usage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = onRefreshStorage) {
                                    Text("Refresh")
                                }
                            }
                            Text(
                                text = "Local Room DB (v3): ${storageSnapshot.databaseSizeBytes / 1024} KB • App Cache: ${storageSnapshot.cacheSizeBytes / 1024} KB",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Records: ${storageSnapshot.memoryCount} memories • ${storageSnapshot.skillCount} skills • ${storageSnapshot.taskCount} tasks • ${storageSnapshot.scheduleCount} schedules • ${storageSnapshot.toolMetricCount} tool logs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        SettingsGroup(title = "Data Portability & Cleanup") {
                            SettingsItem(
                                icon = Icons.Default.DeleteSweep,
                                title = "Clear App Cache",
                                subtitle = "Remove temporary files (${storageSnapshot.cacheSizeBytes / 1024} KB)",
                                onClick = onClearAppCache,
                                modifier = Modifier.testTag("settings_clear_cache_item")
                            )
                            SettingsItem(
                                icon = Icons.Default.Download,
                                title = "Export User Data (Sanitized)",
                                subtitle = "Share memories, skills, and schedules as plain text — zero API keys or secrets",
                                onClick = {
                                    val exportText = onBuildExportText()
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, "ScreenSight AI Local Export")
                                        putExtra(Intent.EXTRA_TEXT, exportText)
                                    }
                                    runCatching {
                                        context.startActivity(Intent.createChooser(shareIntent, "Export ScreenSight Data"))
                                    }
                                },
                                modifier = Modifier.testTag("settings_export_data_item")
                            )
                            SettingsItem(
                                icon = Icons.Default.History,
                                title = "Delete Task History & Logs",
                                subtitle = "Remove completed, failed, and cancelled task records",
                                iconTint = CrimsonAlert,
                                onClick = { showDeleteHistoryConfirm = true },
                                modifier = Modifier.testTag("settings_delete_history_item")
                            )
                            SettingsItem(
                                icon = Icons.Default.DeleteForever,
                                title = "Reset Non-Secret Local Data",
                                subtitle = "Reset memories, tasks, schedules & UI prefs (preserves encrypted API key)",
                                iconTint = CrimsonAlert,
                                onClick = { showResetLocalDataConfirm = true },
                                showDivider = false,
                                modifier = Modifier.testTag("settings_reset_local_data_item")
                            )
                        }
                    }

                    SettingsCategory.SECURITY -> {
                        FeatureCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("API Key Protection", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                StatusIndicator(
                                    label = if (hasApiKey) apiKeySource.name else "Not Set",
                                    tone = if (hasApiKey) StatusTone.POSITIVE else StatusTone.WARNING
                                )
                            }
                            Text(
                                text = "User-entered Gemini API keys are encrypted locally using Android Keystore (AES-256-GCM) and never sent to any developer server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SecondaryButton(
                                text = "Manage API Key",
                                icon = Icons.Default.Key,
                                onClick = onOpenApiSettings,
                                tonal = true
                            )
                        }

                        SettingsGroup(title = "Active Security Architecture") {
                            SettingsToggleItem(
                                icon = Icons.Default.Lock,
                                title = "Sensitive Action Confirmation Gate",
                                subtitle = "Always ON — calls, messages, purchases, form submissions, and deletions require explicit approval.",
                                checked = true,
                                onCheckedChange = {},
                                enabled = false
                            )
                            SettingsToggleItem(
                                icon = Icons.Default.Shield,
                                title = "Password & Prompt-Injection Shield",
                                subtitle = "Always ON — passwords, OTPs, banking fields, and untrusted webpage instructions cannot trigger actions.",
                                checked = true,
                                onCheckedChange = {},
                                enabled = false,
                                showDivider = false
                            )
                        }

                        FeatureCard {
                            Text("Device-Level App Lock", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "ScreenSight AI relies on your Android system lock screen and hardware-backed Android Keystore rather than a weak custom in-app PIN.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val securityLogs = remember(agentTaskLogs) {
                            agentTaskLogs.filter {
                                it.level == AgentTaskLogger.LogLevel.WARNING ||
                                    it.level == AgentTaskLogger.LogLevel.ERROR ||
                                    it.category.contains("SAFETY", ignoreCase = true) ||
                                    it.message.contains("block", ignoreCase = true) ||
                                    it.message.contains("confirm", ignoreCase = true)
                            }.takeLast(8).reversed()
                        }
                        FeatureCard(modifier = Modifier.testTag("security_events_card")) {
                            Text("Recent Safety & Security Events (${securityLogs.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            if (securityLogs.isEmpty()) {
                                Text(
                                    text = "No blocked or warning safety events recorded in this session.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                securityLogs.forEach { entry ->
                                    Text(
                                        text = "[${entry.category}] ${entry.message}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        SecondaryButton(
                            text = "Open Full Privacy & Transparency Disclosures",
                            icon = Icons.Default.Security,
                            onClick = onOpenPrivacyPolicy,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    SettingsCategory.ABOUT_SUPPORT -> {
                        FeatureCard(modifier = Modifier.testTag("about_version_card")) {
                            Text("ScreenSight AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Package: ${BuildConfig.APPLICATION_ID}\nArchitecture: Direct BYOK Gemini Live + On-Device Android Agent Engine (Phases 1–6)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        SettingsGroup(title = "Legal, Privacy & Diagnostics") {
                            SettingsItem(
                                icon = Icons.Default.Info,
                                title = "Open-Source Licenses",
                                subtitle = "Jetpack Compose, Room, WorkManager, OkHttp, Kotlin Coroutines & Serialization",
                                onClick = { showLicensesDialog = true },
                                modifier = Modifier.testTag("settings_open_licenses_item")
                            )
                            SettingsItem(
                                icon = Icons.Default.Security,
                                title = "Privacy & Security Policy",
                                subtitle = "Transparent local-first & BYOK data handling disclosures",
                                onClick = onOpenPrivacyPolicy,
                                modifier = Modifier.testTag("settings_open_privacy_item")
                            )
                            SettingsItem(
                                icon = Icons.Default.ContentCopy,
                                title = "Copy Diagnostics Summary",
                                subtitle = "Copy sanitized build, permission, tool & DB metrics (never includes API keys)",
                                onClick = {
                                    clipboard.setText(AnnotatedString(onBuildDiagnosticsText()))
                                    copiedDiagnosticsNotice = true
                                },
                                showDivider = false,
                                modifier = Modifier.testTag("settings_copy_diagnostics_item")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-Source Licenses") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "ScreenSight AI uses the following open-source libraries under the Apache License 2.0:",
                    style = MaterialTheme.typography.bodyMedium
                )
                listOf(
                    "AndroidX Jetpack Compose & Material 3",
                    "AndroidX Navigation Compose",
                    "AndroidX Room Persistence Library",
                    "AndroidX WorkManager",
                    "AndroidX DataStore Preferences",
                    "Kotlin Coroutines & Kotlinx Serialization",
                    "Square OkHttp WebSocket & HTTP Client",
                    "Google Fonts: Space Grotesk, Plus Jakarta Sans, JetBrains Mono (SIL OFL 1.1)"
                ).forEach { lib ->
                    Text("• $lib", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("licenses_dialog_close_button")) {
                Text("Close")
            }
        }
    )
}
