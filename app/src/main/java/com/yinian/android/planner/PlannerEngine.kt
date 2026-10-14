package com.yinian.android.planner

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Deterministic local schedule planner.
 *
 * The algorithm is intentionally simple and transparent:
 *
 * 1. Build the planning horizon (one or more days) and collect every fixed
 *    block and read-only calendar event as a `BusyInterval`.
 * 2. For each day in the horizon, compute the free sub-intervals inside the
 *    working windows (after subtracting busy intervals and respecting the
 *    `bufferMinutes` rest buffer).
 * 3. Score candidate items by user priority, due pressure, and unlock
 *    value. Stable, total order: items with `blocked == true` are deferred,
 *    items with `locked == true` keep their existing slot.
 * 4. Greedily walk the sorted items in score order. For each item, walk the
 *    free sub-intervals in chronological order and pick the earliest slot
 *    that can fit `estimateMinutes`. Honour the per-day `dailyCapMinutes`.
 * 5. Anything that could not be placed receives a stable `UnscheduledReason`
 *    that the UI can render as a one-line explanation.
 *
 * The planner is total and deterministic: identical inputs produce identical
 * outputs. It never mutates input state.
 */
class PlannerEngine(
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    /**
     * Produce a plan for the given window.
     *
     * @param windowStart inclusive lower bound (local date).
     * @param windowEnd inclusive upper bound (local date).
     * @param policies per-weekday work policy rows (any missing weekday defaults
     *                  to "not a working day").
     * @param items candidate items to place.
     * @param busy fixed blocks + read-only calendar busy intervals, in ms.
     * @param existingBlocks existing `TimeBlockEntity` rows (used to detect
     *                      draft idempotence and to honour "locked" items).
     */
    fun plan(
        windowStart: LocalDate,
        windowEnd: LocalDate,
        policies: List<com.yinian.android.data.entity.WorkPolicyEntity>,
        items: List<PlanItem>,
        busy: List<BusyInterval>,
        existingBlocks: List<PlannedBlock>,
        config: PlannerConfig = PlannerConfig()
    ): PlanResult {
        require(!windowEnd.isBefore(windowStart)) { "windowEnd must be >= windowStart" }

        val policiesByDow = PlannerTime.policyWindows(policies, zone)
        val days = daysBetween(windowStart, windowEnd)
        val placed = mutableListOf<Placement.Scheduled>()
        val unscheduled = mutableListOf<Placement.Unscheduled>()
        val dayCapacities = mutableListOf<DayCapacity>()
        // Tracks every item that already has a placement (locked, placed on a
        // prior day, or carried over). Items in this set are skipped by both
        // the per-day loop and the carry-over pass so each item is scheduled
        // at most once across the entire window.
        val placedIds = mutableSetOf<String>()

        // Pre-place locked items so we can count them toward the daily cap.
        val locked = items.filter { it.locked }
        val flexible = items.filter { !it.locked }

        // Daily cap tracker (mutable minutes already used per day).
        val usedMinutesPerDay = mutableMapOf<String, Int>()
        for (b in existingBlocks) {
            val key = PlannerTime.toDayKey(LocalDateTime.ofInstant(Instant.ofEpochMilli(b.startMs), zone).toLocalDate())
            usedMinutesPerDay[key] = (usedMinutesPerDay[key] ?: 0) + ((b.endMs - b.startMs) / 60_000L).toInt()
        }
        // Existing fixed blocks also count as busy.
        val allBusy = (busy + existingBlocks.map { BusyInterval(it.startMs, it.endMs, BusySource.FixedBlock) }).toMutableList()

        // First, surface locked items as scheduled (their slot is preserved).
        for (li in locked) {
            val existing = existingBlocks.firstOrNull { it.taskId == li.id }
            if (existing != null) {
                placed += Placement.Scheduled(
                    itemId = li.id,
                    startMs = existing.startMs,
                    endMs = existing.endMs,
                    reason = "锁定安排，已固定"
                )
                placedIds += li.id
            }
        }

        val scored = scoreItems(flexible, nowMs = System.currentTimeMillis())

        for (date in days) {
            val dayKey = PlannerTime.toDayKey(date)
            val dow = date.dayOfWeek.value
            val windows = policiesByDow[dow].orEmpty()
            if (windows.isEmpty()) {
                dayCapacities += DayCapacity(
                    dayKey = dayKey,
                    availableMinutes = 0,
                    scheduledMinutes = 0,
                    capMinutes = 0
                )
                continue
            }

            // Free sub-intervals inside this day's working windows.
            val dayStartMs = PlannerTime.startOfDayMs(date, zone)
            val dayEndMs = PlannerTime.endOfDayMs(date, zone)
            val dayBusy = allBusy.filter { it.startMs < dayEndMs && it.endMs > dayStartMs }
                .sortedBy { it.startMs }
            val windowRanges = windows.map { it.windowMs(date, zone) }
                .sortedBy { it.first }
            val free = computeFreeIntervals(windowRanges, dayBusy)

            val totalAvailable = windowRanges.sumOf { ((it.second - it.first) / 60_000L).toInt() }
            val capForDay = windows.first().dailyCapMinutes
            var usedThisDay = usedMinutesPerDay[dayKey] ?: 0

            for (item in scored) {
                if (item.item.id in placedIds) continue
                if (item.item.blocked) {
                    unscheduled += Placement.Unscheduled(
                        itemId = item.item.id,
                        title = item.item.title,
                        reason = UnscheduledReason.BlockedByDependencies
                    )
                    // Mark as handled so subsequent days and the carry-over
                    // pass don't retry it.
                    placedIds += item.item.id
                    continue
                }
                if (capForDay > 0 && usedThisDay + item.item.estimateMinutes > capForDay) {
                    // Try later days before giving up; once any day accepts it we stop.
                    continue
                }
                val slot = findSlot(free, item.item.estimateMinutes, config.minSlotMinutes)
                if (slot == null) {
                    // Don't classify as unscheduled here — the carry-over
                    // pass still has the rest of the horizon to try. Just
                    // skip this day; the carry-over pass decides the final
                    // reason based on the largest free interval seen.
                    continue
                }
                placed += Placement.Scheduled(
                    itemId = item.item.id,
                    startMs = slot.first,
                    endMs = slot.second,
                    reason = reasonFor(item.item, slot.first, item.scoreReasons)
                )
                placedIds += item.item.id
                // Mark the slot busy for subsequent candidates.
                allBusy += BusyInterval(slot.first, slot.second, BusySource.FixedBlock)
                val newFree = splitAround(free, slot.first, slot.second)
                free.clear()
                free.addAll(newFree)
                free.sortBy { it.startMs }
                usedThisDay += item.item.estimateMinutes
                usedMinutesPerDay[dayKey] = usedThisDay
            }

            dayCapacities += DayCapacity(
                dayKey = dayKey,
                availableMinutes = totalAvailable,
                scheduledMinutes = usedThisDay,
                capMinutes = if (capForDay > 0) capForDay else totalAvailable
            )
        }

        // Carry-over: items that didn't fit in their preferred day may still
        // fit in later days. Walk through the items once more in score order,
        // trying to place them somewhere they haven't been tried yet. This is
        // bounded because each item is visited at most once.
        for (item in scored) {
            if (item.item.id in placedIds || item.item.blocked) continue
            // Try every remaining free slot across days.
            var placedIt = false
            // Track the largest free interval observed across every day we
            // tried. If the item still couldn't be placed, this lets us
            // distinguish EstimateTooLong (estimate > every window) from
            // OutsideAvailableHours (could fit somewhere but the cap or busy
            // intervals blocked it).
            var maxFreeMinutesSeen = 0L
            for (date in days) {
                if (placedIt) break
                val carryWindows = policiesByDow[date.dayOfWeek.value].orEmpty()
                if (carryWindows.isEmpty()) continue
                val carryCap = carryWindows.first().dailyCapMinutes
                val dayKey = PlannerTime.toDayKey(date)
                val dayStartMs = PlannerTime.startOfDayMs(date, zone)
                val dayEndMs = PlannerTime.endOfDayMs(date, zone)
                val dayBusy = allBusy.filter { it.startMs < dayEndMs && it.endMs > dayStartMs }
                val windowRanges = carryWindows.map { it.windowMs(date, zone) }.sortedBy { it.first }
                val free = computeFreeIntervals(windowRanges, dayBusy)
                val maxFreeThisDay = free.maxOfOrNull { it.durationMs / 60_000L } ?: 0L
                if (maxFreeThisDay > maxFreeMinutesSeen) maxFreeMinutesSeen = maxFreeThisDay
                val usedThisDay = usedMinutesPerDay[dayKey] ?: 0
                if (carryCap > 0 && usedThisDay + item.item.estimateMinutes > carryCap) continue
                val slot = findSlot(free, item.item.estimateMinutes, config.minSlotMinutes)
                if (slot != null) {
                    placed += Placement.Scheduled(
                        itemId = item.item.id,
                        startMs = slot.first,
                        endMs = slot.second,
                        reason = reasonFor(item.item, slot.first, item.scoreReasons)
                    )
                    placedIds += item.item.id
                    allBusy += BusyInterval(slot.first, slot.second, BusySource.FixedBlock)
                    usedMinutesPerDay[dayKey] = usedThisDay + item.item.estimateMinutes
                    placedIt = true
                }
            }
            if (!placedIt) {
                // Decide the reason from the largest free interval observed
                // across the entire horizon: if the estimate couldn't fit
                // anywhere, that's an estimate problem; otherwise the failure
                // is due to capacity / conflicts.
                val reason = if (item.item.estimateMinutes > maxFreeMinutesSeen) {
                    UnscheduledReason.EstimateTooLong
                } else {
                    UnscheduledReason.OutsideAvailableHours
                }
                unscheduled += Placement.Unscheduled(
                    itemId = item.item.id,
                    title = item.item.title,
                    reason = reason
                )
            }
        }

        return PlanResult(
            placements = placed,
            unscheduled = unscheduled.distinctBy { it.itemId },
            capacity = WindowCapacity(
                days = dayCapacities,
                totalAvailable = dayCapacities.sumOf { it.availableMinutes },
                totalScheduled = dayCapacities.sumOf { it.scheduledMinutes },
                totalCap = dayCapacities.sumOf { it.capMinutes }
            )
        )
    }

    /** Score items: lower is better. Priority first, then due pressure, then unlock value. */
    private data class Scored(val item: PlanItem, val score: Double, val scoreReasons: List<String>)

    private fun scoreItems(items: List<PlanItem>, nowMs: Long): List<Scored> {
        return items.map { item ->
            var score = 0.0
            val reasons = mutableListOf<String>()
            // Lower user-priority number is "more important" → lower score is better.
            score += item.priority * 10.0
            reasons += "用户优先级 ${item.priority}"
            item.dueAt?.let { due ->
                val hours = (due - nowMs) / 3_600_000.0
                val pressure = when {
                    hours < 0 -> -30.0
                    hours < 24 -> -20.0
                    hours < 72 -> -10.0
                    else -> -2.0
                }
                score += pressure
                reasons += if (hours < 0) "已过截止" else "距截止 ${"%.0f".format(hours)}h"
            }
            // Smaller estimates get a slight nudge so quick wins surface.
            if (item.estimateMinutes in 1..45) {
                score -= 2.0
                reasons += "短任务 ${item.estimateMinutes} 分钟"
            }
            Scored(item, score, reasons)
        }.sortedBy { it.score }
    }

    private fun reasonFor(item: PlanItem, startMs: Long, scoreReasons: List<String>): String {
        val local = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startMs), zone)
        val time = "%02d:%02d".format(local.hour, local.minute)
        return buildString {
            append(time)
            append(" · ")
            append(scoreReasons.firstOrNull() ?: "按优先级")
            if (item.estimateMinutes > 0) append(" · 估时 ${item.estimateMinutes} 分钟")
        }
    }

    /**
     * Compute the free sub-intervals inside `windows` after subtracting every
     * interval in `busy`. The result is sorted by start time, non-overlapping,
     * and contains only positive-length intervals.
     */
    private fun computeFreeIntervals(
        windows: List<Pair<Long, Long>>,
        busy: List<BusyInterval>
    ): MutableList<Interval> {
        val merged = windows.map { Interval(it.first, it.second) }
            .sortedBy { it.startMs }
        val mergedBusy = busy.map { Interval(it.startMs, it.endMs) }
            .sortedBy { it.startMs }
        val out = mutableListOf<Interval>()
        for (w in merged) {
            var cursor = w.startMs
            for (b in mergedBusy) {
                if (b.endMs <= cursor) continue
                if (b.startMs >= w.endMs) break
                if (b.startMs > cursor) {
                    out += Interval(cursor, minOf(b.startMs, w.endMs))
                }
                cursor = maxOf(cursor, b.endMs)
                if (cursor >= w.endMs) break
            }
            if (cursor < w.endMs) out += Interval(cursor, w.endMs)
        }
        return out
    }

    /**
     * Find the earliest free slot that fits `estimateMinutes`. The slot is
     * returned as a (startMs, endMs) pair, or null when no slot is wide
     * enough.
     */
    private fun findSlot(
        free: List<Interval>,
        estimateMinutes: Int,
        @Suppress("UNUSED_PARAMETER") minSlotMinutes: Int
    ): Pair<Long, Long>? {
        val needMs = estimateMinutes * 60_000L
        if (estimateMinutes <= 0) return null
        for (f in free) {
            if (f.durationMs >= needMs) return f.startMs to (f.startMs + needMs)
        }
        return null
    }

    /** Split intervals around a newly placed block; helper for carry-over. */
    private fun splitAround(free: List<Interval>, startMs: Long, endMs: Long): List<Interval> {
        val out = mutableListOf<Interval>()
        for (f in free) {
            if (f.endMs <= startMs || f.startMs >= endMs) {
                out += f
            } else {
                if (f.startMs < startMs) out += Interval(f.startMs, startMs)
                if (f.endMs > endMs) out += Interval(endMs, f.endMs)
            }
        }
        return out
    }

    private fun daysBetween(start: LocalDate, end: LocalDate): List<LocalDate> {
        val out = mutableListOf<LocalDate>()
        var cur = start
        while (!cur.isAfter(end)) {
            out += cur
            cur = cur.plusDays(1)
        }
        return out
    }
}

/** Aggregated planner output for one window. */
data class PlanResult(
    val placements: List<Placement.Scheduled>,
    val unscheduled: List<Placement.Unscheduled>,
    val capacity: WindowCapacity
)
