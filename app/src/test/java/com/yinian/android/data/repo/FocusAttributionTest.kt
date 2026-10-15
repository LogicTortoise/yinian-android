package com.yinian.android.data.repo

import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.FocusSessionSegmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * V2-ME coverage for [FocusAttribution]: V4 sessions that have an explicit
 * `focus_session_segments` history and V3 sessions that rely on the documented
 * fallback rule. All day boundaries are checked against a fixed UTC zone so
 * the suite is deterministic across machines.
 */
class FocusAttributionTest {

    private val utc = ZoneId.of("UTC")

    /** A wall-clock helper that pins the supplied local instant into UTC. */
    private fun ms(localDate: LocalDate, hour: Int, minute: Int): Long {
        return ZonedDateTime.of(localDate, java.time.LocalTime.of(hour, minute), utc)
            .toInstant().toEpochMilli()
    }

    private fun session(
        id: String = "s1",
        taskId: String = "t1",
        status: String = "completed",
        startedAt: Long = 0L,
        endedAt: Long? = null,
        pausedMs: Long = 0L,
        activeStartedAt: Long? = null
    ) = FocusSessionEntity(
        id = id,
        taskId = taskId,
        startedAt = startedAt,
        endedAt = endedAt,
        pausedMs = pausedMs,
        activeStartedAt = activeStartedAt,
        status = status
    )

    private fun segment(
        id: String,
        sessionId: String,
        kind: String = "active",
        startedAt: Long,
        endedAt: Long? = null
    ) = FocusSessionSegmentEntity(
        id = id,
        sessionId = sessionId,
        kind = kind,
        startedAt = startedAt,
        endedAt = endedAt
    )

    @Test
    fun v4_single_segment_mid_day_contributes_to_one_day_only() {
        // 30 minute segment entirely on 2026-01-15 between 09:00 and 09:30 UTC.
        val day = LocalDate.of(2026, 1, 15)
        val start = ms(day, 9, 0)
        val end = ms(day, 9, 30)
        val s = session(startedAt = start, endedAt = end)
        val windowStart = ms(day, 0, 0)
        val windowEnd = ms(day.plusDays(1), 0, 0)
        val totals = FocusAttribution.totalsByDay(
            listOf(s),
            listOf(segment("seg1", "s1", startedAt = start, endedAt = end)),
            windowStart, windowEnd, utc, nowMs = end + 60_000L
        )
        assertEquals(1, totals.size)
        assertEquals(day.toString(), totals[0].dayKey)
        assertEquals(30 * 60_000L, totals[0].activeMs)
    }

    @Test
    fun v4_segment_crossing_midnight_is_split_exactly_across_two_local_days() {
        // 90 min segment from 23:30 on 2026-01-15 to 01:00 on 2026-01-16.
        val day1 = LocalDate.of(2026, 1, 15)
        val start = ms(day1, 23, 30)
        val end = ms(LocalDate.of(2026, 1, 16), 1, 0)
        val s = session(startedAt = start, endedAt = end)
        val windowStart = ms(day1, 0, 0)
        val windowEnd = ms(day1.plusDays(2), 0, 0)
        val totals = FocusAttribution.totalsByDay(
            listOf(s),
            listOf(segment("seg1", "s1", startedAt = start, endedAt = end)),
            windowStart, windowEnd, utc, nowMs = end + 60_000L
        )
        // 23:30-24:00 = 30 min on day 1; 00:00-01:00 = 60 min on day 2.
        assertEquals(2, totals.size)
        assertEquals(day1.toString(), totals[0].dayKey)
        assertEquals(30 * 60_000L, totals[0].activeMs)
        assertEquals(LocalDate.of(2026, 1, 16).toString(), totals[1].dayKey)
        assertEquals(60 * 60_000L, totals[1].activeMs)
    }

    @Test
    fun v4_open_segment_without_endedAt_is_capped_at_now() {
        // Active segment started 10 min ago, no endedAt.
        val day = LocalDate.of(2026, 1, 15)
        val start = ms(day, 9, 0)
        val now = ms(day, 9, 10)
        val s = session(status = "active", startedAt = start, activeStartedAt = start)
        val totals = FocusAttribution.totalsByDay(
            listOf(s),
            listOf(segment("seg1", "s1", startedAt = start, endedAt = null)),
            ms(day, 0, 0), ms(day.plusDays(1), 0, 0), utc, nowMs = now
        )
        assertEquals(1, totals.size)
        assertEquals(day.toString(), totals[0].dayKey)
        assertEquals(10 * 60_000L, totals[0].activeMs)
    }

    @Test
    fun v4_mixed_active_and_paused_segments_count_only_active_segments() {
        val day = LocalDate.of(2026, 1, 15)
        val active1Start = ms(day, 9, 0)
        val active1End = ms(day, 9, 30)
        val pausedStart = ms(day, 9, 30)
        val pausedEnd = ms(day, 10, 30)
        val active2Start = ms(day, 10, 30)
        val active2End = ms(day, 10, 45)
        val s = session(startedAt = active1Start, endedAt = active2End)
        val totals = FocusAttribution.totalsByDay(
            listOf(s),
            listOf(
                segment("seg1", "s1", "active", active1Start, active1End),
                segment("seg2", "s1", "paused", pausedStart, pausedEnd),
                segment("seg3", "s1", "active", active2Start, active2End)
            ),
            ms(day, 0, 0), ms(day.plusDays(1), 0, 0), utc, nowMs = active2End + 60_000L
        )
        // 30 min (first active) + 15 min (second active) = 45 min on this day.
        // Paused segment 60 min is excluded.
        assertEquals(1, totals.size)
        assertEquals(day.toString(), totals[0].dayKey)
        assertEquals(45 * 60_000L, totals[0].activeMs)
    }

