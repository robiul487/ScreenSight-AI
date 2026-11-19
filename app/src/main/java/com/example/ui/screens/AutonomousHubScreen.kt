package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TipsAndUpdates
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.agent.ActionRiskLevel
import com.example.agent.DynamicToolManager
import com.example.agent.MultiAgentOrchestrator
import com.example.agent.ProactiveAssistantEngine
import com.example.agent.SelfReflectionEngine
import com.example.data.local.metrics.ToolExecutionMetricEntity
import com.example.data.local.orchestration.LongTermTask
import com.example.data.local.orchestration.LongTermTaskStatus
import com.example.data.local.orchestration.SubtaskStatus
import com.example.data.local.schedule.ScheduleRecurrence
import com.example.data.local.schedule.ScheduledActionType
import com.example.data.local.schedule.ScheduledTask
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.IndigoGlow

/**
 * Phase 5: Autonomous Hub Screen (Bangla + English).
 *
 * Central transparent command center for:
 * 1. Tasks: Multi-stage DAG progress, step timeline, Pause/Resume/Retry/Stop, Reflection & Skill Improvement Proposals
 * 2. Schedules: View, create, pause, and delete local WorkManager schedules (explicit approval for device-modifying actions)
 * 3. Proactive: Toggle Proactive Suggestions completely on/off, view & run or dismiss local suggestions
 * 4. Tools & Logs: Dynamic Tool Discovery (schema, permissions, availability, safety tier, reliability metrics & activity log)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutonomousHubScreen(
    longTermTasks: List<LongTermTask>,
    scheduledTasks: List<ScheduledTask>,
    proactiveSuggestionsEnabled: Boolean,
    proactiveSuggestions: List<ProactiveAssistantEngine.ProactiveSuggestion>,
    discoveredTools: List<DynamicToolManager.ToolDescriptor>,
    toolReliabilityMetrics: List<SelfReflectionEngine.ToolReliabilitySummary>,
    toolExecutionHistory: List<ToolExecutionMetricEntity>,
    latestReflectionReport: SelfReflectionEngine.TaskReflectionReport?,
    skillImprovementProposals: List<SelfReflectionEngine.SkillImprovementProposal>,
    lastMultiAgentContext: MultiAgentOrchestrator.SharedAgentTaskContext?,
    feedbackMessage: String?,
    onCreateGoalTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onCreateSchedule: (
        title: String,
        actionType: ScheduledActionType,
        target: String,
        recurrence: ScheduleRecurrence,
        hour: Int,
        minute: Int,
        intervalMinutes: Long,
        userApproved: Boolean,
        requiresAccessibility: Boolean
    ) -> Unit,
    onToggleSchedule: (String, Boolean) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onCancelAllBackgroundWork: () -> Unit,
    onToggleProactiveEnabled: (Boolean) -> Unit,
    onRunProactiveSuggestion: (ProactiveAssistantEngine.ProactiveSuggestion) -> Unit,
    onDismissProactiveSuggestion: (String) -> Unit,
    onRefreshToolsAndMetrics: () -> Unit,
    onDismissImprovementProposal: (String) -> Unit,
    onOpenSafeBrowser: () -> Unit,
    onDismissFeedback: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showCreateScheduleDialog by remember { mutableStateOf(false) }

    if (showCreateScheduleDialog) {
        CreateScheduleDialog(
            onDismiss = { showCreateScheduleDialog = false },
            onConfirm = { title, actionType, target, recurrence, hour, minute, interval, approved, needsA11y ->
                onCreateSchedule(title, actionType, target, recurrence, hour, minute, interval, approved, needsA11y)
                showCreateScheduleDialog = false
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.autonomous_hub_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "স্বয়ংক্রিয় কাজ, সময়সূচী ও টুল নিয়ন্ত্রণ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("autonomous_hub_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenSafeBrowser,
                        modifier = Modifier.testTag("autonomous_hub_open_browser_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Open Safe Web Browser",
                            tint = ElectricCyan
                        )
                    }
                    IconButton(
                        onClick = onRefreshToolsAndMetrics,
                        modifier = Modifier.testTag("autonomous_hub_refresh_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Tools and Metrics"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .widthIn(max = 720.dp)
        ) {
            AnimatedVisibility(visible = !feedbackMessage.isNullOrBlank()) {
                feedbackMessage?.let { msg ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("autonomous_feedback_banner"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = IndigoGlow.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissFeedback) {
                                Text("OK")
                            }
                        }
                    }
                }
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    modifier = Modifier.testTag("hub_tab_tasks"),
                    text = { Text("Tasks (${longTermTasks.size})") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    modifier = Modifier.testTag("hub_tab_schedules"),
                    text = { Text("Schedules (${scheduledTasks.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    modifier = Modifier.testTag("hub_tab_proactive"),
                    text = { Text("Proactive (${proactiveSuggestions.size})") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    modifier = Modifier.testTag("hub_tab_tools"),
                    text = { Text("Tools (${discoveredTools.size})") }
                )
            }

            when (selectedTab) {
                0 -> TasksOrchestrationTab(
                    tasks = longTermTasks,
                    latestReflectionReport = latestReflectionReport,
                    improvementProposals = skillImprovementProposals,
                    lastMultiAgentContext = lastMultiAgentContext,
                    onCreateGoalTask = onCreateGoalTask,
                    onResumeTask = onResumeTask,
                    onPauseTask = onPauseTask,
                    onRetryTask = onRetryTask,
                    onCancelTask = onCancelTask,
                    onDeleteTask = onDeleteTask,
                    onDismissImprovementProposal = onDismissImprovementProposal
                )

                1 -> SchedulesTab(
                    schedules = scheduledTasks,
                    onOpenCreateDialog = { showCreateScheduleDialog = true },
                    onToggleSchedule = onToggleSchedule,
                    onDeleteSchedule = onDeleteSchedule,
                    onCancelAllBackgroundWork = onCancelAllBackgroundWork
                )

                2 -> ProactiveSuggestionsTab(
                    enabled = proactiveSuggestionsEnabled,
                    suggestions = proactiveSuggestions,
                    onToggleEnabled = onToggleProactiveEnabled,
                    onRunSuggestion = onRunProactiveSuggestion,
                    onDismissSuggestion = onDismissProactiveSuggestion
                )

                3 -> DynamicToolsAndLogsTab(
                    tools = discoveredTools,
                    reliabilityMetrics = toolReliabilityMetrics,
                    executionHistory = toolExecutionHistory
                )
            }
        }
    }
}

@Composable
private fun TasksOrchestrationTab(
    tasks: List<LongTermTask>,
    latestReflectionReport: SelfReflectionEngine.TaskReflectionReport?,
    improvementProposals: List<SelfReflectionEngine.SkillImprovementProposal>,
    lastMultiAgentContext: MultiAgentOrchestrator.SharedAgentTaskContext?,
    onCreateGoalTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onDismissImprovementProposal: (String) -> Unit
) {
    var goalInput by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "New Multi-Stage Autonomous Task",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Planned locally via Memory -> Planner -> Executor -> Verifier with Room checkpoints.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = goalInput,
                        onValueChange = { goalInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("long_term_goal_input"),
                        label = { Text("Goal (e.g., Check time, Open YouTube & search Bangla news)") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            val trimmed = goalInput.trim()
                            if (trimmed.isNotEmpty()) {
                                onCreateGoalTask(trimmed)
                                goalInput = ""
                            }
                        },
                        enabled = goalInput.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("run_multi_stage_goal_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Plan & Run Task", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (lastMultiAgentContext != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("multi_agent_audit_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = IndigoGlow.copy(alpha = 0.12f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Latest Multi-Agent Role Handoff",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = ElectricCyan
                        )
                        lastMultiAgentContext.auditTrail.forEach { entry ->
                            Text(
                                text = "• [${entry.role.name}] ${entry.note}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }

        if (latestReflectionReport != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("self_reflection_report_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Self-Reflection Evaluation",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = latestReflectionReport.summaryEn,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = latestReflectionReport.summaryBn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        items(improvementProposals, key = { it.proposalId }) { proposal ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("skill_improvement_proposal_${proposal.proposalId}"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AmberThinking.copy(alpha = 0.14f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Skill Improvement Suggestion (User Review)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = AmberThinking
                    )
                    Text(text = proposal.titleEn, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text = proposal.rationaleEn, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { onDismissImprovementProposal(proposal.proposalId) },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Acknowledge & Dismiss")
                    }
                }
            }
        }

        if (tasks.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Text(
                            text = "No multi-stage tasks yet",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Enter a goal above to create a checkpointed, pause/resumable autonomous task.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(tasks, key = { it.taskId }) { task ->
                LongTermTaskCard(
                    task = task,
                    onResume = { onResumeTask(task.taskId) },
                    onPause = { onPauseTask(task.taskId) },
                    onRetry = { onRetryTask(task.taskId) },
                    onCancel = { onCancelTask(task.taskId) },
                    onDelete = { onDeleteTask(task.taskId) }
                )
            }
        }
    }
}

@Composable
private fun LongTermTaskCard(
    task: LongTermTask,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val statusColor = when (task.status) {
        LongTermTaskStatus.COMPLETED -> EmeraldLive
        LongTermTaskStatus.RUNNING -> ElectricCyan
        LongTermTaskStatus.PAUSED -> AmberThinking
        LongTermTaskStatus.FAILED, LongTermTaskStatus.CANCELLED -> CrimsonAlert
        LongTermTaskStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("long_term_task_card_${task.taskId}"),
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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.goal,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${task.status.labelEn} (${task.status.labelBn}) • ${task.completedCount}/${task.totalCount} steps",
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete task")
                }
            }

            LinearProgressIndicator(
                progress = { task.progressFraction },
                modifier = Modifier.fillMaxWidth(),
                color = statusColor
            )

            if (task.lastCheckpointNote.isNotBlank()) {
                Text(
                    text = "Checkpoint: ${task.lastCheckpointNote}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Subtask Dependency Timeline
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                task.subtasks.forEachIndexed { idx, sub ->
                    val icon = when (sub.status) {
                        SubtaskStatus.COMPLETED -> Icons.Default.CheckCircle
                        SubtaskStatus.FAILED, SubtaskStatus.CANCELLED -> Icons.Default.Cancel
                        else -> Icons.Default.Schedule
                    }
                    val tint = when (sub.status) {
                        SubtaskStatus.COMPLETED -> EmeraldLive
                        SubtaskStatus.FAILED, SubtaskStatus.CANCELLED -> CrimsonAlert
                        SubtaskStatus.RUNNING -> ElectricCyan
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                        Text(
                            text = "${idx + 1}. ${sub.title} [${sub.toolName}] — ${sub.status.name}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (task.status == LongTermTaskStatus.PAUSED || task.status == LongTermTaskStatus.PENDING) {
                    FilledTonalButton(
                        onClick = onResume,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume")
                    }
                }
                if (task.status == LongTermTaskStatus.RUNNING) {
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pause")
                    }
                }
                if (task.status == LongTermTaskStatus.FAILED) {
                    FilledTonalButton(
                        onClick = onRetry,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry")
                    }
                }
                if (!task.status.isTerminal) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }
}

@Composable
private fun SchedulesTab(
    schedules: List<ScheduledTask>,
    onOpenCreateDialog: () -> Unit,
    onToggleSchedule: (String, Boolean) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onCancelAllBackgroundWork: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenCreateDialog,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("add_schedule_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Schedule", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onCancelAllBackgroundWork,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("cancel_all_background_work_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Stop BG")
                }
            }
        }

        item {
            Text(
                text = "Uses WorkManager locally with visible notifications and respects Android battery optimization & reboot recovery. Sensitive actions (calls, payments, messages) can never be scheduled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (schedules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(32.dp)
                        )
                        Text("No scheduled tasks yet", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Tap 'New Schedule' to create a local reminder, status check, or approved shortcut.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(schedules, key = { it.scheduleId }) { schedule ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("schedule_card_${schedule.scheduleId}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = schedule.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${schedule.actionType.labelEn} • ${schedule.recurrence.labelEn} (${"%02d:%02d".format(schedule.hourOfDay, schedule.minuteOfHour)})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan
                                )
                            }
                            Switch(
                                checked = schedule.enabled,
                                onCheckedChange = { onToggleSchedule(schedule.scheduleId, it) },
                                modifier = Modifier.testTag("schedule_toggle_${schedule.scheduleId}")
                            )
                            IconButton(
                                onClick = { onDeleteSchedule(schedule.scheduleId) },
                                modifier = Modifier.testTag("schedule_delete_${schedule.scheduleId}")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete schedule")
                            }
                        }
                        if (schedule.targetAppOrSkillId.isNotBlank()) {
                            Text(
                                text = "Target: ${schedule.targetAppOrSkillId}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!schedule.lastRunResult.isNullOrBlank()) {
                            Text(
                                text = "Last run: ${schedule.lastRunResult}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProactiveSuggestionsTab(
    enabled: Boolean,
    suggestions: List<ProactiveAssistantEngine.ProactiveSuggestion>,
    onToggleEnabled: (Boolean) -> Unit,
    onRunSuggestion: (ProactiveAssistantEngine.ProactiveSuggestion) -> Unit,
    onDismissSuggestion: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Proactive Suggestions (স্মার্ট পরামর্শ)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Strictly local using approved memories & safe skills. Zero hidden monitoring; never auto-executes sensitive actions.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.testTag("proactive_suggestions_switch")
                    )
                }
            }
        }

        if (!enabled) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = "Proactive suggestions are completely disabled. Turn on the switch above anytime to receive local routine suggestions.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else if (suggestions.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TipsAndUpdates,
                            contentDescription = null,
                            tint = EmeraldLive,
                            modifier = Modifier.size(32.dp)
                        )
                        Text("No suggestions right now", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Save local routines, app preferences, or run skills to see contextual suggestions here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(suggestions, key = { it.id }) { suggestion ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("proactive_suggestion_${suggestion.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${suggestion.titleEn} • ${suggestion.titleBn}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = suggestion.reasonEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onRunSuggestion(suggestion) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Run Now")
                            }
                            OutlinedButton(
                                onClick = { onDismissSuggestion(suggestion.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicToolsAndLogsTab(
    tools: List<DynamicToolManager.ToolDescriptor>,
    reliabilityMetrics: List<SelfReflectionEngine.ToolReliabilitySummary>,
    executionHistory: List<ToolExecutionMetricEntity>
) {
    val metricsByName = remember(reliabilityMetrics) {
        reliabilityMetrics.associateBy { it.toolName }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "Registered Tools (${tools.size}) — Inspected Live from Central Registry",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(tools, key = { it.name }) { tool ->
            val reliability = metricsByName[tool.name]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("discovered_tool_${tool.name}"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
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
                            Icon(
                                imageVector = Icons.Default.Build,
                                contentDescription = null,
                                tint = if (tool.isCurrentlyAvailable) EmeraldLive else AmberThinking,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = when (tool.safetyClassification) {
                                ActionRiskLevel.LOW_RISK -> EmeraldLive.copy(alpha = 0.16f)
                                ActionRiskLevel.SENSITIVE -> AmberThinking.copy(alpha = 0.18f)
                                ActionRiskLevel.HIGH_RISK_BLOCKED -> CrimsonAlert.copy(alpha = 0.18f)
                            }
                        ) {
                            Text(
                                text = tool.safetyClassification.name,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = tool.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Status: ${tool.availabilityNote} • Required params: ${tool.requiredParams.ifEmpty { listOf("none") }.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (tool.isCurrentlyAvailable) ElectricCyan else AmberThinking
                    )
                    if (reliability != null) {
                        val pct = (reliability.successRate * 100).toInt()
                        Text(
                            text = "Reliability: $pct% (${reliability.successCount}/${reliability.totalCalls} runs, avg ${reliability.averageDurationMs}ms)${if (reliability.hasRepeatedFailurePattern) " • Repeated failure pattern" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (reliability.hasRepeatedFailurePattern) CrimsonAlert else EmeraldLive
                        )
                    }
                }
            }
        }

        if (executionHistory.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Recent Agent Activity Log (${executionHistory.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            items(executionHistory.take(25), key = { it.id }) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "${entry.toolName} — ${if (entry.succeeded) "OK" else entry.errorCode ?: "FAILED"} (${entry.durationMs}ms)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (entry.succeeded) EmeraldLive else CrimsonAlert
                        )
                        Text(
                            text = entry.sanitizedSummary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateScheduleDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        title: String,
        actionType: ScheduledActionType,
        target: String,
        recurrence: ScheduleRecurrence,
        hour: Int,
        minute: Int,
        intervalMinutes: Long,
        userApproved: Boolean,
        requiresAccessibility: Boolean
    ) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var actionType by remember { mutableStateOf(ScheduledActionType.REMINDER) }
    var target by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf(ScheduleRecurrence.DAILY) }
    var hourText by remember { mutableStateOf("8") }
    var minuteText by remember { mutableStateOf("0") }
    var intervalText by remember { mutableStateOf("60") }
    var explicitApprovalChecked by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Local Schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (e.g., Morning Check-in)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Action Type", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = actionType == ScheduledActionType.REMINDER,
                        onClick = { actionType = ScheduledActionType.REMINDER },
                        label = { Text("Reminder") }
                    )
                    FilterChip(
                        selected = actionType == ScheduledActionType.DEVICE_STATUS_CHECK,
                        onClick = { actionType = ScheduledActionType.DEVICE_STATUS_CHECK },
                        label = { Text("Status") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = actionType == ScheduledActionType.OPEN_APP,
                        onClick = { actionType = ScheduledActionType.OPEN_APP },
                        label = { Text("Open App") }
                    )
                    FilterChip(
                        selected = actionType == ScheduledActionType.RUN_APPROVED_SKILL,
                        onClick = { actionType = ScheduledActionType.RUN_APPROVED_SKILL },
                        label = { Text("Skill") }
                    )
                }
                if (actionType == ScheduledActionType.OPEN_APP || actionType == ScheduledActionType.RUN_APPROVED_SKILL) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = {
                            Text(if (actionType == ScheduledActionType.OPEN_APP) "App Name (e.g., YouTube)" else "Skill ID")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = recurrence == ScheduleRecurrence.ONE_TIME,
                        onClick = { recurrence = ScheduleRecurrence.ONE_TIME },
                        label = { Text("Once") }
                    )
                    FilterChip(
                        selected = recurrence == ScheduleRecurrence.DAILY,
                        onClick = { recurrence = ScheduleRecurrence.DAILY },
                        label = { Text("Daily") }
                    )
                    FilterChip(
                        selected = recurrence == ScheduleRecurrence.INTERVAL_MINUTES,
                        onClick = { recurrence = ScheduleRecurrence.INTERVAL_MINUTES },
                        label = { Text("Interval") }
                    )
                }
                if (recurrence == ScheduleRecurrence.INTERVAL_MINUTES) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Interval minutes (>= 15)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hourText,
                            onValueChange = { hourText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Hour (0-23)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = minuteText,
                            onValueChange = { minuteText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("Min (0-59)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (actionType.modifiesDeviceState) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = explicitApprovalChecked,
                            onCheckedChange = { explicitApprovalChecked = it }
                        )
                        Text(
                            text = "I explicitly approve this scheduled action (runs with visible notification).",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 8
                    val m = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val iv = intervalText.toLongOrNull()?.coerceAtLeast(15L) ?: 60L
                    onConfirm(
                        title.ifBlank { actionType.labelEn },
                        actionType,
                        target.trim(),
                        recurrence,
                        h,
                        m,
                        iv,
                        if (actionType.modifiesDeviceState) explicitApprovalChecked else true,
                        actionType == ScheduledActionType.RUN_APPROVED_SKILL
                    )
                },
                enabled = !actionType.modifiesDeviceState || explicitApprovalChecked
            ) {
                Text("Save Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
