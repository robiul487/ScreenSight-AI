package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentPermissionsSnapshot
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive

/**
 * Dedicated Agent Permissions & Setup Checklist Screen (Section 13).
 *
 * Shows real Android permission status (never faked) for:
 * - Accessibility Service (Device Control Engine) + Bangla/English setup steps
 * - Microphone
 * - Screen Capture (MediaProjection)
 * - Floating Assistant Overlay
 * - Notifications
 * - Contacts (optional)
 * Plus Family Easy Mode readiness checklist and Agent Tool Calling toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPermissionsScreen(
    permissions: AgentPermissionsSnapshot,
    agentToolsEnabled: Boolean,
    onToggleAgentTools: (Boolean) -> Unit,
    onRefreshPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestContactsPermission: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("permissions_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.agent_permissions_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(
                        onClick = onRefreshPermissions,
                        modifier = Modifier.testTag("permissions_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Permission Status"
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
                // Family Easy Mode Setup Readiness Checklist
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("family_setup_checklist_card"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Family Easy Mode Setup Checklist (${permissions.familyModeReadinessCount}/${permissions.familyModeReadinessTotal})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "A normal Gemini Live voice conversation works with Microphone alone, but tapping buttons, scrolling, typing, and reading third-party apps requires enabling the Android Accessibility Service below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Gemini Live Agent Tool Calling",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Allow Gemini Live to execute validated Android actions during sessions",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = agentToolsEnabled,
                                onCheckedChange = onToggleAgentTools,
                                modifier = Modifier.testTag("agent_tools_toggle")
                            )
                        }
                    }
                }

                // 1. Accessibility Service (Core Device-Control Engine)
                PermissionStatusCard(
                    testTag = "perm_accessibility_card",
                    icon = Icons.Default.AccessibilityNew,
                    title = "Android Accessibility Service",
                    statusLabel = when {
                        permissions.isAgentControlReady -> "Enabled & Connected"
                        permissions.isAccessibilityEnabledInSettings -> "Enabled (Awaiting Connection)"
                        else -> "Disabled"
                    },
                    isGranted = permissions.isAgentControlReady,
                    whyRequired = "Required to read non-sensitive screen text, tap buttons, scroll lists, type search queries, and navigate Back/Home inside other apps.",
                    howToEnable = "Tap Open Settings → Installed Apps / Downloaded Apps → ScreenSight AI Agent Control → Turn ON.\n(সেটিংস → Installed Apps → ScreenSight AI Agent Control চালু করুন।)",
                    actionLabel = if (permissions.isAgentControlReady) "Open Accessibility Settings" else "Enable Accessibility Service",
                    onAction = onOpenAccessibilitySettings
                )

                // 2. Microphone
                PermissionStatusCard(
                    testTag = "perm_microphone_card",
                    icon = Icons.Default.Mic,
                    title = "Microphone (RECORD_AUDIO)",
                    statusLabel = if (permissions.hasMicrophone) "Granted" else "Not Granted",
                    isGranted = permissions.hasMicrophone,
                    whyRequired = "Required for natural Bangla and English voice instructions with Gemini Live.",
                    howToEnable = "Tap Grant Microphone and choose \"While using the app\".",
                    actionLabel = if (permissions.hasMicrophone) "Granted" else "Grant Microphone",
                    onAction = onRequestMicPermission,
                    actionEnabled = !permissions.hasMicrophone
                )

                // 3. Display Over Other Apps (Floating Assistant Pill)
                PermissionStatusCard(
                    testTag = "perm_overlay_card",
                    icon = Icons.Default.Layers,
                    title = "Floating Assistant Overlay",
                    statusLabel = if (permissions.canDrawOverlays) "Enabled" else "Optional • Disabled",
                    isGranted = permissions.canDrawOverlays,
                    whyRequired = "Shows a movable floating pill over other apps so you can mute mic, pause screen sharing, open the app, or stop a task from anywhere.",
                    howToEnable = "Tap Open Overlay Settings and allow \"Display over other apps\" for ScreenSight AI.",
                    actionLabel = "Open Overlay Settings",
                    onAction = onOpenOverlaySettings
                )

                // 4. Screen Capture (MediaProjection)
                PermissionStatusCard(
                    testTag = "perm_screen_capture_card",
                    icon = Icons.Default.PresentToAll,
                    title = "Screen Capture (MediaProjection)",
                    statusLabel = if (permissions.isScreenSharingActive) "Active Now" else "Requested Per Session",
                    isGranted = permissions.isScreenSharingActive,
                    whyRequired = "Provides real-time visual screen context (1 FPS) when Accessibility text is unavailable or for visual guidance.",
                    howToEnable = "Android always asks for explicit consent each time you tap Start Live Session or Share Screen.",
                    actionLabel = "Managed Automatically Per Session",
                    onAction = {},
                    actionEnabled = false
                )

                // 5. Notifications
                PermissionStatusCard(
                    testTag = "perm_notifications_card",
                    icon = Icons.Default.Notifications,
                    title = "Ongoing Session Notification",
                    statusLabel = if (permissions.hasNotifications) "Granted" else "Not Granted",
                    isGranted = permissions.hasNotifications,
                    whyRequired = "Keeps the foreground voice and screen-sharing status visible with a quick Stop button.",
                    howToEnable = "Tap Grant Notifications when prompted on Android 13+.",
                    actionLabel = if (permissions.hasNotifications) "Granted" else "Grant Notifications",
                    onAction = onRequestNotificationPermission,
                    actionEnabled = !permissions.hasNotifications
                )

                // 6. Optional Contacts Permission
                PermissionStatusCard(
                    testTag = "perm_contacts_card",
                    icon = Icons.Default.Contacts,
                    title = "Phone Contacts (Optional)",
                    statusLabel = if (permissions.hasContacts) "Granted" else "Optional • Not Granted",
                    isGranted = permissions.hasContacts,
                    whyRequired = "Optional fallback to look up contacts beyond your saved Trusted Family Contacts list. Calling always confirms and opens the Dialer safely.",
                    howToEnable = "You can also add Trusted Contacts manually without granting full Contacts access.",
                    actionLabel = if (permissions.hasContacts) "Granted" else "Grant Contacts (Optional)",
                    onAction = onRequestContactsPermission,
                    actionEnabled = !permissions.hasContacts
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PermissionStatusCard(
    testTag: String,
    icon: ImageVector,
    title: String,
    statusLabel: String,
    isGranted: Boolean,
    whyRequired: String,
    howToEnable: String,
    actionLabel: String,
    onAction: () -> Unit,
    actionEnabled: Boolean = true
) {
    val badgeColor = if (isGranted) EmeraldLive else AmberThinking

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(badgeColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = badgeColor
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = badgeColor
                        )
                    }
                }
            }

            Text(
                text = whyRequired,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = howToEnable,
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan
            )

            if (actionEnabled) {
                if (!isGranted) {
                    Button(
                        onClick = onAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(actionLabel, fontWeight = FontWeight.Bold)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}
