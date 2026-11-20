package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentExecutionState
import com.example.agent.AgentMode
import com.example.agent.AgentPermissionsSnapshot
import com.example.agent.AgentTaskPlan
import com.example.agent.ProactiveAssistantEngine
import com.example.data.local.orchestration.LongTermTask
import com.example.data.local.orchestration.LongTermTaskStatus
import com.example.data.local.schedule.ScheduledTask
import com.example.data.local.skill.AgentSkill
import com.example.domain.model.AppSettings
import com.example.domain.model.AutonomyLevel
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.LiveSessionState
import com.example.ui.components.AgentStatusCard
import com.example.ui.components.FeatureCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.StatusIndicator
import com.example.ui.components.StatusSnackbarBanner
import com.example.ui.components.StatusTone
import com.example.ui.components.TaskProgressCard
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.ScreenSightSpacing
import java.util.Calendar

/**
 * Autonomous Agent Smart Command Hub (Home Tab).
 *
 * Hierarchy:
 * Clean Header -> Actionable Agent Readiness -> Unified Command Center (Autonomy + Command Input +
 * Voice/Screen + Floating Agent + Family Mode) -> Active Task Monitor -> Proactive Suggestions ->
 * Runnable Skills (Room-backed) -> Customizable Agent Missions -> Scheduled Tasks -> Recent Activity & Privacy.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    onNavigateToSafeBrowser: () -> Unit = {},
    // Phase 6 & 7 Smart Command Hub additions (all with safe defaults)
    activeAgentPlan: AgentTaskPlan? = null,
    agentExecutionState: AgentExecutionState = AgentExecutionState.IDLE,
    longTermTasks: List<LongTermTask> = emptyList(),
    scheduledTasks: List<ScheduledTask> = emptyList(),
    skills: List<AgentSkill> = emptyList(),
    proactiveSuggestions: List<ProactiveAssistantEngine.ProactiveSuggestion> = emptyList(),
    feedbackMessage: String? = null,
    onDismissFeedback: () -> Unit = {},
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToTasks: () -> Unit = onNavigateToAutonomousHub,
    onNavigateToSettingsHub: () -> Unit = onNavigateToApiSettings,
    onSendQuickInstruction: (String) -> Unit = {},
    onPauseLongTermTask: (String) -> Unit = {},
    onResumeLongTermTask: (String) -> Unit = {},
    onStopActiveTask: () -> Unit = {},
    onSelectAutonomyLevel: (AutonomyLevel) -> Unit = {},
    onLaunchFloatingAgent: () -> Unit = {},
    onFixAccessibility: () -> Unit = onNavigateToAgentPermissions,
    onFixMicrophone: () -> Unit = onNavigateToAgentPermissions,
    onFixOverlay: () -> Unit = onNavigateToAgentPermissions,
    onRunSkill: (String, Map<String, String>) -> Unit = { _, _ -> },
    onRunProactiveSuggestion: (ProactiveAssistantEngine.ProactiveSuggestion) -> Unit = {},
    onDismissProactiveSuggestion: (String) -> Unit = {},
    onRunCustomOpenApp: (String) -> Unit = { onQuickOpenYouTube() },
    onRunCustomAlarm: (Int, Int, String) -> Unit = { _, _, _ -> onQuickSetAlarm() }
) {
    val selectedModel = GeminiLiveModel.findByIdOrCustom(settings.selectedModelId)
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Welcome back"
        }
    }
    var quickInstruction by rememberSaveable { mutableStateOf("") }
    var showOpenAppDialog by rememberSaveable { mutableStateOf(false) }
    var showCustomAlarmDialog by rememberSaveable { mutableStateOf(false) }
    var skillAwaitingParams by remember { mutableStateOf<AgentSkill?>(null) }

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

    if (showOpenAppDialog) {
        CustomOpenAppMissionDialog(
            onDismiss = { showOpenAppDialog = false },
            onLaunchApp = { appName ->
                showOpenAppDialog = false
                onRunCustomOpenApp(appName)
            }
        )
    }

    if (showCustomAlarmDialog) {
        CustomAlarmMissionDialog(
            onDismiss = { showCustomAlarmDialog = false },
            onSetAlarm = { hour, minute, label ->
                showCustomAlarmDialog = false
                onRunCustomAlarm(hour, minute, label)
            }
        )
    }

    skillAwaitingParams?.let { skill ->
        HomeSkillParamsDialog(
            skill = skill,
            onDismiss = { skillAwaitingParams = null },
            onRun = { params ->
                skillAwaitingParams = null
                onRunSkill(skill.skillId, params)
            }
        )
    }

    val activeLongTermTask = remember(longTermTasks) {
        longTermTasks.firstOrNull {
            it.status == LongTermTaskStatus.RUNNING || it.status == LongTermTaskStatus.PAUSED
        }
    }
    val upcomingSchedules = remember(scheduledTasks) {
        scheduledTasks.filter { it.enabled }.take(3)
    }
    val runnableSkills = remember(skills) {
        skills.filter { it.enabled && it.userApproved }.take(4)
    }
    val alertCount = proactiveSuggestions.size + if (hasPendingSkillProposal) 1 else 0

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("home_drawer_button")
                        ) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu")
                        }
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = "ScreenSight AI Agent",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "$greeting • ${settings.autonomyLevel.shortLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToAutonomousHub,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_notifications_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (alertCount > 0) {
                                    Badge { Text("$alertCount") }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsNone,
                                contentDescription = "Proactive suggestions and alerts",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(
                        onClick = onNavigateToAppearanceSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_appearance_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Display & Theme",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onNavigateToApiSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "API & Model Setup",
                            tint = if (hasApiKey) EmeraldLive else AmberThinking
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
                    .widthIn(max = ScreenSightSpacing.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusSnackbarBanner(
                    message = feedbackMessage,
                    onDismiss = onDismissFeedback,
                    testTag = "home_feedback_banner"
                )

                // 1. Actionable Agent Readiness Card (every capability chip fixes its exact permission)
                AgentStatusCard(
                    isReady = hasApiKey && permissions.hasMicrophone && permissions.isAgentControlReady,
                    modelName = "${selectedModel.displayName} • Direct BYOK",
                    apiStatusLabel = if (hasApiKey) "Ready" else "Missing",
                    accessibilityReady = permissions.isAgentControlReady,
                    microphoneReady = permissions.hasMicrophone,
                    screenCaptureLabel = if (isSharingScreen) "Live" else "Ready",
                    overlayReady = permissions.canDrawOverlays,
                    onOpenPermissions = onNavigateToAgentPermissions,
                    onClickApi = onNavigateToApiSettings,
                    onClickControl = onFixAccessibility,
                    onClickMic = onFixMicrophone,
                    onClickOverlay = onFixOverlay,
                    modifier = Modifier.testTag("agent_permissions_summary_card")
                )

                // 2. Unified Agent Command Center (Autonomy Switcher + Command Input + Live + Floating Overlay)
                FeatureCard(
                    modifier = Modifier.testTag("hero_welcome_card"),
                    contentPadding = PaddingValues(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Agent Command Hub",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        StatusIndicator(
                            label = when {
                                agentExecutionState.isActive -> agentExecutionState.labelEn
                                sessionState.isActive -> sessionState.displayName
                                else -> "Standby"
                            },
                            tone = if (sessionState.isActive || agentExecutionState.isActive) StatusTone.POSITIVE else StatusTone.INFO
                        )
                    }

                    // Inline Autonomy Level Switcher
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AutonomyLevel.entries.forEach { level ->
                            FilterChip(
                                selected = settings.autonomyLevel == level,
                                onClick = { onSelectAutonomyLevel(level) },
                                label = {
                                    Text(
                                        text = level.shortLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1
                                    )
                                },
                                modifier = Modifier.testTag("home_autonomy_${level.name.lowercase()}")
                            )
                        }
                    }

                    // Direct Autonomous Command Input
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = quickInstruction,
                            onValueChange = { quickInstruction = it },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_quick_instruction_input"),
                            placeholder = { Text("Tell the agent what to do...") },
                            singleLine = true,
                            shape = MaterialTheme.shapes.small
                        )
                        IconButton(
                            onClick = {
                                val trimmed = quickInstruction.trim()
                                if (trimmed.isNotEmpty()) {
                                    onSendQuickInstruction(trimmed)
                                    quickInstruction = ""
                                }
                            },
                            enabled = quickInstruction.isNotBlank(),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (quickInstruction.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .testTag("home_quick_instruction_send")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Run instruction",
                                tint = if (quickInstruction.isNotBlank()) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    // Primary Agent Launch CTA
                    when {
                        sessionState.isActive -> {
                            PrimaryButton(
                                text = stringResource(R.string.resume_live_session),
                                onClick = onNavigateToLiveScreen,
                                icon = Icons.Default.PresentToAll,
                                containerColor = EmeraldLive,
                                contentColor = Color(0xFF070B14),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("resume_live_button")
                            )
                        }
                        hasApiKey -> {
                            PrimaryButton(
                                text = "Launch Voice & Screen Agent",
                                onClick = onStartLiveClicked,
                                icon = Icons.Default.PlayArrow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("start_live_button")
                            )
                        }
                        else -> {
                            PrimaryButton(
                                text = stringResource(R.string.configure_api_key),
                                onClick = onNavigateToApiSettings,
                                icon = Icons.Default.Key,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("configure_api_key_button")
                            )
                        }
                    }

                    // Secondary Functional Agent Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SecondaryButton(
                            text = "Floating Agent",
                            onClick = onLaunchFloatingAgent,
                            icon = Icons.Default.Layers,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_floating_overlay_button")
                        )
                        SecondaryButton(
                            text = "Family Mode",
                            onClick = onOpenFamilyEasyMode,
                            icon = Icons.Default.Elderly,
                            tonal = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_family_mode_button")
                        )
                    }

                    TextButton(
                        onClick = onNavigateToLiveScreen,
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag("open_assistant_console_button")
                    ) {
                        Text("Open Full Agent Console")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // 3. Active Task Monitor (live when an autonomous plan or long-term task is active)
                if (activeAgentPlan != null && agentExecutionState.isActive) {
                    TaskProgressCard(
                        title = activeAgentPlan.taskDescription,
                        statusLabel = agentExecutionState.labelEn,
                        statusTone = StatusTone.INFO,
                        progress = activeAgentPlan.progressFraction,
                        stepSummary = "Step ${activeAgentPlan.completedStepsCount}/${activeAgentPlan.steps.size} • ${agentExecutionState.labelBn}",
                        onClick = onNavigateToLiveScreen,
                        modifier = Modifier.testTag("home_active_task_card")
                    ) {
                        SecondaryButton(
                            text = "Inspect",
                            onClick = onNavigateToLiveScreen,
                            tonal = true,
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryButton(
                            text = "Stop",
                            onClick = onStopActiveTask,
                            icon = Icons.Default.Stop,
                            contentColor = CrimsonAlert,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (activeLongTermTask != null) {
                    val nextStep = activeLongTermTask.subtasks.firstOrNull {
                        it.status.name == "RUNNING" || it.status.name == "PENDING"
                    }
                    TaskProgressCard(
                        title = activeLongTermTask.goal,
                        statusLabel = activeLongTermTask.status.labelEn,
                        statusTone = if (activeLongTermTask.status == LongTermTaskStatus.RUNNING) StatusTone.INFO else StatusTone.WARNING,
                        progress = activeLongTermTask.progressFraction,
                        stepSummary = nextStep?.let { "Current: ${it.title} (${activeLongTermTask.completedCount}/${activeLongTermTask.totalCount})" }
                            ?: "${activeLongTermTask.completedCount}/${activeLongTermTask.totalCount} steps complete",
                        onClick = onNavigateToTasks,
                        modifier = Modifier.testTag("home_active_task_card")
                    ) {
                        if (activeLongTermTask.status == LongTermTaskStatus.RUNNING) {
                            SecondaryButton(
                                text = "Pause",
                                onClick = { onPauseLongTermTask(activeLongTermTask.taskId) },
                                icon = Icons.Default.Pause,
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            SecondaryButton(
                                text = "Resume",
                                onClick = { onResumeLongTermTask(activeLongTermTask.taskId) },
                                icon = Icons.Default.PlayArrow,
                                tonal = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        SecondaryButton(
                            text = "Stop",
                            onClick = onStopActiveTask,
                            icon = Icons.Default.Stop,
                            contentColor = CrimsonAlert,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 4. Proactive Suggestions Strip (surfaces real ProactiveAssistantEngine insights)
                if (proactiveSuggestions.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.testTag("home_proactive_section")
                    ) {
                        SectionHeaderRow(
                            title = "Suggested For You",
                            actionLabel = "All (${proactiveSuggestions.size})",
                            onAction = onNavigateToAutonomousHub
                        )
                        proactiveSuggestions.take(2).forEach { suggestion ->
                            FeatureCard(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = suggestion.titleEn,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = suggestion.reasonEn,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDismissProactiveSuggestion(suggestion.id) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Dismiss suggestion",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                SecondaryButton(
                                    text = "Run Suggestion",
                                    onClick = { onRunProactiveSuggestion(suggestion) },
                                    icon = Icons.Default.PlayArrow,
                                    tonal = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // 5. Runnable Skills (dynamically populated from approved local Room skills)
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.testTag("home_skills_section")
                ) {
                    SectionHeaderRow(
                        title = "Runnable Skills",
                        actionLabel = "Library ($skillCount)",
                        onAction = onNavigateToMemorySkills
                    )
                    if (runnableSkills.isEmpty()) {
                        FeatureCard(
                            onClick = onNavigateToMemorySkills,
                            modifier = Modifier.testTag("home_memory_skills_card")
                        ) {
                            Text(
                                text = "Create or enable reusable agent workflows in Library ($memoryCount memories saved).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        runnableSkills.chunked(2).forEachIndexed { rowIdx, rowSkills ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowSkills.forEach { skill ->
                                    DashboardActionTile(
                                        icon = Icons.Default.AutoAwesome,
                                        title = skill.name,
                                        subtitle = "${skill.executionSteps.size} step(s) • Tap to run",
                                        onClick = {
                                            if (skill.requiredParameters.isNotEmpty()) {
                                                skillAwaitingParams = skill
                                            } else {
                                                onRunSkill(skill.skillId, emptyMap())
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("home_skill_tile_${skill.skillId}")
                                    )
                                }
                                if (rowSkills.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        if (rowIdxHasLink(hasPendingSkillProposal, memoryCount)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(onClick = onNavigateToMemorySkills)
                                    .testTag("home_memory_skills_card"),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (hasPendingSkillProposal) {
                                            "New learned skill proposal ready for review"
                                        } else {
                                            "Memory & Skills Library • $memoryCount memories, $skillCount skills"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (hasPendingSkillProposal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // 6. Customizable Agent Missions (replaces hardcoded prototype buttons)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeaderRow(
                        title = "Agent Missions",
                        actionLabel = "Tasks ($longTermTaskCount)",
                        onAction = onNavigateToTasks
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DashboardActionTile(
                            icon = Icons.Default.Apps,
                            title = "Open Any App",
                            subtitle = "Choose app to launch",
                            onClick = { showOpenAppDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_youtube")
                        )
                        DashboardActionTile(
                            icon = Icons.Default.Alarm,
                            title = "Custom Alarm",
                            subtitle = "Pick time & label",
                            onClick = { showCustomAlarmDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_alarm")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DashboardActionTile(
                            icon = Icons.Default.RecordVoiceOver,
                            title = "Scan Screen",
                            subtitle = "Read active UI",
                            onClick = onQuickReadScreen,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_read_screen")
                        )
                        DashboardActionTile(
                            icon = Icons.Default.Language,
                            title = "Safe Web Agent",
                            subtitle = "Autonomous browser",
                            onClick = onNavigateToSafeBrowser,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_safe_browser_card")
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DashboardActionTile(
                            icon = Icons.Default.Call,
                            title = "Trusted Calls",
                            subtitle = "Family contacts",
                            onClick = onNavigateToTrustedContacts,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("quick_action_contacts")
                        )
                        DashboardActionTile(
                            icon = Icons.Default.Tune,
                            title = "Tools & Engine",
                            subtitle = "25 tools • $proactiveSuggestionCount tips",
                            onClick = onNavigateToAutonomousHub,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_autonomous_hub_card")
                        )
                    }
                }

                // 7. Upcoming Scheduled Tasks
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeaderRow(
                        title = "Scheduled Routines",
                        actionLabel = "Manage ($scheduledTaskCount)",
                        onAction = onNavigateToTasks
                    )
                    FeatureCard(onClick = onNavigateToTasks) {
                        if (upcomingSchedules.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "No scheduled routines yet. Tap to automate daily reminders or skills.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            upcomingSchedules.forEach { schedule ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = schedule.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${schedule.recurrence.labelEn} • ${"%02d:%02d".format(schedule.hourOfDay, schedule.minuteOfHour)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    StatusIndicator(label = "Active", tone = StatusTone.POSITIVE)
                                }
                            }
                        }
                    }
                }

                // 8. Recent Activity & On-Device Privacy Disclosure
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeaderRow(
                        title = "Recent Activity",
                        actionLabel = "Settings",
                        onAction = onNavigateToSettingsHub
                    )
                    FeatureCard(modifier = Modifier.testTag("last_session_card")) {
                        Text(
                            text = settings.lastSessionSummary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Last Agent Action: ${settings.lastAgentTaskSummary}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    FeatureCard(
                        onClick = onNavigateToPrivacy,
                        modifier = Modifier.testTag("privacy_info_card"),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Privacy & Security",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "On-Device Control & BYOK Privacy",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (hasApiKey) {
                                        "Key stored locally ($maskedApiKey) • Tap to review disclosures"
                                    } else {
                                        "No developer backend • Tap to review transparency disclosures"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

private fun rowIdxHasLink(hasPendingSkillProposal: Boolean, memoryCount: Int): Boolean = true

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomOpenAppMissionDialog(
    onDismiss: () -> Unit,
    onLaunchApp: (String) -> Unit
) {
    var appName by rememberSaveable { mutableStateOf("") }
    val popularApps = listOf("YouTube", "Chrome", "WhatsApp", "Maps", "Settings", "Calculator")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open Any App") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Pick an app or enter any installed app name for the agent to launch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    popularApps.forEach { preset ->
                        FilterChip(
                            selected = appName.equals(preset, ignoreCase = true),
                            onClick = { appName = preset },
                            label = { Text(preset) },
                            modifier = Modifier.testTag("mission_app_preset_${preset.lowercase()}")
                        )
                    }
                }
                OutlinedTextField(
                    value = appName,
                    onValueChange = { appName = it },
                    label = { Text("App name") },
                    placeholder = { Text("e.g., Spotify, Camera, Notes") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mission_open_app_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onLaunchApp(appName.trim()) },
                enabled = appName.isNotBlank(),
                modifier = Modifier.testTag("mission_open_app_confirm")
            ) {
                Text("Launch")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CustomAlarmMissionDialog(
    onDismiss: () -> Unit,
    onSetAlarm: (Int, Int, String) -> Unit
) {
    var hourText by rememberSaveable { mutableStateOf("7") }
    var minuteText by rememberSaveable { mutableStateOf("30") }
    var label by rememberSaveable { mutableStateOf("Agent Reminder") }
    val parsedHour = hourText.toIntOrNull()
    val parsedMinute = minuteText.toIntOrNull()
    val isValid = parsedHour != null && parsedHour in 0..23 && parsedMinute != null && parsedMinute in 0..59

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Custom Alarm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { if (it.length <= 2) hourText = it.filter(Char::isDigit) },
                        label = { Text("Hour (0-23)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mission_alarm_hour_input")
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { if (it.length <= 2) minuteText = it.filter(Char::isDigit) },
                        label = { Text("Min (0-59)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("mission_alarm_minute_input")
                    )
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Alarm label") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mission_alarm_label_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (isValid && parsedHour != null && parsedMinute != null) {
                        onSetAlarm(parsedHour, parsedMinute, label.ifBlank { "Alarm" })
                    }
                },
                enabled = isValid,
                modifier = Modifier.testTag("mission_alarm_confirm")
            ) {
                Text("Set Alarm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun HomeSkillParamsDialog(
    skill: AgentSkill,
    onDismiss: () -> Unit,
    onRun: (Map<String, String>) -> Unit
) {
    val paramValues = remember(skill.skillId) {
        mutableStateMapOf<String, String>().apply {
            skill.requiredParameters.forEach { put(it, "") }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run: ${skill.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                skill.requiredParameters.forEach { param ->
                    OutlinedTextField(
                        value = paramValues[param].orEmpty(),
                        onValueChange = { paramValues[param] = it },
                        label = { Text(param.replace("_", " ").replaceFirstChar { it.uppercase() }) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRun(paramValues.toMap()) },
                enabled = skill.requiredParameters.all { paramValues[it].orEmpty().isNotBlank() }
            ) {
                Text("Run Skill")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SectionHeaderRow(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun DashboardActionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .heightIn(min = 76.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Screen & Audio Privacy Notice",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "When you start a live session, ScreenSight AI streams microphone audio and (when you grant MediaProjection consent) 1 FPS screen frames directly to Google's Gemini Live API using your own API key.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "No screen recordings or audio files are stored on disk, and no developer-owned backend sits in between. You can stop screen sharing or end the session at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("privacy_dialog_confirm_button")
            ) {
                Text("I Understand — Continue")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReadFullPolicy) {
                    Text("Full Details")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
