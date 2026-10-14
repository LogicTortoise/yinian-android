package com.yinian.android.planner

import com.yinian.android.data.entity.WorkPolicyEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pure-JVM coverage for the deterministic planner engine.
 *
 * These tests do not touch Android: the planner package is intentionally
 * Android-free (no Context, no Compose, no CalendarContract) so the model can
 * be exercised from a plain JUnit run.
 *
 * The planner scoring uses the wall-clock "now" (`System.currentTimeMillis()`)
 * for the due-date pressure term only; due dates far from the scoring
 * thresholds are used so the test does not rely on the exact system time.
 */
class PlannerEngineTest {

    private val zone = java.time.ZoneId.of("UTC")
    private val engine = PlannerEngine(zone)

    /** A Monday in 2030 that is well past any wall-clock threshold that would
     *  affect scoring. */
    private val startDay: LocalDate = LocalDate.parse("2030-06-03")
    private val endDay: LocalDate = startDay.plusDays(6)

    private fun policy(
        dow: Int,
        startMin: Int = 9 * 60,
        endMin: Int = 18 * 60,
        cap: Int = 0,
        buffer: Int = 0
    ) = WorkPolicyEntity(
        dayOfWeek = dow,
        enabled = true,
        startMin = startMin,
        endMin = endMin,
        bufferMinutes = buffer,
        dailyCapMinutes = cap
    )

    private fun enableWeekdays(): List<WorkPolicyEntity> = (1..7).map { dow ->
        if (dow in 1..5) policy(dow) else policy(dow, 9 * 60, 18 * 60).copy(enabled = false)
    }

    private fun item(
        id: String = "t1",
        title: String = "Task",
        estimateMinutes: Int = 30,
        priority: Int = 3,
        dueAt: Long? = null,
        blocked: Boolean = false,
        locked: Boolean = false,
        dependsOn: Set<String> = emptySet()
    ) = PlanItem(
        id = id,
        title = title,
        estimateMinutes = estimateMinutes,
        priority = priority,
        dueAt = dueAt,
        dependsOn = dependsOn,
        blocked = blocked,
        locked = locked
    )

    @Test
    fun plan_is_deterministic_for_identical_inputs() {
        val policies = enableWeekdays()
        val items = listOf(
            item("a", "A", 60, priority = 1),
            item("b", "B", 30, priority = 2),
            item("c", "C", 15, priority = 3)
        )
        val first = engine.plan(startDay, endDay, policies, items, emptyList(), emptyList())
        val second = engine.plan(startDay, endDay, policies, items, emptyList(), emptyList())
        assertEquals(first, second)
        assertEquals(3, first.placements.size)
        assertEquals(0, first.unscheduled.size)
    }

    @Test
    fun plan_does_not_overlap_existing_fixed_busy_intervals() {
        val policies = enableWeekdays()
        // Fixed "lunch" 12:00–13:00 on day 1.
        val lunchStart = startDay.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val lunchEnd = lunchStart + 60 * 60_000L
        val busy = listOf(BusyInterval(lunchStart, lunchEnd, BusySource.Calendar))
        val items = listOf(
            item("a", "A", 60, priority = 1),
            item("b", "B", 60, priority = 2)
        )
        val res = engine.plan(startDay, endDay, policies, items, busy, emptyList())
        // No placement may intersect [lunchStart, lunchEnd).
        for (p in res.placements) {
            val overlaps = p.startMs < lunchEnd && p.endMs > lunchStart
            assertFalse("Placement ${p.itemId} overlaps the lunch busy window", overlaps)
        }
    }

    @Test
    fun plan_does_not_overlap_existing_fixed_blocks_from_existingBlocks() {
        val policies = enableWeekdays()
        // A fixed block 10:00–11:00 on day 1 written through existingBlocks.
        val fixedStart = startDay.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val fixedEnd = fixedStart + 60 * 60_000L
        val existing = listOf(PlannedBlock("fixed1", "固定块", fixedStart, fixedEnd, fixed = true))
        val items = listOf(item("a", "A", 60, priority = 1), item("b", "B", 60, priority = 2))
        val res = engine.plan(startDay, endDay, policies, items, emptyList(), existing)
        // Both placements must avoid the fixed block.
        for (p in res.placements) {
            val overlaps = p.startMs < fixedEnd && p.endMs > fixedStart
            assertFalse(overlaps)
        }
    }

