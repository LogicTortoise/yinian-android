package com.yinian.android.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import android.content.Context
import com.yinian.android.data.dao.FocusDao
import com.yinian.android.data.dao.IdeaDao
import com.yinian.android.data.dao.RecurringInstanceDao
import com.yinian.android.data.dao.RecurringRuleDao
import com.yinian.android.data.dao.RelationDao
import com.yinian.android.data.dao.ScheduleDraftDao
import com.yinian.android.data.dao.TaskDao
import com.yinian.android.data.dao.TimeBlockDao
import com.yinian.android.data.dao.WorkPolicyDao
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.data.entity.RecurringInstanceEntity
import com.yinian.android.data.entity.RecurringRuleEntity
import com.yinian.android.data.entity.RelationEntity
import com.yinian.android.data.entity.ScheduleDraftEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity
import com.yinian.android.data.entity.WorkPolicyEntity

@Database(
    entities = [
        IdeaEntity::class,
        TaskEntity::class,
        RelationEntity::class,
        TimeBlockEntity::class,
        FocusSessionEntity::class,
        WorkPolicyEntity::class,
        RecurringRuleEntity::class,
        RecurringInstanceEntity::class,
        ScheduleDraftEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ideaDao(): IdeaDao
    abstract fun taskDao(): TaskDao
    abstract fun relationDao(): RelationDao
    abstract fun timeBlockDao(): TimeBlockDao
    abstract fun focusDao(): FocusDao
    abstract fun workPolicyDao(): WorkPolicyDao
    abstract fun recurringRuleDao(): RecurringRuleDao
    abstract fun recurringInstanceDao(): RecurringInstanceDao
    abstract fun scheduleDraftDao(): ScheduleDraftDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * V2 migration: Room v2 → v3.
         *
         * - Adds two nullable columns to `tasks` (`recurringRuleId`, `instanceDate`).
         * - Adds three nullable columns to `time_blocks` (`recurringRuleId`,
         *   `instanceDate`, `draftId`).
         * - Creates four new tables: `work_policies`, `recurring_rules`,
         *   `recurring_instances`, `schedule_drafts`.
         *
         * Every existing V1 row survives the migration with default NULL values
         * for the new columns; nothing is dropped. The migration is non-destructive
         * and replaces `fallbackToDestructiveMigration()` (removed in V2).
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN recurringRuleId TEXT")
                db.execSQL("ALTER TABLE tasks ADD COLUMN instanceDate TEXT")
                db.execSQL("ALTER TABLE time_blocks ADD COLUMN recurringRuleId TEXT")
                db.execSQL("ALTER TABLE time_blocks ADD COLUMN instanceDate TEXT")
                db.execSQL("ALTER TABLE time_blocks ADD COLUMN draftId TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS work_policies (
                        dayOfWeek INTEGER NOT NULL PRIMARY KEY,
                        enabled INTEGER NOT NULL,
                        startMin INTEGER NOT NULL,
                        endMin INTEGER NOT NULL,
                        bufferMinutes INTEGER NOT NULL DEFAULT 0,
                        dailyCapMinutes INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_rules (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        estimateMinutes INTEGER NOT NULL,
                        priority INTEGER NOT NULL,
                        domain TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        byDayMask INTEGER NOT NULL DEFAULT 0,
                        startDayKey TEXT NOT NULL,
                        horizonDays INTEGER NOT NULL DEFAULT 14,
                        anchorTaskId TEXT,
                        archived INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS recurring_instances (
                        ruleId TEXT NOT NULL,
                        dayKey TEXT NOT NULL,
                        title TEXT,
                        estimateMinutes INTEGER,
                        cancelled INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (ruleId, dayKey)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS schedule_drafts (
                        id TEXT NOT NULL PRIMARY KEY,
                        fingerprint TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        appliedAt INTEGER,
                        windowStartDay TEXT NOT NULL,
                        windowEndDay TEXT NOT NULL,
                        note TEXT NOT NULL DEFAULT ''
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "yinian.db"
            )
                .addMigrations(MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
