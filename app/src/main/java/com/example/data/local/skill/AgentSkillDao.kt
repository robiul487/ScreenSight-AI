package com.example.data.local.skill

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentSkillDao {

    @Query("SELECT * FROM agent_skills ORDER BY enabled DESC, usageCount DESC, updatedAtMillis DESC")
    fun observeAllSkills(): Flow<List<AgentSkillEntity>>

    @Query("SELECT * FROM agent_skills ORDER BY enabled DESC, usageCount DESC, updatedAtMillis DESC")
    suspend fun getAllSkills(): List<AgentSkillEntity>

    @Query("SELECT * FROM agent_skills WHERE enabled = 1 AND userApproved = 1 ORDER BY usageCount DESC, updatedAtMillis DESC")
    suspend fun getEnabledApprovedSkills(): List<AgentSkillEntity>

    @Query("SELECT * FROM agent_skills WHERE skillId = :skillId LIMIT 1")
    suspend fun getById(skillId: String): AgentSkillEntity?

    @Query("SELECT COUNT(*) FROM agent_skills")
    suspend fun countSkills(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentSkillEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entities: List<AgentSkillEntity>)

    @Query("UPDATE agent_skills SET enabled = :enabled, updatedAtMillis = :updatedAtMillis WHERE skillId = :skillId")
    suspend fun setEnabled(skillId: String, enabled: Boolean, updatedAtMillis: Long): Int

    @Query("DELETE FROM agent_skills WHERE skillId = :skillId")
    suspend fun deleteById(skillId: String): Int

    @Query("DELETE FROM agent_skills")
    suspend fun deleteAll(): Int
}
