package com.yinian.android.ui.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/**
 * V2 coverage for the [PlanDate] helpers used by the plan screen's
 * day / week / month calendar views. All conversions route through `java.time`
 * so DST / leap-day / local-zone subtleties are handled by the platform
 * rather than the test.
 *
 * The interesting cases:
 *   - Monday-start weeks: every Monday and every non-Monday referenced by
 *     `startOfWeek`.
 *   - The full 42-day month grid: 6 rows × 7 columns, always starting on a
 *     Monday, always ending 41 days later.
 *   - Leap day handling: Feb 29, 2024 produces a 29-day February followed by
 *     March 1.
 */
class PlanDateTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun startOfWeek_returns_Monday_for_every_day_of_a_full_week() {
        // Anchor on a Wednesday — `startOfWeek` must walk backwards to Monday.
        val monday = LocalDate.of(2026, 1, 12)
        val wed = monday.plusDays(2)
        assertEquals(monday, PlanDate.startOfWeek(wed))
        // For all seven weekdays the result must always be a Monday.
        for (i in 0..6) {
            val d = monday.plusDays(i.toLong())
            assertEquals("Day $d (${d.dayOfWeek}) must start-of-week to Monday",
                Monday, PlanDate.startOfWeek(d).dayOfWeek)
        }
    }

    @Test
    fun weekRange_is_a_half_open_seven_day_span_starting_on_monday() {
        val anchor = LocalDate.of(2026, 1, 14)
        val (start, end) = PlanDate.weekRange(anchor)
        assertEquals(Monday, start.dayOfWeek)
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(start, end).toInt())
    }

    @Test
    fun monthGrid_is_42_cells_starting_on_monday_and_anchored_one_month() {
        val anchor = LocalDate.of(2026, 3, 17) // March 17 2026
        val grid = PlanDate.monthGrid(anchor)
        assertEquals(42, grid.size)
        assertEquals(Monday, grid.first().dayOfWeek)
        // The 42nd cell sits exactly 41 days after the first.
        assertEquals(41, java.time.temporal.ChronoUnit.DAYS.between(grid.first(), grid.last()).toInt())
    }

    @Test
    fun monthGrid_for_February_in_a_leap_year_includes_29th() {
        val anchor = LocalDate.of(2024, 2, 1)
        val grid = PlanDate.monthGrid(anchor)
        // February 2024 has 29 days; the grid must include Feb 29.
        val containsLeapDay = grid.any { it == LocalDate.of(2024, 2, 29) }
        assertTrue("2024 leap day must appear in monthGrid", containsLeapDay)
        // The grid covers the full ISO six-week window — confirm a March 1
        // Saturday sits at the bottom of the grid for 2024's Feb layout.
        val containsMarch1 = grid.any { it == LocalDate.of(2024, 3, 1) }
        assertTrue(containsMarch1)
    }

    @Test
    fun monthGrid_for_a_non_leap_February_does_not_include_Feb_29() {
        val anchor = LocalDate.of(2025, 2, 1)
        val grid = PlanDate.monthGrid(anchor)
        // Check that no cell in the rendered grid is labelled Feb 29, 2025
        // (which doesn't exist).
        for (date in grid) {
            assertFalse(
                "2025-02-29 must not appear in any monthGrid cell",
                date.year == 2025 && date.monthValue == 2 && date.dayOfMonth == 29
            )
        }
        // Feb 28 must appear.
        val containsFeb28 = grid.any { it == LocalDate.of(2025, 2, 28) }
        assertTrue(containsFeb28)
    }

    @Test
    fun monthGrid_handles_cross_month_year_boundary() {
        // Anchor on January — the leading cells should come from December
        // of the previous year and the trailing cells from February.
        val anchor = LocalDate.of(2026, 1, 1)
        val grid = PlanDate.monthGrid(anchor)
        assertTrue(grid.any { it.year == 2025 && it.month == Month.DECEMBER })
        assertTrue(grid.any { it.year == 2026 && it.month == Month.FEBRUARY })
    }

    @Test
    fun isSameMonth_is_an_anchor_aware_test() {
        val anchor = LocalDate.of(2026, 3, 17)
        assertTrue(PlanDate.isSameMonth(anchor, LocalDate.of(2026, 3, 1)))
        assertFalse(PlanDate.isSameMonth(anchor, LocalDate.of(2026, 2, 28)))
        assertFalse(PlanDate.isSameMonth(anchor, LocalDate.of(2027, 3, 1)))
    }

    @Test
    fun toLocalDate_and_toStartOfDayMs_round_trip() {
        val zone = utc
        val date = LocalDate.of(2026, 1, 15)
        val startMs = PlanDate.toStartOfDayMs(date, zone)
        val fromMs = PlanDate.toLocalDate(startMs, zone)
        assertEquals(date, fromMs)
    }

    @Test
    fun dayKey_and_parseDayKey_round_trip() {
        val date = LocalDate.of(2026, 1, 15)
        val key = PlanDate.dayKey(date)
        assertEquals("2026-01-15", key)
        assertEquals(date, PlanDate.parseDayKey(key))
    }

    @Test
    fun shiftDays_weeks_months_advance_the_local_date() {
        val anchor = LocalDate.of(2026, 6, 15)
        assertEquals(LocalDate.of(2026, 6, 22), PlanDate.shiftWeeks(anchor, 1))
        assertEquals(LocalDate.of(2026, 6, 16), PlanDate.shiftDays(anchor, 1))
        assertEquals(LocalDate.of(2026, 7, 15), PlanDate.shiftMonths(anchor, 1))
        assertNotEquals(anchor, PlanDate.shiftMonths(anchor, 1))
    }

    @Test
    fun toEndOfDayMs_returns_start_of_next_day() {
        val zone = utc
        val date = LocalDate.of(2026, 1, 15)
        val startMs = PlanDate.toStartOfDayMs(date, zone)
        val endMs = PlanDate.toEndOfDayMs(date, zone)
        // End-of-day ms must match the start of (date + 1).
        assertEquals(PlanDate.toStartOfDayMs(date.plusDays(1), zone), endMs)
        // And must be strictly after start-of-day ms.
        assertTrue(endMs > startMs)
    }

    @Test
    fun monthGrid_first_and_last_cell_have_the_same_year_for_an_anchored_month() {
        // Non-leap February 2026: Feb 1 is a Sunday; the grid starts on
        // Monday Jan 26 and ends on Sunday Mar 8 — a clean six-week grid.
        val anchor = LocalDate.of(2026, 2, 17)
        val grid = PlanDate.monthGrid(anchor)
        assertEquals(LocalDate.of(2026, 1, 26), grid.first())
        assertEquals(LocalDate.of(2026, 3, 8), grid.last())
    }

    private companion object {
        val Monday = java.time.DayOfWeek.MONDAY
    }
}
