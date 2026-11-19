package com.example.data.local.checkpoint

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.memory.AgentMemoryDao
import com.example.data.local.memory.AgentMemoryEntity
import com.example.data.local.metrics.ToolExecutionMetricDao
import com.example.data.local.metrics.ToolExecutionMetricEntity
import com.example.data.local.orchestration.LongTermTaskDao
import com.example.data.local.orchestration.LongTermTaskEntity
import com.example.data.local.schedule.ScheduledTaskDao
import com.example.data.local.schedule.ScheduledTaskEntity
import com.example.data.local.skill.AgentSkillDao
import com.example.data.local.skill.AgentSkillEntity

/**
 * Strictly local, offline-capable Room database storing:
 * - v1: Sanitized ScreenSight AI Agent task checkpoints (`agent_task_checkpoints`)
 * - v2: Privacy-first Long-Term Memories (`agent_memories`) and Reusable Skills (`agent_skills`)
 * - v3: Long-Term Multi-Stage Tasks (`agent_long_term_tasks`), Scheduled Tasks (`agent_scheduled_tasks`),
 *       and Tool Execution Reliability Metrics (`agent_tool_metrics`)
 *
 * Uses explicit [MIGRATION_1_2] and [MIGRATION_2_3] so existing data is preserved across upgrades.
 */
@Database(
    entities = [
        AgentTaskCheckpointEntity::class,
        AgentMemoryEntity::class,
        AgentSkillEntity::class,
        LongTermTaskEntity::class,
        ScheduledTaskEntity::class,
        ToolExecutionMetricEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AgentCheckpointDatabase : RoomDatabase() {
    abstract fun checkpointDao(): AgentTaskCheckpointDao
    abstract fun memoryDao(): AgentMemoryDao
    abstract fun skillDao(): AgentSkillDao
    abstract fun longTermTaskDao(): LongTermTaskDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun toolMetricDao(): ToolExecutionMetricDao

    companion object {
        const val DB_NAME = "screensight_agent_checkpoints.db"

        /**
         * Non-destructive schema migration from v1 (checkpoints only) to v2 (checkpoints + memories + skills).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_memories` (
                        `id` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `memoryKey` TEXT NOT NULL,
                        `normalizedKey` TEXT NOT NULL,
                        `value` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `lastUsedAtMillis` INTEGER NOT NULL,
                        `confidence` REAL NOT NULL,
                        `source` TEXT NOT NULL,
                        `expirationPolicy` TEXT NOT NULL,
                        `expiresAtMillis` INTEGER,
                        `userApproved` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_agent_memories_category` ON `agent_memories` (`category`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_agent_memories_normalized_key` ON `agent_memories` (`normalizedKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_agent_memories_expires_at` ON `agent_memories` (`expiresAtMillis`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_skills` (
                        `skillId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `triggerPhrasesJson` TEXT NOT NULL,
                        `requiredParametersJson` TEXT NOT NULL,
                        `executionStepsJson` TEXT NOT NULL,
                        `allowedToolsJson` TEXT NOT NULL,
                        `safetyClassification` TEXT NOT NULL,
                        `successCriteria` TEXT NOT NULL,
                        `version` INTEGER NOT NULL,
                        `usageCount` INTEGER NOT NULL,
                        `successCount` INTEGER NOT NULL,
                        `usageHistoryJson` TEXT NOT NULL,
                        `lastUsedAtMillis` INTEGER,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `userApproved` INTEGER NOT NULL,
                        `isBuiltIn` INTEGER NOT NULL,
                        PRIMARY KEY(`skillId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_agent_skills_enabled` ON `agent_skills` (`enabled`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_agent_skills_name` ON `agent_skills` (`name`)"
                )
            }
        }

        /**
         * Non-destructive schema migration from v2 to v3 adding long-term tasks, schedules, and tool metrics.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_long_term_tasks` (
                        `taskId` TEXT NOT NULL,
                        `goal` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `subtasksJson` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `completedSubtasks` INTEGER NOT NULL,
                        `totalSubtasks` INTEGER NOT NULL,
                        `replanCount` INTEGER NOT NULL,
                        `lastCheckpointNote` TEXT NOT NULL,
                        `lastError` TEXT,
                        PRIMARY KEY(`taskId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_long_term_tasks_status` ON `agent_long_term_tasks` (`status`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_long_term_tasks_updated` ON `agent_long_term_tasks` (`updatedAtMillis`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_scheduled_tasks` (
                        `scheduleId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `actionType` TEXT NOT NULL,
                        `targetAppOrSkillId` TEXT NOT NULL,
                        `payloadJson` TEXT NOT NULL,
                        `recurrence` TEXT NOT NULL,
                        `hourOfDay` INTEGER NOT NULL,
                        `minuteOfHour` INTEGER NOT NULL,
                        `intervalMinutes` INTEGER NOT NULL,
                        `nextRunAtMillis` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `userApproved` INTEGER NOT NULL,
                        `requiresAccessibility` INTEGER NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        `lastRunAtMillis` INTEGER,
                        `lastRunResult` TEXT,
                        PRIMARY KEY(`scheduleId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_scheduled_tasks_enabled` ON `agent_scheduled_tasks` (`enabled`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_scheduled_tasks_next_run` ON `agent_scheduled_tasks` (`nextRunAtMillis`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `agent_tool_metrics` (
                        `id` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `toolName` TEXT NOT NULL,
                        `succeeded` INTEGER NOT NULL,
                        `blockedBySafety` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `errorCode` TEXT,
                        `sanitizedSummary` TEXT NOT NULL,
                        `timestampMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_tool_metrics_name` ON `agent_tool_metrics` (`toolName`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `idx_tool_metrics_ts` ON `agent_tool_metrics` (`timestampMillis`)"
                )
            }
        }

        @Volatile
        private var instance: AgentCheckpointDatabase? = null

        fun getInstance(context: Context): AgentCheckpointDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentCheckpointDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
