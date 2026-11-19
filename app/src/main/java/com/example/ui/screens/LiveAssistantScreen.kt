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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.agent.AgentStepStatus
import com.example.agent.AgentTaskStep
import com.example.agent.PendingConfirmation
import com.example.domain.model.ConnectionQuality
import com.example.domain.model.LiveSessionState
import com.example.domain.model.MessageSender
import com.example.domain.model.ScreenSightError
import com.example.domain.model.TranscriptMessage
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.IndigoGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live Assistant Screen (Section 9.2).
 * Displays distinct visual telemetry for all 11 states:
 * IDLE, CONNECTING, CONNECTED, REQUESTING_SCREEN_PERMISSION, SHARING_SCREEN,
 * LISTENING, THINKING, SPEAKING, RECONNECTING, ERROR, STOPPED.
 * Includes screen sharing status, microphone toggle, AI audio toggle,
 * Stop Screen Sharing, End Conversation, and real-time transcript + text input.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveAssistantScreen(
    selectedModelName: String,
    sessionState: LiveSessionState,
    isSharingScreen: Boolean,
    isMicMuted: Boolean,
    isUserSpeaking: Boolean,
    micLevel: Float,
    isAiMuted: Boolean,
    isAiSpeaking: Boolean,
    framesSentCount: Int,
    framesSkippedCount: Int,
    lastFrameDimensions: String,
    connectionQuality: ConnectionQuality,
    transcript: List<TranscriptMessage>,
    activeError: ScreenSightError?,
    agentTaskSteps: List<AgentTaskStep> = emptyList(),
    isAgentTaskRunning: Boolean = false,
    pendingConfirmation: PendingConfirmation? = null,
    onApproveConfirmation: () -> Unit = {},
    onRejectConfirmation: () -> Unit = {},
    onStopAgentTask: () -> Unit = {},
    onBack: () -> Unit,
    onStartSession: () -> Unit,
    onRequestShareScreen: () -> Unit,
    onStopShareScreen: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleAiAudioMute: () -> Unit,
    onEndConversation: () -> Unit,
    onSendTextMessage: (String) -> Unit,
    onClearTranscript: () -> Unit,
    onDismissError: () -> Unit,
    onNavigateToApiSettings: () -> Unit
) {
    BackHandler(onBack = onBack)

    var messageInput by rememberSaveable { mutableStateOf("") }
    var showAgentActivityTab by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val agentListState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty() && !showAgentActivityTab) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    LaunchedEffect(agentTaskSteps.size) {
        if (agentTaskSteps.isNotEmpty() && showAgentActivityTab) {
            agentListState.animateScrollToItem(agentTaskSteps.size - 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("live_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home"
                        )
                    }
                },
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dotColor = stateAccentColor(sessionState)
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(dotColor)
                            )
                            Text(
                                text = sessionState.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = dotColor,
                                modifier = Modifier.testTag("live_state_badge")
                            )
                        }
                        Text(
                            text = selectedModelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (transcript.isNotEmpty()) {
                        IconButton(
                            onClick = onClearTranscript,
                            modifier = Modifier.testTag("clear_transcript_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear Conversation Transcript"
                            )
                        }
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
                    .widthIn(max = 650.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Error Banner if activeError is non-null
                AnimatedVisibility(visible = activeError != null) {
                    activeError?.let { error ->
                        ErrorBannerCard(
                            error = error,
                            onDismiss = onDismissError,
                            onOpenApiSettings = if (error is ScreenSightError.MissingApiKey ||
                                error is ScreenSightError.InvalidApiKey ||
                                error is ScreenSightError.UnsupportedModel
                            ) {
                                onNavigateToApiSettings
                            } else {
                                null
                            }
                        )
                    }
                }

                // Visual State Visualizer + Telemetry Header Card
                LiveStateVisualizerCard(
                    sessionState = sessionState,
                    isSharingScreen = isSharingScreen,
                    isMicMuted = isMicMuted,
                    isUserSpeaking = isUserSpeaking,
                    micLevel = micLevel,
                    isAiMuted = isAiMuted,
                    isAiSpeaking = isAiSpeaking,
                    framesSentCount = framesSentCount,
                    framesSkippedCount = framesSkippedCount,
                    lastFrameDimensions = lastFrameDimensions,
                    connectionQuality = connectionQuality
                )

                // Group B Sensitive Action Confirmation Card (Section 15 & 19)
                AnimatedVisibility(visible = pendingConfirmation != null) {
                    pendingConfirmation?.let { pending ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("live_confirmation_banner"),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = AmberThinking.copy(alpha = 0.18f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Confirmation Required • অনুমতি প্রয়োজন",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = AmberThinking
                                )
                                Text(
                                    text = "${pending.promptBn}\n${pending.promptEn}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = onApproveConfirmation,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("live_confirm_approve_button"),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = EmeraldLive,
                                            contentColor = Color(0xFF070B14)
                                        )
                                    ) {
                                        Text("Approve / হ্যাঁ", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = onRejectConfirmation,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("live_confirm_reject_button")
                                    ) {
                                        Text("Cancel / না")
                                    }
                                }
                            }
                        }
                    }
                }

                // Console View Switcher: Transcript vs Agent Activity + Stop Task Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !showAgentActivityTab,
                        onClick = { showAgentActivityTab = false },
                        label = { Text("Transcript") },
                        modifier = Modifier.testTag("console_tab_transcript")
                    )
                    FilterChip(
                        selected = showAgentActivityTab,
                        onClick = { showAgentActivityTab = true },
                        label = { Text("Agent Activity (${agentTaskSteps.size})") },
                        modifier = Modifier.testTag("console_tab_agent_activity")
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (isAgentTaskRunning) {
                        Button(
                            onClick = onStopAgentTask,
                            modifier = Modifier.testTag("console_stop_task_button"),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrimsonAlert,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Stop Task", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Conversation Transcript OR Agent Activity Step Log Area
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag("transcript_container"),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    if (showAgentActivityTab) {
                        if (agentTaskSteps.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.SmartToy,
                                        contentDescription = null,
                                        tint = ElectricCyan.copy(alpha = 0.75f),
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Text(
                                        text = "No Android Agent actions executed yet.\nAsk ScreenSight AI to open apps, search YouTube, read the screen, set an alarm, or call a trusted contact.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                state = agentListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("agent_activity_list"),
                                contentPadding = PaddingValues(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(agentTaskSteps, key = { it.id }) { step ->
                                    AgentTaskStepRow(step = step)
                                }
                            }
                        }
                    } else if (transcript.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = if (sessionState.isActive) {
                                        "Live audio & screen session is active.\nSpeak naturally in Bangla or English, or tap Share Screen below."
                                    } else {
                                        "Start a Live Session to share your screen and talk with ScreenSight AI Agent in real time."
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(transcript, key = { it.id }) { msg ->
                                TranscriptBubble(message = msg)
                            }
                        }
                    }
                }

                // Optional Text Input Row (for multimodal text + screen questions when user prefers typing)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_text_input"),
                        placeholder = {
                            Text(
                                if (isSharingScreen) "Ask about what's on screen..."
                                else "Type a message or speak aloud..."
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    IconButton(
                        onClick = {
                            if (messageInput.isNotBlank()) {
                                onSendTextMessage(messageInput)
                                messageInput = ""
                            }
                        },
                        enabled = messageInput.isNotBlank() && sessionState.isActive,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (messageInput.isNotBlank() && sessionState.isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .testTag("send_text_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = if (messageInput.isNotBlank() && sessionState.isActive) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Bottom Control Dock: Mic toggle, AI audio toggle, Share/Stop Screen, Start/End Session
                LiveControlDock(
                    sessionState = sessionState,
                    isSharingScreen = isSharingScreen,
                    isMicMuted = isMicMuted,
                    isAiMuted = isAiMuted,
                    onStartSession = onStartSession,
                    onRequestShareScreen = onRequestShareScreen,
                    onStopShareScreen = onStopShareScreen,
                    onToggleMicMute = onToggleMicMute,
                    onToggleAiAudioMute = onToggleAiAudioMute,
                    onEndConversation = onEndConversation
                )
            }
        }
    }
}

@Composable
private fun LiveStateVisualizerCard(
    sessionState: LiveSessionState,
    isSharingScreen: Boolean,
    isMicMuted: Boolean,
    isUserSpeaking: Boolean,
    micLevel: Float,
    isAiMuted: Boolean,
    isAiSpeaking: Boolean,
    framesSentCount: Int,
    framesSkippedCount: Int,
    lastFrameDimensions: String,
    connectionQuality: ConnectionQuality
) {
    val infiniteTransition = rememberInfiniteTransition(label = "live_orb_pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val accentColor = stateAccentColor(sessionState)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_visualizer_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Distinct Custom Canvas State Visualizer (not a generic spinner)
                Box(
                    modifier = Modifier.size(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val baseRadius = size.minDimension / 3.2f
                        val dynamicScale = when (sessionState) {
                            LiveSessionState.SPEAKING -> pulseRadius * 1.15f
                            LiveSessionState.LISTENING -> 1f + (micLevel * 0.45f)
                            LiveSessionState.SHARING_SCREEN -> pulseRadius
                            LiveSessionState.THINKING,
                            LiveSessionState.CONNECTING,
                            LiveSessionState.RECONNECTING -> pulseRadius * 0.95f
                            else -> 1f
                        }

                        // Outer halo ring
                        drawCircle(
                            color = accentColor.copy(alpha = 0.22f),
                            radius = (baseRadius * 1.45f * dynamicScale).coerceAtMost(size.minDimension / 2f)
                        )
                        // Middle ring
                        drawCircle(
                            color = accentColor.copy(alpha = 0.45f),
                            radius = (baseRadius * 1.15f * dynamicScale).coerceAtMost(size.minDimension / 2.2f),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        // Core orb
                        drawCircle(
                            color = accentColor,
                            radius = baseRadius
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = sessionState.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            sessionState == LiveSessionState.ERROR ->
                                "Session stopped due to an error. Review the message above or start a new session."
                            sessionState == LiveSessionState.STOPPED || sessionState == LiveSessionState.IDLE ->
                                "Session inactive. Tap Start Live Session below."
                            isAiSpeaking && sessionState.isActive ->
                                "Gemini is speaking (24 kHz PCM stream)"
                            isUserSpeaking && sessionState.isActive ->
                                "Detecting your voice (16 kHz PCM stream)"
                            isSharingScreen && sessionState.isActive ->
                                "Sharing screen at 1 FPS ($lastFrameDimensions)"
                            sessionState == LiveSessionState.REQUESTING_SCREEN_PERMISSION ->
                                "Grant Android system screen capture permission to stream frames"
                            sessionState.isActive ->
                                "Microphone ${if (isMicMuted) "Muted" else "Listening"} • AI Voice ${if (isAiMuted) "Muted" else "Active"}"
                            else -> "Session inactive. Tap Start Live Session below."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Live Telemetry Badges (Screen Sharing Status, Mic Status, Network Backpressure)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryPill(
                    modifier = Modifier.weight(1f),
                    label = "Screen",
                    value = if (isSharingScreen) "LIVE ($framesSentCount sent)" else "Off",
                    highlight = isSharingScreen,
                    color = if (isSharingScreen) EmeraldLive else MaterialTheme.colorScheme.onSurfaceVariant
                )
                TelemetryPill(
                    modifier = Modifier.weight(1f),
                    label = "Mic",
                    value = when {
                        !sessionState.isActive -> "Idle"
                        isMicMuted -> "Muted"
                        isUserSpeaking -> "Speaking"
                        else -> "16kHz On"
                    },
                    highlight = sessionState.isActive && !isMicMuted,
                    color = if (isMicMuted) AmberThinking else ElectricCyan
                )
                TelemetryPill(
                    modifier = Modifier.weight(1f),
                    label = "Network",
                    value = when (connectionQuality) {
                        ConnectionQuality.EXCELLENT -> "Optimal"
                        ConnectionQuality.MODERATE -> "Buffered"
                        ConnectionQuality.SLOW -> "Backpressure"
                        ConnectionQuality.DISCONNECTED -> "Offline"
                    },
                    highlight = connectionQuality == ConnectionQuality.EXCELLENT,
                    color = when (connectionQuality) {
                        ConnectionQuality.EXCELLENT -> EmeraldLive
                        ConnectionQuality.MODERATE -> AmberThinking
                        ConnectionQuality.SLOW -> CrimsonAlert
                        ConnectionQuality.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            if (isSharingScreen && framesSkippedCount > 0) {
                Text(
                    text = "Resource Optimizer: $framesSkippedCount unchanged duplicate screen frames skipped to save bandwidth & quota.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TelemetryPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    highlight: Boolean,
    color: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = if (highlight) color.copy(alpha = 0.5f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1
        )
    }
}

@Composable
private fun LiveControlDock(
    sessionState: LiveSessionState,
    isSharingScreen: Boolean,
    isMicMuted: Boolean,
    isAiMuted: Boolean,
    onStartSession: () -> Unit,
    onRequestShareScreen: () -> Unit,
    onStopShareScreen: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleAiAudioMute: () -> Unit,
    onEndConversation: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!sessionState.isActive) {
                Button(
                    onClick = onStartSession,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("live_start_session_button"),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (sessionState == LiveSessionState.STOPPED || sessionState == LiveSessionState.ERROR) {
                            "Start New Live Session"
                        } else {
                            "Start Live Session"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                // Active session controls: Mic toggle, AI voice toggle, Share/Stop Screen
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        onClick = onToggleMicMute,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("toggle_mic_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (isMicMuted) {
                                AmberThinking.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            }
                        )
                    ) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMicMuted) "Unmute Microphone" else "Mute Microphone",
                            tint = if (isMicMuted) AmberThinking else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isMicMuted) "Unmute Mic" else "Mute Mic")
                    }

                    FilledTonalButton(
                        onClick = onToggleAiAudioMute,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("toggle_ai_audio_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = if (isAiMuted) {
                                Icons.AutoMirrored.Filled.VolumeOff
                            } else {
                                Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = if (isAiMuted) "Unmute AI Voice" else "Mute AI Voice"
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isAiMuted) "AI Muted" else "AI Voice")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSharingScreen) {
                        OutlinedButton(
                            onClick = onStopShareScreen,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("stop_screen_sharing_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.StopScreenShare,
                                contentDescription = "Stop Screen Sharing",
                                tint = AmberThinking
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Sharing")
                        }
                    } else {
                        Button(
                            onClick = onRequestShareScreen,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("share_screen_button"),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldLive,
                                contentColor = Color(0xFF070B14)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PresentToAll,
                                contentDescription = "Share Screen"
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Share Screen",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Button(
                        onClick = onEndConversation,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("end_conversation_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "End Conversation"
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "End Session",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(message: TranscriptMessage) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(message.timestampMillis))

    when (message.sender) {
        MessageSender.SYSTEM -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${message.text} ($timeStr)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        MessageSender.USER -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (message.hasScreenContext) {
                                Text(
                                    text = "Screen Context",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldLive
                                )
                            }
                            Text(
                                text = timeStr,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        MessageSender.GEMINI -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = "ScreenSight AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (message.isStreaming) "Streaming..." else timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBannerCard(
    error: ScreenSightError,
    onDismiss: () -> Unit,
    onOpenApiSettings: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("live_error_banner"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = error.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = error.userMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (onOpenApiSettings != null) {
                    TextButton(
                        onClick = onOpenApiSettings,
                        modifier = Modifier.testTag("error_open_settings_button")
                    ) {
                        Text("Open API Settings")
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("error_dismiss_button")
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}

private fun stateAccentColor(state: LiveSessionState): Color {
    return when (state) {
        LiveSessionState.IDLE,
        LiveSessionState.STOPPED -> Color(0xFF94A3B8)
        LiveSessionState.CONNECTING,
        LiveSessionState.RECONNECTING,
        LiveSessionState.REQUESTING_SCREEN_PERMISSION,
        LiveSessionState.THINKING -> AmberThinking
        LiveSessionState.CONNECTED,
        LiveSessionState.LISTENING -> ElectricCyan
        LiveSessionState.SHARING_SCREEN -> EmeraldLive
        LiveSessionState.SPEAKING -> IndigoGlow
        LiveSessionState.ERROR -> CrimsonAlert
    }
}

@Composable
private fun AgentTaskStepRow(step: AgentTaskStep) {
    val statusColor = when (step.status) {
        AgentStepStatus.COMPLETED -> EmeraldLive
        AgentStepStatus.RUNNING -> ElectricCyan
        AgentStepStatus.WAITING_CONFIRMATION -> AmberThinking
        AgentStepStatus.FAILED -> CrimsonAlert
        AgentStepStatus.CANCELLED -> Color(0xFF94A3B8)
        AgentStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (step.status) {
        AgentStepStatus.COMPLETED -> Icons.Default.CheckCircle
        AgentStepStatus.FAILED,
        AgentStepStatus.CANCELLED -> Icons.Default.Cancel
        else -> Icons.Default.HourglassTop
    }
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(step.timestampMillis))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = step.status.labelEn,
                tint = statusColor,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = step.titleBn,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${step.status.labelEn} • $timeStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor
                    )
                }
                Text(
                    text = step.titleEn,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (step.detail.isNotBlank()) {
                    Text(
                        text = step.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
