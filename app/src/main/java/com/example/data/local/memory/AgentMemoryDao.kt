package com.example.data.local.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMemoryDao {

    @Query("SELECT * FROM agent_memories ORDER BY userApproved DESC, updatedAtMillis DESC")
    fun observeAllMemories(): Flow<List<AgentMemoryEntity>>

    @Query("SELECT * FROM agent_memories ORDER BY updatedAtMillis DESC")
    suspend fun getAllMemories(): List<AgentMemoryEntity>

    @Query(
        "SELECT * FROM agent_memories " +
            "WHERE (expiresAtMillis IS NULL OR expiresAtMillis > :nowMillis) " +
            "ORDER BY userApproved DESC, confidence DESC, lastUsedAtMillis DESC"
    )
    suspend fun getActiveMemories(nowMillis: Long): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AgentMemoryEntity?

    @Query(
        "SELECT * FROM agent_memories " +
            "WHERE category = :category AND normalizedKey = :normalizedKey " +
            "ORDER BY updatedAtMillis DESC"
    )
    suspend fun findByCategoryAndNormalizedKey(
        category: String,
        normalizedKey: String
    ): List<AgentMemoryEntity>

    @Query(
        "SELECT * FROM agent_memories " +
            "WHERE normalizedKey = :normalizedKey " +
            "ORDER BY updatedAtMillis DESC"
    )
    suspend fun findByNormalizedKey(normalizedKey: String): List<AgentMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentMemoryEntity)

    @Query("UPDATE agent_memories SET lastUsedAtMillis = :usedAtMillis WHERE id IN (:ids)")
    suspend fun markUsed(ids: List<String>, usedAtMillis: Long)

    @Query("DELETE FROM agent_memories WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM agent_memories WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>): Int

    @Query("DELETE FROM agent_memories WHERE expiresAtMillis IS NOT NULL AND expiresAtMillis <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int

    @Query("DELETE FROM agent_memories")
    suspend fun deleteAll(): Int
}