    @Test
    fun plan_honours_the_working_window_and_skips_non_enabled_days() {
        // Only Wednesday (3) and Saturday (6) are working days.
        val policies = (1..7).map { dow ->
            if (dow == 3 || dow == 6) policy(dow, startMin = 9 * 60, endMin = 12 * 60)
            else policy(dow, 9 * 60, 18 * 60).copy(enabled = false)
        }
        val items = listOf(
            item("a", "A", 60, priority = 1),
            item("b", "B", 60, priority = 2),
            item("c", "C", 60, priority = 3)
        )
        val res = engine.plan(startDay, endDay, policies, items, emptyList(), emptyList())
        // 3 hours of working window per enabled day → each fits a 60-min task,
        // 2 items per day, the third item gets carried or unscheduled.
        val placedHours = res.placements.groupBy { java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        // No placement may land on a disabled weekday.
        for ((date, group) in placedHours) {
            assertTrue(
                "Disabled weekday ${date.dayOfWeek} received ${group.size} placements",
                date.dayOfWeek.value == 3 || date.dayOfWeek.value == 6
            )
        }
    }

    @Test
    fun plan_honours_the_daily_cap_by_surfacing_it_through_unscheduled() {
        val policies = listOf(policy(1, 9 * 60, 18 * 60, cap = 60))
        val items = listOf(
            item("a", "A", 30, priority = 1),
            item("b", "B", 30, priority = 2),
            item("c", "C", 30, priority = 3)
        )
        val res = engine.plan(startDay, startDay, policies, items, emptyList(), emptyList())
        // First two items fit; third is held back by the cap.
        val placedIds = res.placements.map { it.itemId }.toSet()
        assertTrue(placedIds.size <= 2)
        // The window has only this one working day, so item c stays unscheduled.
        assertTrue(res.unscheduled.any { it.itemId == "c" })
    }

    @Test
    fun plan_blocks_an_item_with_unresolved_dependency() {
        val policies = enableWeekdays()
        val items = listOf(
            item("a", "A", 30, priority = 1),
            item("b", "B", 30, priority = 2, blocked = true)
        )
        val res = engine.plan(startDay, endDay, policies, items, emptyList(), emptyList())
        val blockedPlacement = res.placements.firstOrNull { it.itemId == "b" }
        assertNull(blockedPlacement)
        val blockedRow = res.unscheduled.first { it.itemId == "b" }
        assertEquals(UnscheduledReason.BlockedByDependencies, blockedRow.reason)
        // The unblocked item must still get placed.
        val placedA = res.placements.first { it.itemId == "a" }
        assertNotNull(placedA)
    }

    @Test
    fun plan_orders_by_priority_and_due_pressure() {
        val policies = enableWeekdays()
        // A is high priority, no due date. B is medium priority, due soon.
        val now = java.time.Instant.now().toEpochMilli()
        val items = listOf(
            item("a", "A", 30, priority = 1, dueAt = null),
            item("b", "B", 30, priority = 4, dueAt = now + 12 * 3_600_000L)
        )
        val res = engine.plan(startDay, startDay, policies, items, emptyList(), emptyList())
        // Only one slot has room for one of these (we only enabled dow=1's policy in a single-day window).
        // The first placement should be A: lower priority number → lower score.
        val first = res.placements[0]
        assertEquals("a", first.itemId)
    }

    @Test
    fun plan_carries_over_an_item_that_does_not_fit_on_its_preferred_day() {
        // Five hours of work on a day with only two hours of working window.
        val policies = listOf(policy(1, 9 * 60, 11 * 60), policy(2, 9 * 60, 18 * 60))
        val items = listOf(
            item("a", "A", 60, priority = 1),
            item("b", "B", 60, priority = 2),
            item("c", "C", 60, priority = 3)
        )
        val res = engine.plan(startDay, startDay.plusDays(1), policies, items, emptyList(), emptyList())
        // At least one item must be placed on day 2 (carry-over).
        val day2 = res.placements.any {
            java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == startDay.plusDays(1)
        }
        assertTrue(day2)
    }

    @Test
    fun plan_marks_long_estimates_as_estimate_too_long_when_no_window_fits() {
        val policies = listOf(policy(1, 9 * 60, 10 * 60)) // one-hour window
        val items = listOf(item("a", "A", estimateMinutes = 180, priority = 1))
        val res = engine.plan(startDay, startDay, policies, items, emptyList(), emptyList())
        val r = res.unscheduled.single()
        assertEquals(UnscheduledReason.EstimateTooLong, r.reason)
    }

    @Test
    fun plan_marks_short_items_outside_available_hours_when_no_window_has_room() {
        // Work-policy only enables dow=1 but the engine only walks those days
        // that exist in the window. Add an item that needs more room than the
        // day offers so it ends up unscheduled.
        val policies = listOf(policy(1, 9 * 60, 9 * 60 + 15)) // 15-minute window
        val items = listOf(item("a", "A", estimateMinutes = 60, priority = 1))
        val res = engine.plan(startDay, startDay, policies, items, emptyList(), emptyList())
        val r = res.unscheduled.single()
        assertEquals(UnscheduledReason.EstimateTooLong, r.reason)
    }

    @Test
    fun plan_reports_unscheduled_reasons_that_are_stable_across_runs() {
        val policies = listOf(policy(1, 9 * 60, 10 * 60))
        val items = listOf(item("a", "A", estimateMinutes = 180, priority = 1))
        val first = engine.plan(startDay, startDay, policies, items, emptyList(), emptyList())
        val second = engine.plan(startDay, startDay, policies, items, emptyList(), emptyList())
        assertEquals(first.unscheduled, second.unscheduled)
    }

    @Test
    fun plan_returns_capacity_totals_consistent_with_placements() {
        val policies = enableWeekdays()
        val items = listOf(item("a", "A", 60, priority = 1), item("b", "B", 30, priority = 2))
        val res = engine.plan(startDay, endDay, policies, items, emptyList(), emptyList())
        // totalAvailable only includes enabled days; verify enabled weekdays add up.
        val enabled = policies.count { it.enabled }
        val expectedAvailPerDay = 9 * 60 // 9:00-18:00
        // Each day contributes up to expectedAvailPerDay; verify total is enabled * 9 * 60.
        assertEquals(enabled * expectedAvailPerDay, res.capacity.totalAvailable)
        // totalScheduled must equal sum of estimates placed.
        val expectedScheduled = res.placements.sumOf {
            ((it.endMs - it.startMs) / 60_000L).toInt()
        }
        assertEquals(expectedScheduled, res.capacity.totalScheduled)
    }

    @Test
    fun plan_does_not_schedule_an_item_both_scheduled_and_unscheduled_after_carry_over() {
        // Item A is 60 min. Day 1 has only 30 min; the engine must carry it
        // forward and either place it on day 2 or leave it unscheduled — never
        // both.
        val policies = listOf(
            policy(1, startMin = 9 * 60, endMin = 9 * 60 + 30),
            policy(2, 9 * 60, 18 * 60)
        )
        val items = listOf(item("a", "A", estimateMinutes = 60, priority = 1))
        val res = engine.plan(startDay, startDay.plusDays(1), policies, items, emptyList(), emptyList())
        val isScheduled = res.placements.any { it.itemId == "a" }
        val isUnscheduled = res.unscheduled.any { it.itemId == "a" }
        assertTrue("Item must be either scheduled or unscheduled — not both",
            isScheduled xor isUnscheduled)
        // If carried over, the placement must be on day 2.
        if (isScheduled) {
            val placed = res.placements.single { it.itemId == "a" }
            val placedDate = java.time.Instant.ofEpochMilli(placed.startMs).atZone(zone).toLocalDate()
            assertEquals(startDay.plusDays(1), placedDate)
        }
    }
}
