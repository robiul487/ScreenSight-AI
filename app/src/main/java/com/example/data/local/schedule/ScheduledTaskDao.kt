package com.example.data.local.schedule

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM agent_scheduled_tasks ORDER BY enabled DESC, nextRunAtMillis ASC")
    fun observeAllSchedules(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM agent_scheduled_tasks ORDER BY nextRunAtMillis ASC")
    suspend fun getAllSchedules(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM agent_scheduled_tasks WHERE enabled = 1 AND userApproved = 1 ORDER BY nextRunAtMillis ASC")
    suspend fun getActiveApprovedSchedules(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM agent_scheduled_tasks WHERE scheduleId = :scheduleId LIMIT 1")
    suspend fun getScheduleById(scheduleId: String): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(entity: ScheduledTaskEntity)

    @Query("UPDATE agent_scheduled_tasks SET enabled = :enabled, updatedAtMillis = :updatedAt WHERE scheduleId = :scheduleId")
    suspend fun setEnabled(scheduleId: String, enabled: Boolean, updatedAt: Long)

    @Query(
        "UPDATE agent_scheduled_tasks SET lastRunAtMillis = :runAt, lastRunResult = :result, nextRunAtMillis = :nextRunAt, enabled = :stillEnabled, updatedAtMillis = :runAt WHERE scheduleId = :scheduleId"
    )
    suspend fun recordRunResult(
        scheduleId: String,
        runAt: Long,
        result: String,
        nextRunAt: Long,
        stillEnabled: Boolean
    )

    @Query("DELETE FROM agent_scheduled_tasks WHERE scheduleId = :scheduleId")
    suspend fun deleteSchedule(scheduleId: String)

    @Query("DELETE FROM agent_scheduled_tasks")
    suspend fun clearAllSchedules(): Int
}
