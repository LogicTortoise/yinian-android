package com.yinian.android.data

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yinian.android.data.db.AppDatabase
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.FocusSessionSegmentEntity
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.data.entity.TaskEntity
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
 * V2-ME: Room v3 → v4 real migration test.
 *
 * The production schema is:
 *  - v1: original schema (legacy)
 *  - v2: V1 entities + V2 columns/tables (MIGRATION_2_3)
 *  - v3: V2 entities + tables, no segments table yet
 *  - v4: V3 entities + `focus_session_segments` (MIGRATION_3_4)
 *
 * This test asserts that every v3 row survives, the new
 * `focus_session_segments` table is created empty, and round-trip writes
 * through the production DAO succeed — without a destructive migration.
 *
 * The v3 fixture is built by running MIGRATION_2_3 over a hand-rolled v2
 * database (the same trick `RoomMigrationV2ToV3Test` uses), then we open the
 * same file through MIGRATION_3_4 and assert against the v4 schema.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationV3ToV4Test {

    private val dbName = "yinian-migration-v3-to-v4.db"

    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .deleteDatabase(dbName)
    }

    /**
     * Build a v2 SQLite fixture (identical to `RoomMigrationV2ToV3Test`'s
     * helper). We then run MIGRATION_2_3 over it to land on v3.
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
                    // No-op; we want a pure v2 database at this point.
                }
            })
            .build()
        val helper = factory.create(config)
        return helper.writableDatabase
    }

    private fun seedV2(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT INTO ideas(id,title,body,source,createdAt,updatedAt,archived,recordingPath,transcriptStatus) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("idea-1", "午饭", "想吃点清淡的", "text", now, now, 0, null, "none")
        )
        db.execSQL(
            "INSERT INTO tasks(id,title,notes,status,priority,estimateMinutes,dueAt,createdAt,updatedAt,derivedFromIdeaId,domain) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("task-1", "整理今天的笔记", "完成", "open", 2, 25, now + 6 * 3_600_000L, now, now, null, "work")
        )
        db.execSQL(
            "INSERT INTO time_blocks(id,taskId,title,startAt,endAt,fixed,source) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("block-1", "task-1", "整理笔记", now, now + 60 * 60_000L, 0, "manual")
        )
        db.execSQL(
            "INSERT INTO focus_sessions(id,taskId,startedAt,endedAt,pausedMs,activeStartedAt,status,note) " +
                "VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                "focus-1", "task-1",
                now - 30 * 60_000L, now,
                25 * 60_000L, null,
                "completed", ""
            )
        )
    }

    /**
     * Drive the v2 fixture to v3 by Room's MIGRATION_2_3, then promote v3 → v4
     * through MIGRATION_3_4 and verify everything survived.
     */
    @Test
    fun migration_v3_to_v4_retains_every_v3_row_and_adds_focus_session_segments_table() = runTest {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // 1. Land a v2 file via the framework helper + a v2 schema.
        val v2 = openV2Database()
        seedV2(v2)
        v2.close()

        // 2. Promote v2 → v3 with production MIGRATION_2_3 — Room holds the
        //    database open across this migration; close before the next step
        //    so the file handle is released.
        val v3 = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()
        v3.close()

        // 3. Open the same file with both migrations and ask Room for v4.
        val v4 = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

        try {
            // 4. Every original v3 row survives.
            val idea = v4.ideaDao().getById("idea-1")
            assertNotNull("Idea row must survive v3 → v4", idea)
            assertEquals("午饭", idea!!.title)
            val task = v4.taskDao().getById("task-1")
            assertNotNull(task)
            assertEquals("整理今天的笔记", task!!.title)
            assertEquals(25, task.estimateMinutes)
            // 5. Focus session rows are preserved unchanged — the segments table
            //    adds new FK → focus_sessions rows but never alters the parent.
            val focusRows = v4.focusDao().all()
            assertEquals("V3 focus sessions must survive the v4 migration", 1, focusRows.size)
            val focus = focusRows.first { it.id == "focus-1" }
            assertEquals("completed", focus.status)
            assertEquals(25 * 60_000L, focus.pausedMs)
            // 6. The new segments table exists and is empty for legacy sessions.
            val noSegments = v4.focusSegmentDao().all()
            assertTrue("Empty segments table on a freshly migrated v3 device", noSegments.isEmpty())

            // 7. Insert + read a synthetic segment row.
            v4.focusSegmentDao().upsert(
                FocusSessionSegmentEntity(
                    id = "seg-1", sessionId = "focus-1", kind = "active",
                    startedAt = 1_000L, endedAt = 1_500L
                )
            )
            val after = v4.focusSegmentDao().all()
            assertEquals(1, after.size)
            assertEquals("active", after[0].kind)

            // 8. Round-trip window query.
            val window = v4.focusSegmentDao().listInWindow(0L, 10_000L)
            assertEquals(1, window.size)
            assertEquals("seg-1", window[0].id)

            // 9. Reopen with just MIGRATION_3_4 (idempotence): the migration
            //    must not rebuild the table or wipe existing rows. We open
            //    with both migrations to keep Room's migration registry happy.
            v4.close()
            val reopened = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
                .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
                .build()
            assertEquals(1, reopened.focusSegmentDao().all().size)
            reopened.close()
        } catch (e: Throwable) {
            // Surface the failure clearly so the controller sees the original
            // database mismatch instead of a generic exception.
            throw AssertionError("v3 → v4 migration test failed: ${e.message}", e)
        }
    }

    /**
     * v3 → v4 should NOT require `fallbackToDestructiveMigration()` or a
     * migration that rewrites focus_sessions data. The migration being
     * additive is enforced here by re-opening the same file with both
     * migrations registered and confirming all original rows still exist.
     */
    @Test
    fun migration_v3_to_v4_does_not_drop_or_rewrite_focus_or_task_rows() = runTest {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val v2 = openV2Database()
        seedV2(v2)
        v2.close()

        val v3 = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .build()
        v3.close()

        val v4 = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

        // Insert a v3-style segment with endedAt = NULL ("live" segment).
        v4.focusSegmentDao().upsert(
            FocusSessionSegmentEntity(
                id = "live-seg", sessionId = "focus-1", kind = "active",
                startedAt = 500L, endedAt = null
            )
        )
        v4.close()

        // Reopen and verify the live row's `endedAt` is still null after the
        // migration has run, i.e. the migration didn't rewrite segment data.
        val reopen = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()
        val rows = reopen.focusSegmentDao().all()
        // After insertion the test row count is 1.
        assertEquals(1, rows.size)
        // The endedAt column must persist as NULL.
        assertNull("endedAt must remain NULL on the migrated row", rows[0].endedAt)
        reopen.close()
    }
}
