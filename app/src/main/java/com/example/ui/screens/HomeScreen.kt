package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentMode
import com.example.agent.AgentPermissionsSnapshot
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.LiveSessionState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.IndigoGlow

/**
 * Main Dashboard Home Screen (Section 17).
 * Preserves ScreenSight AI's dark navy/slate/cyan visual identity and all existing BYOK /
 * Gemini Live capabilities while introducing:
 * - Android AI Agent control status (clearly distinguished from Gemini Live voice/screen)
 * - Dual operating modes (Standard Agent & Family Easy Mode)
 * - Real deterministic Quick Actions
 * - Agent Permissions & Setup summary card
 * - Last Session & Recent Agent Task summary
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    hasApiKey: Boolean,
    maskedApiKey: String,
    sessionState: LiveSessionState,
    isSharingScreen: Boolean,
    permissions: AgentPermissionsSnapshot,
    showFirstSessionPrivacyDialog: Boolean,
    onStartLiveClicked: () -> Unit,
    onConfirmPrivacyAndStart: () -> Unit,
    onDismissPrivacyDialog: () -> Unit,
    onSelectAgentMode: (AgentMode) -> Unit,
    onOpenFamilyEasyMode: () -> Unit,
    onNavigateToLiveScreen: () -> Unit,
    onNavigateToAgentPermissions: () -> Unit,
    onNavigateToTrustedContacts: () -> Unit,
    onQuickOpenYouTube: () -> Unit,
    onQuickReadScreen: () -> Unit,
    onQuickSetAlarm: () -> Unit,
    onQuickTellTime: () -> Unit,
    onNavigateToApiSettings: () -> Unit,
    onNavigateToAppearanceSettings: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    memoryCount: Int = 0,
    skillCount: Int = 0,
    hasPendingSkillProposal: Boolean = false,
    onNavigateToMemorySkills: () -> Unit = {},
    longTermTaskCount: Int = 0,
    scheduledTaskCount: Int = 0,
    proactiveSuggestionCount: Int = 0,
    onNavigateToAutonomousHub: () -> Unit = {},
    onNavigateToSafeBrowser: () -> Unit = {}
) {
    val selectedModel = GeminiLiveModel.findByIdOrCustom(settings.selectedModelId)

    if (showFirstSessionPrivacyDialog) {
        FirstSessionPrivacyConfirmationDialog(
            onConfirm = onConfirmPrivacyAndStart,
            onDismiss = onDismissPrivacyDialog,
            onReadFullPolicy = {
                onDismissPrivacyDialog()
                onNavigateToPrivacy()
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(ElectricCyan, IndigoGlow)
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PresentToAll,
                                contentDescription = "ScreenSight AI Logo",
                                tint = Color(0xFF070B14),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "BYOK • Voice Android AI Agent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToAppearanceSettings,
                        modifier = Modifier.testTag("home_appearance_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Appearance Settings"
                        )
                    }
                    IconButton(
                        onClick = onNavigateToApiSettings,
                        modifier = Modifier.testTag("home_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "API & Model Settings"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
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
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero Welcome & Live Voice Agent Launcher Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hero_welcome_card"),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                        MaterialTheme.colorScheme.surface
                                    )
                                )
                            )
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Status Badge Row
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val badgeColor = when {
                                sessionState.isActive -> EmeraldLive
                                hasApiKey -> ElectricCyan
                                else -> AmberThinking
                            }
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor)
                            )
                            Text(
                                text = when {
                                    sessionState.isActive -> "LIVE AGENT ACTIVE: ${sessionState.displayName.uppercase()}"
                                    hasApiKey -> "READY FOR GEMINI LIVE AGENT"
                                    else -> "API KEY CONFIGURATION REQUIRED"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = badgeColor
                            )
                        }

                        Text(
                            text = stringResource(R.string.welcome_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = stringResource(R.string.welcome_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Connection, Model, API Key, and Agent Control Status Grid
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            StatusDetailRow(
                                icon = Icons.Default.Key,
                                label = "API Key Status",
                                value = if (hasApiKey) "Configured ($maskedApiKey)" else "Not Configured",
                                statusColor = if (hasApiKey) EmeraldLive else AmberThinking,
                                testTag = "status_api_key"
                            )
                            StatusDetailRow(
                                icon = Icons.Default.Psychology,
                                label = "Selected Model",
                                value = selectedModel.displayName,
                                statusColor = MaterialTheme.colorScheme.primary,
                                testTag = "status_selected_model"
                            )
                            StatusDetailRow(
                                icon = Icons.Default.Mic,
                                label = "Gemini Connection",
                                value = if (isSharingScreen) {
                                    "Screen + Audio Active"
                                } else {
                                    sessionState.displayName
                                },
                                statusColor = if (sessionState.isActive) EmeraldLive else MaterialTheme.colorScheme.onSurfaceVariant,
                                testTag = "status_connection"
                            )
                            StatusDetailRow(
                                icon = Icons.Default.AccessibilityNew,
                                label = "Agent Device Control",
                                value = if (permissions.isAgentControlReady) {
                                    "Accessibility Ready"
                                } else {
                                    "Not Enabled (Tap Setup)"
                                },
                                statusColor = if (permissions.isAgentControlReady) EmeraldLive else AmberThinking,
                                testTag = "status_agent_control"
                            )
                        }

                        // Primary Prominent Action Button
                        if (sessionState.isActive) {
                            Button(
                                onClick = onNavigateToLiveScreen,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("resume_live_button"),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldLive,
                                    contentColor = Color(0xFF070B14)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PresentToAll,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.resume_live_session),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (hasApiKey) {
                            Button(
                                onClick = onStartLiveClicked,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("start_live_button"),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.start_live_session),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            Button(
                                onClick = onNavigateToApiSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .testTag("configure_api_key_button"),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.configure_api_key),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        AnimatedVisibility(visible = !sessionState.isActive) {
                            OutlinedButton(
                                onClick = onNavigateToLiveScreen,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_assistant_console_button"),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("Open Live Assistant Console")
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Dual Operating Mode Card (Standard Agent vs Family Easy Mode)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_mode_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = ElectricCyan
                            )
                            Column {
                                Text(
                                    text = "Agent Operating Mode",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Both modes share the same Gemini Live & Accessibility engine",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilterChip(
                                selected = settings.agentMode == AgentMode.STANDARD_AGENT,
                                onClick = { onSelectAgentMode(AgentMode.STANDARD_AGENT) },
                                label = { Text("Standard Agent") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("mode_chip_standard")
                            )
                            FilterChip(
                                selected = settings.agentMode == AgentMode.FAMILY_EASY_MODE,
                                onClick = { onSelectAgentMode(AgentMode.FAMILY_EASY_MODE) },
                                label = { Text("Family Easy Mode") },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("mode_chip_family")
                            )
                        }

                        FilledTonalButton(
                            onClick = onOpenFamilyEasyMode,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("open_family_mode_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Default.Elderly, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Open Family Easy Mode (সহজ বাংলা মোড)",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Quick Agent Actions Grid (Real deterministic shortcuts)
                Text(
                    text = "Quick Agent Actions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QuickAccessTile(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_youtube"),
                            icon = Icons.Default.PlayCircle,
                            title = "Open YouTube",
                            subtitle = "Launch via Intent",
                            onClick = onQuickOpenYouTube
                        )
                        QuickAccessTile(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_contacts"),
                            icon = Icons.Default.Call,
                            title = "Trusted Calls",
                            subtitle = "Family nicknames",
                            onClick = onNavigateToTrustedContacts
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QuickAccessTile(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_read_screen"),
                            icon = Icons.Default.RecordVoiceOver,
                            title = "Read Screen",
                            subtitle = "Accessible UI text",
                            onClick = onQuickReadScreen
                        )
                        QuickAccessTile(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_alarm"),
                            icon = Icons.Default.Alarm,
                            title = "Set 6:00 AM Alarm",
                            subtitle = "Clock Intent",
                            onClick = onQuickSetAlarm
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        QuickAccessTile(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_time"),
                            icon = Icons.Default.AccessTime,
                            title = "Date & Time",
                            subtitle = "Speak in Bangla",
                            onClick = onQuickTellTime
                        )
                        QuickAccessTile(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_permissions"),
                            icon = Icons.Default.Shield,
                            title = "Permissions",
                            subtitle = if (permissions.isAgentControlReady) "Agent Ready" else "Setup Needed",
                            onClick = onNavigateToAgentPermissions
                        )
                    }
                }

                // Phase 4: Local Long-Term Memory & Reusable Skills Entry Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToMemorySkills)
                        .testTag("home_memory_skills_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ElectricCyan.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = "Memory and Skills",
                                tint = ElectricCyan
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Memory & Skills (স্মৃতি ও দক্ষতা)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (hasPendingSkillProposal) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = EmeraldLive.copy(alpha = 0.2f)
                                    ) {
                                        Text(
                                            text = "NEW",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EmeraldLive,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = if (settings.memoryEnabled) {
                                    "On-device • $memoryCount memories • $skillCount skills"
                                } else {
                                    "Memory Disabled • $skillCount skills"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Memory and Skills",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Phase 5: Autonomous Hub & Safe Browser Quick Entry Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateToAutonomousHub)
                            .testTag("home_autonomous_hub_card"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = ElectricCyan
                            )
                            Text(
                                text = "Autonomous Hub",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$longTermTaskCount tasks • $scheduledTaskCount schedules • $proactiveSuggestionCount tips",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable(onClick = onNavigateToSafeBrowser)
                            .testTag("home_safe_browser_card"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = EmeraldLive
                            )
                            Text(
                                text = "Safe Browser",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Grounded web view & injection guard",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Agent Permissions Summary Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToAgentPermissions)
                        .testTag("agent_permissions_summary_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessibilityNew,
                                    contentDescription = null,
                                    tint = if (permissions.isAgentControlReady) EmeraldLive else AmberThinking
                                )
                                Text(
                                    text = "Agent Permissions Status",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Open Agent Permissions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PermissionBadgeChip(
                                modifier = Modifier.weight(1f),
                                label = "Accessibility",
                                granted = permissions.isAgentControlReady
                            )
                            PermissionBadgeChip(
                                modifier = Modifier.weight(1f),
                                label = "Microphone",
                                granted = permissions.hasMicrophone
                            )
                            PermissionBadgeChip(
                                modifier = Modifier.weight(1f),
                                label = "Overlay",
                                granted = permissions.canDrawOverlays
                            )
                        }
                    }
                }

                // Last Session & Recent Agent Task Status Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("last_session_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Last Session & Agent Activity",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = settings.lastSessionSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Last Agent Action: ${settings.lastAgentTaskSummary}",
                                style = MaterialTheme.typography.labelMedium,
                                color = ElectricCyan
                            )
                        }
                    }
                }

                // Quick Settings Access Row (Preserved)
                Text(
                    text = "Quick Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickAccessTile(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_api_settings_tile"),
                        icon = Icons.Default.Key,
                        title = "API & Model",
                        subtitle = selectedModel.id.removePrefix("models/"),
                        onClick = onNavigateToApiSettings
                    )
                    QuickAccessTile(
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_appearance_tile"),
                        icon = Icons.Default.Palette,
                        title = "Appearance",
                        subtitle = "${settings.themePreference.label} • ${settings.selectedVoiceName}",
                        onClick = onNavigateToAppearanceSettings
                    )
                }

                // Privacy & Direct Connection Card (Preserved)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToPrivacy)
                        .testTag("privacy_info_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "Privacy & Security",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Direct BYOK Architecture & Privacy",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "No developer backend. Your screen frames & voice stream directly to Google's Gemini Live API using your own Android Keystore-encrypted API key.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Open Privacy & Security",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PermissionBadgeChip(
    modifier: Modifier = Modifier,
    label: String,
    granted: Boolean
) {
    val color = if (granted) EmeraldLive else AmberThinking
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun StatusDetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    statusColor: Color,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = statusColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = statusColor
        )
    }
}

@Composable
private fun QuickAccessTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FirstSessionPrivacyConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onReadFullPolicy: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Before Your First Live Session",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Please confirm how ScreenSight AI processes your data:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "• When you tap Share Screen, 1 FPS JPEG screen frames are transmitted directly to Google's Gemini API.\n" +
                        "• While your microphone is unmuted, 16 kHz PCM audio is transmitted to Google's Gemini API.\n" +
                        "• API usage and billing belong to your own Google AI Studio account.\n" +
                        "• ScreenSight AI has no developer backend and does not record or store your screen frames.\n" +
                        "• You can stop screen sharing or end the session at any time.",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(
                    onClick = onReadFullPolicy,
                    modifier = Modifier.testTag("dialog_read_privacy_button")
                ) {
                    Text("View Full Privacy & Security Details")
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("dialog_confirm_privacy_button")
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("I Understand, Start Live")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_cancel_privacy_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
