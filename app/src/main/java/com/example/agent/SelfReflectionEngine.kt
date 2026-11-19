package com.example.agent

import com.example.data.local.metrics.ToolExecutionMetricDao
import com.example.data.local.metrics.ToolExecutionMetricEntity
import com.example.data.local.skill.AgentSkill
import java.util.UUID

/**
 * Phase 5: Local Self-Reflection & Performance Evaluation Engine.
 *
 * Evaluates completed/failed tasks and persistent local tool execution history to:
 * - Track whether the expected task outcome was achieved
 * - Record failed steps and recovery attempts
 * - Compute per-tool reliability metrics & detect repeated failure patterns
 * - Propose safe, user-reviewable skill/workflow improvements (e.g., adding a wait_for_ui recovery step)
 *
 * STRICT SAFETY INVARIANT:
 * - NEVER modifies safety rules, permissions, or executable code.
 * - All improvement proposals require explicit user review and approval.
 */
class SelfReflectionEngine(
    private val metricDao: ToolExecutionMetricDao? = null
) {

    /**
     * Explicit immutable guardrail confirming self-reflection can never modify safety rules or code.
     */
    val canModifySafetyRulesOrCode: Boolean = false

    data class ToolReliabilitySummary(
        val toolName: String,
        val totalCalls: Int,
        val successCount: Int,
        val failureCount: Int,
        val safetyBlockedCount: Int,
        val averageDurationMs: Long,
        val successRate: Float,
        val topErrorCode: String?,
        val hasRepeatedFailurePattern: Boolean
    )

    data class SkillImprovementProposal(
        val proposalId: String,
        val targetSkillId: String?,
        val titleEn: String,
        val titleBn: String,
        val rationaleEn: String,
        val rationaleBn: String,
        val suggestedRecoveryTool: String,
        val createdAtMillis: Long = System.currentTimeMillis()
    )

    data class TaskReflectionReport(
        val reportId: String,
        val taskId: String,
        val goal: String,
        val outcomeAchieved: Boolean,
        val totalSteps: Int,
        val completedSteps: Int,
        val failedSteps: Int,
        val recoveryAttempts: Int,
        val repeatedFailureTools: List<String>,
        val summaryEn: String,
        val summaryBn: String,
        val improvementProposals: List<SkillImprovementProposal>,
        val createdAtMillis: Long = System.currentTimeMillis()
    )

    /**
     * Evaluates a completed or failed task along with historical tool metrics to generate
     * a structured reflection report and safe user-reviewable improvement proposals.
     */
    suspend fun evaluateTask(
        taskId: String,
        goal: String,
        outcomeAchieved: Boolean,
        steps: List<AgentTaskStep>,
        existingSkills: List<AgentSkill> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): TaskReflectionReport {
        val completedCount = steps.count { it.status == AgentStepStatus.COMPLETED }
        val failedList = steps.filter { it.status == AgentStepStatus.FAILED }
        val totalRetries = steps.sumOf { it.retryCount.coerceAtLeast(0) }

        val reliability = computeToolReliabilityMetrics()
        val repeatedFailureTools = reliability
            .filter { it.hasRepeatedFailurePattern }
            .map { it.toolName }

        val proposals = mutableListOf<SkillImprovementProposal>()

        // Detect repeated failure patterns across history or within this task
        val problemToolNames = (failedList.map { it.actionType.toolName } + repeatedFailureTools).distinct()
        for (toolName in problemToolNames) {
            val metric = reliability.firstOrNull { it.toolName == toolName }
            val matchingSkill = existingSkills.firstOrNull { skill ->
                toolName in skill.allowedTools
            }
            if ((metric != null && metric.hasRepeatedFailurePattern) ||
                failedList.count { it.actionType.toolName == toolName } >= 2
            ) {
                val suggestedRecovery = when (AgentActionType.fromToolName(toolName)) {
                    AgentActionType.CLICK_ELEMENT,
                    AgentActionType.SEARCH_TEXT -> AgentActionType.WAIT_FOR_UI.toolName
                    AgentActionType.OPEN_APP -> AgentActionType.GET_CURRENT_APP.toolName
                    else -> AgentActionType.GET_SCREEN_STATE.toolName
                }
                proposals.add(
                    SkillImprovementProposal(
                        proposalId = "imp_${UUID.randomUUID().toString().take(8)}",
                        targetSkillId = matchingSkill?.skillId,
                        titleEn = "Add UI stabilization before $toolName",
                        titleBn = "$toolName ধাপের আগে স্ক্রিন স্থিতিশীল হওয়ার অপেক্ষা যোগ করুন",
                        rationaleEn = "Repeated failure pattern detected on $toolName (${metric?.failureCount ?: failedList.size} failures). Adding $suggestedRecovery before retry may improve reliability.",
                        rationaleBn = "$toolName টুলে একাধিকবার সমস্যা হয়েছে। পুনরায় চেষ্টার আগে $suggestedRecovery যোগ করলে নির্ভরযোগ্যতা বাড়তে পারে।",
                        suggestedRecoveryTool = suggestedRecovery,
                        createdAtMillis = nowMillis
                    )
                )
            }
        }

        val cleanGoal = MemoryPrivacyGuard.sanitizeSkillText(goal, maxLen = 100).ifBlank { "Agent task" }
        val summaryEn = if (outcomeAchieved) {
            "Task \"$cleanGoal\" succeeded ($completedCount/${steps.size} steps, $totalRetries recovery retries)."
        } else {
            "Task \"$cleanGoal\" failed (${failedList.size} failed steps, $totalRetries recovery retries)."
        }
        val summaryBn = if (outcomeAchieved) {
            "\"$cleanGoal\" কাজটি সফলভাবে সম্পন্ন হয়েছে ($completedCount/${steps.size} ধাপ)।"
        } else {
            "\"$cleanGoal\" কাজটি সম্পূর্ণ করা যায়নি (${failedList.size}টি ধাপ ব্যর্থ)।"
        }

        return TaskReflectionReport(
            reportId = "ref_${UUID.randomUUID().toString().take(10)}",
            taskId = taskId,
            goal = cleanGoal,
            outcomeAchieved = outcomeAchieved,
            totalSteps = steps.size,
            completedSteps = completedCount,
            failedSteps = failedList.size,
            recoveryAttempts = totalRetries,
            repeatedFailureTools = repeatedFailureTools,
            summaryEn = summaryEn,
            summaryBn = summaryBn,
            improvementProposals = proposals,
            createdAtMillis = nowMillis
        )
    }

    /**
     * Computes persistent per-tool reliability metrics from Room and flags repeated failure patterns.
     */
    suspend fun computeToolReliabilityMetrics(limit: Int = 200): List<ToolReliabilitySummary> {
        val rows = metricDao?.getRecentMetrics(limit).orEmpty()
        return summarizeMetrics(rows)
    }

    /**
     * Pure computation helper for unit testing and UI presentation.
     */
    fun summarizeMetrics(rows: List<ToolExecutionMetricEntity>): List<ToolReliabilitySummary> {
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { it.toolName }
            .map { (toolName, entries) ->
                val total = entries.size
                val successes = entries.count { it.succeeded }
                val failures = entries.count { !it.succeeded && !it.blockedBySafety }
                val blocked = entries.count { it.blockedBySafety }
                val avgDur = entries.map { it.durationMs }.average().toLong().coerceAtLeast(0L)
                val effectiveAttempts = (total - blocked).coerceAtLeast(1)
                val rate = (successes.toFloat() / effectiveAttempts.toFloat()).coerceIn(0f, 1f)
                val topErr = entries
                    .filter { !it.succeeded && !it.errorCode.isNullOrBlank() }
                    .groupingBy { it.errorCode!! }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key
                val repeatedPattern = failures >= REPEATED_FAILURE_THRESHOLD && rate < 0.6f

                ToolReliabilitySummary(
                    toolName = toolName,
                    totalCalls = total,
                    successCount = successes,
                    failureCount = failures,
                    safetyBlockedCount = blocked,
                    averageDurationMs = avgDur,
                    successRate = rate,
                    topErrorCode = topErr,
                    hasRepeatedFailurePattern = repeatedPattern
                )
            }
            .sortedByDescending { it.totalCalls }
    }

    companion object {
        private const val REPEATED_FAILURE_THRESHOLD = 3
    }
}
