package com.example.data.local.checkpoint

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for strictly local, offline-capable autonomous task checkpoints.
 */
@Dao
interface AgentTaskCheckpointDao {

    @Query("SELECT * FROM agent_task_checkpoints ORDER BY updatedAtMillis DESC LIMIT :limit")
    fun observeRecentCheckpoints(limit: Int = 30): Flow<List<AgentTaskCheckpointEntity>>

    @Query(
        """
        SELECT * FROM agent_task_checkpoints
        WHERE stateName NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'IDLE')
        ORDER BY updatedAtMillis DESC
        """
    )
    fun observeIncompleteCheckpoints(): Flow<List<AgentTaskCheckpointEntity>>

    @Query(
        """
        SELECT * FROM agent_task_checkpoints
        WHERE stateName NOT IN ('COMPLETED', 'CANCELLED', 'FAILED', 'IDLE')
        ORDER BY updatedAtMillis DESC
        """
    )
    suspend fun getIncompleteCheckpoints(): List<AgentTaskCheckpointEntity>

    @Query("SELECT * FROM agent_task_checkpoints WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): AgentTaskCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: AgentTaskCheckpointEntity)

    @Query("UPDATE agent_task_checkpoints SET stateName = :stateName, sanitizedLastError = :reason, updatedAtMillis = :updatedAt WHERE taskId = :taskId")
    suspend fun updateState(taskId: String, stateName: String, reason: String?, updatedAt: Long)

    @Query("DELETE FROM agent_task_checkpoints WHERE taskId = :taskId")
    suspend fun deleteById(taskId: String)

    @Query("DELETE FROM agent_task_checkpoints")
    suspend fun deleteAll()
}
