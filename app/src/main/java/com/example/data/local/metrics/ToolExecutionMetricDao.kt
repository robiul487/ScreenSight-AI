package com.example.data.local.metrics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolExecutionMetricDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetric(metric: ToolExecutionMetricEntity)

    @Query("SELECT * FROM agent_tool_metrics ORDER BY timestampMillis DESC LIMIT :limit")
    fun observeRecentMetrics(limit: Int = 100): Flow<List<ToolExecutionMetricEntity>>

    @Query("SELECT * FROM agent_tool_metrics ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun getRecentMetrics(limit: Int = 200): List<ToolExecutionMetricEntity>

    @Query("SELECT * FROM agent_tool_metrics WHERE toolName = :toolName ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun getMetricsForTool(toolName: String, limit: Int = 50): List<ToolExecutionMetricEntity>

    @Query("DELETE FROM agent_tool_metrics WHERE timestampMillis < :cutoffMillis")
    suspend fun pruneOlderThan(cutoffMillis: Long): Int

    @Query("DELETE FROM agent_tool_metrics")
    suspend fun clearAll(): Int
}
