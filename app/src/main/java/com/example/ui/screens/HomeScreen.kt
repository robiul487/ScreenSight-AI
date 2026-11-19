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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
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
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.LiveSessionState
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.IndigoGlow

/**
 * Main Home Screen (Section 9.1).
 * Includes:
 * - Header: ScreenSight AI logo, App name, Settings icon.
 * - Main section: Welcome message, Gemini connection status, Selected model, API key status, Start Live button.
 * - Secondary information: Last session status, Quick settings access, Privacy information.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    hasApiKey: Boolean,
    maskedApiKey: String,
    sessionState: LiveSessionState,
    isSharingScreen: Boolean,
    showFirstSessionPrivacyDialog: Boolean,
    onStartLiveClicked: () -> Unit,
    onConfirmPrivacyAndStart: () -> Unit,
    onDismissPrivacyDialog: () -> Unit,
    onNavigateToLiveScreen: () -> Unit,
    onNavigateToApiSettings: () -> Unit,
    onNavigateToAppearanceSettings: () -> Unit,
    onNavigateToPrivacy: () -> Unit
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
                                text = "BYOK • Apache 2.0 Open Source",
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
                // Hero Welcome & Live Session Launcher Card
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
                                    sessionState.isActive -> "LIVE SESSION ACTIVE: ${sessionState.displayName.uppercase()}"
                                    hasApiKey -> "READY FOR GEMINI LIVE"
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

                        // Connection, Model, and API Key Status Grid
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

                        AnimatedVisibility(visible = hasApiKey && !sessionState.isActive) {
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

                // Last Session Status Card
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Last Session Status",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = settings.lastSessionSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Quick Settings Access Row
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

                // Privacy & Direct Connection Card
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
