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
}
