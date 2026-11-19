package com.example.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.memory.MemoryCategory
import com.example.data.local.memory.MemoryExpirationPolicy
import com.example.data.local.skill.AgentSkill
import com.example.data.local.skill.SkillExecutionStep
import com.example.data.local.skill.SkillProposal
import com.example.data.local.skill.SkillSafetyClassification
import com.example.ui.components.AppBottomSheet
import com.example.ui.components.AppConfirmationDialog
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppTopBar
import com.example.ui.components.EmptyState
import com.example.ui.components.FeatureCard
import com.example.ui.components.MemoryCard
import com.example.ui.components.PrimaryButton
import com.example.ui.components.SecondaryButton
import com.example.ui.components.SkillCard
import com.example.ui.components.StatusIndicator
import com.example.ui.components.StatusSnackbarBanner
import com.example.ui.components.StatusTone
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.ScreenSightSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private enum class SkillFilter(val label: String) {
    ALL("All"),
    BUILT_IN("Built-in"),
    APPROVED("User-Approved"),
    DISABLED("Disabled")
}

/**
 * Phase 6 Library Screen (Bottom Navigation Tab 4).
 * Combines On-Device Memory (with search + category filter) and Reusable Skills
 * (with status filter + Skill Workflow Detail bottom sheet + learned proposal approval).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    memoryEnabled: Boolean,
    defaultRetentionPolicy: MemoryExpirationPolicy,
    memories: List<AgentMemoryRecord>,
    skills: List<AgentSkill>,
    pendingSkillProposal: SkillProposal?,
    activeSkillId: String?,
    isAgentTaskRunning: Boolean,
    feedbackMessage: String?,
    onToggleMemoryEnabled: (Boolean) -> Unit,
    onAddMemory: (MemoryCategory, String, String, MemoryExpirationPolicy) -> Unit,
    onEditMemory: (String, MemoryCategory, String, String, MemoryExpirationPolicy) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onClearAllMemories: () -> Unit,
    onSetSkillEnabled: (String, Boolean) -> Unit,
    onRunSkill: (String, Map<String, String>) -> Unit,
    onStopActiveSkill: () -> Unit,
    onSaveOrEditSkill: (AgentSkill) -> Unit,
    onDeleteSkill: (String) -> Unit,
    onApproveSkillProposal: (String?) -> Unit,
    onDismissSkillProposal: () -> Unit,
    onDismissFeedback: () -> Unit,
    onOpenDrawer: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(if (pendingSkillProposal != null) 1 else 0) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<MemoryCategory?>(null) }
    var selectedSkillFilter by remember { mutableStateOf(SkillFilter.ALL) }

    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<AgentMemoryRecord?>(null) }
    var showClearAllMemoriesDialog by remember { mutableStateOf(false) }

    var showCreateSkillDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var inspectedSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var skillAwaitingParams by remember { mutableStateOf<AgentSkill?>(null) }

    if (showClearAllMemoriesDialog) {
        AppConfirmationDialog(
            title = "Clear All Memories?",
            message = "All ${memories.size} locally saved memories will be permanently removed from this device. Your skills and API key will not be affected.",
            confirmText = "Clear All",
            destructive = true,
            onConfirm = {
                showClearAllMemoriesDialog = false
                onClearAllMemories()
            },
            onDismiss = { showClearAllMemoriesDialog = false },
            confirmTestTag = "library_confirm_clear_memories"
        )
    }

    if (showAddMemoryDialog || editingMemory != null) {
        LibraryMemoryEditorDialog(
            existing = editingMemory,
            defaultRetentionPolicy = defaultRetentionPolicy,
            onDismiss = {
                showAddMemoryDialog = false
                editingMemory = null
            },
            onConfirm = { cat, k, v, exp ->
                val target = editingMemory
                if (target == null) onAddMemory(cat, k, v, exp) else onEditMemory(target.id, cat, k, v, exp)
                showAddMemoryDialog = false
                editingMemory = null
            }
        )
    }

    if (showCreateSkillDialog || editingSkill != null) {
        LibrarySkillEditorDialog(
            existing = editingSkill,
            onDismiss = {
                showCreateSkillDialog = false
                editingSkill = null
            },
            onSave = { skill ->
                onSaveOrEditSkill(skill)
                showCreateSkillDialog = false
                editingSkill = null
            }
        )
    }

    inspectedSkill?.let { skill ->
        SkillWorkflowDetailSheet(
            skill = skill,
            onDismiss = { inspectedSkill = null },
            onRun = {
                inspectedSkill = null
                if (skill.requiredParameters.isNotEmpty()) {
                    skillAwaitingParams = skill
                } else {
                    onRunSkill(skill.skillId, emptyMap())
                }
            },
            onEdit = {
                inspectedSkill = null
                editingSkill = skill
            }
        )
    }

    skillAwaitingParams?.let { skill ->
        LibrarySkillParamsDialog(
            skill = skill,
            onDismiss = { skillAwaitingParams = null },
            onRun = { params ->
                skillAwaitingParams = null
                onRunSkill(skill.skillId, params)
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            AppTopBar(
                title = "Library",
                subtitle = "On-device memories & reusable skills",
                onOpenDrawer = onOpenDrawer
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
                    testTag = "library_feedback_banner"
                )

                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.testTag("library_tab_memory"),
                        text = { Text("Memory (${memories.size})") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.testTag("library_tab_skills"),
                        text = {
                            val suffix = if (pendingSkillProposal != null) " • NEW" else ""
                            Text("Skills (${skills.size})$suffix")
                        }
                    )
                }

                if (selectedTab == 0) {
                    LibraryMemoryTab(
                        memoryEnabled = memoryEnabled,
                        memories = memories,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        selectedCategory = selectedCategory,
                        onSelectCategory = { selectedCategory = it },
                        onToggleMemoryEnabled = onToggleMemoryEnabled,
                        onAddMemoryClick = { showAddMemoryDialog = true },
                        onEditMemoryClick = { editingMemory = it },
                        onDeleteMemoryClick = onDeleteMemory,
                        onClearAllClick = { showClearAllMemoriesDialog = true }
                    )
                } else {
                    LibrarySkillsTab(
                        skills = skills,
                        selectedFilter = selectedSkillFilter,
                        onSelectFilter = { selectedSkillFilter = it },
                        pendingProposal = pendingSkillProposal,
                        activeSkillId = activeSkillId,
                        isAgentTaskRunning = isAgentTaskRunning,
                        onCreateSkillClick = { showCreateSkillDialog = true },
                        onApproveProposal = onApproveSkillProposal,
                        onDismissProposal = onDismissSkillProposal,
                        onToggleSkill = onSetSkillEnabled,
                        onInspectSkill = { inspectedSkill = it },
                        onRunSkillClick = { skill ->
                            if (skill.requiredParameters.isNotEmpty()) {
                                skillAwaitingParams = skill
                            } else {
                                onRunSkill(skill.skillId, emptyMap())
                            }
                        },
                        onStopActiveSkill = onStopActiveSkill,
                        onEditSkillClick = { editingSkill = it },
                        onDeleteSkillClick = onDeleteSkill
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryMemoryTab(
    memoryEnabled: Boolean,
    memories: List<AgentMemoryRecord>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    selectedCategory: MemoryCategory?,
    onSelectCategory: (MemoryCategory?) -> Unit,
    onToggleMemoryEnabled: (Boolean) -> Unit,
    onAddMemoryClick: () -> Unit,
    onEditMemoryClick: (AgentMemoryRecord) -> Unit,
    onDeleteMemoryClick: (String) -> Unit,
    onClearAllClick: () -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val filteredMemories = remember(memories, searchQuery, selectedCategory) {
        memories.filter { record ->
            val catMatch = selectedCategory == null || record.category == selectedCategory
            val q = searchQuery.trim()
            val textMatch = q.isEmpty() ||
                record.memoryKey.contains(q, ignoreCase = true) ||
                record.value.contains(q, ignoreCase = true) ||
                record.category.displayNameEn.contains(q, ignoreCase = true)
            catMatch && textMatch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_memory_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "privacy_master_toggle") {
            FeatureCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = if (memoryEnabled) EmeraldLive else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (memoryEnabled) "On-Device Memory Active" else "Memory Disabled",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Stored locally in Room. Passwords, OTPs, PINs, tokens, and private messages are never saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = memoryEnabled,
                        onCheckedChange = onToggleMemoryEnabled,
                        modifier = Modifier.testTag("library_memory_enabled_switch")
                    )
                }
            }
        }

        item(key = "memory_controls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_memory_search_input"),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Search saved memories...") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { onSelectCategory(null) },
                        label = { Text("All") }
                    )
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { onSelectCategory(if (selectedCategory == cat) null else cat) },
                            label = { Text(cat.displayNameEn) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryButton(
                        text = "Add Memory",
                        onClick = onAddMemoryClick,
                        icon = Icons.Default.Add,
                        enabled = memoryEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("library_add_memory_button")
                    )
                    SecondaryButton(
                        text = "Clear All",
                        onClick = onClearAllClick,
                        icon = Icons.Default.DeleteSweep,
                        enabled = memories.isNotEmpty(),
                        contentColor = CrimsonAlert,
                        modifier = Modifier.testTag("library_clear_memories_button")
                    )
                }
            }
        }

        if (filteredMemories.isEmpty()) {
            item(key = "empty_memories") {
                EmptyState(
                    icon = Icons.Default.Psychology,
                    title = if (memories.isEmpty()) "No memories saved yet" else "No matching memories",
                    subtitle = if (memories.isEmpty()) {
                        "Save preferences, routines, or contact aliases locally so the agent adapts to you."
                    } else {
                        "Try clearing your search or category filter."
                    }
                )
            }
        } else {
            items(filteredMemories, key = { it.id }) { record ->
                MemoryCard(
                    categoryLabel = record.category.displayNameEn,
                    title = record.memoryKey,
                    content = record.value,
                    metaLine = "${(record.confidence * 100).toInt()}% • ${dateFmt.format(Date(record.createdAtMillis))} • ${record.expirationPolicy.labelEn}",
                    approved = record.userApproved,
                    modifier = Modifier.testTag("library_memory_card_${record.id}")
                ) {
                    AppIconButton(
                        icon = Icons.Default.Edit,
                        contentDescription = "Edit memory",
                        onClick = { onEditMemoryClick(record) }
                    )
                    AppIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "Delete memory",
                        onClick = { onDeleteMemoryClick(record.id) },
                        tint = CrimsonAlert
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySkillsTab(
    skills: List<AgentSkill>,
    selectedFilter: SkillFilter,
    onSelectFilter: (SkillFilter) -> Unit,
    pendingProposal: SkillProposal?,
    activeSkillId: String?,
    isAgentTaskRunning: Boolean,
    onCreateSkillClick: () -> Unit,
    onApproveProposal: (String?) -> Unit,
    onDismissProposal: () -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onInspectSkill: (AgentSkill) -> Unit,
    onRunSkillClick: (AgentSkill) -> Unit,
    onStopActiveSkill: () -> Unit,
    onEditSkillClick: (AgentSkill) -> Unit,
    onDeleteSkillClick: (String) -> Unit
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val filteredSkills = remember(skills, selectedFilter) {
        when (selectedFilter) {
            SkillFilter.ALL -> skills
            SkillFilter.BUILT_IN -> skills.filter { it.isBuiltIn }
            SkillFilter.APPROVED -> skills.filter { !it.isBuiltIn && it.userApproved }
            SkillFilter.DISABLED -> skills.filter { !it.enabled }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("library_skills_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "skills_header_actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = "Create Reusable Skill",
                    onClick = onCreateSkillClick,
                    icon = Icons.Default.Add,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("library_create_skill_button")
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SkillFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { onSelectFilter(filter) },
                            label = { Text(filter.label) }
                        )
                    }
                }
            }
        }

        if (pendingProposal != null) {
            item(key = "pending_proposal") {
                var proposedName by remember(pendingProposal.proposalId) {
                    mutableStateOf(pendingProposal.suggestedName)
                }
                FeatureCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
                    modifier = Modifier.testTag("library_skill_proposal_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Learned Skill Proposal (Review Required)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedTextField(
                        value = proposedName,
                        onValueChange = { proposedName = it },
                        label = { Text("Skill Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Steps: ${pendingProposal.executionSteps.joinToString(" -> ") { it.toolName }}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = "Approve & Save",
                            onClick = { onApproveProposal(proposedName.trim()) },
                            icon = Icons.Default.Check,
                            containerColor = EmeraldLive,
                            contentColor = Color(0xFF070B14),
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryButton(
                            text = "Dismiss",
                            onClick = onDismissProposal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (filteredSkills.isEmpty()) {
            item(key = "empty_skills") {
                EmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "No skills in this category",
                    subtitle = "Create a custom workflow or switch filters to see built-in and approved skills."
                )
            }
        } else {
            items(filteredSkills, key = { it.skillId }) { skill ->
                val successRate = if (skill.usageCount > 0) {
                    "${(skill.successCount * 100) / skill.usageCount}% success"
                } else {
                    "Not run yet"
                }
                val lastUsed = skill.lastUsedAtMillis?.let { " • Last ${dateFmt.format(Date(it))}" }.orEmpty()
                val badgeLabel = when {
                    !skill.enabled -> "Disabled"
                    skill.isBuiltIn -> "Built-in • ${skill.safetyClassification.name}"
                    skill.userApproved -> "Approved • v${skill.version}"
                    else -> "Pending Approval"
                }
                val badgeTone = when {
                    !skill.enabled -> StatusTone.NEUTRAL
                    skill.safetyClassification == SkillSafetyClassification.LOW_RISK -> StatusTone.POSITIVE
                    else -> StatusTone.WARNING
                }

                SkillCard(
                    name = skill.name,
                    description = skill.description,
                    statsLine = "${skill.executionSteps.size} steps • ${skill.usageCount} runs • $successRate$lastUsed",
                    badgeLabel = badgeLabel,
                    badgeTone = badgeTone,
                    enabled = skill.enabled,
                    onToggleEnabled = { onToggleSkill(skill.skillId, it) },
                    modifier = Modifier.testTag("library_skill_card_${skill.skillId}"),
                    toggleTestTag = "library_skill_toggle_${skill.skillId}"
                ) {
                    if (activeSkillId == skill.skillId) {
                        PrimaryButton(
                            text = "Stop",
                            onClick = onStopActiveSkill,
                            icon = Icons.Default.Stop,
                            containerColor = CrimsonAlert,
                            contentColor = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        SecondaryButton(
                            text = "Run",
                            onClick = { onRunSkillClick(skill) },
                            icon = Icons.Default.PlayArrow,
                            tonal = true,
                            enabled = skill.enabled && !isAgentTaskRunning,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("library_run_skill_${skill.skillId}")
                        )
                    }
                    AppIconButton(
                        icon = Icons.Default.Info,
                        contentDescription = "View skill workflow details",
                        onClick = { onInspectSkill(skill) },
                        modifier = Modifier.testTag("library_inspect_skill_${skill.skillId}")
                    )
                    AppIconButton(
                        icon = Icons.Default.Edit,
                        contentDescription = "Edit skill",
                        onClick = { onEditSkillClick(skill) }
                    )
                    AppIconButton(
                        icon = Icons.Default.Delete,
                        contentDescription = "Delete skill",
                        onClick = { onDeleteSkillClick(skill.skillId) },
                        tint = CrimsonAlert
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillWorkflowDetailSheet(
    skill: AgentSkill,
    onDismiss: () -> Unit,
    onRun: () -> Unit,
    onEdit: () -> Unit
) {
    AppBottomSheet(title = skill.name, onDismiss = onDismiss) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusIndicator(
                label = if (skill.isBuiltIn) "Built-in" else "User-Approved",
                tone = StatusTone.INFO
            )
            StatusIndicator(
                label = skill.safetyClassification.labelEn,
                tone = if (skill.safetyClassification == SkillSafetyClassification.LOW_RISK) StatusTone.POSITIVE else StatusTone.WARNING
            )
        }
        if (skill.description.isNotBlank()) {
            Text(text = skill.description, style = MaterialTheme.typography.bodyMedium)
        }
        if (skill.triggerPhrases.isNotEmpty()) {
            Text(
                text = "Voice Triggers: ${skill.triggerPhrases.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = "Ordered Execution Workflow (${skill.executionSteps.size} steps)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        skill.executionSteps.forEachIndexed { idx, step ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${idx + 1}. [${step.toolName}] ${step.description}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                if (step.recoveryToolName.isNotBlank()) {
                    Text(
                        text = "   Recovery fallback: ${step.recoveryToolName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            text = "Allowed Tools: ${skill.allowedTools.joinToString(", ")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PrimaryButton(
                text = "Run Skill",
                onClick = onRun,
                icon = Icons.Default.PlayArrow,
                enabled = skill.enabled,
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = "Edit",
                onClick = onEdit,
                icon = Icons.Default.Edit,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryMemoryEditorDialog(
    existing: AgentMemoryRecord?,
    defaultRetentionPolicy: MemoryExpirationPolicy,
    onDismiss: () -> Unit,
    onConfirm: (MemoryCategory, String, String, MemoryExpirationPolicy) -> Unit
) {
    var category by remember(existing) { mutableStateOf(existing?.category ?: MemoryCategory.USER_PREFERENCE) }
    var key by remember(existing) { mutableStateOf(existing?.memoryKey ?: "") }
    var value by remember(existing) { mutableStateOf(existing?.value ?: "") }
    var expiry by remember(existing) { mutableStateOf(existing?.expirationPolicy ?: defaultRetentionPolicy) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add Memory" else "Edit Memory") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.displayNameEn, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Memory Key (e.g., preferred_video_app)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Content (e.g., YouTube)") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(text = "Retention", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryExpirationPolicy.entries.forEach { policy ->
                        FilterChip(
                            selected = expiry == policy,
                            onClick = { expiry = policy },
                            label = { Text(policy.labelEn, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(category, key.trim(), value.trim(), expiry) },
                enabled = key.isNotBlank() && value.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LibrarySkillEditorDialog(
    existing: AgentSkill?,
    onDismiss: () -> Unit,
    onSave: (AgentSkill) -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var description by remember(existing) { mutableStateOf(existing?.description ?: "") }
    var triggers by remember(existing) { mutableStateOf(existing?.triggerPhrases?.joinToString(", ") ?: "") }
    var appTarget by remember(existing) {
        mutableStateOf(existing?.executionSteps?.firstOrNull()?.parameterTemplates?.get("app_name") ?: "YouTube")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Create Skill" else "Edit Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = triggers,
                    onValueChange = { triggers = it },
                    label = { Text("Trigger phrases (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (existing == null) {
                    OutlinedTextField(
                        value = appTarget,
                        onValueChange = { appTarget = it },
                        label = { Text("App to open") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val triggerList = triggers.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val updated = existing?.copy(
                        name = name.trim(),
                        description = description.trim(),
                        triggerPhrases = triggerList.ifEmpty { listOf(name.trim().lowercase()) }
                    ) ?: AgentSkill(
                        skillId = "custom_${UUID.randomUUID().toString().take(8)}",
                        name = name.trim(),
                        description = description.trim().ifBlank { "Open $appTarget" },
                        triggerPhrases = triggerList.ifEmpty { listOf(name.trim().lowercase()) },
                        requiredParameters = emptyList(),
                        executionSteps = listOf(
                            SkillExecutionStep(
                                stepIndex = 0,
                                description = "Open ${appTarget.trim()}",
                                toolName = "open_app",
                                parameterTemplates = mapOf("app_name" to appTarget.trim())
                            )
                        ),
                        allowedTools = listOf("open_app"),
                        safetyClassification = SkillSafetyClassification.LOW_RISK,
                        successCriteria = "Target app opened",
                        userApproved = true,
                        enabled = true
                    )
                    onSave(updated)
                },
                enabled = name.isNotBlank()
            ) { Text("Save Skill") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LibrarySkillParamsDialog(
    skill: AgentSkill,
    onDismiss: () -> Unit,
    onRun: (Map<String, String>) -> Unit
) {
    val values = remember(skill.skillId) {
        mutableStateMapOf<String, String>().apply {
            skill.requiredParameters.forEach { put(it, "") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run: ${skill.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                skill.requiredParameters.forEach { param ->
                    OutlinedTextField(
                        value = values[param].orEmpty(),
                        onValueChange = { values[param] = it },
                        label = { Text(param.replace("_", " ").replaceFirstChar { it.uppercase() }) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRun(values.toMap()) },
                enabled = skill.requiredParameters.all { !values[it].isNullOrBlank() }
            ) { Text("Run Now") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
