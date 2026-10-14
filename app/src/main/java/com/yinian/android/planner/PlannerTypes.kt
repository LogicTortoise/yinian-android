package com.yinian.android.planner

import com.yinian.android.data.entity.WorkPolicyEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Pure value types used by the deterministic local planner.
 *
 * The planner package is deliberately kept free of Android types (no Context,
 * Compose, CalendarContract) so the test child can exercise it from a plain
 * JVM unit test. The Android layer is responsible for converting `Long`
 * wall-clock timestamps and `TimeZone` defaults to these types before
 * calling into the planner.
 */

/** Inclusive lower bound and exclusive upper bound on a millisecond timeline. */
data class Interval(val startMs: Long, val endMs: Long) {
    init { require(endMs > startMs) { "endMs must be > startMs" } }
    val durationMs: Long get() = endMs - startMs
}

/** A read-only busy interval that the planner must treat as unavailable. */
data class BusyInterval(val startMs: Long, val endMs: Long, val source: BusySource)

enum class BusySource { FixedBlock, Calendar }

/**
 * Snapshot of a candidate item the planner can place. The Android layer
 * constructs these from `TaskEntity` + any `RecurringInstanceEntity` overrides;
 * the planner never touches Room directly.
 */
data class PlanItem(
    val id: String,
    val title: String,
    val estimateMinutes: Int,
    val priority: Int,
    val dueAt: Long?,
    /** IDs of tasks that must be `done` before this one can be scheduled. */
    val dependsOn: Set<String>,
    /** True when this item has a `depends_on` chain that has not yet been cleared. */
    val blocked: Boolean,
    /** True for items that should never be moved by the planner (e.g. a fixed
     *  block that already has an assigned slot in the window). */
    val locked: Boolean = false
)

/** Output of the planner: where to place each item, or why it could not be placed. */
sealed class Placement {
    data class Scheduled(
        val itemId: String,
        val startMs: Long,
        val endMs: Long,
        val reason: String
    ) : Placement()
    data class Unscheduled(
        val itemId: String,
        val title: String,
        val reason: UnscheduledReason
    ) : Placement()
}

/** Stable, human-readable explanations for why an item did not get a slot. */
sealed class UnscheduledReason {
    data object BlockedByDependencies : UnscheduledReason()
    data object OutsideAvailableHours : UnscheduledReason()
    data object DailyCapacityReached : UnscheduledReason()
    data class ConflictsWith(val source: BusySource, val title: String) : UnscheduledReason()
    data object EstimateTooLong : UnscheduledReason()
}

/**
 * Capacity summary for one day, used by the plan screen to explain "why
 * nothing more fits" without forcing the user to inspect the full placement
 * list.
 */
data class DayCapacity(
    val dayKey: String,
    val availableMinutes: Int,
    val scheduledMinutes: Int,
    val capMinutes: Int
) {
    val remainingMinutes: Int get() = (capMinutes - scheduledMinutes).coerceAtLeast(0)
    val overloaded: Boolean get() = capMinutes in 1 until scheduledMinutes
}

/**
 * Aggregated capacity for a multi-day planning window (typically a week).
 * `days` is ordered by `dayKey`.
 */
data class WindowCapacity(
    val days: List<DayCapacity>,
    val totalAvailable: Int,
    val totalScheduled: Int,
    val totalCap: Int
)

/** A placement decision for one block that needs to be inserted. */
data class PlannedBlock(
    val taskId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val fixed: Boolean = false,
    val draftId: String? = null,
    val recurringRuleId: String? = null,
    val instanceDate: String? = null
)

/**
 * Configuration knobs for the planner. Defaults match the V1 explicit
 * preferences (single working window, no buffer, no daily cap).
 */
data class PlannerConfig(
    val minSlotMinutes: Int = 15,
    /** If true, the planner may suggest splitting an estimate across two slots. */
    val allowSplitting: Boolean = false,
    /** Items with priority strictly less than this are treated as urgent. */
    val urgentPriorityThreshold: Int = 2
)

/** Helpers that translate local dates ↔ ms and policy rows ↔ working windows. */
object PlannerTime {
    /** A day-of-week bitmask where Mon=bit0..Sun=bit6. */
    fun dayMask(dow: DayOfWeek): Int = 1 shl (dow.value - 1)

    fun toDayKey(date: LocalDate): String = date.toString() // ISO yyyy-MM-dd

    fun dayKeyToLocalDate(key: String): LocalDate = LocalDate.parse(key)

    fun startOfDayMs(date: LocalDate, zone: ZoneId): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun endOfDayMs(date: LocalDate, zone: ZoneId): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun msOfDay(hour: Int, minute: Int): Int = hour * 60 + minute

    /**
     * Convert a list of `WorkPolicyEntity` rows (one per ISO weekday) into
     * the per-day working windows the planner reasons over. The output is
     * keyed by ISO day-of-week (1..7). Missing days default to disabled
     * (empty list of windows).
     */
    fun policyWindows(
        policies: List<WorkPolicyEntity>,
        zone: ZoneId
    ): Map<Int, List<DayWindow>> {
        val byDow = policies.associateBy { it.dayOfWeek }
        val out = mutableMapOf<Int, List<DayWindow>>()
        for (dow in 1..7) {
            val p = byDow[dow]
            out[dow] = if (p == null || !p.enabled) emptyList() else listOf(
                DayWindow(
                    dayOfWeek = dow,
                    startMin = p.startMin,
                    endMin = p.endMin,
                    bufferMinutes = p.bufferMinutes,
                    dailyCapMinutes = p.dailyCapMinutes
                )
            )
        }
        // Reference `zone` so the compiler does not warn about an unused parameter
        // when callers pass the local zone; actual conversion happens at the boundary.
        @Suppress("UNUSED_EXPRESSION") zone
        return out
    }
}

/**
 * A single working window inside one ISO weekday. `startMin`/`endMin` are
 * minutes-of-day in local time. `bufferMinutes` is the rest buffer that must
 * be left between two scheduled blocks; `dailyCapMinutes` is the upper bound
 * on the sum of planned minutes for this weekday (0 means no explicit cap).
 */
data class DayWindow(
    val dayOfWeek: Int,
    val startMin: Int,
    val endMin: Int,
    val bufferMinutes: Int = 0,
    val dailyCapMinutes: Int = 0
) {
    init {
        require(startMin in 0..1439) { "startMin out of range" }
        require(endMin in 1..1440) { "endMin out of range" }
        require(endMin > startMin) { "endMin must be > startMin" }
        require(bufferMinutes >= 0)
        require(dailyCapMinutes >= 0)
    }

    fun windowMs(date: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val startLocal = LocalDateTime.of(date, java.time.LocalTime.of(startMin / 60, startMin % 60))
        val endLocal = LocalDateTime.of(date, java.time.LocalTime.of(endMin / 60, endMin % 60))
        return startLocal.atZone(zone).toInstant().toEpochMilli() to
            endLocal.atZone(zone).toInstant().toEpochMilli()
    }
}

/** Common timezone utilities used by callers, kept here to keep the planner Android-free. */
object Zone {
    fun systemDefault(): ZoneId = ZoneId.systemDefault()
}
