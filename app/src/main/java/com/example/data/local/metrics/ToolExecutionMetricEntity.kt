package com.example.data.local.metrics

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Strictly local Room entity recording sanitized tool execution outcomes for
 * Phase 5 Dynamic Tool Discovery history and Self-Reflection reliability tracking.
 * Never stores secrets, passwords, OTPs, or private message content.
 */
@Entity(
    tableName = "agent_tool_metrics",
    indices = [
        Index(value = ["toolName"], name = "idx_tool_metrics_name"),
        Index(value = ["timestampMillis"], name = "idx_tool_metrics_ts")
    ]
)
data class ToolExecutionMetricEntity(
    @PrimaryKey
    val id: String,
    val taskId: String,
    val toolName: String,
    val succeeded: Boolean,
    val blockedBySafety: Boolean,
    val retryCount: Int,
    val durationMs: Long,
    val errorCode: String?,
    val sanitizedSummary: String,
    val timestampMillis: Long
)
