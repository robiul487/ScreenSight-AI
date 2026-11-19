package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.agent.AgentPermissionsSnapshot
import com.example.agent.PendingConfirmation
import com.example.domain.model.LiveSessionState
import com.example.domain.model.ScreenSightError
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.IndigoGlow

/**
 * Father / Family Easy Mode Screen (Sections 8 & 18).
 *
 * Simplified, high-contrast, Bangla-first voice screen designed for elderly or less
 * technically experienced users. Shares the exact same underlying Gemini Live pipeline,
 * AgentCoordinator, ActionExecutor, and AccessibilityService as Standard Mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilyEasyModeScreen(
    hasApiKey: Boolean,
    sessionState: LiveSessionState,
    isMicMuted: Boolean,
    isUserSpeaking: Boolean,
    isAiSpeaking: Boolean,
    isAgentTaskRunning: Boolean,
    statusBannerBn: String,
    pendingConfirmation: PendingConfirmation?,
    activeError: ScreenSightError?,
    permissions: AgentPermissionsSnapshot,
    onTapToTalk: () -> Unit,
    onStopEverything: () -> Unit,
    onApproveConfirmation: () -> Unit,
    onRejectConfirmation: () -> Unit,
    onQuickCallFamily: () -> Unit,
    onQuickOpenYouTube: () -> Unit,
    onQuickReadScreen: () -> Unit,
    onQuickTellTime: () -> Unit,
    onOpenPermissionsSetup: () -> Unit,
    onOpenApiSettings: () -> Unit,
    onSwitchToStandardMode: () -> Unit
) {
    BackHandler(onBack = onSwitchToStandardMode)

    val infiniteTransition = rememberInfiniteTransition(label = "family_mic_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(950, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val liveStatusBangla = when {
        pendingConfirmation != null -> stringResource(R.string.family_mode_confirm_needed)
        isAgentTaskRunning -> stringResource(R.string.family_mode_working)
        sessionState == LiveSessionState.CONNECTING ||
            sessionState == LiveSessionState.RECONNECTING -> "সংযোগ হচ্ছে, একটু অপেক্ষা করুন..."
        sessionState == LiveSessionState.SPEAKING || isAiSpeaking -> "সহকারী কথা বলছে..."
        sessionState == LiveSessionState.THINKING -> stringResource(R.string.family_mode_thinking)
        sessionState.isActive && isMicMuted -> "মাইক বন্ধ আছে — আবার চালু করতে নিচে চাপ দিন"
        sessionState.isActive && isUserSpeaking -> stringResource(R.string.family_mode_listening)
        sessionState.isActive -> stringResource(R.string.family_mode_listening)
        activeError != null -> stringResource(R.string.family_mode_retry)
        else -> statusBannerBn.ifBlank { stringResource(R.string.family_mode_prompt) }
    }

    val orbColor = when {
        pendingConfirmation != null -> AmberThinking
        activeError != null -> CrimsonAlert
        isAgentTaskRunning -> ElectricCyan
        sessionState == LiveSessionState.SPEAKING || isAiSpeaking -> IndigoGlow
        sessionState.isActive && !isMicMuted -> EmeraldLive
        sessionState.isActive && isMicMuted -> AmberThinking
        else -> ElectricCyan
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.family_mode_subtitle),
                            style = MaterialTheme.typography.labelLarge,
                            color = ElectricCyan
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = onSwitchToStandardMode,
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .testTag("family_switch_to_standard_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch to Standard Mode",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("সাধারণ মোড", fontWeight = FontWeight.SemiBold)
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
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Gentle Setup Banner only when API key or Accessibility is missing
                if (!hasApiKey) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("family_missing_key_card"),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = AmberThinking.copy(alpha = 0.16f))
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "প্রথমে Gemini API Key সেট করতে হবে",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "পরিবারের কাউকে দিয়ে API Settings-এ API Key বসিয়ে নিন।",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = onOpenApiSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text("API সেটিংস খুলুন", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else if (!permissions.isAgentControlReady) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("family_accessibility_hint_card"),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = AmberThinking,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "অ্যাপ নিয়ন্ত্রণ অনুমতি বাকি আছে",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "স্ক্রিনে ক্লিক বা পড়ার জন্য Accessibility চালু করুন।",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = onOpenPermissionsSetup) {
                                Text("চালু করুন", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Group B Sensitive Action Bangla Confirmation Card
                AnimatedVisibility(visible = pendingConfirmation != null) {
                    pendingConfirmation?.let { pending ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("family_confirmation_card"),
                            shape = RoundedCornerShape(22.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = AmberThinking.copy(alpha = 0.2f)
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.family_mode_confirm_needed),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = AmberThinking
                                )
                                Text(
                                    text = pending.promptBn,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = onApproveConfirmation,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .testTag("family_confirm_yes_button"),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = EmeraldLive,
                                            contentColor = Color(0xFF070B14)
                                        )
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("হ্যাঁ, করুন", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = onRejectConfirmation,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .testTag("family_confirm_no_button"),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CrimsonAlert,
                                            contentColor = Color.White
                                        )
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("না, বাতিল", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Main High-Contrast Bangla Instruction
                Text(
                    text = stringResource(R.string.family_mode_prompt),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .testTag("family_main_prompt")
                )

                // Giant Tap-to-Talk Circular Microphone Button
                Box(
                    modifier = Modifier
                        .size(210.dp)
                        .semantics { contentDescription = "কথা বলুন - Tap to Talk" }
                        .testTag("family_tap_to_talk_button"),
                    contentAlignment = Alignment.Center
                ) {
                    val activePulse = if (sessionState.isActive || isAgentTaskRunning) pulse else 1f
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val base = size.minDimension / 2.4f
                        drawCircle(
                            color = orbColor.copy(alpha = 0.16f),
                            radius = (base * 1.18f * activePulse).coerceAtMost(size.minDimension / 2f)
                        )
                        drawCircle(
                            color = orbColor.copy(alpha = 0.28f),
                            radius = (base * 1.04f * activePulse).coerceAtMost(size.minDimension / 2.1f)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(164.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(orbColor, IndigoGlow)
                                )
                            )
                            .clickable(onClick = onTapToTalk),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (sessionState.isActive && isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color(0xFF070B14),
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = when {
                                    !sessionState.isActive -> stringResource(R.string.family_mode_tap_to_talk)
                                    isMicMuted -> "মাইক চালু"
                                    else -> "শুনছি..."
                                },
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF070B14)
                            )
                        }
                    }
                }

                // Live Readable Bangla Status Pill
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("family_status_pill"),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Text(
                        text = liveStatusBangla,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = orbColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                    )
                }

                // Obvious Red Stop / Cancel Control when session or task is active
                AnimatedVisibility(visible = sessionState.isActive || isAgentTaskRunning) {
                    Button(
                        onClick = onStopEverything,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("family_stop_button"),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrimsonAlert,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "থামুন",
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.family_mode_stop),
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 4 Large High-Contrast Quick Action Tiles (Section 18)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FamilyQuickCard(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("family_quick_call"),
                            icon = Icons.Default.Call,
                            label = "ফোন করুন",
                            accent = EmeraldLive,
                            onClick = onQuickCallFamily
                        )
                        FamilyQuickCard(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("family_quick_youtube"),
                            icon = Icons.Default.PlayCircle,
                            label = "YouTube",
                            accent = CrimsonAlert,
                            onClick = onQuickOpenYouTube
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        FamilyQuickCard(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("family_quick_read_screen"),
                            icon = Icons.Default.RecordVoiceOver,
                            label = "স্ক্রিন পড়ুন",
                            accent = ElectricCyan,
                            onClick = onQuickReadScreen
                        )
                        FamilyQuickCard(
                            modifier = Modifier
                                .weight(1f)
                                .testTag("family_quick_time"),
                            icon = Icons.Default.AccessTime,
                            label = "সময় বলুন",
                            accent = AmberThinking,
                            onClick = onQuickTellTime
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FamilyQuickCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accent,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}
