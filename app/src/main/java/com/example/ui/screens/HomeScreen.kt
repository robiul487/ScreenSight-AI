package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.AgentExecutionState
import com.example.agent.AgentMode
import com.example.agent.AgentPermissionsSnapshot
import com.example.agent.AgentTaskPlan
import com.example.data.local.orchestration.LongTermTask
import com.example.data.local.orchestration.LongTermTaskStatus
import com.example.data.local.schedule.ScheduledTask
import com.example.domain.model.AppSettings
import com.example.domain.model.GeminiLiveModel
import com.example.domain.model.LiveSessionState
import com.example.ui.components.AgentStatusCard
import com.example.ui.components.FeatureCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.StatusIndicator
import com.example.ui.components.StatusTone
import com.example.ui.components.TaskProgressCard
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.ScreenSightSpacing
import java.util.Calendar

/**
 * Phase 6 Redesigned Home Dashboard.
 * Clean, high-hierarchy AI command center with progressive disclosure:
 * Header -> Agent Status -> Main Assistant Card -> Active Task -> Quick Actions ->
 * Scheduled Preview -> Recent Activity & Privacy.
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
    onNavigateToSafeBrowser: () -> Unit = {},
    // Phase 6 additions with safe defaults
    activeAgentPlan: AgentTaskPlan? = null,
    agentExecutionState: AgentExecutionState = AgentExecutionState.IDLE,
    longTermTasks: List<LongTermTask> = emptyList(),
    scheduledTasks: List<ScheduledTask> = emptyList(),
    onOpenDrawer: (() -> Unit)? = null,
    onNavigateToTasks: () -> Unit = onNavigateToAutonomousHub,
    onNavigateToSettingsHub: () -> Unit = onNavigateToApiSettings,
    onSendQuickInstruction: (String) -> Unit = {},
    onPauseLongTermTask: (String) -> Unit = {},
    onResumeLongTermTask: (String) -> Unit = {},
    onStopActiveTask: () -> Unit = {}
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

    val activeLongTermTask = remember(longTermTasks) {
        longTermTasks.firstOrNull {
            it.status == LongTermTaskStatus.RUNNING || it.status == LongTermTaskStatus.PAUSED
        }
    }
    val upcomingSchedules = remember(scheduledTasks) {
        scheduledTasks.filter { it.enabled }.take(3)
    }

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
                                imageVector = Icons.Default.PresentToAll,
                                contentDescription = "ScreenSight AI Logo",
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
                                text = greeting,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Icon(
                            imageVector = Icons.Default.NotificationsNone,
                            contentDescription = "Proactive suggestions and alerts"
                        )
                    }
                    IconButton(
                        onClick = onNavigateToAppearanceSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_appearance_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = "Appearance Settings"
                        )
                    }
                    IconButton(
                        onClick = onNavigateToApiSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_settings_button")
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
                    .widthIn(max = ScreenSightSpacing.maxContentWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Agent Status Overview (never exposes raw API keys)
                AgentStatusCard(
                    isReady = hasApiKey && permissions.hasMicrophone,
                    modelName = "${selectedModel.displayName} • Direct BYOK",
                    apiStatusLabel = if (hasApiKey) "Configured" else "Missing",
                    accessibilityReady = permissions.isAgentControlReady,
                    microphoneReady = permissions.hasMicrophone,
                    screenCaptureLabel = if (isSharingScreen) "Sharing" else "On Demand",
                    onOpenPermissions = onNavigateToAgentPermissions,
                    modifier = Modifier.testTag("agent_permissions_summary_card")
                )

                // 2. Main Assistant Card (Voice, Screen-Aware, Text Input, Family Mode)
                FeatureCard(
                    modifier = Modifier.testTag("hero_welcome_card"),
                    contentPadding = PaddingValues(20.dp)
                ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Assistant Workspace",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            StatusIndicator(
                                label = if (sessionState.isActive) sessionState.displayName else "Standby",
                                tone = if (sessionState.isActive) StatusTone.POSITIVE else StatusTone.INFO
                            )
                        }

                        Text(
                            text = "Start live voice or screen-aware assistance, or send a direct instruction to plan and execute on device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Quick Text Instruction Input
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
                                placeholder = { Text("Ask or give an instruction...") },
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
                                    contentDescription = "Send instruction",
                                    tint = if (quickInstruction.isNotBlank()) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }

                        // Primary CTA
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
                                    text = stringResource(R.string.start_live_session),
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

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SecondaryButton(
                                text = "Agent Console",
                                onClick = onNavigateToLiveScreen,
                                icon = Icons.AutoMirrored.Filled.ArrowForward,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("open_assistant_console_button")
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
                }

                // 3. Active Task (shown dynamically when a coordinator plan or long-term task is active/paused)
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

                // 4. Quick Actions (Only implemented & real actions)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeaderRow(title = "Quick Actions")
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DashboardActionTile(
                                icon = Icons.Default.PlayCircle,
                                title = "Open App",
                                subtitle = "Launch YouTube",
                                onClick = onQuickOpenYouTube,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quick_action_youtube")
                            )
                            DashboardActionTile(
                                icon = Icons.Default.Alarm,
                                title = "Set Alarm",
                                subtitle = "6:00 AM Clock",
                                onClick = onQuickSetAlarm,
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
                                title = "Read Screen",
                                subtitle = "Visible UI text",
                                onClick = onQuickReadScreen,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quick_action_read_screen")
                            )
                            DashboardActionTile(
                                icon = Icons.Default.TaskAlt,
                                title = "Create Task",
                                subtitle = "$longTermTaskCount saved",
                                onClick = onNavigateToTasks,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quick_action_create_task")
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DashboardActionTile(
                                icon = Icons.Default.Psychology,
                                title = "Run Skill",
                                subtitle = "$skillCount skills • $memoryCount mem",
                                onClick = onNavigateToMemorySkills,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("home_memory_skills_card")
                            )
                            DashboardActionTile(
                                icon = Icons.Default.Language,
                                title = "Browse Web",
                                subtitle = "Safe WebView",
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
                                icon = Icons.Default.AutoAwesome,
                                title = "Tools & Tips",
                                subtitle = "$proactiveSuggestionCount suggestions",
                                onClick = onNavigateToAutonomousHub,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("home_autonomous_hub_card")
                            )
                        }
                }

                // 5. Upcoming Scheduled Tasks (Compact progressive disclosure)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeaderRow(
                            title = "Scheduled Tasks",
                            actionLabel = "View all ($scheduledTaskCount)",
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
                                        text = "No upcoming schedules. Create reminders or routines in Tasks.",
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

                // 6. Recent Activity & Direct Privacy Link
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
                            if (hasPendingSkillProposal) {
                                TextButton(onClick = onNavigateToMemorySkills) {
                                    Text("Review newly proposed skill in Library")
                                }
                            }
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