    @Test
    fun v3_fallback_session_with_completed_status_uses_endedAt_anchor() {
        // 60 min completed session: V3 only knows pausedMs = 60 min and endedAt.
        val day = LocalDate.of(2026, 1, 15)
        val start = ms(day, 9, 0)
        val end = ms(day, 10, 0)
        val s = session(
            status = "completed",
            startedAt = start,
            endedAt = end,
            pausedMs = 60 * 60_000L,
            activeStartedAt = null
        )
        val totals = FocusAttribution.totalsByDay(
            listOf(s),
            // No segment rows — V3 fallback path.
            emptyList(),
            ms(day, 0, 0), ms(day.plusDays(1), 0, 0), utc, nowMs = end + 60_000L
        )
        assertEquals(1, totals.size)
        assertEquals(day.toString(), totals[0].dayKey)
        assertEquals(60 * 60_000L, totals[0].activeMs)
    }

    @Test
    fun v3_fallback_session_with_cancelled_status_is_excluded_entirely() {
        val day = LocalDate.of(2026, 1, 15)
        val s = session(
            status = "cancelled",
            startedAt = ms(day, 9, 0),
            endedAt = ms(day, 10, 0),
            pausedMs = 60 * 60_000L,
            activeStartedAt = null
        )
        val totals = FocusAttribution.totalsByDay(
            listOf(s), emptyList(),
            ms(day, 0, 0), ms(day.plusDays(1), 0, 0), utc, nowMs = ms(day, 10, 30)
        )
        assertTrue("Cancelled session must be excluded", totals.isEmpty())
    }

    @Test
    fun v3_fallback_active_session_anchors_at_now_minus_active_time() {
        // "active" session, 20 min of accumulated time + 30 min active segment.
        // Anchor end = now; anchor start = now - 50 min (totalActiveMs).
        val now = Instant.parse("2026-01-15T10:00:00Z").toEpochMilli()
        val nowMinusActive = now - 50 * 60_000L
        val s = session(
            status = "active",
            startedAt = nowMinusActive,
            endedAt = null,
            pausedMs = 20 * 60_000L,
            activeStartedAt = now - 30 * 60_000L
        )
        val totals = FocusAttribution.totalsByDay(
            listOf(s), emptyList(),
            Instant.parse("2026-01-15T00:00:00Z").toEpochMilli(),
            Instant.parse("2026-01-16T00:00:00Z").toEpochMilli(),
            utc, nowMs = now
        )
        assertEquals(1, totals.size)
        assertEquals(50 * 60_000L, totals[0].activeMs)
    }

    @Test
    fun v4_session_with_actualMsByTask_groups_active_minutes_per_task() {
        val day = LocalDate.of(2026, 1, 15)
        // Two tasks, one session per task, all on the same day.
        val s1 = session(id = "s1", status = "completed", startedAt = ms(day, 9, 0), endedAt = ms(day, 9, 30))
        val s2 = session(
            id = "s2", taskId = "t2", status = "completed",
            startedAt = ms(day, 10, 0), endedAt = ms(day, 10, 15)
        )
        val s1Segments = listOf(segment("a", "s1", startedAt = ms(day, 9, 0), endedAt = ms(day, 9, 30)))
        val s2Segments = listOf(segment("b", "s2", startedAt = ms(day, 10, 0), endedAt = ms(day, 10, 15)))
        val perTask = FocusAttribution.actualMsByTask(
            listOf(s1, s2),
            s1Segments + s2Segments,
            ms(day, 0, 0),
            ms(day.plusDays(1), 0, 0),
            utc, nowMs = ms(day, 11, 0)
        )
        assertEquals(30 * 60_000L, perTask["t1"])
        assertEquals(15 * 60_000L, perTask["t2"])
    }

    @Test
    fun cancelled_session_is_omitted_from_per_task_actualMs() {
        val day = LocalDate.of(2026, 1, 15)
        val s = session(
            status = "cancelled",
            startedAt = ms(day, 9, 0), endedAt = ms(day, 9, 30),
            pausedMs = 30 * 60_000L, activeStartedAt = null
        )
        val perTask = FocusAttribution.actualMsByTask(
            listOf(s), emptyList(),
            ms(day, 0, 0),
            ms(day.plusDays(1), 0, 0),
            utc, nowMs = ms(day, 10, 0)
        )
        assertTrue("Cancelled session must not contribute", perTask.isEmpty())
    }

    @Test
    fun v4_segment_partly_inside_the_window_and_partly_outside_is_clipped() {
        val day = LocalDate.of(2026, 1, 15)
        // 120 min segment from 23:00 to 01:00 next day.
        val segStart = ms(day, 23, 0)
        val segEnd = ms(LocalDate.of(2026, 1, 16), 1, 0)
        val s = session(startedAt = segStart, endedAt = segEnd)
        // Query only day 1: 23:00-24:00 = 60 min.
        val totals = FocusAttribution.totalsByDay(
            listOf(s), listOf(segment("seg1", "s1", startedAt = segStart, endedAt = segEnd)),
            ms(day, 0, 0), ms(day.plusDays(1), 0, 0), utc, nowMs = segEnd + 60_000L
        )
        assertEquals(1, totals.size)
        assertEquals(60 * 60_000L, totals[0].activeMs)
    }
}
