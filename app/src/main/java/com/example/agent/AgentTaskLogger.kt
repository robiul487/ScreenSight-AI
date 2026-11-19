package com.example.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Privacy-Safe Task Execution Logger (Phase 2, Section 11).
 *
 * Records structured execution telemetry per task:
 * - Task ID & sanitized user request
 * - Generated / updated plan summary
 * - State machine transitions
 * - Tool calls & results (with secrets/OTPs/passwords/private bodies scrubbed)
 * - Retry events, errors, and final completion status
 *
 * Never stores API keys, passwords, OTPs, PINs, card numbers, or private message content.
 */
class AgentTaskLogger {

    enum class LogEventType {
        TASK_STARTED,
        STATE_TRANSITION,
        PLAN_CREATED,
        PLAN_UPDATED,
        TOOL_CALL,
        TOOL_RESULT,
        OBSERVATION,
        RETRY,
        CONFIRMATION,
        ERROR,
        TASK_FINISHED
    }

    data class TaskLogEntry(
        val id: String = UUID.randomUUID().toString(),
        val taskId: String,
        val timestampMillis: Long = System.currentTimeMillis(),
        val type: LogEventType,
        val message: String,
        val metadata: Map<String, String> = emptyMap()
    )

    data class TaskExecutionRecord(
        val taskId: String,
        val userRequest: String,
        val startedAtMillis: Long = System.currentTimeMillis(),
        val completedAtMillis: Long? = null,
        val finalState: AgentExecutionState = AgentExecutionState.UNDERSTANDING,
        val planSummary: String = "",
        val totalToolCalls: Int = 0,
        val totalRetries: Int = 0,
        val lastError: String? = null,
        val entries: List<TaskLogEntry> = emptyList()
    )

    private val _records = MutableStateFlow<List<TaskExecutionRecord>>(emptyList())
    val records: StateFlow<List<TaskExecutionRecord>> = _records.asStateFlow()

    private val _recentEntries = MutableStateFlow<List<TaskLogEntry>>(emptyList())
    val recentEntries: StateFlow<List<TaskLogEntry>> = _recentEntries.asStateFlow()

    fun startTask(taskId: String, userRequest: String) {
        val safeRequest = sanitizeForLog(userRequest)
        val entry = TaskLogEntry(
            taskId = taskId,
            type = LogEventType.TASK_STARTED,
            message = "Task started: ${safeRequest.ifBlank { "Voice instruction" }}"
        )
        val newRecord = TaskExecutionRecord(
            taskId = taskId,
            userRequest = safeRequest,
            entries = listOf(entry)
        )
        _records.update { current ->
            (current.filterNot { it.taskId == taskId } + newRecord).takeLast(MAX_RECORDS)
        }
        appendRecentEntry(entry)
    }

    fun logStateTransition(taskId: String, from: AgentExecutionState, to: AgentExecutionState, reason: String = "") {
        val suffix = if (reason.isNotBlank()) " (${sanitizeForLog(reason)})" else ""
        val entry = TaskLogEntry(
            taskId = taskId,
            type = LogEventType.STATE_TRANSITION,
            message = "${from.name} → ${to.name}$suffix",
            metadata = mapOf("from" to from.name, "to" to to.name)
        )
        updateRecord(taskId) { rec ->
            rec.copy(
                finalState = to,
                completedAtMillis = if (!to.isActive && to != AgentExecutionState.IDLE) System.currentTimeMillis() else rec.completedAtMillis,
                entries = (rec.entries + entry).takeLast(MAX_ENTRIES_PER_TASK)
            )
        }
        appendRecentEntry(entry)
    }

    fun logPlan(taskId: String, plan: AgentTaskPlan, isUpdate: Boolean = false) {
        val stepSummary = plan.steps.joinToString(" → ") { "${it.stepIndex + 1}.${it.toolName}" }
        val safeDesc = sanitizeForLog(plan.taskDescription)
        val entry = TaskLogEntry(
            taskId = taskId,
            type = if (isUpdate) LogEventType.PLAN_UPDATED else LogEventType.PLAN_CREATED,
            message = "${if (isUpdate) "Plan updated" else "Plan created"}: $safeDesc [$stepSummary]",
            metadata = mapOf("steps" to plan.steps.size.toString())
        )
        updateRecord(taskId) { rec ->
            rec.copy(
                planSummary = "$safeDesc (${plan.steps.size} steps)",
                entries = (rec.entries + entry).takeLast(MAX_ENTRIES_PER_TASK)
            )
        }
        appendRecentEntry(entry)
    }

    fun logToolCall(taskId: String, toolName: String, params: Map<String, String>) {
        val safeParams = sanitizeParams(params)
        val entry = TaskLogEntry(
            taskId = taskId,
            type = LogEventType.TOOL_CALL,
            message = "Tool call: $toolName(${safeParams.entries.joinToString(", ") { "${it.key}=${it.value}" }})",
            metadata = mapOf("tool" to toolName)
        )
        updateRecord(taskId) { rec ->
            rec.copy(
                totalToolCalls = rec.totalToolCalls + 1,
                entries = (rec.entries + entry).takeLast(MAX_ENTRIES_PER_TASK)
            )
        }
        appendRecentEntry(entry)
    }

