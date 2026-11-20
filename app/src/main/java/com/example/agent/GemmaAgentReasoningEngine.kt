package com.example.agent

import com.example.data.ai.AIModelRouter
import com.example.data.ai.AIProposedToolCall
import com.example.data.ai.AIReasoningRequest
import com.example.data.ai.AIReasoningResponse
import com.example.data.ai.AIReasoningTaskStage
import com.example.data.ai.ModelUsageRecord
import com.example.data.ai.VerifiedReasoningModels
import com.example.data.local.memory.AgentMemoryRecord
import com.example.data.local.orchestration.TaskSubtask
import com.example.data.local.skill.AgentSkill
import com.example.data.remote.BidiFunctionDeclaration
import com.example.data.remote.GeminiLiveJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Integrates Gemma 4 reasoning safely into ScreenSight AI's existing autonomous agent pipeline.
 *
 * Strict Safety & Architectural Invariants (Parts 6 & 8):
 * - Gemma NEVER executes Android device actions directly.
 * - Every tool call or planned subtask proposed by Gemma is validated locally against
 *   [AgentToolRegistry] and evaluated by [AgentSafetyManager] before returning.
 * - Unregistered/invented tools, invalid parameters, and Group C high-risk actions are rejected.
 * - Group B sensitive actions (calls, messages) are always tagged `isSensitive = true` so
 *   [AgentCoordinator] / [LongTermTaskOrchestrator] enforce explicit user confirmation.
 * - All prompt context sent to cloud models is sanitized via [MemoryPrivacyGuard] and
 *   [AgentSafetyManager.redactSensitiveContent].
 */
