package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinian.android.data.entity.WorkPolicyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * V2 coverage for per-weekday work policy persistence and weekly capacity.
 *
 * The repository stores rows in `work_policies` keyed by ISO day-of-week and
 * exposes a one-shot capacity snapshot via `computeWeekCapacity`. The fake DAO
 * preserves `deleteAll() + upsert()` ordering; the production code relies on
 * that ordering to reset a weekday by writing a disabled row.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryWorkPolicyTest {

    @Test
    fun saveWorkPolicies_replaces_every_row() = runTest {
        val repo = makeRepository()
        val initial = listOf(
            WorkPolicyEntity(1, true, 9 * 60, 18 * 60),
            WorkPolicyEntity(2, true, 10 * 60, 17 * 60)
        )
        repo.saveWorkPolicies(initial)
        val observed = repo.observeWorkPolicies().first()
        assertEquals(2, observed.size)

        // Now overwrite with one row — the second weekday is gone.
        val after = listOf(WorkPolicyEntity(3, true, 13 * 60, 22 * 60, bufferMinutes = 10, dailyCapMinutes = 360))
        repo.saveWorkPolicies(after)
        val observedAgain = repo.observeWorkPolicies().first()
        assertEquals(1, observedAgain.size)
        assertEquals(3, observedAgain[0].dayOfWeek)
    }

    @Test
    fun saveWorkPolicies_persists_bufferMinutes_and_dailyCap() = runTest {
        val repo = makeRepository()
        val p = WorkPolicyEntity(1, true, 9 * 60, 18 * 60, bufferMinutes = 15, dailyCapMinutes = 8 * 60)
        repo.saveWorkPolicies(listOf(p))
        val row = repo.observeWorkPolicies().first().single()
        assertEquals(15, row.bufferMinutes)
        assertEquals(8 * 60, row.dailyCapMinutes)
    }

    @Test
    fun computeWeekCapacity_totals_match_enabled_weekdays() = runTest {
        val repo = makeRepository()
        // Mon–Fri 9-18 (9h each), Sat/Sun disabled.
        val policies = (1..5).map {
            WorkPolicyEntity(it, true, 9 * 60, 18 * 60, dailyCapMinutes = 9 * 60)
        } + listOf(
            WorkPolicyEntity(6, false, 9 * 60, 18 * 60),
            WorkPolicyEntity(7, false, 9 * 60, 18 * 60)
        )
        repo.saveWorkPolicies(policies)

        val snap = repo.computeWeekCapacity()
        // Total available = 5 * 9h = 45h = 2700 minutes.
        assertEquals(5 * 9 * 60, snap.totalAvailable)
        assertEquals(5 * 9 * 60, snap.totalCap)
        // The snapshot must report exactly the 7 weekday rows (Mon..Sun, ISO order).
        assertEquals(7, snap.days.size)
        // The first five rows (Mon..Fri) contribute the budget; the last two (Sat..Sun) contribute zero.
        // Use the row index instead of date substring math to avoid "-06" colliding with month 06.
        assertEquals(0, snap.days[5].availableMinutes)
        assertEquals(0, snap.days[6].availableMinutes)
        assertEquals(9 * 60, snap.days[0].availableMinutes)
    }

    @Test
    fun computeWeekCapacity_counts_only_non_fixed_or_recurring_blocks_as_scheduled() = runTest {
        val repo = makeRepository()
        val policies = (1..5).map {
            WorkPolicyEntity(it, true, 9 * 60, 18 * 60, dailyCapMinutes = 9 * 60)
        }
        repo.saveWorkPolicies(policies)
        // Pick a workday inside the current week; insert a 60-min non-fixed block.
        val date = java.time.LocalDate.now()
        val dow = date.dayOfWeek.value
        val start = date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 10 * 3600_000L
        repo.addManualTimeBlock(taskId = null, title = "普通块", startAt = start, endAt = start + 60 * 60_000L, fixed = false)
        repo.addManualTimeBlock(taskId = null, title = "固定块", startAt = start + 2 * 3600_000L, endAt = start + 3 * 3600_000L, fixed = true)
        val snap = repo.computeWeekCapacity()
        val day = snap.days.first { it.dayKey == date.toString() }
        if (dow in 1..5) {
            // 60 min of non-fixed block counts; 60 min of fixed block does not.
            assertEquals(60, day.scheduledMinutes)
        } else {
            // Weekend: the test policy disables weekends, so available stays 0.
            assertEquals(0, day.availableMinutes)
        }
    }

    @Test
    fun saveWorkPolicies_overwriting_a_disabled_day_works() = runTest {
        val repo = makeRepository()
        // Initial: Sat enabled. After save: Sat disabled.
        repo.saveWorkPolicies(listOf(WorkPolicyEntity(6, true, 9 * 60, 12 * 60)))
        assertTrue(repo.observeWorkPolicies().first().any { it.dayOfWeek == 6 && it.enabled })
        repo.saveWorkPolicies(listOf(WorkPolicyEntity(6, false, 9 * 60, 12 * 60)))
        val now = repo.observeWorkPolicies().first()
        assertTrue(now.any { it.dayOfWeek == 6 && !it.enabled })
    }
}
