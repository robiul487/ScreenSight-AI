package com.example.data.local.skill

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Safety tier for a reusable local skill.
 * Skills NEVER bypass AgentSafetyManager: every step is still evaluated at runtime.
 */
enum class SkillSafetyClassification(
    val labelEn: String,
    val labelBn: String
) {
    LOW_RISK("Safe (Low Risk)", "নিরাপদ"),
    SENSITIVE_CONFIRMATION("Needs Confirmation", "অনুমতি প্রয়োজন");

    companion object {
        fun fromStored(raw: String): SkillSafetyClassification {
            return entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                ?: LOW_RISK
        }
    }
}

@Serializable
data class SkillExecutionStep(
    val stepIndex: Int,
    val description: String,
    val toolName: String,
    val parameterTemplates: Map<String, String> = emptyMap(),
    val expectedOutcome: String = "",
    val condition: String = "",
    val verificationCheck: String = "",
    val recoveryToolName: String? = null,
    val recoveryParameters: Map<String, String> = emptyMap()
)

@Serializable
data class SkillUsageRecord(
    val timestampMillis: Long,
    val succeeded: Boolean,
    val summary: String
)

/**
 * Room entity storing a reusable, locally-managed ScreenSight AI skill.
 */
@Entity(
    tableName = "agent_skills",
    indices = [
        Index(value = ["enabled"], name = "idx_agent_skills_enabled"),
        Index(value = ["name"], name = "idx_agent_skills_name")
    ]
)
data class AgentSkillEntity(
    @PrimaryKey
    val skillId: String,
    val name: String,
    val description: String,
    val triggerPhrasesJson: String,
    val requiredParametersJson: String,
    val executionStepsJson: String,
    val allowedToolsJson: String,
    val safetyClassification: String,
    val successCriteria: String,
    val version: Int,
    val usageCount: Int,
    val successCount: Int,
    val usageHistoryJson: String,
    val lastUsedAtMillis: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val enabled: Boolean,
    val userApproved: Boolean,
    val isBuiltIn: Boolean
)

/**
 * Decoded domain model for a reusable local skill.
 */
data class AgentSkill(
    val skillId: String,
    val name: String,
    val description: String,
    val triggerPhrases: List<String>,
    val requiredParameters: List<String>,
    val executionSteps: List<SkillExecutionStep>,
    val allowedTools: List<String>,
    val safetyClassification: SkillSafetyClassification,
    val successCriteria: String,
    val version: Int = 1,
    val usageCount: Int = 0,
    val successCount: Int = 0,
    val usageHistory: List<SkillUsageRecord> = emptyList(),
    val lastUsedAtMillis: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    val userApproved: Boolean = true,
    val isBuiltIn: Boolean = false
)

/**
 * Candidate reusable workflow proposed to the user after a successful non-sensitive multi-step task.
 * NEVER auto-saved — requires explicit user approval before persistence.
 */
data class SkillProposal(
    val proposalId: String,
    val suggestedName: String,
    val suggestedNameBn: String,
    val description: String,
    val triggerPhrases: List<String>,
    val requiredParameters: List<String>,
    val executionSteps: List<SkillExecutionStep>,
    val allowedTools: List<String>,
    val requiredPermissions: List<String>,
    val safetyClassification: SkillSafetyClassification,
    val successCriteria: String,
    val sourceTaskId: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)