class GemmaAgentReasoningEngine(
    val modelRouter: AIModelRouter,
    private val toolRegistry: AgentToolRegistry,
    private val dynamicToolManager: DynamicToolManager
) {

    data class PlannedGoalResult(
        val usedCloudModel: Boolean,
        val subtasks: List<TaskSubtask>,
        val planSummary: String = "",
        val directAnswer: String? = null,
        val rejectedToolProposalsCount: Int = 0,
        val blockedBySafetyCount: Int = 0,
        val modelUsage: ModelUsageRecord? = null,
        val fallbackReason: String? = null
    )

    data class RecoveryProposalResult(
        val hasRecoveryPlan: Boolean,
        val replacementSubtasks: List<TaskSubtask> = emptyList(),
        val explanation: String = "",
        val modelUsage: ModelUsageRecord? = null
    )

    data class VerificationAssistResult(
        val usedCloudModel: Boolean,
        val appearsVerified: Boolean,
        val explanation: String,
        val modelUsage: ModelUsageRecord? = null
    )

    data class MemoryRankingResult(
        val orderedMemoryIds: List<String>,
        val summaryHint: String = "",
        val modelUsage: ModelUsageRecord? = null
    )

    data class SkillSuggestionAssistResult(
        val suggestedName: String,
        val suggestedDescription: String,
        val modelUsage: ModelUsageRecord? = null
    )

    /**
     * Plans dependency-ordered subtasks (or a direct informational answer) for a complex user goal
     * using Gemma 4 native tool calling or constrained JSON output, with strict local validation.
     */
    suspend fun planGoalWithReasoning(
        goal: String,
        retrievedMemories: List<AgentMemoryRecord> = emptyList(),
        availableSkills: List<AgentSkill> = emptyList(),
        taskIdForTelemetry: String? = null
    ): PlannedGoalResult {
        val cleanGoal = sanitizeForCloudPrompt(goal, maxLen = 400)
        if (cleanGoal.isBlank()) {
            return PlannedGoalResult(usedCloudModel = false, subtasks = emptyList())
        }

        if (!modelRouter.shouldUseCloudReasoning(cleanGoal, AIReasoningTaskStage.TASK_PLANNING)) {
            return PlannedGoalResult(
                usedCloudModel = false,
                subtasks = emptyList(),
                fallbackReason = "Routed to local fast-path"
            )
        }

        val candidateTools = dynamicToolManager.selectToolsForTask(cleanGoal, maxTools = 8)
            .filter {
                it.actionType != AgentActionType.CREATE_PLAN &&
                    it.actionType != AgentActionType.UPDATE_PLAN &&
                    it.actionType != AgentActionType.COMPLETE_TASK
            }
        val declarations = candidateTools.mapNotNull { desc ->
            toolRegistry.getTool(desc.name)?.let { reg ->
                BidiFunctionDeclaration(
                    name = reg.name,
                    description = reg.description,
                    parameters = reg.parametersSchema
                )
            }
        }

        val prompt = buildPlanningPrompt(
            cleanGoal = cleanGoal,
            memories = retrievedMemories,
            skills = availableSkills,
            allowedToolNames = declarations.map { it.name }
        )

        val response = modelRouter.reason(
            request = AIReasoningRequest(
                taskStage = AIReasoningTaskStage.TASK_PLANNING,
                userPrompt = prompt,
                systemInstruction = PLANNING_SYSTEM_INSTRUCTION,
                tools = declarations,
                expectJsonOutput = false,
                temperature = 0.15,
                maxOutputTokens = 1536
            ),
            taskIdForTelemetry = taskIdForTelemetry
        )

        val usage = buildUsageRecord(response)
        if (!response.success) {
            return PlannedGoalResult(
                usedCloudModel = false,
                subtasks = emptyList(),
                modelUsage = usage,
                fallbackReason = response.error?.userMessage ?: "Reasoning provider unavailable"
            )
        }

        return parseAndValidatePlanResponse(response, usage)
    }

    /**
     * Asks Gemma 4 to propose a safe alternative recovery plan when a subtask fails,
     * rejecting any proposal that repeats an already-failed signature or violates safety.
     */
    suspend fun proposeRecoveryPlan(
        goal: String,
        failedSubtask: TaskSubtask,
        failureReason: String,
        completedSubtaskIds: Set<String>,
        triedActionSignatures: Set<String> = emptySet(),
        taskIdForTelemetry: String? = null
    ): RecoveryProposalResult {
        val cleanGoal = sanitizeForCloudPrompt(goal, maxLen = 240)
        val cleanFailure = sanitizeForCloudPrompt(failureReason, maxLen = 240)
        if (!modelRouter.shouldUseCloudReasoning(cleanGoal, AIReasoningTaskStage.ERROR_RECOVERY)) {
            return RecoveryProposalResult(hasRecoveryPlan = false)
        }

        val candidateTools = dynamicToolManager.selectToolsForTask(cleanGoal, maxTools = 6)
            .filter { it.isCurrentlyAvailable }
        val allowedNames = candidateTools.map { it.name }

        val prompt = buildString {
            appendLine("Goal: $cleanGoal")
            appendLine("Failed subtask: ${failedSubtask.title} (tool=${failedSubtask.toolName}, params=${failedSubtask.parameters})")
            appendLine("Failure reason: $cleanFailure")
            appendLine("Already completed subtask IDs (DO NOT REPEAT): ${completedSubtaskIds.joinToString(", ").ifBlank { "none" }}")
            appendLine("Already tried failed actions (DO NOT REPEAT): ${triedActionSignatures.joinToString("; ").ifBlank { "none" }}")
            appendLine("Allowed registered tools ONLY: ${allowedNames.joinToString(", ")}")
            appendLine(
                """Respond ONLY with JSON: {"explanation": "short reason", "subtasks": [{"title": "...", "tool_name": "...", "parameters": {}, "expected_outcome": "..."}]}"""
            )
        }

        val response = modelRouter.reason(
            request = AIReasoningRequest(
                taskStage = AIReasoningTaskStage.ERROR_RECOVERY,
                userPrompt = prompt,
                systemInstruction = RECOVERY_SYSTEM_INSTRUCTION,
                expectJsonOutput = true,
                temperature = 0.15,
                maxOutputTokens = 1024
            ),
            taskIdForTelemetry = taskIdForTelemetry
        )
        val usage = buildUsageRecord(response)
        if (!response.success) {
            return RecoveryProposalResult(hasRecoveryPlan = false, modelUsage = usage)
        }

        val parsed = parseAndValidatePlanResponse(response, usage)
        val deduped = parsed.subtasks.filter { sub ->
            val sig = actionSignature(sub.toolName, sub.parameters)
            sig !in triedActionSignatures &&
                sig != actionSignature(failedSubtask.toolName, failedSubtask.parameters)
        }

        return RecoveryProposalResult(
            hasRecoveryPlan = deduped.isNotEmpty(),
            replacementSubtasks = deduped,
            explanation = parsed.planSummary.ifBlank { "Proposed ${deduped.size} recovery step(s)." },
            modelUsage = usage
        )
    }

    /**
     * Assists outcome verification strictly based on real observed Accessibility screen text.
     */
    suspend fun assistVerification(
        goal: String,
        expectedOutcome: String,
        observedScreenSummary: String,
        taskIdForTelemetry: String? = null
    ): VerificationAssistResult {
        val cleanObserved = sanitizeForCloudPrompt(observedScreenSummary, maxLen = 600)
        if (cleanObserved.isBlank()) {
            return VerificationAssistResult(
                usedCloudModel = false,
                appearsVerified = false,
                explanation = "No observable screen state available to verify."
            )
        }
        if (!modelRouter.shouldUseCloudReasoning(goal, AIReasoningTaskStage.VERIFICATION_ASSIST)) {
            val matched = expectedOutcome.isNotBlank() &&
                cleanObserved.contains(expectedOutcome, ignoreCase = true)
            return VerificationAssistResult(
                usedCloudModel = false,
                appearsVerified = matched,
                explanation = if (matched) "Observed expected indicator on screen." else "Expected indicator not confirmed locally."
            )
        }

        val prompt = buildString {
            appendLine("Goal: ${sanitizeForCloudPrompt(goal, 180)}")
            appendLine("Expected observable outcome: ${sanitizeForCloudPrompt(expectedOutcome, 180)}")
            appendLine("Observed screen text (redacted): $cleanObserved")
            appendLine("""Reply strictly with JSON: {"verified": true|false, "explanation": "short evidence-based reason"}""")
        }

        val response = modelRouter.reason(
            request = AIReasoningRequest(
                taskStage = AIReasoningTaskStage.VERIFICATION_ASSIST,
                userPrompt = prompt,
                systemInstruction = "You verify Android UI task completion strictly from the observed screen text. Never guess or fabricate observations.",
                expectJsonOutput = true,
                temperature = 0.0,
                maxOutputTokens = 300
            ),
            taskIdForTelemetry = taskIdForTelemetry
        )
        val usage = buildUsageRecord(response)
        if (!response.success) {
            return VerificationAssistResult(
                usedCloudModel = false,
                appearsVerified = false,
                explanation = "Cloud verification unavailable; rely on local screen observation.",
                modelUsage = usage
            )
        }

        val jsonObj = extractJsonObjectFromText(response.text)
        val verified = jsonObj?.get("verified")?.jsonPrimitive?.booleanOrNull ?: false
        val note = jsonObj?.get("explanation")?.jsonPrimitive?.contentOrNull
            ?.take(220)
            ?: "Verification parsed."
        return VerificationAssistResult(
            usedCloudModel = true,
            appearsVerified = verified,
            explanation = note,
            modelUsage = usage
        )
    }

    /**
     * Assists selecting the most relevant ALREADY-SANITIZED local memories for a query.
     * Never writes to memory or bypasses [MemoryPrivacyGuard].
     */
    suspend fun assistMemoryRelevance(
        userQuery: String,
        candidateMemories: List<AgentMemoryRecord>
    ): MemoryRankingResult {
        if (candidateMemories.size <= 1) {
            return MemoryRankingResult(orderedMemoryIds = candidateMemories.map { it.id })
        }
        val cleanQuery = sanitizeForCloudPrompt(userQuery, 200)
        if (!modelRouter.shouldUseCloudReasoning(cleanQuery, AIReasoningTaskStage.MEMORY_INTELLIGENCE)) {
            return MemoryRankingResult(orderedMemoryIds = candidateMemories.map { it.id })
        }

        val safeMemoryLines = candidateMemories.take(10).mapNotNull { mem ->
            when (val check = MemoryPrivacyGuard.validateAndSanitizeMemory(mem.memoryKey, mem.value)) {
                is MemoryPrivacyGuard.ValidationResult.Accepted ->
                    "${mem.id}: [${mem.category.name}] ${check.sanitizedKey}=${check.sanitizedValue}"
                is MemoryPrivacyGuard.ValidationResult.Rejected -> null
            }
        }
        if (safeMemoryLines.isEmpty()) {
            return MemoryRankingResult(orderedMemoryIds = emptyList())
        }

        val prompt = buildString {
            appendLine("User query: $cleanQuery")
            appendLine("Sanitized local memories:")
            safeMemoryLines.forEach { appendLine("- $it") }
            appendLine("""Reply strictly with JSON: {"relevant_ids": ["id1", "id2"], "summary_hint": "short context hint"}""")
        }

        val response = modelRouter.reason(
            request = AIReasoningRequest(
                taskStage = AIReasoningTaskStage.MEMORY_INTELLIGENCE,
                userPrompt = prompt,
                systemInstruction = "Rank only the provided memory IDs by relevance to the user query. Never invent new facts or IDs.",
                expectJsonOutput = true,
                temperature = 0.0,
                maxOutputTokens = 300
            )
        )
        val usage = buildUsageRecord(response)
        if (!response.success) {
            return MemoryRankingResult(orderedMemoryIds = candidateMemories.map { it.id }, modelUsage = usage)
        }

        val validIds = candidateMemories.map { it.id }.toSet()
        val jsonObj = extractJsonObjectFromText(response.text)
        val ranked = jsonObj?.get("relevant_ids")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it in validIds }
            ?.distinct()
            .orEmpty()
        val hint = jsonObj?.get("summary_hint")?.jsonPrimitive?.contentOrNull.orEmpty().take(180)
        return MemoryRankingResult(
            orderedMemoryIds = if (ranked.isNotEmpty()) ranked else candidateMemories.map { it.id },
            summaryHint = hint,
            modelUsage = usage
        )
    }

    /**
     * Assists naming & describing a learned reusable skill proposal (still requires explicit user approval).
     */
    suspend fun assistSkillProposal(
        taskGoal: String,
        toolNamesUsed: List<String>
    ): SkillSuggestionAssistResult {
        val cleanGoal = sanitizeForCloudPrompt(taskGoal, 160)
        val defaultName = cleanGoal.take(40).ifBlank { "Custom Workflow" }
        val defaultDesc = "Reusable workflow using ${toolNamesUsed.distinct().joinToString(" -> ")}"
        if (!modelRouter.shouldUseCloudReasoning(cleanGoal, AIReasoningTaskStage.SKILL_INTELLIGENCE)) {
            return SkillSuggestionAssistResult(defaultName, defaultDesc)
        }

        val prompt = buildString {
            appendLine("Completed task goal: $cleanGoal")
            appendLine("Verified tools used: ${toolNamesUsed.joinToString(" -> ")}")
            appendLine("""Reply strictly with JSON: {"name": "Short 2-5 word title", "description": "One-sentence safe summary"}""")
        }

        val response = modelRouter.reason(
            request = AIReasoningRequest(
                taskStage = AIReasoningTaskStage.SKILL_INTELLIGENCE,
                userPrompt = prompt,
                systemInstruction = "Propose a clear, non-sensitive skill title and description for user approval.",
                expectJsonOutput = true,
                temperature = 0.2,
                maxOutputTokens = 200
            )
        )
        val usage = buildUsageRecord(response)
        if (!response.success) {
            return SkillSuggestionAssistResult(defaultName, defaultDesc, usage)
        }

        val jsonObj = extractJsonObjectFromText(response.text)
        val name = MemoryPrivacyGuard.sanitizeSkillText(
            jsonObj?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            maxLen = 48
        ).ifBlank { defaultName }
        val desc = MemoryPrivacyGuard.sanitizeSkillText(
            jsonObj?.get("description")?.jsonPrimitive?.contentOrNull.orEmpty(),
            maxLen = 160
        ).ifBlank { defaultDesc }
        return SkillSuggestionAssistResult(name, desc, usage)
    }

    /**
     * Parses both native Gemma 4 function calls AND constrained JSON fallback plans,
     * validating EVERY proposed tool call through [AgentToolRegistry] and [AgentSafetyManager].
     */
    internal fun parseAndValidatePlanResponse(
        response: AIReasoningResponse,
        usage: ModelUsageRecord
    ): PlannedGoalResult {
        val rawProposals = mutableListOf<RawSubtaskProposal>()
        var planSummary = ""
        var directAnswer: String? = null

        // 1. Native functionCall parts (Gemma 4 / Gemini REST)
        if (response.proposedToolCalls.isNotEmpty()) {
            response.proposedToolCalls.forEachIndexed { idx, call ->
                rawProposals.add(
                    RawSubtaskProposal(
                        title = call.arguments["reason"]?.takeIf { it.isNotBlank() }
                            ?: "${call.toolName} (step ${idx + 1})",
                        toolName = call.toolName,
                        parameters = call.arguments,
                        expectedOutcome = "${call.toolName} completed"
                    )
                )
            }
            if (response.text.isNotBlank()) {
                planSummary = response.text.take(240)
            }
        } else if (response.text.isNotBlank()) {
            // 2. Structured JSON output fallback (also used for Gemma 3 27B or text-mode structured replies)
            val parsedObj = extractJsonObjectFromText(response.text)
            if (parsedObj != null) {
                planSummary = parsedObj["plan_summary"]?.jsonPrimitive?.contentOrNull
                    ?: parsedObj["explanation"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                directAnswer = parsedObj["direct_answer"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                val subtasksArr = parsedObj["subtasks"] as? JsonArray
                    ?: parsedObj["tool_calls"] as? JsonArray
                subtasksArr?.forEach { elem ->
                    val obj = elem as? JsonObject ?: return@forEach
                    val tName = obj["tool_name"]?.jsonPrimitive?.contentOrNull
                        ?: obj["name"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: tName
                    val expected = obj["expected_outcome"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val paramsMap = LinkedHashMap<String, String>()
                    (obj["parameters"] as? JsonObject ?: obj["args"] as? JsonObject)?.forEach { (k, v) ->
                        paramsMap[k] = when (v) {
                            is JsonPrimitive -> v.contentOrNull ?: v.toString()
                            else -> v.toString()
                        }
                    }
                    rawProposals.add(RawSubtaskProposal(title, tName, paramsMap, expected))
                }
            } else {
                // Pure conversational/analytical answer with no device action needed
                directAnswer = response.text.trim()
                planSummary = "Direct reasoning answer"
            }
        }

        // 3. Strictly validate every proposed tool call against AgentToolRegistry + AgentSafetyManager
        val validatedSubtasks = mutableListOf<TaskSubtask>()
        var rejectedCount = 0
        var blockedCount = 0
        var prevId: String? = null

        for (proposal in rawProposals.take(MAX_PLANNED_SUBTASKS)) {
            val (registeredTool, validation) = toolRegistry.validateCall(proposal.toolName, proposal.parameters)
            if (!validation.isValid || registeredTool == null) {
                rejectedCount++
                continue
            }
            // Disallow planning meta-tools inside executable DAG subtasks
            if (registeredTool.actionType == AgentActionType.CREATE_PLAN ||
                registeredTool.actionType == AgentActionType.UPDATE_PLAN ||
                registeredTool.actionType == AgentActionType.COMPLETE_TASK
            ) {
                rejectedCount++
                continue
            }

            val req = AgentActionRequest(
                actionType = registeredTool.actionType,
                parameters = proposal.parameters,
                reason = proposal.title
            )
            val safety = AgentSafetyManager.evaluate(req)
            if (safety.isBlocked) {
                blockedCount++
                continue
            }

            val subId = "gemma_sub_${validatedSubtasks.size + 1}"
            validatedSubtasks.add(
                TaskSubtask(
                    subtaskId = subId,
                    title = MemoryPrivacyGuard.sanitizeSkillText(proposal.title, 90)
                        .ifBlank { registeredTool.actionType.displayLabelEn },
                    description = "Planned by ${usage.modelDisplayName}",
                    toolName = registeredTool.name,
                    parameters = proposal.parameters,
                    dependsOn = listOfNotNull(prevId),
                    expectedOutcome = MemoryPrivacyGuard.sanitizeSkillText(proposal.expectedOutcome, 120)
                        .ifBlank { "${registeredTool.actionType.displayLabelEn} verified" },
                    isSensitive = safety.requiresConfirmation
                )
            )
            prevId = subId
        }

        return PlannedGoalResult(
            usedCloudModel = true,
            subtasks = validatedSubtasks,
            planSummary = planSummary.ifBlank {
                if (validatedSubtasks.isNotEmpty()) {
                    "Planned ${validatedSubtasks.size} validated step(s) via ${usage.modelDisplayName}"
                } else if (!directAnswer.isNullOrBlank()) {
                    "Answered via ${usage.modelDisplayName}"
                } else {
                    "No valid executable tools proposed"
                }
            },
            directAnswer = directAnswer,
            rejectedToolProposalsCount = rejectedCount,
            blockedBySafetyCount = blockedCount,
            modelUsage = usage
        )
    }

    private fun buildPlanningPrompt(
        cleanGoal: String,
        memories: List<AgentMemoryRecord>,
        skills: List<AgentSkill>,
        allowedToolNames: List<String>
    ): String = buildString {
        appendLine("User goal: $cleanGoal")
        if (memories.isNotEmpty()) {
            appendLine("Sanitized local user preferences:")
            memories.take(4).forEach { m ->
                appendLine("- ${m.memoryKey}: ${m.value}")
            }
        }
        val approvedSkills = skills.filter { it.enabled && it.userApproved }.take(4)
        if (approvedSkills.isNotEmpty()) {
            appendLine("Available user-approved skills:")
            approvedSkills.forEach { s ->
                appendLine("- ${s.name}: ${s.executionSteps.joinToString(" -> ") { it.toolName }}")
            }
        }
        appendLine("Allowed registered device tools ONLY: ${allowedToolNames.joinToString(", ")}")
        appendLine("Rules:")
        appendLine("1. If the user asks a general question/explanation requiring NO device action, respond with JSON: {\"plan_summary\": \"...\", \"direct_answer\": \"concise answer in user language\", \"subtasks\": []}.")
        appendLine("2. If device actions are needed, either invoke native function calls from the allowed tools OR return strict JSON: {\"plan_summary\": \"...\", \"subtasks\": [{\"title\": \"...\", \"tool_name\": \"<exact allowed tool>\", \"parameters\": {...}, \"expected_outcome\": \"...\"}]}.")
        appendLine("3. NEVER invent tool names or perform payments, deletions, or password/OTP entry.")
    }

    private fun buildUsageRecord(response: AIReasoningResponse): ModelUsageRecord {
        val desc = VerifiedReasoningModels.findByIdOrCustom(response.modelId)
        return ModelUsageRecord(
            modelId = response.modelId,
            modelDisplayName = desc.displayName,
            providerId = response.providerId,
            taskStage = response.taskStage,
            succeeded = response.success,
            usedFallback = response.usedFallback,
            fallbackNote = response.fallbackReason,
            promptTokens = response.tokenUsage.promptTokens,
            outputTokens = response.tokenUsage.candidatesTokens,
            latencyMs = response.latencyMs
        )
    }

    private data class RawSubtaskProposal(
        val title: String,
        val toolName: String,
        val parameters: Map<String, String>,
        val expectedOutcome: String
    )

    companion object {
        private const val MAX_PLANNED_SUBTASKS = 8

        private const val PLANNING_SYSTEM_INSTRUCTION =
            "You are ScreenSight AI's reasoning and planning engine. Decompose user goals into minimal, safe, ordered steps using ONLY the declared Android tools, or provide a clear concise answer when no device tool is needed. Never invent tools, never bypass confirmation for calls/messages, and never attempt financial, deletion, or credential actions."

        private const val RECOVERY_SYSTEM_INSTRUCTION =
            "You are ScreenSight AI's error-recovery planner. Propose a safe alternative step sequence using ONLY the allowed tools without repeating already-failed or already-completed actions."

        fun sanitizeForCloudPrompt(raw: String, maxLen: Int): String {
            val redacted = AgentSafetyManager.redactSensitiveContent(
                AgentTaskLogger.sanitizeForLog(raw)
            )
            return MemoryPrivacyGuard.sanitizeSkillText(redacted, maxLen = maxLen)
        }

        fun actionSignature(toolName: String, params: Map<String, String>): String {
            val sortedParams = params.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value.trim().lowercase()}" }
            return "${toolName.trim().lowercase()}|$sortedParams"
        }

        /**
         * Safely extracts a JSON object from either clean JSON or a markdown ```json ... ``` fenced block.
         */
        fun extractJsonObjectFromText(raw: String): JsonObject? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val candidate = when {
                trimmed.startsWith("{") && trimmed.endsWith("}") -> trimmed
                trimmed.contains("```") -> {
                    val afterFence = trimmed.substringAfter("```json", trimmed.substringAfter("```", ""))
                    afterFence.substringBefore("```").trim()
                }
                else -> {
                    val start = trimmed.indexOf('{')
                    val end = trimmed.lastIndexOf('}')
                    if (start in 0 until end) trimmed.substring(start, end + 1) else return null
                }
            }
            return runCatching {
                GeminiLiveJson.json.parseToJsonElement(candidate).jsonObject
            }.getOrNull()
        }
    }
}
