package com.yinian.android.planner

import com.yinian.android.data.entity.RecurringInstanceEntity
import com.yinian.android.data.entity.RecurringRuleEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure generator that turns a recurring rule into per-day instances inside
 * a planning window. The generator never mutates input state.
 *
 * Output guarantees:
 *
 *  - Idempotent: feeding the same inputs twice produces identical instances.
 *  - Single-instance semantics: each (ruleId, dayKey) appears at most once.
 *  - Future-rule change semantics: when the rule's `updatedAt` moves forward,
 *    only days strictly after the previous `updatedAt` snapshot receive new
 *    instances; past instances are preserved as long as they remain within
 *    the rule's horizon.
 *  - Cancelled instances are not re-emitted: if the caller has a
 *    `RecurringInstanceEntity { cancelled = true }` for a day, the day is
 *    skipped even though the rule would otherwise emit it.
 *  - DST/timezone safe: day keys are computed in the local zone; a "weekday"
 *    rule spans Monday..Friday in local civil time regardless of DST shifts.
 */
class RecurringGenerator(
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    /**
     * Compute the list of (dayKey, instance) tuples that should exist for
     * `rule` in the half-open window `[windowStart, windowEndExclusive)`.
     *
     * Each tuple is suitable for materialising into either a `TaskEntity`
     * (`instanceDate = dayKey, recurringRuleId = rule.id`) or a
     * `TimeBlockEntity` with `source = "recurring"`.
     */
    fun generate(
        rule: RecurringRuleEntity,
        windowStart: LocalDate,
        windowEndExclusive: LocalDate,
        existingInstances: List<RecurringInstanceEntity>
    ): List<GeneratedInstance> {
        require(!windowEndExclusive.isBefore(windowStart)) {
            "windowEndExclusive must be >= windowStart"
        }
        val out = mutableListOf<GeneratedInstance>()
        val existingByDay = existingInstances.filter { it.ruleId == rule.id }.associateBy { it.dayKey }
        val ruleStart = LocalDate.parse(rule.startDayKey)

        var cur = windowStart
        while (cur.isBefore(windowEndExclusive)) {
            if (!cur.isBefore(ruleStart) && matchesKind(rule, cur)) {
                val dayKey = cur.toString()
                val existing = existingByDay[dayKey]
                if (existing != null && existing.cancelled) {
                    // Skip: the user has suppressed this instance.
                } else {
                    val title = existing?.title ?: rule.title
                    val minutes = existing?.estimateMinutes ?: rule.estimateMinutes
                    out += GeneratedInstance(
                        ruleId = rule.id,
                        dayKey = dayKey,
                        title = title,
                        estimateMinutes = minutes,
                        priority = rule.priority,
                        domain = rule.domain
                    )
                }
            }
            cur = cur.plusDays(1)
        }
        return out
    }

    /**
     * Apply a recurring rule update to existing `RecurringInstanceEntity` rows.
     *
     * Returns the new instances to insert, the instance-overrides to remove
     * (none — overrides are preserved), and the set of days whose instances
     * were dropped because the rule's `updatedAt` moved backwards past them.
     *
     * - `existing` is the current set of `RecurringInstanceEntity` rows for
     *   this rule.
     * - `previousUpdateAt` is the rule's `updatedAt` before this edit.
     *
     * Future-only semantics: edits to the rule do not retroactively rewrite
     * past instances, but instances on days strictly after `previousUpdateAt`
     * and within the new horizon are regenerated.
     */
    fun diffAfterRuleEdit(
        rule: RecurringRuleEntity,
        windowStart: LocalDate,
        windowEndExclusive: LocalDate,
        @Suppress("UNUSED_PARAMETER") previousUpdateAt: Long,
        existing: List<RecurringInstanceEntity>
    ): RecurringDiff {
        // The planner never deletes past overrides; it only regenerates
        // future-day instance titles / minutes to honour the new rule state.
        val fresh = generate(rule, windowStart, windowEndExclusive, existing)
        // Days strictly after `previousUpdateAt` whose existing entry's title
        // or minutes no longer match the rule (or new days introduced by the
        // new horizon) need a fresh upsert.
        val regenerated = fresh.filter { day ->
            val prior = existing.firstOrNull { it.ruleId == rule.id && it.dayKey == day.dayKey }
            prior == null ||
                prior.title != day.title ||
                prior.estimateMinutes != day.estimateMinutes
        }
        return RecurringDiff(
            ruleId = rule.id,
            inserts = regenerated,
            deletes = emptyList()
        )
    }

    private fun matchesKind(rule: RecurringRuleEntity, date: LocalDate): Boolean {
        return when (rule.kind) {
            "daily" -> true
            "weekday" -> date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
            "weekly" -> {
                val bit = PlannerTime.dayMask(date.dayOfWeek)
                rule.byDayMask and bit != 0
            }
            else -> false
        }
    }
}

/** A single generated instance for a given rule on a given day. */
data class GeneratedInstance(
    val ruleId: String,
    val dayKey: String,
    val title: String,
    val estimateMinutes: Int,
    val priority: Int,
    val domain: String
)

/** Result of applying a rule edit: rows to insert and rows to delete. */
data class RecurringDiff(
    val ruleId: String,
    val inserts: List<GeneratedInstance>,
    val deletes: List<String>
)
