package com.example.data.local.memory

import com.example.agent.MemoryPrivacyGuard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Strictly local, offline-capable Long-Term Memory Repository backed by Room (`agent_memories`).
 *
 * Guarantees:
 * - Enforces [MemoryPrivacyGuard] on every create & update (rejects passwords, OTPs, PINs, API keys, tokens, private message bodies).
 * - Deduplicates & resolves conflicting records cleanly by `(category, normalizedKey)`.
 * - Honors global `memoryEnabled` toggle — zero reads for AI context and zero writes when disabled.
 * - Supports expiration policies, cleanup, user inspection/editing/deletion, and graceful handling of corrupted rows.
 */
class AgentMemoryRepository(
    private val dao: AgentMemoryDao,
    private val isMemoryEnabledProvider: () -> Boolean = { true }
) {

    sealed class SaveOutcome {
        data class Saved(val record: AgentMemoryRecord, val replacedExisting: Boolean) : SaveOutcome()
        data class KeptHigherPriority(val existing: AgentMemoryRecord) : SaveOutcome()
        data class Rejected(val reasonEn: String, val reasonBn: String) : SaveOutcome()
        data object MemoryDisabled : SaveOutcome()
    }

    /**
     * Observes all stored local memories for user inspection & management in the UI
     * (remains visible even if memory recording is temporarily paused so users can delete/clear data).
     */
    fun observeAllMemories(): Flow<List<AgentMemoryRecord>> {
        return dao.observeAllMemories().map { entities ->
            entities.mapNotNull { it.toDomainOrNull() }
        }
    }

    suspend fun getAllMemories(): List<AgentMemoryRecord> {
        return runCatching { dao.getAllMemories() }
            .getOrDefault(emptyList())
            .mapNotNull { it.toDomainOrNull() }
    }

    /**
     * Returns non-expired memories for local retrieval/planning.
     * Immediately returns empty when memory is disabled.
     */
    suspend fun getActiveMemoriesForRetrieval(nowMillis: Long = System.currentTimeMillis()): List<AgentMemoryRecord> {
        if (!isMemoryEnabledProvider()) return emptyList()
        return runCatching { dao.getActiveMemories(nowMillis) }
            .getOrDefault(emptyList())
            .mapNotNull { it.toDomainOrNull() }
            .filterNot { it.isExpired(nowMillis) }
    }

    /**
     * Saves or updates a memory record with privacy validation, deduplication, and conflict resolution.
     */
    suspend fun saveMemory(
        category: MemoryCategory,
        memoryKey: String,
        value: String,
        confidence: Float = 0.85f,
        source: MemorySource = MemorySource.USER_EXPLICIT,
        expirationPolicy: MemoryExpirationPolicy = MemoryExpirationPolicy.NEVER,
        customExpiresAtMillis: Long? = null,
        userApproved: Boolean = source == MemorySource.USER_EXPLICIT || source == MemorySource.USER_EDITED,
        nowMillis: Long = System.currentTimeMillis()
    ): SaveOutcome {
        if (!isMemoryEnabledProvider()) {
            return SaveOutcome.MemoryDisabled
        }

        val validation = MemoryPrivacyGuard.validateAndSanitizeMemory(memoryKey, value)
        if (validation is MemoryPrivacyGuard.ValidationResult.Rejected) {
            return SaveOutcome.Rejected(validation.reasonEn, validation.reasonBn)
        }
        val accepted = validation as MemoryPrivacyGuard.ValidationResult.Accepted
        val normalizedKey = MemoryPrivacyGuard.normalizeKey(accepted.sanitizedKey)
        if (normalizedKey.isBlank()) {
            return SaveOutcome.Rejected(
                reasonEn = "Memory key must contain readable letters or numbers.",
                reasonBn = "স্মৃতির শিরোনামে বৈধ অক্ষর বা সংখ্যা থাকতে হবে।"
            )
        }

        val clampedConfidence = confidence.coerceIn(0.05f, 1.0f)
        val expiresAt = expirationPolicy.computeExpiresAt(nowMillis, customExpiresAtMillis)

        // Check existing records with same (category, normalizedKey) to prevent duplicates & contradictions
        val existingMatches = runCatching {
            dao.findByCategoryAndNormalizedKey(category.name, normalizedKey)
        }.getOrDefault(emptyList())

        val primaryExisting = existingMatches.firstOrNull()?.toDomainOrNull()
        if (primaryExisting != null) {
            // Do not let low-priority automatic inference overwrite an explicit user-approved memory
            if (primaryExisting.userApproved && !userApproved && clampedConfidence <= primaryExisting.confidence) {
                return SaveOutcome.KeptHigherPriority(primaryExisting)
            }

            // Clean up any duplicate rows for the same (category, normalizedKey)
            val duplicateIds = existingMatches.drop(1).map { it.id }
            if (duplicateIds.isNotEmpty()) {
                runCatching { dao.deleteByIds(duplicateIds) }
            }

            val updatedEntity = AgentMemoryEntity(
                id = primaryExisting.id,
                category = category.name,
                memoryKey = accepted.sanitizedKey,
                normalizedKey = normalizedKey,
                value = accepted.sanitizedValue,
                createdAtMillis = primaryExisting.createdAtMillis,
                updatedAtMillis = nowMillis,
                lastUsedAtMillis = nowMillis,
                confidence = maxOf(clampedConfidence, if (userApproved) 0.95f else clampedConfidence),
                source = source.name,
                expirationPolicy = expirationPolicy.name,
                expiresAtMillis = expiresAt,
                userApproved = userApproved || primaryExisting.userApproved
            )
            dao.upsert(updatedEntity)
            return SaveOutcome.Saved(
                record = updatedEntity.toDomainOrNull()!!,
                replacedExisting = true
            )
        }

        val newEntity = AgentMemoryEntity(
            id = "mem_${UUID.randomUUID().toString().take(12)}",
            category = category.name,
            memoryKey = accepted.sanitizedKey,
            normalizedKey = normalizedKey,
            value = accepted.sanitizedValue,
            createdAtMillis = nowMillis,
            updatedAtMillis = nowMillis,
            lastUsedAtMillis = nowMillis,
            confidence = clampedConfidence,
            source = source.name,
            expirationPolicy = expirationPolicy.name,
            expiresAtMillis = expiresAt,
            userApproved = userApproved
        )
        dao.upsert(newEntity)
        return SaveOutcome.Saved(
            record = newEntity.toDomainOrNull()!!,
            replacedExisting = false
        )
    }

    /**
     * Edits an existing memory record explicitly requested by the user in the Management UI.
     */
    suspend fun updateMemoryById(
        id: String,
        category: MemoryCategory,
        memoryKey: String,
        value: String,
        expirationPolicy: MemoryExpirationPolicy,
        customExpiresAtMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): SaveOutcome {
        val existing = dao.getById(id)
            ?: return SaveOutcome.Rejected("Memory record not found.", "স্মৃতিটি পাওয়া যায়নি।")

        val validation = MemoryPrivacyGuard.validateAndSanitizeMemory(memoryKey, value)
        if (validation is MemoryPrivacyGuard.ValidationResult.Rejected) {
            return SaveOutcome.Rejected(validation.reasonEn, validation.reasonBn)
        }
        val accepted = validation as MemoryPrivacyGuard.ValidationResult.Accepted
        val normalizedKey = MemoryPrivacyGuard.normalizeKey(accepted.sanitizedKey)
        if (normalizedKey.isBlank()) {
            return SaveOutcome.Rejected(
                reasonEn = "Memory key must contain readable letters or numbers.",
                reasonBn = "স্মৃতির শিরোনামে বৈধ অক্ষর বা সংখ্যা থাকতে হবে।"
            )
        }

        // Deduplicate if the user renamed this key to collide with another existing record in the same category
        val colliding = dao.findByCategoryAndNormalizedKey(category.name, normalizedKey)
            .filter { it.id != id }
            .map { it.id }
        if (colliding.isNotEmpty()) {
            dao.deleteByIds(colliding)
        }

        val updated = existing.copy(
            category = category.name,
            memoryKey = accepted.sanitizedKey,
            normalizedKey = normalizedKey,
            value = accepted.sanitizedValue,
            updatedAtMillis = nowMillis,
            confidence = 1.0f,
            source = MemorySource.USER_EDITED.name,
            expirationPolicy = expirationPolicy.name,
            expiresAtMillis = expirationPolicy.computeExpiresAt(nowMillis, customExpiresAtMillis),
            userApproved = true
        )
        dao.upsert(updated)
        return SaveOutcome.Saved(updated.toDomainOrNull()!!, replacedExisting = true)
    }

    suspend fun markMemoriesUsed(ids: List<String>, nowMillis: Long = System.currentTimeMillis()) {
        if (ids.isEmpty() || !isMemoryEnabledProvider()) return
        runCatching { dao.markUsed(ids, nowMillis) }
    }

    suspend fun deleteMemory(id: String): Boolean {
        return runCatching { dao.deleteById(id) > 0 }.getOrDefault(false)
    }

    suspend fun purgeExpiredMemories(nowMillis: Long = System.currentTimeMillis()): Int {
        return runCatching { dao.deleteExpired(nowMillis) }.getOrDefault(0)
    }

    suspend fun clearAllMemories(): Int {
        return runCatching { dao.deleteAll() }.getOrDefault(0)
    }

    private fun AgentMemoryEntity.toDomainOrNull(): AgentMemoryRecord? {
        return runCatching {
            val cleanKey = memoryKey.trim()
            val cleanVal = value.trim()
            if (cleanKey.isEmpty() || cleanVal.isEmpty()) return null
            // Defensive check: never surface a memory if corrupted data contains a secret
            if (MemoryPrivacyGuard.containsSensitiveSecret(cleanKey, cleanVal)) return null
            AgentMemoryRecord(
                id = id,
                category = MemoryCategory.fromStored(category),
                memoryKey = cleanKey,
                value = cleanVal,
                createdAtMillis = createdAtMillis,
                updatedAtMillis = updatedAtMillis,
                lastUsedAtMillis = lastUsedAtMillis,
                confidence = confidence.coerceIn(0.0f, 1.0f),
                source = MemorySource.fromStored(source),
                expirationPolicy = MemoryExpirationPolicy.fromStored(expirationPolicy),
                expiresAtMillis = expiresAtMillis,
                userApproved = userApproved
            )
        }.getOrNull()
    }
}
