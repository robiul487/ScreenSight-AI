package com.example.data.local.orchestration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LongTermTaskDao {
    @Query("SELECT * FROM agent_long_term_tasks ORDER BY updatedAtMillis DESC")
    fun observeAllTasks(): Flow<List<LongTermTaskEntity>>

    @Query("SELECT * FROM agent_long_term_tasks ORDER BY updatedAtMillis DESC")
    suspend fun getAllTasks(): List<LongTermTaskEntity>

    @Query("SELECT * FROM agent_long_term_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: String): LongTermTaskEntity?

    @Query(
        "SELECT * FROM agent_long_term_tasks WHERE status IN ('PENDING', 'RUNNING', 'PAUSED') ORDER BY updatedAtMillis DESC"
    )
    suspend fun getUnfinishedTasks(): List<LongTermTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTask(entity: LongTermTaskEntity)

    @Query("DELETE FROM agent_long_term_tasks WHERE taskId = :taskId")
    suspend fun deleteTask(taskId: String)

    @Query("DELETE FROM agent_long_term_tasks")
    suspend fun clearAllTasks(): Int
}
