package com.example.agent

import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.orchestration.TaskSubtask
import com.example.data.local.skill.AgentSkill
import java.util.UUID

/**
 * Phase 5: Controlled Multi-Agent Architecture.
 *
 * Implements strictly bounded logical roles sharing a single [SharedAgentTaskContext]
 * on top of the existing single configured API model (no unnecessary parallel API calls):
 * - [AgentRole.MEMORY]: Retrieves relevant sanitized local memories (read-only).
 * - [AgentRole.PLANNER]: Selects safe tools / matching skills and produces structured subtasks (no direct execution).
 * - [AgentRole.RESEARCH]: Performs read-only screen/web information analysis (no state modification).
 * - [AgentRole.EXECUTOR]: The ONLY role permitted to dispatch device tools, ALWAYS routed through
 *   [AgentToolRegistry] + [AgentSafetyManager].
 * - [AgentRole.VERIFIER]: Independently checks post-execution outcome before task completion.
 *
 * CRITICAL SECURITY INVARIANTS:
 * - No role can grant permissions to itself or another role (`canGrantPermissions == false`).
 * - Non-executor roles attempting to invoke device tools are strictly denied.
 * - Every tool call passes through central [AgentToolRegistry] and [AgentSafetyManager].
 */
class MultiAgentOrchestrator(
    private val dynamicToolManager: DynamicToolManager,
    private val memoryRetriever: IntelligentMemoryRetriever? = null,
    private val skillLearningEngine: SkillLearningEngine = SkillLearningEngine(dynamicToolManager.toolRegistry),
    private val toolExecutor: suspend (AgentActionRequest, Boolean) -> AgentActionResult = { req, confirmed ->
        val tool = dynamicToolManager.toolRegistry.getTool(req.actionType.toolName)
        if (tool != null) {
            dynamicToolManager.toolRegistry.executeRegisteredTool(tool, req, confirmed)
        } else {
            AgentActionResult(
                success = false,
                status = "UNSUPPORTED_ACTION",
                summaryEn = "Tool ${req.actionType.toolName} is not registered.",
                summaryBn = "এই টুলটি নিবন্ধিত নয়।"
            )
        }
    }
) {

    enum class AgentRole(
        val labelEn: String,
        val labelBn: String,
        val canInvokeDeviceTools: Boolean,
        val canReadMemory: Boolean,
        val canModifyPlan: Boolean,
        val canGrantPermissions: Boolean = false
    ) {
        MEMORY("Memory Agent", "স্মৃতি এজেন্ট", canInvokeDeviceTools = false, canReadMemory = true, canModifyPlan = false),
        PLANNER("Planner Agent", "পরিকল্পনা এজেন্ট", canInvokeDeviceTools = false, canReadMemory = true, canModifyPlan = true),
        RESEARCH("Research Agent", "তথ্য এজেন্ট", canInvokeDeviceTools = false, canReadMemory = true, canModifyPlan = false),
        EXECUTOR("Executor Agent", "কার্যকরী এজেন্ট", canInvokeDeviceTools = true, canReadMemory = false, canModifyPlan = false),
        VERIFIER("Verifier Agent", "যাচাই এজেন্ট", canInvokeDeviceTools = false, canReadMemory = false, canModifyPlan = false)
    }

    data class RoleAuditEntry(
        val role: AgentRole,
        val action: String,
        val permitted: Boolean,
        val note: String,
        val timestampMillis: Long = System.currentTimeMillis()
    )

    data class SharedAgentTaskContext(
        val taskId: String,
        val sanitizedGoal: String,
        val retrievedMemories: List<AgentMemoryRecord> = emptyList(),
        val selectedTools: List<DynamicToolManager.ToolDescriptor> = emptyList(),
        val matchedSkill: AgentSkill? = null,
        val plannedSubtasks: List<TaskSubtask> = emptyList(),
        val researchNotes: List<String> = emptyList(),
        val executionResults: List<AgentActionResult> = emptyList(),
        val verifiedComplete: Boolean = false,
        val verificationNote: String = "",
        val auditTrail: List<RoleAuditEntry> = emptyList()
    )

    sealed class RoleToolDispatchResult {
        data class Executed(val result: AgentActionResult) : RoleToolDispatchResult()
        data class DeniedByRoleBoundary(val role: AgentRole, val reasonEn: String) : RoleToolDispatchResult()
        data class BlockedBySafety(val reasonEn: String, val reasonBn: String) : RoleToolDispatchResult()
        data class ConfirmationRequired(val reasonEn: String, val reasonBn: String) : RoleToolDispatchResult()
    }

    /**
     * Enforces role permission boundaries on every tool dispatch.
     * ONLY [AgentRole.EXECUTOR] may invoke tools, and ONLY after central validation + [AgentSafetyManager].
     */
    suspend fun dispatchToolForRole(
        role: AgentRole,
        request: AgentActionRequest,
        userConfirmed: Boolean = false,
        activePackageName: String = ""
    ): RoleToolDispatchResult {
        if (!role.canInvokeDeviceTools) {
            return RoleToolDispatchResult.DeniedByRoleBoundary(
                role = role,
                reasonEn = "Role ${role.name} is forbidden from invoking device tools; only EXECUTOR may execute actions."
            )
        }

        val (registeredTool, validation) = dynamicToolManager.validateCall(
            request.actionType.toolName,
            request.parameters
        )
        if (!validation.isValid || registeredTool == null) {
            return RoleToolDispatchResult.Executed(
                AgentActionResult(
                    success = false,
                    status = validation.errorCode.ifBlank { "INVALID_ARGUMENTS" },
                    summaryEn = validation.errorMessageEn,
                    summaryBn = validation.errorMessageBn
                )
            )
        }

        val safety = AgentSafetyManager.evaluate(request, activePackageName)
        if (safety.isBlocked) {
            return RoleToolDispatchResult.BlockedBySafety(safety.reasonEn, safety.reasonBn)
        }
        if (safety.requiresConfirmation && !userConfirmed) {
            return RoleToolDispatchResult.ConfirmationRequired(safety.reasonEn, safety.reasonBn)
        }

        val result = toolExecutor(request, userConfirmed)
        return RoleToolDispatchResult.Executed(result)
    }

    /**
     * Explicitly denies any attempt by one agent role to grant permissions to another role.
     */
    fun attemptPermissionDelegation(fromRole: AgentRole, toRole: AgentRole): Boolean {
        // Hard invariant: roles can NEVER grant or escalate permissions to each other.
        return fromRole.canGrantPermissions && toRole.canGrantPermissions
    }

    /**
     * Runs a controlled sequential multi-agent handoff for a user goal:
     * MEMORY -> PLANNER -> RESEARCH (optional) -> EXECUTOR (safe subtasks) -> VERIFIER.
     *
     * Runs locally without spawning parallel LLM API calls. Sensitive subtasks are NOT
     * silently executed; they are surfaced as requiring explicit user confirmation.
     */
    suspend fun runControlledWorkflow(
        goal: String,
        availableSkills: List<AgentSkill> = emptyList(),
        executeSafePlan: Boolean = true,
        userConfirmedSensitive: Boolean = false
    ): SharedAgentTaskContext {
        val taskId = "ma_${UUID.randomUUID().toString().take(8)}"
        val cleanGoal = MemoryPrivacyGuard.sanitizeSkillText(goal, maxLen = 180)
        val audit = mutableListOf<RoleAuditEntry>()

        // 1. MEMORY role: read-only retrieval of sanitized local memories
        val memories: List<AgentMemoryRecord> = if (memoryRetriever != null && cleanGoal.isNotBlank()) {
            memoryRetriever.retrieveForQuery(userQuery = cleanGoal, maxResults = 4).selectedMemories
        } else {
            emptyList()
        }
        audit.add(
            RoleAuditEntry(
                role = AgentRole.MEMORY,
                action = "retrieve_memories",
                permitted = true,
                note = "Retrieved ${memories.size} relevant local memories"
            )
        )

        // 2. PLANNER role: discover tools, check reusable approved skills, build dependency-ordered subtasks
        val selectedTools = dynamicToolManager.selectToolsForTask(cleanGoal, maxTools = 6)
        val matchedSkill = skillLearningEngine.findRelevantSkillForGoal(cleanGoal, availableSkills)
        val plannedSubtasks = buildSubtasksFromGoal(cleanGoal, matchedSkill, selectedTools)
        audit.add(
            RoleAuditEntry(
                role = AgentRole.PLANNER,
                action = "build_subtask_plan",
                permitted = true,
                note = if (matchedSkill != null) {
                    "Reused approved skill '${matchedSkill.name}' (${plannedSubtasks.size} subtasks)"
                } else {
                    "Selected ${selectedTools.size} tools -> planned ${plannedSubtasks.size} subtasks"
                }
            )
        )

        // 3. RESEARCH role: read-only context summary (never executes modifying tools)
        val researchNotes = buildList {
            if (memories.isNotEmpty()) {
                add("Local memory hints: ${memories.joinToString("; ") { "${it.memoryKey}=${it.value}" }}")
            }
            if (matchedSkill != null) {
                add("Matched proven skill: ${matchedSkill.name} (v${matchedSkill.version}, ${matchedSkill.successCount}/${matchedSkill.usageCount} success)")
            }
            val unavailableTools = selectedTools.filterNot { it.isCurrentlyAvailable }
            if (unavailableTools.isNotEmpty()) {
                add("Unavailable tools needing permission: ${unavailableTools.joinToString(", ") { it.name }}")
            }
        }
        audit.add(
            RoleAuditEntry(
                role = AgentRole.RESEARCH,
                action = "analyze_context",
                permitted = true,
                note = "Prepared ${researchNotes.size} read-only context notes"
            )
        )

        // 4. EXECUTOR role: executes planned subtasks strictly through AgentToolRegistry + AgentSafetyManager
        val results = mutableListOf<AgentActionResult>()
        if (executeSafePlan) {
            for (subtask in plannedSubtasks) {
                val actionType = AgentActionType.fromToolName(subtask.toolName) ?: continue
                val req = AgentActionRequest(
                    actionType = actionType,
                    parameters = subtask.parameters,
                    reason = "Multi-agent executor: ${subtask.title}",
                    requiresConfirmation = subtask.isSensitive
                )
                when (val dispatched = dispatchToolForRole(AgentRole.EXECUTOR, req, userConfirmedSensitive)) {
                    is RoleToolDispatchResult.Executed -> {
                        results.add(dispatched.result)
                        audit.add(
                            RoleAuditEntry(
                                role = AgentRole.EXECUTOR,
                                action = "execute:${subtask.toolName}",
                                permitted = true,
                                note = "${dispatched.result.status}: ${dispatched.result.summaryEn}"
                            )
                        )
                        if (!dispatched.result.success) break
                    }
                    is RoleToolDispatchResult.BlockedBySafety -> {
                        results.add(
                            AgentActionResult(
                                success = false,
                                status = "BLOCKED_HIGH_RISK",
                                summaryEn = dispatched.reasonEn,
                                summaryBn = dispatched.reasonBn,
                                blockedBySafety = true
                            )
                        )
                        audit.add(
                            RoleAuditEntry(
                                role = AgentRole.EXECUTOR,
                                action = "execute:${subtask.toolName}",
                                permitted = false,
                                note = "Blocked by AgentSafetyManager"
                            )
                        )
                        break
                    }
                    is RoleToolDispatchResult.ConfirmationRequired -> {
                        results.add(
                            AgentActionResult(
                                success = false,
                                status = "CONFIRMATION_REQUIRED",
                                summaryEn = dispatched.reasonEn,
                                summaryBn = dispatched.reasonBn
                            )
                        )
                        audit.add(
                            RoleAuditEntry(
                                role = AgentRole.EXECUTOR,
                                action = "execute:${subtask.toolName}",
                                permitted = false,
                                note = "Paused awaiting explicit user confirmation"
                            )
                        )
                        break
                    }
                    is RoleToolDispatchResult.DeniedByRoleBoundary -> {
                        audit.add(
                            RoleAuditEntry(
                                role = AgentRole.EXECUTOR,
                                action = "execute:${subtask.toolName}",
                                permitted = false,
                                note = dispatched.reasonEn
                            )
                        )
                        break
                    }
                }
            }
        }

        // 5. VERIFIER role: verifies whether all planned subtasks succeeded with real results
        val verified = executeSafePlan &&
            plannedSubtasks.isNotEmpty() &&
            results.size == plannedSubtasks.size &&
            results.all { it.success }
        val verifyNote = when {
            !executeSafePlan -> "Plan prepared and verified structurally; awaiting execution."
            verified -> "All ${results.size} subtasks verified complete."
            results.any { it.blockedBySafety } -> "Halted by safety boundary."
            results.any { it.status == "CONFIRMATION_REQUIRED" } -> "Awaiting explicit user confirmation for sensitive step."
            else -> "Incomplete: ${results.count { it.success }}/${plannedSubtasks.size} subtasks verified."
        }
        audit.add(
            RoleAuditEntry(
                role = AgentRole.VERIFIER,
                action = "verify_outcome",
                permitted = true,
                note = verifyNote
            )
        )

        return SharedAgentTaskContext(
            taskId = taskId,
            sanitizedGoal = cleanGoal,
            retrievedMemories = memories,
            selectedTools = selectedTools,
            matchedSkill = matchedSkill,
            plannedSubtasks = plannedSubtasks,
            researchNotes = researchNotes,
            executionResults = results,
            verifiedComplete = verified,
            verificationNote = verifyNote,
            auditTrail = audit
        )
    }

    private fun buildSubtasksFromGoal(
        cleanGoal: String,
        matchedSkill: AgentSkill?,
        selectedTools: List<DynamicToolManager.ToolDescriptor>
    ): List<TaskSubtask> {
        if (matchedSkill != null && matchedSkill.executionSteps.isNotEmpty()) {
            var prevId: String? = null
            return matchedSkill.executionSteps.mapIndexed { idx, step ->
                val id = "sub_${idx + 1}"
                val task = TaskSubtask(
                    subtaskId = id,
                    title = step.description.ifBlank { step.toolName },
                    description = "From approved skill ${matchedSkill.name}",
                    toolName = step.toolName,
                    parameters = step.parameterTemplates,
                    dependsOn = listOfNotNull(prevId),
                    expectedOutcome = step.expectedOutcome,
                    isSensitive = matchedSkill.safetyClassification ==
                        com.example.data.local.skill.SkillSafetyClassification.SENSITIVE_CONFIRMATION
                )
                prevId = id
                task
            }
        }

        val lower = cleanGoal.lowercase()
        val subtasks = mutableListOf<TaskSubtask>()
        var lastId: String? = null

        fun addStep(title: String, tool: AgentActionType, params: Map<String, String> = emptyMap(), sensitive: Boolean = false) {
            val id = "sub_${subtasks.size + 1}"
            subtasks.add(
                TaskSubtask(
                    subtaskId = id,
                    title = title,
                    toolName = tool.toolName,
                    parameters = params,
                    dependsOn = listOfNotNull(lastId),
                    expectedOutcome = "$title completed",
                    isSensitive = sensitive
                )
            )
            lastId = id
        }

        when {
            lower.contains("time") || lower.contains("date") || lower.contains("সময়") || lower.contains("কয়টা") -> {
                addStep("Check current date and time", AgentActionType.GET_DATE_TIME)
            }
            lower.contains("youtube") || lower.contains("ইউটিউব") -> {
                addStep("Open YouTube", AgentActionType.OPEN_APP, mapOf("app_name" to "YouTube"))
                if (lower.contains("search") || lower.contains("খোঁজ") || lower.contains("song") || lower.contains("গান") || lower.contains("news") || lower.contains("খবর")) {
                    addStep("Search in YouTube", AgentActionType.SEARCH_TEXT, mapOf("query" to cleanGoal, "app_name" to "YouTube"))
                }
            }
            lower.contains("read screen") || lower.contains("স্ক্রিন পড়") -> {
                addStep("Capture screen state", AgentActionType.GET_SCREEN_STATE)
                addStep("Read visible screen", AgentActionType.READ_SCREEN)
            }
            lower.contains("volume") || lower.contains("ভলিউম") -> {
                val dir = if (lower.contains("down") || lower.contains("কমা")) "down" else "up"
                addStep("Adjust media volume ($dir)", AgentActionType.SET_VOLUME, mapOf("direction" to dir))
            }
            lower.contains("call") || lower.contains("কল") -> {
                addStep(
                    "Prepare call (requires confirmation)",
                    AgentActionType.CALL_TRUSTED_CONTACT,
                    mapOf("contact_query" to cleanGoal),
                    sensitive = true
                )
            }
            else -> {
                val firstSafe = selectedTools.firstOrNull { it.safetyClassification == ActionRiskLevel.LOW_RISK }
                if (firstSafe != null && firstSafe.requiredParams.isEmpty()) {
                    addStep(firstSafe.actionType.displayLabelEn, firstSafe.actionType)
                } else {
                    addStep("Inspect current device state", AgentActionType.GET_DATE_TIME)
                }
            }
        }

        return subtasks
    }
}
