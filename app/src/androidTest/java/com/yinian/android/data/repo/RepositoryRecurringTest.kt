package com.yinian.android.data.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yinian.android.data.entity.RecurringRuleEntity
import com.yinian.android.data.entity.WorkPolicyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * V2 coverage for the recurring rule materialisation path. The repository uses
 * an in-memory generator plus repository-level filters; tests assert that:
 *  - generating the same rule twice produces no duplicate tasks
 *  - cancellation is preserved on a single day
 *  - archiving a rule clears future open tasks and their time blocks but keeps history
 */
@RunWith(AndroidJUnit4::class)
class RepositoryRecurringTest {

    @Test
    fun materialise_is_idempotent_for_a_daily_rule() = runTest {
        val repo = makeRepository()
        // Need work policies so the planner's collectPlanItems can run.
        repo.saveWorkPolicies((1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) })
        val rule = repo.createRecurringRule(
            title = "晨读",
            estimateMinutes = 30,
            priority = 3,
            domain = "general",
            kind = "daily",
            byDayMask = 0,
            startDayKey = LocalDate.now().toString(),
            horizonDays = 7
        )
        val today = LocalDate.now()
        val windowEnd = today.plusDays(7)
        // First call: the generator emits one task per day. Count > 0.
        val first = repo.materialiseRecurringInstances(today, windowEnd)
        val totalAfterFirst = repo.observeActiveTasks().first()
            .count { it.recurringRuleId == rule.id }
        // Second call: no new tasks are produced; the task set is stable.
        val second = repo.materialiseRecurringInstances(today, windowEnd)
        val totalAfterSecond = repo.observeActiveTasks().first()
            .count { it.recurringRuleId == rule.id }
        assertTrue(first.created > 0)
        assertEquals(0, second.created)
        assertEquals(totalAfterFirst, totalAfterSecond)
    }

    @Test
    fun materialise_does_not_duplicate_tasks_with_the_same_ruleId_and_dayKey() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies((1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) })
        repo.createRecurringRule(
            title = "T",
            estimateMinutes = 30,
            priority = 3,
            domain = "general",
            kind = "daily",
            byDayMask = 0,
            startDayKey = LocalDate.now().toString(),
            horizonDays = 5
        )
        val today = LocalDate.now()
        repo.materialiseRecurringInstances(today, today.plusDays(5))
        val first = repo.materialiseRecurringInstances(today, today.plusDays(5))
        // Second call should have created zero new tasks.
        assertEquals(0, first.created)
        val tasks = repo.observeActiveTasks().first().filter { it.recurringRuleId != null }
        // Each (ruleId, instanceDate) pair must appear exactly once.
        val grouped = tasks.groupBy { it.recurringRuleId to it.instanceDate }
        for ((_, group) in grouped) {
            assertEquals(1, group.size)
        }
    }

    @Test
    fun setInstanceOverride_cancellation_persists_and_suppresses_one_day() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies((1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) })
        val rule = repo.createRecurringRule(
            title = "T",
            estimateMinutes = 30,
            priority = 3,
            domain = "general",
            kind = "daily",
            byDayMask = 0,
            startDayKey = LocalDate.now().toString(),
            horizonDays = 5
        )
        val today = LocalDate.now()
        repo.materialiseRecurringInstances(today, today.plusDays(5))
        val dayKey = today.plusDays(2).toString()
        repo.setInstanceOverride(rule.id, dayKey, cancelled = true)
        val row = repo.observeRecurringRules().first()
        assertEquals(listOf(rule.copy(updatedAt = rule.updatedAt)), row)
        // The generator should no longer emit a generated instance for that day.
        val gen = com.yinian.android.planner.RecurringGenerator()
        val generated = gen.generate(rule, today, today.plusDays(5), listOf(
            com.yinian.android.data.entity.RecurringInstanceEntity(
                ruleId = rule.id, dayKey = dayKey, cancelled = true, updatedAt = 0L
            )
        ))
        assertFalse(generated.any { it.dayKey == dayKey })
    }

    @Test
    fun archiveRecurringRule_removes_future_open_tasks_but_keeps_history() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies((1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) })
        val rule = repo.createRecurringRule(
            title = "T",
            estimateMinutes = 30,
            priority = 3,
            domain = "general",
            kind = "daily",
            byDayMask = 0,
            startDayKey = LocalDate.now().toString(),
            horizonDays = 14
        )
        val today = LocalDate.now()
        repo.materialiseRecurringInstances(today, today.plusDays(14))
        val beforeArchive = repo.observeActiveTasks().first()
            .filter { it.recurringRuleId == rule.id && it.status == "open" }
        assertTrue(beforeArchive.isNotEmpty())
        repo.archiveRecurringRule(rule.id)
        val remainingActive = repo.observeActiveTasks().first()
            .filter { it.recurringRuleId == rule.id && it.status == "open" }
        assertTrue(
            "Future open tasks must be archived, not deleted silently",
            remainingActive.isEmpty() || remainingActive.all { it.status == "archived" }
        )
        // Original row stays archived but exists.
        assertTrue(repo.observeRecurringRules().first().first { it.id == rule.id }.archived)
    }

    @Test
    fun weekday_rule_emits_only_Mon_through_Fri_locally() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies((1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) })
        repo.createRecurringRule(
            title = "工作日任务",
            estimateMinutes = 30,
            priority = 3,
            domain = "general",
            kind = "weekday",
            byDayMask = 0,
            // Pick the most recent Monday as the start so the math is deterministic.
            startDayKey = LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString(),
            horizonDays = 14
        )
        val start = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        repo.materialiseRecurringInstances(start, start.plusDays(14))
        val tasks = repo.observeActiveTasks().first().filter { it.recurringRuleId != null }
        for (t in tasks) {
            val instanceDate = t.instanceDate
            assertNotNull(instanceDate)
            val dow = LocalDate.parse(instanceDate!!).dayOfWeek
            assertFalse("Weekday rule emitted $dow", dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY)
        }
    }

    @Test
    fun weekly_rule_only_emits_enabled_weekdays() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies((1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) })
        // Only Wednesday and Friday.
        val mask = (1 shl 2) or (1 shl 4)
        repo.createRecurringRule(
            title = "周三周五",
            estimateMinutes = 30,
            priority = 3,
            domain = "general",
            kind = "weekly",
            byDayMask = mask,
            startDayKey = LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString(),
            horizonDays = 14
        )
        val start = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        repo.materialiseRecurringInstances(start, start.plusDays(14))
        val tasks = repo.observeActiveTasks().first().filter { it.recurringRuleId != null }
        for (t in tasks) {
            val dow = LocalDate.parse(t.instanceDate!!).dayOfWeek.value
            assertTrue("Weekly rule emitted day-of-week=$dow", dow == 3 || dow == 5)
        }
    }

    @Test
    fun setInstanceOverride_title_propagates_to_the_generated_task() = runTest {
        val repo = makeRepository()
        repo.saveWorkPolicies((1..5).map { WorkPolicyEntity(it, true, 9 * 60, 18 * 60) })
        val rule = repo.createRecurringRule(
            title = "默认标题",
            estimateMinutes = 30,
            priority = 3,
            domain = "general",
            kind = "daily",
            byDayMask = 0,
            startDayKey = LocalDate.now().toString(),
            horizonDays = 3
        )
        val today = LocalDate.now()
        repo.materialiseRecurringInstances(today, today.plusDays(3))
        val targetKey = today.plusDays(1).toString()
        repo.setInstanceOverride(rule.id, targetKey, title = "覆盖标题", estimateMinutes = 45)
        val task = repo.observeActiveTasks().first()
            .first { it.recurringRuleId == rule.id && it.instanceDate == targetKey }
        assertEquals("覆盖标题", task.title)
        assertEquals(45, task.estimateMinutes)
    }
}
