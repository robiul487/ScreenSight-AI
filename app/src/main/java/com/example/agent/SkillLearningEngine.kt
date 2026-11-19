package com.example.agent

import com.example.data.local.skill.AgentSkill
import com.example.data.local.skill.SkillExecutionStep
import com.example.data.local.skill.SkillProposal
import com.example.data.local.skill.SkillSafetyClassification
import java.util.UUID

/**
 * Local Skill Learning Engine.
 *
 * Inspects successfully completed multi-step tasks and proposes reusable skills ONLY when:
 * 1. The workflow contains >= 2 reusable safe tool steps that all completed successfully.
 * 2. Zero steps involved sensitive, financial, credential, destructive, or private-communication tools.
 * 3. No equivalent skill already exists.
 *
 * CRITICAL GUARANTEE:
 * - Never auto-saves a learned skill. Always returns a [SkillProposal] for explicit user review & approval.
 */
class SkillLearningEngine(
    private val toolRegistry: AgentToolRegistry = AgentToolRegistry()
) {

    fun proposeSkillFromCompletedTask(
        taskId: String,
        taskDescription: String,
        plan: AgentTaskPlan?,
        executedSteps: List<AgentTaskStep>,
        existingSkills: List<AgentSkill>,
        nowMillis: Long = System.currentTimeMillis()
    ): SkillProposal? {
        val cleanDesc = MemoryPrivacyGuard.sanitizeSkillText(taskDescription, maxLen = 140)
        if (cleanDesc.isBlank()) return null

        // Never learn sensitive, financial, credential, or destructive workflows
        if (MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText(cleanDesc)) {
            return null
        }

        // Reject if any executed step failed, was cancelled, or touched a non-learnable sensitive tool
        if (executedSteps.any { it.status == AgentStepStatus.FAILED || it.status == AgentStepStatus.CANCELLED }) {
            return null
        }
        if (executedSteps.any { it.actionType in NON_LEARNABLE_SENSITIVE_ACTIONS }) {
            return null
        }

        val candidatePlanSteps = plan?.steps
            ?.filter { it.status == AgentStepStatus.COMPLETED }
            ?.filter { step ->
                val actionType = AgentActionType.fromToolName(step.toolName)
                actionType != null &&
                    actionType !in META_LIFECYCLE_ACTIONS &&
                    actionType !in NON_LEARNABLE_SENSITIVE_ACTIONS &&
                    toolRegistry.getTool(actionType.toolName) != null
            }
            .orEmpty()

        val effectiveSteps: List<SkillExecutionStep> = if (candidatePlanSteps.size >= MIN_REUSABLE_STEPS) {
            candidatePlanSteps.mapIndexed { idx, step ->
                val combinedStepText = "${step.description} ${step.arguments.values.joinToString(" ")}"
                if (MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText(combinedStepText)) {
                    return null
                }
                val safeArgs = step.arguments.filterKeys { key ->
                    !MemoryPrivacyGuard.containsSensitiveSecret(key = key, value = step.arguments[key].orEmpty())
                }
                SkillExecutionStep(
                    stepIndex = idx,
                    description = MemoryPrivacyGuard.sanitizeSkillText(step.description, maxLen = 120),
                    toolName = step.toolName,
                    parameterTemplates = safeArgs,
                    expectedOutcome = MemoryPrivacyGuard.sanitizeSkillText(step.expectedOutcome, maxLen = 100),
                    verificationCheck = deriveVerificationCheck(step.toolName, safeArgs),
                    recoveryToolName = deriveSafeRecoveryTool(step.toolName),
                    recoveryParameters = deriveSafeRecoveryParams(step.toolName)
                )
            }
        } else {
            val reusableExecuted = executedSteps.filter {
                it.status == AgentStepStatus.COMPLETED &&
                    it.actionType !in META_LIFECYCLE_ACTIONS &&
                    it.actionType !in NON_LEARNABLE_SENSITIVE_ACTIONS &&
                    toolRegistry.getTool(it.actionType.toolName) != null
            }
            if (reusableExecuted.size < MIN_REUSABLE_STEPS) return null
            reusableExecuted.mapIndexed { idx, step ->
                if (MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText("${step.titleEn} ${step.detail}")) {
                    return null
                }
                SkillExecutionStep(
                    stepIndex = idx,
                    description = MemoryPrivacyGuard.sanitizeSkillText(step.titleEn, maxLen = 120),
                    toolName = step.actionType.toolName,
                    parameterTemplates = emptyMap(),
                    expectedOutcome = "",
                    verificationCheck = deriveVerificationCheck(step.actionType.toolName, emptyMap()),
                    recoveryToolName = deriveSafeRecoveryTool(step.actionType.toolName),
                    recoveryParameters = deriveSafeRecoveryParams(step.actionType.toolName)
                )
            }
        }

        if (effectiveSteps.size < MIN_REUSABLE_STEPS) return null

        // Prevent proposing an exact duplicate of an existing skill with same tool sequence and name
        val newToolSequence = effectiveSteps.map { it.toolName }
        val alreadyExists = existingSkills.any { existing ->
            existing.name.equals(cleanDesc, ignoreCase = true) ||
                (existing.executionSteps.map { it.toolName } == newToolSequence &&
                    existing.executionSteps.map { it.parameterTemplates } == effectiveSteps.map { it.parameterTemplates })
        }
        if (alreadyExists) return null

        val allowedTools = effectiveSteps.map { it.toolName }.distinct()
        val requiredPermissions = deriveRequiredPermissions(allowedTools)
        val suggestedName = cleanDesc.take(64)

        return SkillProposal(
            proposalId = "prop_${UUID.randomUUID().toString().take(10)}",
            suggestedName = suggestedName,
            suggestedNameBn = "শেখা কাজ: $suggestedName",
            description = "Learned from completed multi-step workflow (${effectiveSteps.size} steps).",
            triggerPhrases = listOf(suggestedName.lowercase()),
            requiredParameters = emptyList(),
            executionSteps = effectiveSteps,
            allowedTools = allowedTools,
            requiredPermissions = requiredPermissions,
            safetyClassification = SkillSafetyClassification.LOW_RISK,
            successCriteria = "All ${effectiveSteps.size} workflow steps verified",
            sourceTaskId = taskId,
            createdAtMillis = nowMillis
        )
    }

    /**
     * Phase 5: Scans completed task execution records to identify repeatable multi-step workflows
     * that occur multiple times and proposes a structured reusable skill (never auto-saved).
     */
    fun detectRepeatableWorkflowProposal(
        historyRecords: List<AgentTaskLogger.TaskExecutionRecord>,
        existingSkills: List<AgentSkill>,
        minOccurrences: Int = 2,
        nowMillis: Long = System.currentTimeMillis()
    ): SkillProposal? {
        val safeCompleted = historyRecords.filter { record ->
            record.finalState == AgentExecutionState.COMPLETED &&
                !MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText(record.userRequest)
        }
        if (safeCompleted.size < minOccurrences) return null

        val groupedBySignature = safeCompleted.mapNotNull { record ->
            val tools = record.entries
                .filter { it.type == AgentTaskLogger.LogEventType.TOOL_CALL }
                .mapNotNull { entry -> entry.metadata["tool"]?.let { AgentActionType.fromToolName(it) } }
                .filter {
                    it !in META_LIFECYCLE_ACTIONS &&
                        it !in NON_LEARNABLE_SENSITIVE_ACTIONS &&
                        toolRegistry.getTool(it.toolName) != null
                }
            if (tools.size < MIN_REUSABLE_STEPS) return@mapNotNull null
            val signature = tools.joinToString("->") { it.toolName }
            signature to (record to tools)
        }.groupBy({ it.first }, { it.second })

        val topPattern = groupedBySignature.entries
            .filter { it.value.size >= minOccurrences }
            .maxByOrNull { it.value.size }
            ?: return null

        val sampleRecord = topPattern.value.last().first
        val toolSequence = topPattern.value.last().second
        val toolNames = toolSequence.map { it.toolName }

        val duplicate = existingSkills.any { existing ->
            existing.executionSteps.map { it.toolName } == toolNames
        }
        if (duplicate) return null

        val cleanTitle = MemoryPrivacyGuard.sanitizeSkillText(sampleRecord.userRequest, maxLen = 64)
            .ifBlank { "Routine: ${toolSequence.first().displayLabelEn}" }

        val steps = toolSequence.mapIndexed { idx, actionType ->
            SkillExecutionStep(
                stepIndex = idx,
                description = actionType.displayLabelEn,
                toolName = actionType.toolName,
                parameterTemplates = emptyMap(),
                expectedOutcome = "${actionType.displayLabelEn} verified",
                verificationCheck = deriveVerificationCheck(actionType.toolName, emptyMap()),
                recoveryToolName = deriveSafeRecoveryTool(actionType.toolName),
                recoveryParameters = deriveSafeRecoveryParams(actionType.toolName)
            )
        }

        return SkillProposal(
            proposalId = "prop_${UUID.randomUUID().toString().take(10)}",
            suggestedName = cleanTitle,
            suggestedNameBn = "পুনরাবৃত্ত দক্ষতা: $cleanTitle",
            description = "Detected repeatable ${steps.size}-step workflow (${topPattern.value.size} completed runs).",
            triggerPhrases = listOf(cleanTitle.lowercase()),
            requiredParameters = emptyList(),
            executionSteps = steps,
            allowedTools = toolNames.distinct(),
            requiredPermissions = deriveRequiredPermissions(toolNames.distinct()),
            safetyClassification = SkillSafetyClassification.LOW_RISK,
            successCriteria = "All ${steps.size} workflow steps verified",
            sourceTaskId = sampleRecord.taskId,
            createdAtMillis = nowMillis
        )
    }

    /**
     * Phase 5: Finds the best-matching enabled, user-approved skill for a natural-language goal
     * so the orchestrator can reuse proven workflows instead of reinventing steps from scratch.
     */
    fun findRelevantSkillForGoal(
        goal: String,
        availableSkills: List<AgentSkill>
    ): AgentSkill? {
        val cleanGoal = goal.trim().lowercase()
        if (cleanGoal.isBlank()) return null
        val goalTokens = tokenize(cleanGoal)
        if (goalTokens.isEmpty()) return null

        return availableSkills
            .filter { it.enabled && it.userApproved }
            .map { skill ->
                var score = 0.0
                if (cleanGoal.contains(skill.name.lowercase())) score += 4.0
                skill.triggerPhrases.forEach { trigger ->
                    val cleanTrigger = trigger.trim().lowercase()
                    if (cleanTrigger.isNotBlank() && cleanGoal.contains(cleanTrigger)) {
                        score += 5.0
                    } else {
                        val triggerTokens = tokenize(cleanTrigger)
                        val overlap = goalTokens.intersect(triggerTokens).size
                        if (overlap > 0 && triggerTokens.isNotEmpty()) {
                            score += (overlap.toDouble() / triggerTokens.size.toDouble()) * 2.5
                        }
                    }
                }
                val nameOverlap = goalTokens.intersect(tokenize(skill.name)).size
                score += nameOverlap * 1.2
                // Slight preference for reliable skills with proven success history
                if (skill.usageCount > 0) {
                    val rate = skill.successCount.toDouble() / skill.usageCount.toDouble()
                    score += rate * 0.5
                }
                skill to score
            }
            .filter { it.second >= 2.0 }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun deriveVerificationCheck(toolName: String, params: Map<String, String>): String {
        return when (AgentActionType.fromToolName(toolName)) {
            AgentActionType.OPEN_APP -> "verify_app:${params["app_name"].orEmpty().ifBlank { "foreground" }}"
            AgentActionType.SEARCH_TEXT -> "verify_results:${params["query"].orEmpty().take(32)}"
            AgentActionType.SET_VOLUME -> "verify_volume:${params["direction"].orEmpty()}"
            AgentActionType.GET_DATE_TIME -> "verify_time_returned"
            else -> "verify_step_succeeded"
        }
    }

    private fun deriveSafeRecoveryTool(toolName: String): String? {
        return when (AgentActionType.fromToolName(toolName)) {
            AgentActionType.CLICK_ELEMENT,
            AgentActionType.SEARCH_TEXT -> AgentActionType.WAIT_FOR_UI.toolName
            AgentActionType.OPEN_APP -> AgentActionType.GET_CURRENT_APP.toolName
            else -> null
        }
    }

    private fun deriveSafeRecoveryParams(toolName: String): Map<String, String> {
        return when (AgentActionType.fromToolName(toolName)) {
            AgentActionType.CLICK_ELEMENT,
            AgentActionType.SEARCH_TEXT -> mapOf("timeout_ms" to "1500")
            else -> emptyMap()
        }
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9\\u0980-\\u09FF]+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun deriveRequiredPermissions(toolNames: List<String>): List<String> {
        val perms = mutableListOf<String>()
        val needsAccessibility = toolNames.any { name ->
            val required = toolRegistry.getTool(name)?.requiredPermissions.orEmpty()
            AgentToolRegistry.RequiredPermission.ACCESSIBILITY_SERVICE in required
        }
        if (needsAccessibility) {
            perms.add("Android Accessibility Control")
        }
        if (perms.isEmpty()) {
            perms.add("Standard Android App Intents (No extra permission)")
        }
        return perms
    }

    companion object {
        private const val MIN_REUSABLE_STEPS = 2

        private val META_LIFECYCLE_ACTIONS = setOf(
            AgentActionType.CREATE_PLAN,
            AgentActionType.UPDATE_PLAN,
            AgentActionType.COMPLETE_TASK,
            AgentActionType.ASK_CONFIRMATION
        )

        /**
         * Sensitive / private communication / system-settings actions are NEVER auto-learned.
         */
        private val NON_LEARNABLE_SENSITIVE_ACTIONS = setOf(
            AgentActionType.CALL_TRUSTED_CONTACT,
            AgentActionType.OPEN_DIALER,
            AgentActionType.PREPARE_MESSAGE,
            AgentActionType.OPEN_SETTINGS,
            AgentActionType.TYPE_TEXT
        )
    }
}
