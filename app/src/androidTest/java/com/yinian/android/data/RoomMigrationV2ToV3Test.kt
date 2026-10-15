package com.yinian.android.data

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yinian.android.data.db.AppDatabase
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.data.entity.RelationEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room migration test for V1 (v2 schema) → V2 (v3 schema).
 *
 * The production `AppDatabase` declares `exportSchema = false` to avoid
 * shipping the schema JSON files Room normally needs for `MigrationTestHelper`.
 * We work around that by:
 *   1. Using `FrameworkSQLiteOpenHelperFactory` to create a raw v2 database
 *      whose schema matches the V1 entities byte-for-byte (no V2 tables yet).
 *   2. Inserting representative V1 rows (idea + task + relation + fixed block
 *      + focus session).
 *   3. Closing the helper so the file handle is released.
 *   4. Reopening the same file via `Room.databaseBuilder().addMigrations(MIGRATION_2_3)`
 *      and asserting that every original row survives AND the new V2 columns
 *      and tables are present.
 *
 * A compile-only `Migration(2,3)` is NOT acceptance — this test opens the
 * post-migration database through the production DAO accessors and round-trips
 * data through them.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationV2ToV3Test {

    private val dbName = "yinian-migration-test.db"

    @Before
    fun setUp() {
        // Clean any leftover from a previous run.
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase(dbName)
    }

    /**
     * Build a V2-compatible SQLite database directly using
     * `FrameworkSQLiteOpenHelperFactory`. Schema mirrors V1 entities exactly
     * (the production `AppDatabase` version=2 was identical to this in V1).
     */
    private fun openV2Database(): androidx.sqlite.db.SupportSQLiteDatabase {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE ideas (
                            id TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            body TEXT NOT NULL,
                            source TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            archived INTEGER NOT NULL DEFAULT 0,
                            recordingPath TEXT,
                            transcriptStatus TEXT NOT NULL DEFAULT 'none'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE tasks (
                            id TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            notes TEXT NOT NULL,
                            status TEXT NOT NULL,
                            priority INTEGER NOT NULL,
                            estimateMinutes INTEGER NOT NULL,
                            dueAt INTEGER,
                            createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL,
                            derivedFromIdeaId TEXT,
                            domain TEXT NOT NULL DEFAULT 'general'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE relations (
                            fromId TEXT NOT NULL,
                            toId TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            createdAt INTEGER NOT NULL,
                            PRIMARY KEY(fromId, toId, kind)
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE time_blocks (
                            id TEXT NOT NULL PRIMARY KEY,
                            taskId TEXT,
                            title TEXT NOT NULL,
                            startAt INTEGER NOT NULL,
                            endAt INTEGER NOT NULL,
                            fixed INTEGER NOT NULL DEFAULT 0,
                            source TEXT NOT NULL DEFAULT 'manual'
                        )
                        """.trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE focus_sessions (
                            id TEXT NOT NULL PRIMARY KEY,
                            taskId TEXT,
                            startedAt INTEGER NOT NULL,
                            endedAt INTEGER,
                            pausedMs INTEGER NOT NULL DEFAULT 0,
                            activeStartedAt INTEGER,
                            status TEXT NOT NULL,
                            note TEXT NOT NULL DEFAULT ''
                        )
                        """.trimIndent()
                    )
                }

                override fun onUpgrade(
                    db: androidx.sqlite.db.SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                    // No-op; we want a pure V2 database at this point.
                }
            })
            .build()
        val helper = factory.create(config)
        // Trigger creation. close() releases the connection without deleting the file.
        val db = helper.writableDatabase
        return db
    }

    private fun seedV2(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO ideas(id,title,body,source,createdAt,updatedAt,archived,recordingPath,transcriptStatus) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("idea-1", "午饭", "想吃点清淡的", "text", now, now, 0, null, "none")
        )
        db.execSQL(
            "INSERT INTO ideas(id,title,body,source,createdAt,updatedAt,archived,recordingPath,transcriptStatus) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("idea-2", "回看", "复盘专注", "voice", now - 3_600_000L, now - 3_600_000L, 0, "/sdcard/a.m4a", "disabled")
        )
        db.execSQL(
            "INSERT INTO tasks(id,title,notes,status,priority,estimateMinutes,dueAt,createdAt,updatedAt,derivedFromIdeaId,domain) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("task-1", "整理今天的笔记", "完成", "open", 2, 25, now + 6 * 3_600_000L, now, now, null, "work")
        )
        db.execSQL(
            "INSERT INTO tasks(id,title,notes,status,priority,estimateMinutes,dueAt,createdAt,updatedAt,derivedFromIdeaId,domain) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("task-2", "回复客户的邮件", "草稿", "open", 1, 15, now + 3 * 3_600_000L, now, now, null, "work")
        )
        db.execSQL(
            "INSERT INTO relations(fromId,toId,kind,createdAt) VALUES(?,?,?,?)",
            arrayOf<Any?>("task-2", "task-1", "depends_on", now)
        )
        db.execSQL(
            "INSERT INTO relations(fromId,toId,kind,createdAt) VALUES(?,?,?,?)",
            arrayOf<Any?>("idea-1", "task-1", "derived_from", now)
        )
        db.execSQL(
            "INSERT INTO time_blocks(id,taskId,title,startAt,endAt,fixed,source) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("block-1", "task-2", "会议", now, now + 60 * 60_000L, 1, "manual")
        )
        db.execSQL(
            "INSERT INTO time_blocks(id,taskId,title,startAt,endAt,fixed,source) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("block-2", "task-1", "整理笔记", now + 2 * 60 * 60_000L, now + (2 * 60 + 30) * 60_000L, 0, "manual")
        )
        db.execSQL(
            "INSERT INTO focus_sessions(id,taskId,startedAt,endedAt,pausedMs,activeStartedAt,status,note) " +
                "VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("focus-1", "task-1", now - 30 * 60_000L, now, 25 * 60_000L, null, "completed", "")
        )
    }

    @Test
    fun migration_v2_to_v3_preserves_existing_rows_and_adds_v2_columns_and_tables() = runTest {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Build a v2 fixture and seed it.
        val v2 = openV2Database()
        seedV2(v2)
        v2.close()

        // 2. Open the same file as a Room v3 database with the production migration.
        // We register BOTH MIGRATION_2_3 and MIGRATION_3_4 because the production
        // AppDatabase is version=4; registering only the v2→v3 path leaves Room at
        // v3 and the next reopen from this fixture would demand v3→v4 too.
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

        try {
            // 3. Original idea rows must survive.
            val idea1 = db.ideaDao().getById("idea-1")
            assertNotNull("Migrated DB is missing the seeded idea row", idea1)
            assertEquals("午饭", idea1!!.title)
            assertEquals("想吃点清淡的", idea1.body)
            // The V2 row should still be text/non-voice.
            assertEquals("text", idea1.source)
            assertEquals("none", idea1.transcriptStatus)
            // Migrated columns exist (selectable) and default null.
            assertNull(idea1.recordingPath)

            val idea2 = db.ideaDao().getById("idea-2")
            assertNotNull(idea2)
            assertEquals("voice", idea2!!.source)
            assertEquals("/sdcard/a.m4a", idea2.recordingPath)
            assertEquals("disabled", idea2.transcriptStatus)

            // 4. Tasks: every original field survives; the new V2 columns are null
            //    and the migrated columns are selectable.
            val task1 = db.taskDao().getById("task-1")
            assertNotNull(task1)
            assertEquals("整理今天的笔记", task1!!.title)
            assertEquals(25, task1.estimateMinutes)
            assertEquals("open", task1.status)
            // V2 columns exist with null default.
            assertNull(task1.recurringRuleId)
            assertNull(task1.instanceDate)
            // Derived from idea is preserved via the relation row.

            val task2 = db.taskDao().getById("task-2")
            assertNotNull(task2)
            assertEquals(1, task2!!.priority)

            // 5. Relations table still has both edges.
            val relations = db.relationDao().getAll()
            val depends = relations.first { it.kind == "depends_on" }
            assertEquals("task-2", depends.fromId)
            assertEquals("task-1", depends.toId)
            val derived = relations.first { it.kind == "derived_from" }
            assertEquals("idea-1", derived.fromId)
            assertEquals("task-1", derived.toId)

            // 6. Time blocks: both rows survive, AND the new V2 columns are selectable.
            //    We round-trip through DAO so it queries the migrated schema.
            val now = System.currentTimeMillis()
            val blocks = db.timeBlockDao().listInWindow(0L, now + 7L * 24L * 3600_000L)
            val b1 = blocks.first { it.id == "block-1" }
            assertEquals("会议", b1.title)
            assertTrue(b1.fixed)
            assertNull(b1.recurringRuleId)
            assertNull(b1.instanceDate)
            assertNull(b1.draftId)
            val b2 = blocks.first { it.id == "block-2" }
            assertEquals("整理笔记", b2.title)

            // 7. Focus sessions table is unchanged by V2 schema; one row round-trips.
            val focusList = db.focusDao().all()
            val f = focusList.first { it.id == "focus-1" }
            assertEquals("completed", f.status)
            assertEquals(25L * 60_000L, f.pausedMs)

            // 8. The four V2 tables exist and are empty (they were added by ALTER
            //    and CREATE statements on migrate). verify by inserting and reading
            //    back a synthetic row.
            db.workPolicyDao().upsert(
                com.yinian.android.data.entity.WorkPolicyEntity(
                    dayOfWeek = 1, enabled = true, startMin = 9 * 60, endMin = 18 * 60,
                    bufferMinutes = 0, dailyCapMinutes = 0
                )
            )
            assertEquals(1, db.workPolicyDao().all().size)

            val rid = "r1"
            db.recurringRuleDao().upsert(
                com.yinian.android.data.entity.RecurringRuleEntity(
                    id = rid,
                    title = "T",
                    estimateMinutes = 30,
                    priority = 3,
                    domain = "general",
                    kind = "daily",
                    byDayMask = 0,
                    startDayKey = "2030-06-03",
                    horizonDays = 7,
                    anchorTaskId = null,
                    archived = false,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )
            assertEquals(rid, db.recurringRuleDao().getById(rid)?.id)

            db.recurringInstanceDao().upsert(
                com.yinian.android.data.entity.RecurringInstanceEntity(
                    ruleId = rid, dayKey = "2030-06-03", cancelled = false, updatedAt = 0L
                )
            )
            assertEquals(1, db.recurringInstanceDao().listForRule(rid).size)

            db.scheduleDraftDao().upsert(
                com.yinian.android.data.entity.ScheduleDraftEntity(
                    id = "d1", fingerprint = "fp", createdAt = 0L, appliedAt = null,
                    windowStartDay = "2030-06-03", windowEndDay = "2030-06-09", note = ""
                )
            )
            assertEquals("d1", db.scheduleDraftDao().getById("d1")?.id)
        } finally {
            db.close()
        }
    }
}
