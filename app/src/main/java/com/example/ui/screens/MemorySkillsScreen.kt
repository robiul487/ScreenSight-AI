package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.memory.MemoryCategory
import com.example.data.local.memory.MemoryExpirationPolicy
import com.example.data.local.skill.AgentSkill
import com.example.data.local.skill.SkillExecutionStep
import com.example.data.local.skill.SkillProposal
import com.example.data.local.skill.SkillSafetyClassification
import com.example.ui.theme.AmberThinking
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldLive
import com.example.ui.theme.IndigoGlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Phase 4 Local Long-Term Memory & Reusable Skills Management Screen.
 *
 * Designed to be simple, privacy-transparent, and Bangla-friendly (consistent with Family Easy Mode):
 * - Global Memory On/Off toggle
 * - View, Add, Edit, Delete individual memories, and Clear All with explicit confirmation
 * - View, Enable/Disable, Run, Edit, Delete skills with visible ordered steps & usage history
 * - Approve or Dismiss newly proposed learned skills
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemorySkillsScreen(
    memoryEnabled: Boolean,
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
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var selectedTab by rememberSaveable { mutableIntStateOf(if (pendingSkillProposal != null) 1 else 0) }
    var showAddMemoryDialog by remember { mutableStateOf(false) }
    var editingMemory by remember { mutableStateOf<AgentMemoryRecord?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<AgentSkill?>(null) }
    var showCreateSkillDialog by remember { mutableStateOf(false) }
    var skillAwaitingParams by remember { mutableStateOf<AgentSkill?>(null) }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = CrimsonAlert
                )
            },
            title = { Text("সব স্মৃতি মুছে ফেলবেন? (Clear All Memories)") },
            text = {
                Text(
                    "আপনার ফোনে সংরক্ষিত সব ${memories.size}টি স্মৃতি স্থায়ীভাবে মুছে যাবে। আপনি কি নিশ্চিত?\n\n" +
                        "All ${memories.size} locally saved memories will be permanently deleted from this device."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearAllDialog = false
                        onClearAllMemories()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
                    modifier = Modifier.testTag("confirm_clear_all_memories_button")
                ) {
                    Text("হ্যাঁ, সব মুছুন (Delete All)")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearAllDialog = false },
                    modifier = Modifier.testTag("cancel_clear_all_memories_button")
                ) {
                    Text("বাতিল (Cancel)")
                }
            }
        )
    }

    if (showAddMemoryDialog || editingMemory != null) {
        MemoryEditorDialog(
            existing = editingMemory,
            onDismiss = {
                showAddMemoryDialog = false
                editingMemory = null
            },
            onConfirm = { category, key, value, expiry ->
                val target = editingMemory
                if (target == null) {
                    onAddMemory(category, key, value, expiry)
                } else {
                    onEditMemory(target.id, category, key, value, expiry)
                }
                showAddMemoryDialog = false
                editingMemory = null
            }
        )
    }

    if (showCreateSkillDialog || editingSkill != null) {
        SkillEditorDialog(
            existing = editingSkill,
            onDismiss = {
                showCreateSkillDialog = false
                editingSkill = null
            },
            onSave = { updated ->
                onSaveOrEditSkill(updated)
                showCreateSkillDialog = false
                editingSkill = null
            }
        )
    }

    skillAwaitingParams?.let { skill ->
        RunSkillParametersDialog(
            skill = skill,
            onDismiss = { skillAwaitingParams = null },
            onRun = { params ->
                skillAwaitingParams = null
                onRunSkill(skill.skillId, params)
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
                            text = stringResource(R.string.memory_skills_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "স্মৃতি ও দক্ষতা • On-Device Only",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricCyan
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("memory_skills_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
            ) {
                // Global On-Device Memory Privacy & Enable/Disable Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("memory_master_toggle_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = if (memoryEnabled) EmeraldLive else AmberThinking,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (memoryEnabled) {
                                    "স্মৃতি চালু আছে (Memory Enabled)"
                                } else {
                                    "স্মৃতি বন্ধ আছে (Memory Disabled)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "সব তথ্য শুধু আপনার ফোনেই থাকে। পাসওয়ার্ড, ওটিপি, পিন বা ব্যক্তিগত মেসেজ কখনো সংরক্ষণ হয় না।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = memoryEnabled,
                            onCheckedChange = onToggleMemoryEnabled,
                            modifier = Modifier.testTag("memory_enabled_switch")
                        )
                    }
                }

                // Inline Privacy/Status Feedback Banner
                AnimatedVisibility(visible = !feedbackMessage.isNullOrBlank()) {
                    feedbackMessage?.let { msg ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("memory_feedback_banner"),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onDismissFeedback,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss message",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Tabs: Memories vs Skills
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.testTag("tab_memories"),
                        text = {
                            Text(
                                text = "স্মৃতি / Memories (${memories.size})",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.testTag("tab_skills"),
                        text = {
                            val badge = if (pendingSkillProposal != null) " • NEW" else ""
                            Text(
                                text = "দক্ষতা / Skills (${skills.size})$badge",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }

                if (selectedTab == 0) {
                    MemoriesTabContent(
                        memoryEnabled = memoryEnabled,
                        memories = memories,
                        onAddClick = { showAddMemoryDialog = true },
                        onEditClick = { editingMemory = it },
                        onDeleteClick = onDeleteMemory,
                        onClearAllClick = { showClearAllDialog = true }
                    )
                } else {
                    SkillsTabContent(
                        skills = skills,
                        pendingSkillProposal = pendingSkillProposal,
                        activeSkillId = activeSkillId,
                        isAgentTaskRunning = isAgentTaskRunning,
                        onApproveProposal = onApproveSkillProposal,
                        onDismissProposal = onDismissSkillProposal,
                        onCreateSkillClick = { showCreateSkillDialog = true },
                        onToggleSkill = onSetSkillEnabled,
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
private fun MemoriesTabContent(
    memoryEnabled: Boolean,
    memories: List<AgentMemoryRecord>,
    onAddClick: () -> Unit,
    onEditClick: (AgentMemoryRecord) -> Unit,
    onDeleteClick: (String) -> Unit,
    onClearAllClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onAddClick,
                    enabled = memoryEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("add_memory_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("নতুন স্মৃতি যোগ", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onClearAllClick,
                    enabled = memories.isNotEmpty(),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("clear_all_memories_button"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonAlert)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("সব মুছুন")
                }
            }
        }

        if (memories.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "এখনো কোনো স্মৃতি সংরক্ষিত নেই",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "আপনি মুখে বলতে পারেন \"মনে রেখো আমার পছন্দের অ্যাপ YouTube\" অথবা উপরের বাটনে চেপে যোগ করতে পারেন।",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(memories, key = { it.id }) { mem ->
                MemoryRecordCard(
                    record = mem,
                    onEdit = { onEditClick(mem) },
                    onDelete = { onDeleteClick(mem.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun MemoryRecordCard(
    record: AgentMemoryRecord,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("memory_card_${record.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ElectricCyan.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = "${record.category.displayNameBn} • ${record.category.displayNameEn}",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                if (record.userApproved) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = "User approved memory",
                        tint = EmeraldLive,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Text(
                text = record.memoryKey,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = record.value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Confidence ${(record.confidence * 100).toInt()}% • ${record.expirationPolicy.labelBn}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.testTag("edit_memory_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit memory"
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.testTag("delete_memory_${record.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete memory",
                            tint = CrimsonAlert
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SkillsTabContent(
    skills: List<AgentSkill>,
    pendingSkillProposal: SkillProposal?,
    activeSkillId: String?,
    isAgentTaskRunning: Boolean,
    onApproveProposal: (String?) -> Unit,
    onDismissProposal: () -> Unit,
    onCreateSkillClick: () -> Unit,
    onToggleSkill: (String, Boolean) -> Unit,
    onRunSkillClick: (AgentSkill) -> Unit,
    onStopActiveSkill: () -> Unit,
    onEditSkillClick: (AgentSkill) -> Unit,
    onDeleteSkillClick: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onCreateSkillClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("create_skill_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("নতুন দক্ষতা তৈরি করুন (Create Skill)", fontWeight = FontWeight.Bold)
            }
        }

        if (pendingSkillProposal != null) {
            item {
                PendingSkillProposalCard(
                    proposal = pendingSkillProposal,
                    onApprove = onApproveProposal,
                    onDismiss = onDismissProposal
                )
            }
        }

        items(skills, key = { it.skillId }) { skill ->
            SkillCard(
                skill = skill,
                isRunningThisSkill = activeSkillId == skill.skillId,
                isAnyTaskRunning = isAgentTaskRunning,
                onToggleEnabled = { onToggleSkill(skill.skillId, it) },
                onRun = { onRunSkillClick(skill) },
                onStop = onStopActiveSkill,
                onEdit = { onEditSkillClick(skill) },
                onDelete = { onDeleteSkillClick(skill.skillId) }
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun PendingSkillProposalCard(
    proposal: SkillProposal,
    onApprove: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var editedName by remember(proposal.proposalId) { mutableStateOf(proposal.suggestedName) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("pending_skill_proposal_card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = IndigoGlow.copy(alpha = 0.20f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = ElectricCyan
                )
                Text(
                    text = "নতুন দক্ষতা শেখার প্রস্তাব (Learned Skill Proposal)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ElectricCyan
                )
            }
            Text(
                text = "এই সফল কাজটি ভবিষ্যতে সহজে ব্যবহারের জন্য সংরক্ষণ করতে চান? আপনার অনুমতি ছাড়া এটি সংরক্ষণ করা হবে না।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                label = { Text("Skill Name / দক্ষতার নাম") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "ধাপসমূহ (Steps): ${proposal.executionSteps.joinToString(" → ") { it.toolName }}",
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = "Permissions: ${proposal.requiredPermissions.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onApprove(editedName.trim()) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("approve_skill_proposal_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = EmeraldLive,
                        contentColor = Color(0xFF070B14)
                    )
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("অনুমোদন ও সংরক্ষণ", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("dismiss_skill_proposal_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("বাতিল")
                }
            }
        }
    }
}

@Composable
private fun SkillCard(
    skill: AgentSkill,
    isRunningThisSkill: Boolean,
    isAnyTaskRunning: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember(skill.skillId) { mutableStateOf(false) }
    val safetyColor = if (skill.safetyClassification == SkillSafetyClassification.LOW_RISK) {
        EmeraldLive
    } else {
        AmberThinking
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("skill_card_${skill.skillId}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = skill.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${skill.safetyClassification.labelBn} • v${skill.version} • ব্যবহৃত ${skill.usageCount} বার",
                        style = MaterialTheme.typography.labelSmall,
                        color = safetyColor
                    )
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("skill_toggle_${skill.skillId}")
                )
            }

            if (skill.description.isNotBlank()) {
                Text(
                    text = skill.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (skill.triggerPhrases.isNotEmpty()) {
                Text(
                    text = "বলুন (Triggers): ${skill.triggerPhrases.take(3).joinToString(" • ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isRunningThisSkill) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonAlert),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("stop_skill_${skill.skillId}"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("থামুন (Stop)", fontWeight = FontWeight.Bold)
                    }
                } else {
                    FilledTonalButton(
                        onClick = onRun,
                        enabled = skill.enabled && !isAnyTaskRunning,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("run_skill_${skill.skillId}"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("চালান (Run)", fontWeight = FontWeight.Bold)
                    }
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("expand_skill_${skill.skillId}")
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Hide steps and history" else "Show steps and history"
                    )
                }
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag("edit_skill_${skill.skillId}")
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit skill")
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_skill_${skill.skillId}")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete skill", tint = CrimsonAlert)
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Ordered Steps (${skill.executionSteps.size})",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    skill.executionSteps.forEachIndexed { idx, step ->
                        Text(
                            text = "${idx + 1}. [${step.toolName}] ${step.description}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = ElectricCyan
                        )
                        Text(
                            text = "Usage History (${skill.successCount}/${skill.usageCount} succeeded)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (skill.usageHistory.isEmpty()) {
                        Text(
                            text = "এখনো চালানো হয়নি (Not run yet)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val fmt = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
                        skill.usageHistory.take(4).forEach { entry ->
                            val icon = if (entry.succeeded) "✓" else "✕"
                            Text(
                                text = "$icon ${fmt.format(Date(entry.timestampMillis))} — ${entry.summary}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.succeeded) EmeraldLive else CrimsonAlert
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryEditorDialog(
    existing: AgentMemoryRecord?,
    onDismiss: () -> Unit,
    onConfirm: (MemoryCategory, String, String, MemoryExpirationPolicy) -> Unit
) {
    var category by remember(existing) { mutableStateOf(existing?.category ?: MemoryCategory.USER_PREFERENCE) }
    var key by remember(existing) { mutableStateOf(existing?.memoryKey ?: "") }
    var value by remember(existing) { mutableStateOf(existing?.value ?: "") }
    var expiry by remember(existing) { mutableStateOf(existing?.expirationPolicy ?: MemoryExpirationPolicy.NEVER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "নতুন স্মৃতি যোগ করুন" else "স্মৃতি সম্পাদনা করুন") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "ক্যাটাগরি (Category)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MemoryCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.displayNameBn, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("শিরোনাম / Key (e.g., favorite_music_app)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("তথ্য / Value (e.g., YouTube)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Text(
                    text = "মেয়াদ (Expiration)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        MemoryExpirationPolicy.NEVER,
                        MemoryExpirationPolicy.SEVEN_DAYS,
                        MemoryExpirationPolicy.THIRTY_DAYS,
                        MemoryExpirationPolicy.NINETY_DAYS
                    ).forEach { pol ->
                        FilterChip(
                            selected = expiry == pol,
                            onClick = { expiry = pol },
                            label = { Text(pol.labelBn, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Text(
                    text = "নিরাপত্তা নোট: পাসওয়ার্ড, ওটিপি, পিন, API Key বা ব্যক্তিগত মেসেজ এখানে সংরক্ষণ করা যাবে না।",
                    style = MaterialTheme.typography.labelSmall,
                    color = AmberThinking
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(category, key, value, expiry) },
                enabled = key.isNotBlank() && value.isNotBlank()
            ) {
                Text("সংরক্ষণ (Save)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("বাতিল (Cancel)")
            }
        }
    )
}

@Composable
private fun SkillEditorDialog(
    existing: AgentSkill?,
    onDismiss: () -> Unit,
    onSave: (AgentSkill) -> Unit
) {
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var description by remember(existing) { mutableStateOf(existing?.description ?: "") }
    var triggersText by remember(existing) { mutableStateOf(existing?.triggerPhrases?.joinToString(", ") ?: "") }
    var appNameParam by remember(existing) {
        mutableStateOf(
            existing?.executionSteps?.firstOrNull()?.parameterTemplates?.get("app_name") ?: "YouTube"
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "নতুন দক্ষতা তৈরি (Create Skill)" else "দক্ষতা সম্পাদনা (Edit Skill)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill Name / দক্ষতার নাম") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description / বিবরণ") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                OutlinedTextField(
                    value = triggersText,
                    onValueChange = { triggersText = it },
                    label = { Text("Trigger Phrases (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )
                if (existing == null) {
                    OutlinedTextField(
                        value = appNameParam,
                        onValueChange = { appNameParam = it },
                        label = { Text("Target App Name (open_app step)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val triggers = triggersText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val updated = existing?.copy(
                        name = name.trim(),
                        description = description.trim(),
                        triggerPhrases = triggers
                    ) ?: AgentSkill(
                        skillId = "skill_${UUID.randomUUID().toString().take(10)}",
                        name = name.trim(),
                        description = description.trim().ifBlank { "Opens $appNameParam and observes the screen." },
                        triggerPhrases = triggers.ifEmpty { listOf(name.trim().lowercase()) },
                        requiredParameters = emptyList(),
                        executionSteps = listOf(
                            SkillExecutionStep(
                                stepIndex = 0,
                                description = "Open ${appNameParam.trim()}",
                                toolName = "open_app",
                                parameterTemplates = mapOf("app_name" to appNameParam.trim()),
                                expectedOutcome = appNameParam.trim()
                            ),
                            SkillExecutionStep(
                                stepIndex = 1,
                                description = "Observe active screen",
                                toolName = "get_screen_state",
                                parameterTemplates = emptyMap(),
                                expectedOutcome = "Screen observed"
                            )
                        ),
                        allowedTools = listOf("open_app", "get_screen_state"),
                        safetyClassification = SkillSafetyClassification.LOW_RISK,
                        successCriteria = "Target app opened and observed"
                    )
                    onSave(updated)
                },
                enabled = name.isNotBlank()
            ) {
                Text("সংরক্ষণ (Save)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("বাতিল")
            }
        }
    )
}

@Composable
private fun RunSkillParametersDialog(
    skill: AgentSkill,
    onDismiss: () -> Unit,
    onRun: (Map<String, String>) -> Unit
) {
    val paramValues = remember(skill.skillId) {
        mutableStateMapOf<String, String>().apply {
            skill.requiredParameters.forEach { param ->
                put(
                    param,
                    when (param) {
                        "hour_24" -> "6"
                        "minute" -> "0"
                        "app_name" -> "YouTube"
                        "song_query" -> "Rabindra Sangeet"
                        else -> ""
                    }
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("চালান: ${skill.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "প্রয়োজনীয় তথ্য দিন (Enter parameters):",
                    style = MaterialTheme.typography.bodySmall
                )
                skill.requiredParameters.forEach { param ->
                    OutlinedTextField(
                        value = paramValues[param].orEmpty(),
                        onValueChange = { paramValues[param] = it },
                        label = { Text(param) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRun(paramValues.toMap()) },
                enabled = skill.requiredParameters.all { !paramValues[it].isNullOrBlank() }
            ) {
                Text("চালান (Run)")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("বাতিল")
            }
        }
    )
}
