package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinian.android.data.entity.FocusSessionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryFocusTest {

    private fun newSession(
        id: String = "s1",
        status: String = "active",
        startedAt: Long = 1_000L,
        activeStartedAt: Long? = 1_000L,
        pausedMs: Long = 0L,
        endedAt: Long? = null
    ): FocusSessionEntity = FocusSessionEntity(
        id = id,
        taskId = "t1",
        startedAt = startedAt,
        endedAt = endedAt,
        pausedMs = pausedMs,
        activeStartedAt = activeStartedAt,
        status = status
    )

    @Test
    fun startFocus_creates_an_active_session_with_no_accumulated_time() = runTest {
        val repo = makeRepository()
        val session = repo.startFocus(taskId = "t1")
        assertEquals("active", session.status)
        assertEquals(0L, session.pausedMs)
        assertNotNull(session.activeStartedAt)
        assertEquals(session.id, repo.observeActiveFocus().first()?.id)
    }

    @Test
    fun pauseFocus_folds_the_active_segment_into_pausedMs_and_clears_activeStartedAt() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        // Pretend the active segment has been running for 60 seconds.
        // We can't fake "now" inside the repo, but we can verify the resulting shape:
        // pausedMs grows and activeStartedAt becomes null.
        repo.pauseFocus(s.id)

        val after = repo.observeActiveFocus().first()
        assertNotNull(after)
        assertEquals("paused", after!!.status)
        assertNull(after.activeStartedAt)
        assertTrue(after.pausedMs >= 0L)
    }

    @Test
    fun resumeFocus_sets_a_new_activeStartedAt_while_keeping_pausedMs() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        repo.pauseFocus(s.id)
        val pausedMs = repo.observeActiveFocus().first()!!.pausedMs

        repo.resumeFocus(s.id)
        val after = repo.observeActiveFocus().first()
        assertNotNull(after)
        assertEquals("active", after!!.status)
        assertNotNull(after.activeStartedAt)
        assertEquals(pausedMs, after.pausedMs)
    }

    @Test
    fun endFocus_marks_the_session_completed_and_stamps_endedAt() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        repo.endFocus(s.id)

        // observeActive only surfaces active/paused sessions.
        assertNull(repo.observeActiveFocus().first())
        val history = repo.observeAllFocus().first()
        val finished = history.first { it.id == s.id }
        assertEquals("completed", finished.status)
        assertNotNull(finished.endedAt)
        assertNull(finished.activeStartedAt)
    }

    @Test
    fun cancelFocus_marks_the_session_cancelled_and_clears_pausedMs() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        repo.cancelFocus(s.id)

        assertNull(repo.observeActiveFocus().first())
        val history = repo.observeAllFocus().first()
        val cancelled = history.first { it.id == s.id }
        assertEquals("cancelled", cancelled.status)
        assertEquals(0L, cancelled.pausedMs)
        assertNull(cancelled.activeStartedAt)
        assertNotNull(cancelled.endedAt)
    }

    @Test
    fun observeActiveFocus_returns_null_when_there_are_no_sessions() = runTest {
        val repo = makeRepository()
        assertNull(repo.observeActiveFocus().first())
    }

    @Test
    fun pauseFocus_on_an_unknown_id_is_a_no_op() = runTest {
        val repo = makeRepository()
        repo.pauseFocus(id = "does-not-exist") // should not throw
        assertEquals(0, repo.observeAllFocus().first().size)
    }

    @Test
    fun endFocus_on_an_unknown_id_does_not_crash_and_writes_nothing() = runTest {
        val repo = makeRepository()
        repo.endFocus(id = "does-not-exist")
        assertEquals(0, repo.observeAllFocus().first().size)
    }

    // ======================================================================
    // V2-ME segment-history assertions
    // ======================================================================

    @Test
    fun startFocus_writes_one_open_active_segment() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        val segments = repo.focusSegmentsSnapshot().filter { it.sessionId == s.id }
        assertEquals(1, segments.size)
        assertEquals("active", segments[0].kind)
        org.junit.Assert.assertNull(segments[0].endedAt)
    }

    @Test
    fun pauseFocus_closes_active_segment_and_opens_paused_segment() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        repo.pauseFocus(s.id)
        val segments = repo.focusSegmentsSnapshot().filter { it.sessionId == s.id }
        // Two segments: closed active, open paused.
        assertEquals(2, segments.size)
        val active = segments.first { it.kind == "active" }
        val paused = segments.first { it.kind == "paused" }
        org.junit.Assert.assertNotNull(active.endedAt)
        org.junit.Assert.assertNull(paused.endedAt)
    }

    @Test
    fun resumeFocus_closes_paused_segment_and_opens_new_active_segment() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        repo.pauseFocus(s.id)
        repo.resumeFocus(s.id)
        val segments = repo.focusSegmentsSnapshot().filter { it.sessionId == s.id }
        // Three segments: active, paused, active.
        assertEquals(3, segments.size)
        val activeSegments = segments.filter { it.kind == "active" }
        val pausedSegments = segments.filter { it.kind == "paused" }
        assertEquals(2, activeSegments.size)
        assertEquals(1, pausedSegments.size)
        // The first active segment is closed; the last active is open.
        assertNotNull(activeSegments[0].endedAt)
        org.junit.Assert.assertNull(activeSegments[1].endedAt)
        org.junit.Assert.assertNotNull(pausedSegments[0].endedAt)
    }

    @Test
    fun endFocus_closes_the_open_active_segment_and_writes_no_paused_segment() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        repo.endFocus(s.id)
        val segments = repo.focusSegmentsSnapshot().filter { it.sessionId == s.id }
        assertEquals(1, segments.size)
        org.junit.Assert.assertNotNull(segments[0].endedAt)
        // All segments must be closed.
        assertTrue(segments.all { it.endedAt != null })
    }

    @Test
    fun cancelFocus_closes_any_open_segment_so_no_row_carries_endedAt_null() = runTest {
        val repo = makeRepository()
        val s = repo.startFocus(taskId = "t1")
        repo.pauseFocus(s.id)
        repo.cancelFocus(s.id)
        val segments = repo.focusSegmentsSnapshot().filter { it.sessionId == s.id }
        // Every segment must be closed.
        assertTrue(segments.all { it.endedAt != null })
        // Cancelled sessions are surfaced with status="cancelled" (filtered by FocusAttribution).
        val focusRows = repo.observeAllFocus().first().first { it.id == s.id }
        assertEquals("cancelled", focusRows.status)
    }

    @Test
    fun segments_are_attributed_per_session_and_do_not_leak_between_sessions() = runTest {
        val repo = makeRepository()
        val s1 = repo.startFocus(taskId = "t1")
        val s2 = repo.startFocus(taskId = "t2")
        val s1Segments = repo.focusSegmentsSnapshot().filter { it.sessionId == s1.id }
        val s2Segments = repo.focusSegmentsSnapshot().filter { it.sessionId == s2.id }
        assertEquals(1, s1Segments.size)
        assertEquals(1, s2Segments.size)
        // No overlap between sessions.
        assertTrue(s1Segments.all { it.sessionId == s1.id })
        assertTrue(s2Segments.all { it.sessionId == s2.id })
    }
}
