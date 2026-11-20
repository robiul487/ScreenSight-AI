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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.agent.AgentExecutionState
import com.example.agent.AgentStepStatus
import com.example.agent.AgentTaskLogger
import com.example.agent.AgentTaskPlan
import com.example.agent.AgentTaskStep
import com.example.agent.LastActionVerification
import com.example.agent.PendingConfirmation
import com.example.agent.PhysicalDeviceTestHarness
import com.example.agent.ScreenUiSnapshot
import com.example.data.local.checkpoint.RecoverableTaskCheckpoint
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    agentExecutionState: AgentExecutionState = AgentExecutionState.IDLE,
    activeAgentPlan: AgentTaskPlan? = null,
    agentTaskSteps: List<AgentTaskStep> = emptyList(),
    agentTaskLogs: List<AgentTaskLogger.TaskLogEntry> = emptyList(),
    isAgentTaskRunning: Boolean = false,
    pendingConfirmation: PendingConfirmation? = null,
    isAccessibilityConnected: Boolean = false,
    activeForegroundPackage: String = "",
    latestScreenSnapshot: ScreenUiSnapshot? = null,
    lastActionVerification: LastActionVerification? = null,
    recoverableCheckpoints: List<RecoverableTaskCheckpoint> = emptyList(),
    deviceTestRecords: List<PhysicalDeviceTestHarness.DeviceTestRunRecord> = emptyList(),
    activeHarnessScenario: PhysicalDeviceTestHarness.DeviceTestScenario? = null,
    onResumeCheckpoint: (String) -> Unit = {},
    onCancelCheckpoint: (String) -> Unit = {},
    onRefreshScreenSnapshot: () -> Unit = {},
    onSelectHarnessScenario: (PhysicalDeviceTestHarness.DeviceTestScenario?) -> Unit = {},
    onClearHarnessRecords: () -> Unit = {},
    onApproveConfirmation: () -> Unit = {},
    onRejectConfirmation: () -> Unit = {},
    onStopAgentTask: () -> Unit = {},
    onClearAgentLogs: () -> Unit = {},
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
    onNavigateToApiSettings: () -> Unit,
    isTopLevelTab: Boolean = false,
    onOpenDrawer: (() -> Unit)? = null,
    onRunAutonomousCommand: (String) -> Boolean = {
        onSendTextMessage(it)
        true
    },
    onLaunchFloatingAgent: () -> Unit = {}
) {
    BackHandler(onBack = onBack)

    var messageInput by rememberSaveable { mutableStateOf("") }
    // 0 = Transcript, 1 = Agent Activity, 2 = Task Logs, 3 = Phase 3 Diagnostics
    var selectedConsoleTab by rememberSaveable { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val agentListState = rememberLazyListState()
    val logListState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty() && selectedConsoleTab == 0) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    LaunchedEffect(agentTaskSteps.size) {
        if (agentTaskSteps.isNotEmpty() && selectedConsoleTab == 1) {
            agentListState.animateScrollToItem(agentTaskSteps.size - 1)
        }
    }

    LaunchedEffect(agentTaskLogs.size) {
        if (agentTaskLogs.isNotEmpty() && selectedConsoleTab == 2) {
            logListState.animateScrollToItem(agentTaskLogs.size - 1)
        }
    }

    Scaffold(
        contentWindowInsets = if (isTopLevelTab) WindowInsets.statusBars else WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (isTopLevelTab && onOpenDrawer != null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.testTag("live_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Open navigation menu"
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("live_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Home"
                            )
                        }
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
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

                // Phase 6: Clear Visual Agent Stage Indicator when agent is active
                AnimatedVisibility(visible = agentExecutionState != AgentExecutionState.IDLE) {
                    AgentExecutionStageStrip(state = agentExecutionState)
                }

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

                // Phase 3 Recoverable Task Checkpoint Banner (shown after restart when incomplete task exists)
                AnimatedVisibility(visible = recoverableCheckpoints.isNotEmpty() && pendingConfirmation == null) {
                    recoverableCheckpoints.firstOrNull()?.let { checkpoint ->
                        RecoverableCheckpointBanner(
                            checkpoint = checkpoint,
                            onResume = { onResumeCheckpoint(checkpoint.taskId) },
                            onCancel = { onCancelCheckpoint(checkpoint.taskId) }
                        )
                    }
                }

                // Compact Autonomous Agent Execution State & Multi-Step Plan Strip
                AnimatedVisibility(
                    visible = activeAgentPlan != null || agentExecutionState != AgentExecutionState.IDLE
                ) {
                    AgentPlanStatusCard(
                        executionState = agentExecutionState,
                        activePlan = activeAgentPlan
                    )
                }

                // Console View Switcher: Transcript | Agent Activity | Task Logs | Diagnostics + Stop Task Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = selectedConsoleTab == 0,
                            onClick = { selectedConsoleTab = 0 },
                            label = {
                                Text(
                                    text = "Chat",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag("console_tab_transcript")
                        )
                        FilterChip(
                            selected = selectedConsoleTab == 1,
                            onClick = { selectedConsoleTab = 1 },
                            label = {
                                Text(
                                    text = "Steps (${agentTaskSteps.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag("console_tab_agent_activity")
                        )
                        FilterChip(
                            selected = selectedConsoleTab == 2,
                            onClick = { selectedConsoleTab = 2 },
                            label = {
                                Text(
                                    text = "Logs (${agentTaskLogs.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag("console_tab_task_logs")
                        )
                        FilterChip(
                            selected = selectedConsoleTab == 3,
                            onClick = { selectedConsoleTab = 3 },
                            label = {
                                Text(
                                    text = "Tools",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            },
                            modifier = Modifier.testTag("console_tab_diagnostics")
                        )
                    }
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
                            Text("Stop", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (selectedConsoleTab == 2 && agentTaskLogs.isNotEmpty()) {
                        TextButton(
                            onClick = onClearAgentLogs,
                            modifier = Modifier.testTag("clear_agent_logs_button")
                        ) {
                            Text("Clear", style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (selectedConsoleTab == 3) {
                        TextButton(
                            onClick = onRefreshScreenSnapshot,
                            modifier = Modifier.testTag("refresh_diagnostics_button")
                        ) {
                            Text("Refresh", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                // Conversation Transcript OR Agent Activity Step Log OR Sanitized Task Logs Area
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
                    when (selectedConsoleTab) {
                        1 -> {
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
                        }

                        2 -> {
                            if (agentTaskLogs.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No task execution logs yet.\nAutonomous state transitions, plans, tool calls, retries, and outcomes appear here (privacy-scrubbed).",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.testTag("agent_task_logs_empty")
                                    )
                                }
                            } else {
                                LazyColumn(
                                    state = logListState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("agent_task_logs_list"),
                                    contentPadding = PaddingValues(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(agentTaskLogs) { entry ->
                                        AgentTaskLogRow(entry = entry)
                                    }
                                }
                            }
                        }

                        3 -> {
                            AgentDiagnosticsConsolePanel(
                                isAccessibilityConnected = isAccessibilityConnected,
                                activeForegroundPackage = activeForegroundPackage,
                                latestScreenSnapshot = latestScreenSnapshot,
                                agentExecutionState = agentExecutionState,
                                activeAgentPlan = activeAgentPlan,
                                pendingConfirmation = pendingConfirmation,
                                lastActionVerification = lastActionVerification,
                                deviceTestRecords = deviceTestRecords,
                                activeHarnessScenario = activeHarnessScenario,
                                onSelectHarnessScenario = onSelectHarnessScenario,
                                onClearHarnessRecords = onClearHarnessRecords
                            )
                        }

                        else -> if (transcript.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(18.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = if (sessionState.isActive) Icons.Default.GraphicEq else Icons.Default.SmartToy,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                        modifier = Modifier.size(38.dp)
                                    )
                                    Text(
                                        text = if (sessionState.isActive) {
                                            "Live voice & screen session is active.\nSpeak naturally in Bangla or English, or tap Share Screen below."
                                        } else {
                                            "Dual-Mode Agent Ready.\nType any command below to run immediately on device, or start a Live Voice & Screen session."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    if (!sessionState.isActive) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            AssistChip(
                                                onClick = {
                                                    val sentLive = onRunAutonomousCommand("Check date and time")
                                                    if (!sentLive) selectedConsoleTab = 1
                                                },
                                                label = { Text("Check time") },
                                                leadingIcon = {
                                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                                },
                                                modifier = Modifier.testTag("agent_starter_time")
                                            )
                                            AssistChip(
                                                onClick = {
                                                    val sentLive = onRunAutonomousCommand("Read current screen")
                                                    if (!sentLive) selectedConsoleTab = 1
                                                },
                                                label = { Text("Scan screen") },
                                                modifier = Modifier.testTag("agent_starter_screen")
                                            )
                                            AssistChip(
                                                onClick = {
                                                    val sentLive = onRunAutonomousCommand("Open YouTube")
                                                    if (!sentLive) selectedConsoleTab = 1
                                                },
                                                label = { Text("Open YouTube") },
                                                modifier = Modifier.testTag("agent_starter_youtube")
                                            )
                                            AssistChip(
                                                onClick = onLaunchFloatingAgent,
                                                label = { Text("Floating Pill") },
                                                leadingIcon = {
                                                    Icon(Icons.Default.PictureInPictureAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                                },
                                                modifier = Modifier.testTag("agent_floating_overlay_button")
                                            )
                                        }
                                    }
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
                }

                // Dual-Mode Input Row: Live chat when active OR immediate on-device autonomous command when idle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val canSubmitCommand = messageInput.isNotBlank()
                    OutlinedTextField(
                        value = messageInput,
                        onValueChange = { messageInput = it },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_text_input"),
                        placeholder = {
                            Text(
                                when {
                                    isSharingScreen -> "Ask about what's on screen..."
                                    sessionState.isActive -> "Type a live message or speak aloud..."
                                    else -> "Give a command to run autonomously..."
                                }
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )
                    IconButton(
                        onClick = {
                            val trimmed = messageInput.trim()
                            if (trimmed.isNotEmpty()) {
                                val sentToLiveSession = onRunAutonomousCommand(trimmed)
                                messageInput = ""
                                if (!sentToLiveSession) {
                                    selectedConsoleTab = 1
                                }
                            }
                        },
                        enabled = canSubmitCommand,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (canSubmitCommand) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                            .testTag("send_text_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = if (sessionState.isActive) "Send Message" else "Run Command Autonomously",
                            tint = if (canSubmitCommand) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }

                // Bottom Control Dock: Mic toggle, AI audio toggle, Share/Stop Screen, Start/End Session + Floating Pill
                LiveControlDock(
                    sessionState = sessionState,
                    isSharingScreen = isSharingScreen,
                    isMicMuted = isMicMuted,
                    isAiMuted = isAiMuted,
                    applyNavigationBarsPadding = !isTopLevelTab,
                    onStartSession = onStartSession,
                    onLaunchFloatingAgent = onLaunchFloatingAgent,
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
private fun AgentExecutionStageStrip(state: AgentExecutionState) {
    val stages = listOf(
        "Understand" to (state == AgentExecutionState.UNDERSTANDING),
        "Plan" to (state == AgentExecutionState.PLANNING),
        "Execute" to (state == AgentExecutionState.EXECUTING || state == AgentExecutionState.WAITING_FOR_USER || state == AgentExecutionState.WAITING_FOR_ACTION),
        "Verify" to (state == AgentExecutionState.OBSERVING || state == AgentExecutionState.RECOVERING),
        "Done" to (state == AgentExecutionState.COMPLETED)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("agent_stage_strip"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stages.forEach { (label, active) ->
            val accent = when {
                state == AgentExecutionState.FAILED && label == "Execute" -> CrimsonAlert
                active -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = if (active) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Distinct Custom Canvas State Visualizer (not a generic spinner)
                Box(
                    modifier = Modifier.size(50.dp),
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
    applyNavigationBarsPadding: Boolean = true,
    onStartSession: () -> Unit,
    onLaunchFloatingAgent: () -> Unit = {},
    onRequestShareScreen: () -> Unit,
    onStopShareScreen: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleAiAudioMute: () -> Unit,
    onEndConversation: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onStartSession,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("live_start_session_button"),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (sessionState == LiveSessionState.STOPPED || sessionState == LiveSessionState.ERROR) {
                                "New Live Session"
                            } else {
                                "Launch Voice & Screen"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                    FilledTonalButton(
                        onClick = onLaunchFloatingAgent,
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("live_dock_floating_button"),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureInPictureAlt,
                            contentDescription = "Launch Floating Agent Pill"
                        )
                    }
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
private fun AgentPlanStatusCard(
    executionState: AgentExecutionState,
    activePlan: AgentTaskPlan?
) {
    val badgeColor = when (executionState) {
        AgentExecutionState.COMPLETED -> EmeraldLive
        AgentExecutionState.FAILED -> CrimsonAlert
        AgentExecutionState.RECOVERING,
        AgentExecutionState.WAITING_FOR_USER -> AmberThinking
        AgentExecutionState.PLANNING,
        AgentExecutionState.UNDERSTANDING,
        AgentExecutionState.EXECUTING,
        AgentExecutionState.OBSERVING,
        AgentExecutionState.WAITING_FOR_ACTION -> ElectricCyan
        AgentExecutionState.CANCELLED,
        AgentExecutionState.IDLE -> Color(0xFF94A3B8)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_plan_status_card"),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(badgeColor)
                    )
                    Text(
                        text = "Agent: ${executionState.labelEn}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.testTag("agent_execution_state_badge")
                    )
                }
                if (activePlan != null && activePlan.steps.isNotEmpty()) {
                    Text(
                        text = "Plan ${activePlan.completedStepsCount}/${activePlan.steps.size} • Steps ${activePlan.totalToolStepsExecuted}/${activePlan.maxStepsAllowed}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (activePlan != null && activePlan.steps.isNotEmpty()) {
                Text(
                    text = activePlan.taskDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = { activePlan.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = badgeColor,
                    trackColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

@Composable
private fun AgentTaskLogRow(entry: AgentTaskLogger.TaskLogEntry) {
    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMillis))
    val tagColor = when (entry.type) {
        AgentTaskLogger.LogEventType.ERROR -> CrimsonAlert
        AgentTaskLogger.LogEventType.RETRY,
        AgentTaskLogger.LogEventType.CONFIRMATION -> AmberThinking
        AgentTaskLogger.LogEventType.STATE_TRANSITION,
        AgentTaskLogger.LogEventType.PLAN_CREATED,
        AgentTaskLogger.LogEventType.PLAN_UPDATED -> ElectricCyan
        AgentTaskLogger.LogEventType.TOOL_RESULT,
        AgentTaskLogger.LogEventType.TASK_FINISHED -> EmeraldLive
        else -> IndigoGlow
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeStr,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = entry.type.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tagColor
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AgentTaskStepRow(step: AgentTaskStep) {
    val statusColor = when (step.status) {
        AgentStepStatus.COMPLETED -> EmeraldLive
        AgentStepStatus.RUNNING -> ElectricCyan
        AgentStepStatus.RECOVERING,
        AgentStepStatus.WAITING_CONFIRMATION -> AmberThinking
        AgentStepStatus.FAILED -> CrimsonAlert
        AgentStepStatus.CANCELLED -> Color(0xFF94A3B8)
        AgentStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusIcon = when (step.status) {
        AgentStepStatus.COMPLETED -> Icons.Default.CheckCircle
        AgentStepStatus.RECOVERING -> Icons.Default.Refresh
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
                    val retrySuffix = if (step.retryCount > 0) " • Retry ${step.retryCount}" else ""
                    Text(
                        text = "${step.status.labelEn}$retrySuffix • $timeStr",
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

@Composable
private fun RecoverableCheckpointBanner(
    checkpoint: RecoverableTaskCheckpoint,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recoverable_checkpoint_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = IndigoGlow.copy(alpha = 0.16f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Incomplete Task Detected • অসমাপ্ত কাজ পাওয়া গেছে",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = ElectricCyan
            )
            Text(
                text = "${checkpoint.sanitizedRequest} (${checkpoint.completedStepsCount}/${checkpoint.totalStepsCount} steps)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (checkpoint.containsSensitiveAction) {
                Text(
                    text = "Safety: sensitive actions (calls/messages) will NOT be auto-replayed.",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberThinking
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("checkpoint_resume_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text("Resume Safe Steps", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("checkpoint_cancel_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    Text("Discard / বাতিল", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun AgentDiagnosticsConsolePanel(
    isAccessibilityConnected: Boolean,
    activeForegroundPackage: String,
    latestScreenSnapshot: ScreenUiSnapshot?,
    agentExecutionState: AgentExecutionState,
    activeAgentPlan: AgentTaskPlan?,
    pendingConfirmation: PendingConfirmation?,
    lastActionVerification: LastActionVerification?,
    deviceTestRecords: List<PhysicalDeviceTestHarness.DeviceTestRunRecord>,
    activeHarnessScenario: PhysicalDeviceTestHarness.DeviceTestScenario?,
    onSelectHarnessScenario: (PhysicalDeviceTestHarness.DeviceTestScenario?) -> Unit,
    onClearHarnessRecords: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("agent_diagnostics_panel"),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "On-Device Grounding & Runtime Status",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    DiagnosticLine(
                        label = "Accessibility Service",
                        value = if (isAccessibilityConnected) "CONNECTED" else "DISCONNECTED",
                        valueColor = if (isAccessibilityConnected) EmeraldLive else AmberThinking
                    )
                    DiagnosticLine(
                        label = "Foreground App",
                        value = activeForegroundPackage.ifBlank { latestScreenSnapshot?.packageName.orEmpty().ifBlank { "None" } }
                    )
                    DiagnosticLine(
                        label = "Grounded UI Elements",
                        value = if (latestScreenSnapshot != null) {
                            "${latestScreenSnapshot.elements.size} (${latestScreenSnapshot.observationId}, ${latestScreenSnapshot.screenWidthPx}x${latestScreenSnapshot.screenHeightPx})"
                        } else {
                            "0 (no snapshot)"
                        }
                    )
                    DiagnosticLine(
                        label = "Agent State",
                        value = "${agentExecutionState.name} (${agentExecutionState.labelBn})"
                    )
                    DiagnosticLine(
                        label = "Task Progress",
                        value = activeAgentPlan?.let { "${it.completedStepsCount}/${it.steps.size} • ${it.taskDescription.take(36)}" } ?: "No active plan"
                    )
                    DiagnosticLine(
                        label = "Pending Confirmation",
                        value = pendingConfirmation?.let { "${it.actionRequest.actionType.toolName} (${it.id.take(6)})" } ?: "None",
                        valueColor = if (pendingConfirmation != null) AmberThinking else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Last Action Verification",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = ElectricCyan
                    )
                    if (lastActionVerification == null) {
                        Text(
                            text = "No verified actions recorded in this session yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        DiagnosticLine(
                            label = "Tool / Status",
                            value = "${lastActionVerification.toolName} • ${lastActionVerification.status}",
                            valueColor = if (lastActionVerification.verifiedSuccess) EmeraldLive else CrimsonAlert
                        )
                        DiagnosticLine(
                            label = "Verified / Screen Changed",
                            value = "${lastActionVerification.verifiedSuccess} / ${lastActionVerification.screenChanged} • retries=${lastActionVerification.retryCount} • ${lastActionVerification.durationMs}ms"
                        )
                        Text(
                            text = lastActionVerification.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Physical Device Test Harness (10 Scenarios)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (deviceTestRecords.isNotEmpty()) {
                    TextButton(onClick = onClearHarnessRecords) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        items(PhysicalDeviceTestHarness.DeviceTestScenario.entries) { scenario ->
            val latest = deviceTestRecords.lastOrNull { it.scenario == scenario }
            val isArmed = activeHarnessScenario == scenario
            val badgeText = when (latest?.status) {
                PhysicalDeviceTestHarness.HarnessRunStatus.PASSED ->
                    if (latest.executedOnPhysicalDevice) "PASS (DEVICE)" else "PASS (SIM)"
                PhysicalDeviceTestHarness.HarnessRunStatus.FAILED -> "FAILED"
                else -> if (isArmed) "ARMED" else "NOT_RUN"
            }
            val badgeColor = when (latest?.status) {
                PhysicalDeviceTestHarness.HarnessRunStatus.PASSED -> EmeraldLive
                PhysicalDeviceTestHarness.HarnessRunStatus.FAILED -> CrimsonAlert
                else -> if (isArmed) ElectricCyan else MaterialTheme.colorScheme.onSurfaceVariant
            }
            Surface(
                onClick = { onSelectHarnessScenario(scenario) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("harness_scenario_${scenario.index}"),
                shape = RoundedCornerShape(12.dp),
                color = if (isArmed) {
                    ElectricCyan.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "#${scenario.index} ${scenario.titleEn}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor
                        )
                    }
                    Text(
                        text = latest?.let {
                            "tool=${it.toolName} • ${it.verificationResult} • retries=${it.retryCount} • ${it.executionDurationMs}ms"
                        } ?: scenario.expectedVerification,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLine(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
            maxLines = 1
        )
    }
}
