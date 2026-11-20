package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agent.AgentExecutionState
import com.example.agent.AgentTaskPlan
import com.example.agent.MultiAgentOrchestrator
import com.example.data.local.checkpoint.RecoverableTaskCheckpoint
import com.example.data.local.orchestration.LongTermTask
import com.example.data.local.orchestration.LongTermTaskStatus
import com.example.data.local.orchestration.SubtaskStatus
import com.example.data.local.schedule.ScheduleRecurrence
import com.example.data.local.schedule.ScheduledActionType
import com.example.data.local.schedule.ScheduledTask
import com.example.ui.components.AppBottomSheet
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyState
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 6 Task Management Screen (Bottom Navigation Tab 3).
 * Organizes all autonomous work into 4 dedicated tabs: Active, Scheduled, Completed, Failed.
 * Includes a rich Task Details bottom sheet with timeline, checkpoint metadata, and controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    activeAgentPlan: AgentTaskPlan?,
    agentExecutionState: AgentExecutionState,
    recoverableCheckpoints: List<RecoverableTaskCheckpoint>,
    longTermTasks: List<LongTermTask>,
    scheduledTasks: List<ScheduledTask>,
    lastMultiAgentContext: MultiAgentOrchestrator.SharedAgentTaskContext?,
    feedbackMessage: String?,
    onCreateGoalTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onStopActiveCoordinatorTask: () -> Unit,
    onResumeCheckpoint: (String) -> Unit,
    onCancelCheckpoint: (String) -> Unit,
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
    onDismissFeedback: () -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenToolsAndDiagnostics: () -> Unit,
    taskModelUsageMap: Map<String, com.example.data.ai.ModelUsageRecord> = emptyMap()
) {
    // 0 = Active, 1 = Scheduled, 2 = Completed, 3 = Failed
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var goalInput by rememberSaveable { mutableStateOf("") }
    var inspectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateScheduleDialog by remember { mutableStateOf(false) }

    val activeTasks = remember(longTermTasks) {
        longTermTasks.filter {
            it.status == LongTermTaskStatus.RUNNING ||
                it.status == LongTermTaskStatus.PENDING ||
                it.status == LongTermTaskStatus.PAUSED
        }
    }
    val completedTasks = remember(longTermTasks) {
        longTermTasks.filter { it.status == LongTermTaskStatus.COMPLETED }
    }
    val failedTasks = remember(longTermTasks) {
        longTermTasks.filter {
            it.status == LongTermTaskStatus.FAILED || it.status == LongTermTaskStatus.CANCELLED
        }
    }

    val inspectedTask = remember(longTermTasks, inspectedTaskId) {
        longTermTasks.firstOrNull { it.taskId == inspectedTaskId }
    }

    if (inspectedTask != null) {
        TaskDetailsBottomSheet(
            task = inspectedTask,
            multiAgentContext = lastMultiAgentContext?.takeIf { it.sanitizedGoal.equals(inspectedTask.goal, ignoreCase = true) },
            modelUsageRecord = taskModelUsageMap[inspectedTask.taskId],
            onDismiss = { inspectedTaskId = null },
            onPause = { onPauseTask(inspectedTask.taskId) },
            onResume = { onResumeTask(inspectedTask.taskId) },
            onRetry = { onRetryTask(inspectedTask.taskId) },
            onCancel = { onCancelTask(inspectedTask.taskId) },
            onDelete = {
                onDeleteTask(inspectedTask.taskId)
                inspectedTaskId = null
            }
        )
    }

    if (showCreateScheduleDialog) {
        TasksCreateScheduleDialog(
            onDismiss = { showCreateScheduleDialog = false },
            onConfirm = { title, action, target, recurrence, hour, minute, interval, approved, a11y ->
                onCreateSchedule(title, action, target, recurrence, hour, minute, interval, approved, a11y)
                showCreateScheduleDialog = false
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            AppTopBar(
                title = "Tasks",
                subtitle = "Autonomous orchestration, checkpoints & schedules",
                onOpenDrawer = onOpenDrawer,
                actions = {
                    AppIconButton(
                        icon = Icons.Default.AddAlarm,
                        contentDescription = "Create Scheduled Task",
                        onClick = { showCreateScheduleDialog = true },
                        modifier = Modifier.testTag("tasks_top_schedule_button")
                    )
                    AppIconButton(
                        icon = Icons.Default.AutoAwesome,
                        contentDescription = "Tools and Multi-Agent Diagnostics",
                        onClick = onOpenToolsAndDiagnostics,
                        modifier = Modifier.testTag("tasks_open_diagnostics_button")
                    )
                }
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
                    .fillMaxSize()
            ) {
                StatusSnackbarBanner(
                    message = feedbackMessage,
                    onDismiss = onDismissFeedback,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    testTag = "tasks_feedback_banner"
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.testTag("tasks_tab_active"),
                        text = {
                            Text(
                                text = "Active (${activeTasks.size + recoverableCheckpoints.size})",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.testTag("tasks_tab_scheduled"),
                        text = {
                            Text(
                                text = "Sched (${scheduledTasks.size})",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        modifier = Modifier.testTag("tasks_tab_completed"),
                        text = {
                            Text(
                                text = "Done (${completedTasks.size})",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        modifier = Modifier.testTag("tasks_tab_failed"),
                        text = {
                            Text(
                                text = "Failed (${failedTasks.size})",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }

                when (selectedTab) {
                    0 -> ActiveTasksTabContent(
                        goalInput = goalInput,
                        onGoalInputChange = { goalInput = it },
                        onSubmitGoal = {
                            val trimmed = goalInput.trim()
                            if (trimmed.isNotEmpty()) {
                                onCreateGoalTask(trimmed)
                                goalInput = ""
                            }
                        },
                        onQuickRunStarter = { starterGoal ->
                            onCreateGoalTask(starterGoal)
                        },
                        onOpenCreateSchedule = { showCreateScheduleDialog = true },
                        activeAgentPlan = activeAgentPlan,
                        agentExecutionState = agentExecutionState,
                        recoverableCheckpoints = recoverableCheckpoints,
                        activeTasks = activeTasks,
                        onStopActiveCoordinatorTask = onStopActiveCoordinatorTask,
                        onResumeCheckpoint = onResumeCheckpoint,
                        onCancelCheckpoint = onCancelCheckpoint,
                        onInspectTask = { inspectedTaskId = it },
                        onPauseTask = onPauseTask,
                        onResumeTask = onResumeTask,
                        onCancelTask = onCancelTask
                    )

                    1 -> ScheduledTasksTabContent(
                        schedules = scheduledTasks,
                        onNewScheduleClick = { showCreateScheduleDialog = true },
                        onToggleSchedule = onToggleSchedule,
                        onDeleteSchedule = onDeleteSchedule,
                        onCancelAllBackgroundWork = onCancelAllBackgroundWork
                    )

                    2 -> TaskHistoryListContent(
                        tasks = completedTasks,
                        emptyIcon = Icons.Default.CheckCircle,
                        emptyTitle = "No verified completed tasks yet",
                        emptySubtitle = "Tasks appear here only after every step completes and passes verification.",
                        showRetry = false,
                        onInspectTask = { inspectedTaskId = it },
                        onRetryTask = onRetryTask,
                        onDeleteTask = onDeleteTask
                    )

                    3 -> TaskHistoryListContent(
                        tasks = failedTasks,
                        emptyIcon = Icons.Default.ErrorOutline,
                        emptyTitle = "No failed or cancelled tasks",
                        emptySubtitle = "Interrupted or failed multi-step runs appear here with recovery options.",
                        showRetry = true,
                        onInspectTask = { inspectedTaskId = it },
                        onRetryTask = onRetryTask,
                        onDeleteTask = onDeleteTask
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveTasksTabContent(
    goalInput: String,
    onGoalInputChange: (String) -> Unit,
    onSubmitGoal: () -> Unit,
    onQuickRunStarter: (String) -> Unit,
    onOpenCreateSchedule: () -> Unit,
    activeAgentPlan: AgentTaskPlan?,
    agentExecutionState: AgentExecutionState,
    recoverableCheckpoints: List<RecoverableTaskCheckpoint>,
    activeTasks: List<LongTermTask>,
    onStopActiveCoordinatorTask: () -> Unit,
    onResumeCheckpoint: (String) -> Unit,
    onCancelCheckpoint: (String) -> Unit,
    onInspectTask: (String) -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tasks_active_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "create_task_card") {
            FeatureCard {
                Text(
                    text = "Plan a Multi-Step Task",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = goalInput,
                    onValueChange = onGoalInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tasks_goal_input"),
                    label = { Text("Describe goal (e.g., Check time, Open YouTube)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
                PrimaryButton(
                    text = "Plan & Run Locally",
                    onClick = onSubmitGoal,
                    icon = Icons.Default.AutoAwesome,
                    enabled = goalInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("tasks_create_goal_button")
                )
                Text(
                    text = "Quick Starter Missions",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = { onQuickRunStarter("Check date and time") },
                        label = { Text("Check time") },
                        modifier = Modifier.testTag("task_starter_time")
                    )
                    AssistChip(
                        onClick = { onQuickRunStarter("Open YouTube") },
                        label = { Text("Open YouTube") },
                        modifier = Modifier.testTag("task_starter_youtube")
                    )
                    AssistChip(
                        onClick = { onQuickRunStarter("Read current screen") },
                        label = { Text("Scan screen") },
                        modifier = Modifier.testTag("task_starter_screen")
                    )
                    AssistChip(
                        onClick = onOpenCreateSchedule,
                        label = { Text("+ Schedule") },
                        leadingIcon = {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("task_starter_schedule")
                    )
                }
            }
        }

        if (activeAgentPlan != null && agentExecutionState.isActive) {
            item(key = "live_coordinator_plan") {
                TaskProgressCard(
                    title = activeAgentPlan.taskDescription,
                    statusLabel = agentExecutionState.labelEn,
                    statusTone = StatusTone.INFO,
                    progress = activeAgentPlan.progressFraction,
                    stepSummary = "Live Agent • ${activeAgentPlan.completedStepsCount}/${activeAgentPlan.steps.size} steps • ${agentExecutionState.labelBn}"
                ) {
                    SecondaryButton(
                        text = "Stop Execution",
                        onClick = onStopActiveCoordinatorTask,
                        icon = Icons.Default.Stop,
                        contentColor = CrimsonAlert,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        items(recoverableCheckpoints, key = { "cp_${it.taskId}" }) { checkpoint ->
            FeatureCard(modifier = Modifier.testTag("checkpoint_card_${checkpoint.taskId}")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = checkpoint.sanitizedRequest,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    StatusIndicator(label = "Checkpoint", tone = StatusTone.WARNING)
                }
                Text(
                    text = "Saved at ${checkpoint.completedStepsCount}/${checkpoint.totalStepsCount} steps • Updated ${dateFmt.format(Date(checkpoint.updatedAtMillis))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(
                        text = "Resume",
                        onClick = { onResumeCheckpoint(checkpoint.taskId) },
                        icon = Icons.Default.PlayArrow,
                        tonal = true,
                        modifier = Modifier.weight(1f)
                    )
                    SecondaryButton(
                        text = "Discard",
                        onClick = { onCancelCheckpoint(checkpoint.taskId) },
                        contentColor = CrimsonAlert,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (activeTasks.isEmpty() && recoverableCheckpoints.isEmpty() && !(activeAgentPlan != null && agentExecutionState.isActive)) {
            item(key = "empty_active") {
                EmptyState(
                    icon = Icons.Default.TaskAlt,
                    title = "No active tasks",
                    subtitle = "Create a multi-stage goal above or give ScreenSight AI a voice instruction on the Agent tab."
                )
            }
        } else {
            items(activeTasks, key = { it.taskId }) { task ->
                val nextSub = task.subtasks.firstOrNull { it.status == SubtaskStatus.RUNNING || it.status == SubtaskStatus.PENDING }
                TaskProgressCard(
                    title = task.goal,
                    statusLabel = task.status.labelEn,
                    statusTone = if (task.status == LongTermTaskStatus.RUNNING) StatusTone.INFO else StatusTone.WARNING,
                    progress = task.progressFraction,
                    stepSummary = buildString {
                        append("${task.completedCount}/${task.totalCount} steps")
                        nextSub?.let { append(" • Next: ${it.title}") }
                        append(" • Updated ${dateFmt.format(Date(task.updatedAtMillis))}")
                    },
                    onClick = { onInspectTask(task.taskId) },
                    modifier = Modifier.testTag("task_item_card_${task.taskId}")
                ) {
                    if (task.status == LongTermTaskStatus.RUNNING) {
                        SecondaryButton(
                            text = "Pause",
                            onClick = { onPauseTask(task.taskId) },
                            icon = Icons.Default.Pause,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        SecondaryButton(
                            text = "Resume",
                            onClick = { onResumeTask(task.taskId) },
                            icon = Icons.Default.PlayArrow,
                            tonal = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    SecondaryButton(
                        text = "Cancel",
                        onClick = { onCancelTask(task.taskId) },
                        icon = Icons.Default.Stop,
                        contentColor = CrimsonAlert,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScheduledTasksTabContent(
    schedules: List<ScheduledTask>,
    onNewScheduleClick: () -> Unit,
    onToggleSchedule: (String, Boolean) -> Unit,
    onDeleteSchedule: (String) -> Unit,
    onCancelAllBackgroundWork: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("tasks_scheduled_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "schedule_actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrimaryButton(
                    text = "New Schedule",
                    onClick = onNewScheduleClick,
                    icon = Icons.Default.Add,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tasks_add_schedule_button")
                )
                SecondaryButton(
                    text = "Stop BG",
                    onClick = onCancelAllBackgroundWork,
                    icon = Icons.Default.Stop,
                    contentColor = CrimsonAlert
                )
            }
        }

        if (schedules.isEmpty()) {
            item(key = "empty_schedules") {
                EmptyState(
                    icon = Icons.Default.Schedule,
                    title = "No scheduled tasks",
                    subtitle = "Schedule local WorkManager reminders, status checks, or user-approved safe actions.",
                    actionLabel = "Create Schedule",
                    onAction = onNewScheduleClick
                )
            }
        } else {
            items(schedules, key = { it.scheduleId }) { schedule ->
                FeatureCard(modifier = Modifier.testTag("tasks_schedule_card_${schedule.scheduleId}")) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = schedule.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "${schedule.actionType.labelEn} • ${schedule.recurrence.labelEn} (${"%02d:%02d".format(schedule.hourOfDay, schedule.minuteOfHour)})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Switch(
                            checked = schedule.enabled,
                            onCheckedChange = { onToggleSchedule(schedule.scheduleId, it) },
                            modifier = Modifier.testTag("tasks_schedule_toggle_${schedule.scheduleId}")
                        )
                        IconButton(onClick = { onDeleteSchedule(schedule.scheduleId) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete schedule", tint = CrimsonAlert)
                        }
                    }
                    if (!schedule.lastRunResult.isNullOrBlank()) {
                        Text(
                            text = "Last run: ${schedule.lastRunResult}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskHistoryListContent(
    tasks: List<LongTermTask>,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyTitle: String,
    emptySubtitle: String,
    showRetry: Boolean,
    onInspectTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (tasks.isEmpty()) {
            item(key = "empty_history") {
                EmptyState(icon = emptyIcon, title = emptyTitle, subtitle = emptySubtitle)
            }
        } else {
            items(tasks, key = { it.taskId }) { task ->
                val tone = if (task.status == LongTermTaskStatus.COMPLETED) StatusTone.POSITIVE else StatusTone.ERROR
                TaskProgressCard(
                    title = task.goal,
                    statusLabel = task.status.labelEn,
                    statusTone = tone,
                    progress = task.progressFraction,
                    stepSummary = "${task.completedCount}/${task.totalCount} steps • Updated ${dateFmt.format(Date(task.updatedAtMillis))}",
                    onClick = { onInspectTask(task.taskId) },
                    modifier = Modifier.testTag("task_history_card_${task.taskId}")
                ) {
                    SecondaryButton(
                        text = "Details",
                        onClick = { onInspectTask(task.taskId) },
                        tonal = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (showRetry) {
                        SecondaryButton(
                            text = "Retry",
                            onClick = { onRetryTask(task.taskId) },
                            icon = Icons.Default.Refresh,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    AppIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "Delete task history",
                        onClick = { onDeleteTask(task.taskId) },
                        tint = CrimsonAlert
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailsBottomSheet(
    task: LongTermTask,
    multiAgentContext: MultiAgentOrchestrator.SharedAgentTaskContext?,
    modelUsageRecord: com.example.data.ai.ModelUsageRecord? = null,
    onDismiss: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }
    val statusTone = when (task.status) {
        LongTermTaskStatus.COMPLETED -> StatusTone.POSITIVE
        LongTermTaskStatus.RUNNING -> StatusTone.INFO
        LongTermTaskStatus.PAUSED, LongTermTaskStatus.PENDING -> StatusTone.WARNING
        LongTermTaskStatus.FAILED, LongTermTaskStatus.CANCELLED -> StatusTone.ERROR
    }

    AppBottomSheet(title = "Task Details", onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = task.goal,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            StatusIndicator(label = task.status.labelEn, tone = statusTone)
        }

        Text(
            text = "Created: ${dateFmt.format(Date(task.createdAtMillis))} • Last update: ${dateFmt.format(Date(task.updatedAtMillis))}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (task.lastCheckpointNote.isNotBlank()) {
            Text(
                text = "Checkpoint & Verification: ${task.lastCheckpointNote}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        val effectiveUsage = modelUsageRecord ?: multiAgentContext?.modelUsage
        if (effectiveUsage != null) {
            FeatureCard(modifier = Modifier.testTag("task_details_model_usage_card")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI Model Usage (Sanitized)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatusIndicator(
                        label = if (effectiveUsage.succeeded) {
                            if (effectiveUsage.usedFallback) "Fallback OK" else "Success"
                        } else {
                            "Fallback Local"
                        },
                        tone = if (effectiveUsage.succeeded) StatusTone.POSITIVE else StatusTone.WARNING
                    )
                }
                Text(
                    text = "Model: ${effectiveUsage.modelDisplayName} (${effectiveUsage.providerId.displayName})",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = buildString {
                        append("Stage: ${effectiveUsage.taskStage.displayLabel}")
                        if (effectiveUsage.promptTokens > 0 || effectiveUsage.outputTokens > 0) {
                            append(" • Tokens: ${effectiveUsage.promptTokens} in / ${effectiveUsage.outputTokens} out")
                        }
                        if (effectiveUsage.latencyMs > 0) {
                            append(" • ${effectiveUsage.latencyMs}ms")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                effectiveUsage.fallbackNote?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = "Fallback reason: $note",
                        style = MaterialTheme.typography.labelSmall,
                        color = AmberThinking
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
            text = "Execution Timeline (${task.completedCount}/${task.totalCount})",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            task.subtasks.forEachIndexed { idx, sub ->
                val icon = when (sub.status) {
                    SubtaskStatus.COMPLETED -> Icons.Default.CheckCircle
                    SubtaskStatus.FAILED, SubtaskStatus.CANCELLED -> Icons.Default.Cancel
                    else -> Icons.Default.Schedule
                }
                val tint = when (sub.status) {
                    SubtaskStatus.COMPLETED -> EmeraldLive
                    SubtaskStatus.FAILED, SubtaskStatus.CANCELLED -> CrimsonAlert
                    SubtaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    else -> AmberThinking
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${idx + 1}. ${sub.title} [${sub.toolName}]",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        val meta = buildString {
                            append(sub.status.name)
                            if (sub.retryCount > 0) append(" • Retries: ${sub.retryCount}")
                            val resultDetail = sub.verifiedResult ?: sub.lastError
                            if (!resultDetail.isNullOrBlank()) append(" • $resultDetail")
                        }
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (multiAgentContext != null && multiAgentContext.auditTrail.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "Role Handoff Trail",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            multiAgentContext.auditTrail.take(5).forEach { entry ->
                Text(
                    text = "• [${entry.role.name}] ${entry.note}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (task.status) {
                LongTermTaskStatus.RUNNING -> SecondaryButton("Pause", onPause, icon = Icons.Default.Pause, modifier = Modifier.weight(1f))
                LongTermTaskStatus.PAUSED, LongTermTaskStatus.PENDING -> PrimaryButton("Resume", onResume, icon = Icons.Default.PlayArrow, modifier = Modifier.weight(1f))
                LongTermTaskStatus.FAILED -> PrimaryButton("Retry", onRetry, icon = Icons.Default.Refresh, modifier = Modifier.weight(1f))
                else -> Unit
            }
            if (!task.status.isTerminal) {
                SecondaryButton("Cancel", onCancel, icon = Icons.Default.Stop, contentColor = CrimsonAlert, modifier = Modifier.weight(1f))
            } else {
                SecondaryButton("Delete", onDelete, icon = Icons.Default.Delete, contentColor = CrimsonAlert, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TasksCreateScheduleDialog(
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
    var selectedAction by remember { mutableStateOf(ScheduledActionType.REMINDER) }
    var target by remember { mutableStateOf("") }
    var recurrence by remember { mutableStateOf(ScheduleRecurrence.DAILY) }
    var hourText by remember { mutableStateOf("8") }
    var minuteText by remember { mutableStateOf("0") }
    var approved by remember { mutableStateOf(false) }

    val needsApproval = selectedAction == ScheduledActionType.OPEN_APP || selectedAction == ScheduledActionType.RUN_APPROVED_SKILL

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Scheduled Task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (e.g., Morning Check-In)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        ScheduledActionType.REMINDER to "Reminder",
                        ScheduledActionType.DEVICE_STATUS_CHECK to "Status",
                        ScheduledActionType.OPEN_APP to "Open App"
                    ).forEach { (type, label) ->
                        FilterChip(
                            selected = selectedAction == type,
                            onClick = { selectedAction = type },
                            label = { Text(label) }
                        )
                    }
                }
                if (selectedAction == ScheduledActionType.OPEN_APP) {
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("App Name (e.g., YouTube)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScheduleRecurrence.entries.forEach { rec ->
                        FilterChip(
                            selected = recurrence == rec,
                            onClick = { recurrence = rec },
                            label = { Text(rec.labelEn) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Hour (0-23)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                        label = { Text("Min (0-59)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (needsApproval) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = approved, onCheckedChange = { approved = it })
                        Text(
                            text = "I approve this background action with visible notification.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = (hourText.toIntOrNull() ?: 8).coerceIn(0, 23)
                    val m = (minuteText.toIntOrNull() ?: 0).coerceIn(0, 59)
                    onConfirm(
                        title.trim(),
                        selectedAction,
                        target.trim(),
                        recurrence,
                        h,
                        m,
                        60L,
                        if (needsApproval) approved else true,
                        false
                    )
                },
                enabled = title.isNotBlank() && (!needsApproval || approved)
            ) {
                Text("Save Schedule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