    fun logToolResult(taskId: String, toolName: String, result: AgentActionResult) {
        val statusTag = if (result.success) "OK" else "FAIL"
        val entry = TaskLogEntry(
            taskId = taskId,
            type = LogEventType.TOOL_RESULT,
            message = "Result [$statusTag] $toolName (${result.status}): ${sanitizeForLog(result.summaryEn)}",
            metadata = mapOf("tool" to toolName, "status" to result.status, "success" to result.success.toString())
        )
        updateRecord(taskId) { rec ->
            rec.copy(
                lastError = if (!result.success) sanitizeForLog(result.summaryEn) else rec.lastError,
                entries = (rec.entries + entry).takeLast(MAX_ENTRIES_PER_TASK)
            )
        }
        appendRecentEntry(entry)
    }

    fun logRetry(taskId: String, toolName: String, attempt: Int, maxRetries: Int, reason: String) {
        val entry = TaskLogEntry(
            taskId = taskId,
            type = LogEventType.RETRY,
            message = "Retry $attempt/$maxRetries for $toolName: ${sanitizeForLog(reason)}",
            metadata = mapOf("tool" to toolName, "attempt" to attempt.toString())
        )
        updateRecord(taskId) { rec ->
            rec.copy(
                totalRetries = rec.totalRetries + 1,
                entries = (rec.entries + entry).takeLast(MAX_ENTRIES_PER_TASK)
            )
        }
        appendRecentEntry(entry)
    }

    fun logError(taskId: String, error: String) {
        val safeErr = sanitizeForLog(error)
        val entry = TaskLogEntry(
            taskId = taskId,
            type = LogEventType.ERROR,
            message = "Error: $safeErr"
        )
        updateRecord(taskId) { rec ->
            rec.copy(
                lastError = safeErr,
                entries = (rec.entries + entry).takeLast(MAX_ENTRIES_PER_TASK)
            )
        }
        appendRecentEntry(entry)
    }

    fun logFinished(taskId: String, finalState: AgentExecutionState, summary: String) {
        val entry = TaskLogEntry(
            taskId = taskId,
            type = LogEventType.TASK_FINISHED,
            message = "Task ${finalState.name}: ${sanitizeForLog(summary)}"
        )
        updateRecord(taskId) { rec ->
            rec.copy(
                finalState = finalState,
                completedAtMillis = System.currentTimeMillis(),
                entries = (rec.entries + entry).takeLast(MAX_ENTRIES_PER_TASK)
            )
        }
        appendRecentEntry(entry)
    }

    fun clearLogs() {
        _records.value = emptyList()
        _recentEntries.value = emptyList()
    }

    private fun updateRecord(taskId: String, transform: (TaskExecutionRecord) -> TaskExecutionRecord) {
        _records.update { list ->
            val exists = list.any { it.taskId == taskId }
            if (!exists) {
                (list + transform(TaskExecutionRecord(taskId = taskId, userRequest = "Agent task"))).takeLast(MAX_RECORDS)
            } else {
                list.map { if (it.taskId == taskId) transform(it) else it }
            }
        }
    }

    private fun appendRecentEntry(entry: TaskLogEntry) {
        _recentEntries.update { list -> (list + entry).takeLast(MAX_RECENT_ENTRIES) }
    }

    companion object {
        private const val MAX_RECORDS = 20
        private const val MAX_ENTRIES_PER_TASK = 60
        private const val MAX_RECENT_ENTRIES = 80
        private val SENSITIVE_PARAM_KEYS = setOf(
            "message_text", "password", "pin", "otp", "api_key", "token", "secret", "cvv"
        )
        private val API_KEY_PATTERN = Regex("AIza[0-9A-Za-z\\-_]{10,}")
        private val INLINE_SECRET_PATTERN = Regex(
            "(?i)\\b(password|passcode|pin|token|secret|api[_-]?key|cvv)\\s*[:=]\\s*[^\\s,;]+"
        )

        fun sanitizeForLog(raw: String): String {
            var clean = AgentSafetyManager.redactSensitiveContent(raw.trim())
            clean = API_KEY_PATTERN.replace(clean, "[REDACTED_API_KEY]")
            clean = INLINE_SECRET_PATTERN.replace(clean) { match ->
                "${match.groupValues[1]}=[REDACTED]"
            }
            return if (clean.length > 180) clean.take(180) + "..." else clean
        }

        fun sanitizeParams(params: Map<String, String>): Map<String, String> {
            return params.mapValues { (key, value) ->
                val lowerKey = key.lowercase()
                if (SENSITIVE_PARAM_KEYS.any { lowerKey.contains(it) }) {
                    "[REDACTED]"
                } else {
                    sanitizeForLog(value)
                }
            }
        }
    }
}
