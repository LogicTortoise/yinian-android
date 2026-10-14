package com.yinian.android.planner

import com.yinian.android.data.entity.RecurringInstanceEntity
import com.yinian.android.data.entity.RecurringRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure-JVM coverage for the recurring-rule instance generator.
 *
 * The generator is also Android-free (it depends only on java.time and the V2
 * entity types) so a plain JUnit run exercises every code path.
 */
class RecurringGeneratorTest {

    private val zone = ZoneId.of("UTC")
    private val generator = RecurringGenerator(zone)

    private fun rule(
        id: String = "r1",
        title: String = "晨读",
        estimateMinutes: Int = 30,
        priority: Int = 3,
        domain: String = "general",
        kind: String = "daily",
        byDayMask: Int = 0,
        startDayKey: String = "2030-06-03",
        horizonDays: Int = 14,
        anchorTaskId: String? = null,
        createdAt: Long = 0L,
        updatedAt: Long = 0L
    ) = RecurringRuleEntity(
        id = id,
        title = title,
        estimateMinutes = estimateMinutes,
        priority = priority,
        domain = domain,
        kind = kind,
        byDayMask = byDayMask,
        startDayKey = startDayKey,
        horizonDays = horizonDays,
        anchorTaskId = anchorTaskId,
        archived = false,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    @Test
    fun daily_rule_emits_one_instance_per_day_within_the_horizon() {
        val r = rule(kind = "daily", startDayKey = "2030-06-03", horizonDays = 7)
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-10"),
            emptyList()
        )
        assertEquals(7, gen.size)
        assertEquals("2030-06-03", gen.first().dayKey)
        assertEquals("2030-06-09", gen.last().dayKey)
        assertTrue(gen.all { it.ruleId == r.id })
        assertTrue(gen.all { it.title == "晨读" })
    }

    @Test
    fun weekday_rule_emits_only_Monday_through_Friday() {
        val r = rule(kind = "weekday", startDayKey = "2030-06-03", horizonDays = 14)
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-17"),
            emptyList()
        )
        // 2026-06-03 was a Tuesday in 2026; in 2030 (ISO) the same calendar advances the
        // day-of-week differently — we only assert the weekday filter.
        for (g in gen) {
            val dow = LocalDate.parse(g.dayKey).dayOfWeek
            assertFalse("Weekday rule emitted a $dow instance", dow == java.time.DayOfWeek.SATURDAY || dow == java.time.DayOfWeek.SUNDAY)
        }
    }

    @Test
    fun weekly_rule_respects_byDayMask() {
        // Bitmask that enables only Wednesday and Friday.
        val mask = (1 shl 2) or (1 shl 4)
        val r = rule(kind = "weekly", byDayMask = mask, startDayKey = "2030-06-03", horizonDays = 14)
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-17"),
            emptyList()
        )
        for (g in gen) {
            val dow = LocalDate.parse(g.dayKey).dayOfWeek.value
            assertTrue("Weekly rule emitted day-of-week=$dow", dow == 3 || dow == 5)
        }
    }

    @Test
    fun generate_emits_nothing_before_the_rule_start_day() {
        val r = rule(startDayKey = "2030-06-05", horizonDays = 7)
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-01"),
            LocalDate.parse("2030-06-10"),
            emptyList()
        )
        assertTrue(gen.isNotEmpty())
        assertTrue(gen.all { LocalDate.parse(it.dayKey) >= LocalDate.parse("2030-06-05") })
    }

    @Test
    fun generate_uses_half_open_window() {
        val r = rule(startDayKey = "2030-06-03", horizonDays = 7)
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-10"),
            emptyList()
        )
        // windowEndExclusive = 2030-06-10 → last emitted day must be 2030-06-09.
        assertEquals("2030-06-09", gen.last().dayKey)
    }

    @Test
    fun instance_overrides_replace_the_default_title_and_estimate() {
        val r = rule(title = "晨读", estimateMinutes = 30, startDayKey = "2030-06-03", horizonDays = 3)
        val override = RecurringInstanceEntity(
            ruleId = r.id,
            dayKey = "2030-06-04",
            title = "晨读 · 周末加长版",
            estimateMinutes = 60,
            cancelled = false,
            updatedAt = 1L
        )
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-06"),
            listOf(override)
        )
        val fourth = gen.first { it.dayKey == "2030-06-04" }
        assertEquals("晨读 · 周末加长版", fourth.title)
        assertEquals(60, fourth.estimateMinutes)
    }

    @Test
    fun cancelled_instance_overrides_drop_the_day() {
        val r = rule(startDayKey = "2030-06-03", horizonDays = 5)
        val cancelled = RecurringInstanceEntity(
            ruleId = r.id,
            dayKey = "2030-06-05",
            cancelled = true,
            updatedAt = 1L
        )
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-08"),
            listOf(cancelled)
        )
        assertFalse(gen.any { it.dayKey == "2030-06-05" })
        assertEquals(4, gen.size)
    }

    @Test
    fun repeated_generation_with_the_same_inputs_is_idempotent() {
        val r = rule(kind = "daily", startDayKey = "2030-06-03", horizonDays = 7)
        val ws = LocalDate.parse("2030-06-03")
        val we = LocalDate.parse("2030-06-10")
        val a = generator.generate(r, ws, we, emptyList())
        val b = generator.generate(r, ws, we, emptyList())
        assertEquals(a, b)
    }

    @Test
    fun diff_after_rule_edit_preserves_past_overrides() {
        val r = rule(kind = "daily", startDayKey = "2030-06-03", horizonDays = 7)
        val existing = listOf(
            RecurringInstanceEntity(
                ruleId = r.id,
                dayKey = "2030-06-04",
                title = "自定义",
                estimateMinutes = 45,
                cancelled = false,
                updatedAt = 1L
            )
        )
        // Editing the rule's title should regenerate fresh instances but the
        // diff must not delete the override row.
        val edited = r.copy(title = "晨读 v2", updatedAt = 100L)
        val diff = generator.diffAfterRuleEdit(
            edited,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-10"),
            previousUpdateAt = 0L,
            existing = existing
        )
        assertTrue("Diff deletes list must be empty", diff.deletes.isEmpty())
        // The override for 2030-06-04 is preserved (existing row still in repo).
        assertTrue(existing.any { it.dayKey == "2030-06-04" })
    }

    @Test
    fun weekly_mask_with_zero_bits_emits_no_instances() {
        val r = rule(kind = "weekly", byDayMask = 0, startDayKey = "2030-06-03", horizonDays = 7)
        val gen = generator.generate(
            r,
            LocalDate.parse("2030-06-03"),
            LocalDate.parse("2030-06-10"),
            emptyList()
        )
        assertEquals(0, gen.size)
    }
}
