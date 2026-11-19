package com.example.agent

import com.example.data.local.metrics.ToolExecutionMetricDao
import com.example.data.local.metrics.ToolExecutionMetricEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.UUID

/**
 * Phase 5: Dynamic Tool Discovery & Management Layer.
 *
 * Wraps the existing central [AgentToolRegistry] (preserving its exact 25 default tools) and adds:
 * - Dynamic inspection of tool descriptions, required/optional parameters, permissions, and safety tier
 * - Task-aware dynamic tool selection
 * - Schema validation & real-time availability checks before execution
 * - Duplicate registration prevention for dynamically registered safe tools
 * - Persistent sanitized tool execution history in Room
 * - Strict safety guarantee: NO arbitrary code execution and NO bypassing [AgentSafetyManager]
 */
class DynamicToolManager(
    val toolRegistry: AgentToolRegistry,
    private val metricDao: ToolExecutionMetricDao? = null,
    private val isAccessibilityConnectedProvider: () -> Boolean = {
        AgentAccessibilityService.isConnected.value
    },
    private val hasContactsPermissionProvider: () -> Boolean = { false }
) {

    data class ToolDescriptor(
        val name: String,
        val actionType: AgentActionType,
        val description: String,
        val requiredParams: List<String>,
        val optionalParams: List<String>,
        val requiredPermissions: Set<AgentToolRegistry.RequiredPermission>,
        val safetyClassification: ActionRiskLevel,
        val supportsRetry: Boolean,
        val timeoutMs: Long,
        val isCurrentlyAvailable: Boolean,
        val availabilityNote: String
    )

    data class ToolAvailability(
        val toolName: String,
        val isAvailable: Boolean,
        val missingPermissions: List<AgentToolRegistry.RequiredPermission>,
        val reasonEn: String,
        val reasonBn: String
    )

    sealed class DynamicRegistrationOutcome {
        data class Registered(val toolName: String) : DynamicRegistrationOutcome()
        data class RejectedDuplicate(val toolName: String) : DynamicRegistrationOutcome()
        data class RejectedUnsafe(val reasonEn: String, val reasonBn: String) : DynamicRegistrationOutcome()
    }

    /**
     * Discovers all currently registered tools with live permission availability and safety classification.
     */
    fun discoverTools(): List<ToolDescriptor> {
        return toolRegistry.listRegisteredTools().map { toDescriptor(it) }
    }

    /**
     * Inspects a single registered tool by name (supports alias resolution via [AgentToolRegistry]).
     */
    fun inspectTool(toolName: String): ToolDescriptor? {
        val tool = toolRegistry.getTool(toolName) ?: return null
        return toDescriptor(tool)
    }

    /**
     * Validates a tool call against its declared JSON schema and custom validator.
     */
    fun validateCall(
        toolName: String,
        params: Map<String, String>
    ): Pair<AgentToolRegistry.RegisteredTool?, ToolValidationResult> {
        return toolRegistry.validateCall(toolName, params)
    }

    /**
     * Checks live device permission availability before attempting execution.
     */
    fun checkToolAvailability(toolName: String): ToolAvailability {
        val tool = toolRegistry.getTool(toolName)
            ?: return ToolAvailability(
                toolName = toolName,
                isAvailable = false,
                missingPermissions = emptyList(),
                reasonEn = "Tool \"$toolName\" is not registered.",
                reasonBn = "\"$toolName\" টুলটি নিবন্ধিত নয়।"
            )

        val missing = mutableListOf<AgentToolRegistry.RequiredPermission>()
        if (AgentToolRegistry.RequiredPermission.ACCESSIBILITY_SERVICE in tool.requiredPermissions &&
            !isAccessibilityConnectedProvider()
        ) {
            missing.add(AgentToolRegistry.RequiredPermission.ACCESSIBILITY_SERVICE)
        }

        if (missing.isNotEmpty()) {
            return ToolAvailability(
                toolName = tool.name,
                isAvailable = false,
                missingPermissions = missing,
                reasonEn = "Accessibility Service is currently off; enable it to use ${tool.name}.",
                reasonBn = "${tool.actionType.displayLabelBn} ব্যবহার করতে Accessibility চালু করতে হবে।"
            )
        }

        val optionalNote = if (AgentToolRegistry.RequiredPermission.CONTACTS_OPTIONAL in tool.requiredPermissions &&
            !hasContactsPermissionProvider()
        ) {
            "Available (using local Trusted Contacts; Contacts permission optional)"
        } else {
            "Ready"
        }

        return ToolAvailability(
            toolName = tool.name,
            isAvailable = true,
            missingPermissions = emptyList(),
            reasonEn = optionalNote,
            reasonBn = "ব্যবহারের জন্য প্রস্তুত"
        )
    }

    /**
     * Dynamically selects the most relevant registered tools for a natural-language task goal,
     * ranking by keyword/intent relevance and prioritizing currently available tools.
     */
    fun selectToolsForTask(
        goal: String,
        maxTools: Int = 8,
        includeUnavailable: Boolean = true
    ): List<ToolDescriptor> {
        val cleanGoal = goal.trim().lowercase()
        val all = discoverTools().let { list ->
            if (includeUnavailable) list else list.filter { it.isCurrentlyAvailable }
        }
        if (cleanGoal.isBlank()) return all.take(maxTools)

        val goalTokens = cleanGoal
            .split(Regex("[^a-z0-9\\u0980-\\u09FF]+"))
            .filter { it.length >= 2 }
            .toSet()

        val scored = all.map { desc ->
            var score = 0.0
            val text = "${desc.name} ${desc.description} ${desc.actionType.displayLabelEn} ${desc.actionType.displayLabelBn}".lowercase()

            goalTokens.forEach { token ->
                if (text.contains(token)) score += 2.0
            }

            // Intent-specific boosts for common Bangla & English mobile tasks
            if ((cleanGoal.contains("youtube") || cleanGoal.contains("ইউটিউব") || cleanGoal.contains("open") || cleanGoal.contains("খোল")) &&
                desc.actionType == AgentActionType.OPEN_APP
            ) score += 6.0
            if ((cleanGoal.contains("search") || cleanGoal.contains("খোঁজ") || cleanGoal.contains("find") || cleanGoal.contains("song") || cleanGoal.contains("গান")) &&
                desc.actionType == AgentActionType.SEARCH_TEXT
            ) score += 6.0
            if ((cleanGoal.contains("alarm") || cleanGoal.contains("অ্যালার্ম") || cleanGoal.contains("wake")) &&
                desc.actionType == AgentActionType.SET_ALARM
            ) score += 7.0
            if ((cleanGoal.contains("time") || cleanGoal.contains("date") || cleanGoal.contains("সময়") || cleanGoal.contains("তারিখ") || cleanGoal.contains("কয়টা")) &&
                desc.actionType == AgentActionType.GET_DATE_TIME
            ) score += 7.0
            if ((cleanGoal.contains("volume") || cleanGoal.contains("ভলিউম") || cleanGoal.contains("sound") || cleanGoal.contains("mute")) &&
                desc.actionType == AgentActionType.SET_VOLUME
            ) score += 7.0
            if ((cleanGoal.contains("read") || cleanGoal.contains("screen") || cleanGoal.contains("পড়") || cleanGoal.contains("স্ক্রিন")) &&
                (desc.actionType == AgentActionType.READ_SCREEN || desc.actionType == AgentActionType.GET_SCREEN_STATE)
            ) score += 6.0
            if ((cleanGoal.contains("call") || cleanGoal.contains("কল") || cleanGoal.contains("phone")) &&
                (desc.actionType == AgentActionType.CALL_TRUSTED_CONTACT || desc.actionType == AgentActionType.OPEN_DIALER)
            ) score += 6.0
            if ((cleanGoal.contains("message") || cleanGoal.contains("sms") || cleanGoal.contains("whatsapp") || cleanGoal.contains("মেসেজ")) &&
                desc.actionType == AgentActionType.PREPARE_MESSAGE
            ) score += 6.0
            if ((cleanGoal.contains("web") || cleanGoal.contains("http") || cleanGoal.contains("site") || cleanGoal.contains("link") || cleanGoal.contains("ওয়েব")) &&
                desc.actionType == AgentActionType.OPEN_URL
            ) score += 6.0
            if ((cleanGoal.contains("setting") || cleanGoal.contains("wifi") || cleanGoal.contains("bluetooth") || cleanGoal.contains("সেটিংস")) &&
                desc.actionType == AgentActionType.OPEN_SETTINGS
            ) score += 6.0

            if (desc.isCurrentlyAvailable) score += 0.5
            desc to score
        }

        val matching = scored.filter { it.second > 0.5 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(maxTools)

        return if (matching.isNotEmpty()) {
            matching
        } else {
            // Safe baseline discovery set when goal has generic wording
            all.filter {
                it.actionType in setOf(
                    AgentActionType.GET_SCREEN_STATE,
                    AgentActionType.OPEN_APP,
                    AgentActionType.SEARCH_TEXT,
                    AgentActionType.GET_DATE_TIME,
                    AgentActionType.VERIFY_SCREEN
                )
            }.take(maxTools)
        }
    }

    /**
     * Safely registers an additional typed tool at runtime while enforcing:
     * 1. Duplicate tool registration prevention (rejects if name already exists)
     * 2. No arbitrary code / shell / unsafe name or description
     * 3. Mandatory [AgentSafetyManager] wrapper so no dynamic tool can EVER bypass safety evaluation
     */
    fun registerDynamicSafeTool(tool: AgentToolRegistry.RegisteredTool): DynamicRegistrationOutcome {
        val normalized = tool.name.trim().lowercase()
        if (normalized.isEmpty()) {
            return DynamicRegistrationOutcome.RejectedUnsafe(
                reasonEn = "Dynamic tool name cannot be blank.",
                reasonBn = "টুলের নাম খালি রাখা যাবে না।"
            )
        }

        // Prevent duplicate tool registration
        if (toolRegistry.getTool(normalized) != null) {
            return DynamicRegistrationOutcome.RejectedDuplicate(tool.name)
        }

        // Reject any tool attempting shell/code execution or high-risk bypasses
        val combinedText = "${tool.name} ${tool.description}".lowercase()
        if (FORBIDDEN_DYNAMIC_TOOL_MARKERS.any { combinedText.contains(it) } ||
            MemoryPrivacyGuard.isSensitiveOrHighRiskWorkflowText(combinedText)
        ) {
            return DynamicRegistrationOutcome.RejectedUnsafe(
                reasonEn = "Dynamic tool rejected: arbitrary code execution, shell commands, and high-risk financial/security actions are strictly prohibited.",
                reasonBn = "নিরাপত্তার কারণে অনিরাপদ বা কোড এক্সিকিউশন টুল নিবন্ধন করা যাবে না।"
            )
        }

        // Wrap executor so AgentSafetyManager is ALWAYS invoked before execution
        val originalExecutor = tool.executor
        val safetyGuardedTool = tool.copy(
            executor = { req, confirmed ->
                val safety = AgentSafetyManager.evaluate(req)
                if (safety.isBlocked) {
                    AgentActionResult(
                        success = false,
                        status = "BLOCKED_HIGH_RISK",
                        summaryEn = safety.reasonEn,
                        summaryBn = safety.reasonBn,
                        blockedBySafety = true
                    )
                } else if (safety.requiresConfirmation && !confirmed) {
                    AgentActionResult(
                        success = false,
                        status = "CONFIRMATION_REQUIRED",
                        summaryEn = safety.reasonEn,
                        summaryBn = safety.reasonBn
                    )
                } else if (originalExecutor != null) {
                    originalExecutor(req, confirmed)
                } else {
                    AgentActionResult(
                        success = false,
                        status = "NO_EXECUTOR",
                        summaryEn = "No safe executor bound for ${tool.name}.",
                        summaryBn = "এই টুলের এক্সিকিউটর কনফিগার করা নেই।"
                    )
                }
            }
        )

        toolRegistry.registerTool(safetyGuardedTool)
        return DynamicRegistrationOutcome.Registered(safetyGuardedTool.name)
    }

    /**
     * Persists a sanitized tool execution record into local Room storage.
     */
    suspend fun recordExecution(
        taskId: String,
        toolName: String,
        succeeded: Boolean,
        blockedBySafety: Boolean,
        retryCount: Int,
        durationMs: Long,
        errorCode: String?,
        summary: String,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        val dao = metricDao ?: return
        val entity = ToolExecutionMetricEntity(
            id = "tm_${UUID.randomUUID().toString().take(12)}",
            taskId = taskId.ifBlank { "direct" },
            toolName = toolName.trim().lowercase(),
            succeeded = succeeded,
            blockedBySafety = blockedBySafety,
            retryCount = retryCount.coerceAtLeast(0),
            durationMs = durationMs.coerceAtLeast(0L),
            errorCode = errorCode?.take(48),
            sanitizedSummary = AgentTaskLogger.sanitizeForLog(summary).take(180),
            timestampMillis = timestampMillis
        )
        dao.insertMetric(entity)
    }

    fun observeExecutionHistory(limit: Int = 80): Flow<List<ToolExecutionMetricEntity>> {
        return metricDao?.observeRecentMetrics(limit) ?: flowOf(emptyList())
    }

    suspend fun getExecutionHistory(limit: Int = 100): List<ToolExecutionMetricEntity> {
        return metricDao?.getRecentMetrics(limit).orEmpty()
    }

    private fun toDescriptor(tool: AgentToolRegistry.RegisteredTool): ToolDescriptor {
        val required = tool.parametersSchema.required.orEmpty()
        val allProps = tool.parametersSchema.properties?.keys.orEmpty()
        val optional = allProps.filterNot { it in required }
        val availability = checkToolAvailability(tool.name)
        val safety = classifyDefaultToolRisk(tool.actionType)

        return ToolDescriptor(
            name = tool.name,
            actionType = tool.actionType,
            description = tool.description,
            requiredParams = required,
            optionalParams = optional,
            requiredPermissions = tool.requiredPermissions,
            safetyClassification = safety,
            supportsRetry = tool.supportsRetry,
            timeoutMs = tool.timeoutMs,
            isCurrentlyAvailable = availability.isAvailable,
            availabilityNote = availability.reasonEn
        )
    }

    private fun classifyDefaultToolRisk(actionType: AgentActionType): ActionRiskLevel {
        return when (actionType) {
            AgentActionType.CALL_TRUSTED_CONTACT,
            AgentActionType.OPEN_DIALER,
            AgentActionType.PREPARE_MESSAGE,
            AgentActionType.ASK_CONFIRMATION -> ActionRiskLevel.SENSITIVE
            else -> ActionRiskLevel.LOW_RISK
        }
    }

    companion object {
        private val FORBIDDEN_DYNAMIC_TOOL_MARKERS = listOf(
            "exec_shell", "shell", "runtime.exec", "eval", "dexclassloader",
            "load_dex", "root", "su ", "bypass_safety", "disable_safety",
            "auto_pay", "send_money", "extract_otp", "read_password"
        )
    }
}
