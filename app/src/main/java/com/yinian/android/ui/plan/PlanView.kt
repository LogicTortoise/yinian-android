package com.yinian.android.ui.plan

import androidx.compose.runtime.Stable
import com.yinian.android.data.entity.TimeBlockEntity
import java.time.LocalDate

/** Calendar view modes surfaced on the Plan screen. */
enum class PlanViewMode {
    Day,
    Week,
    Month;

    companion object {
        /** Stable label used for content descriptions and tests. */
        fun label(mode: PlanViewMode): String = when (mode) {
            Day -> "日视图"
            Week -> "周视图"
            Month -> "月视图"
        }
    }
}

/** Unit of navigation for "previous / next" relative to a view mode. */
fun PlanViewMode.stepUnit(): Long = when (this) {
    PlanViewMode.Day -> 1L
    PlanViewMode.Week -> 7L
    PlanViewMode.Month -> 0L // Month nav is handled separately (variable length)
}

/**
 * Summary of one day's local plan content. `blockCount` is the number of
 * non-recurring, non-running application-owned blocks on that day; the
 * breakdown is exposed for badges.
 */
@Stable
data class DaySummary(
    val date: LocalDate,
    val blocks: List<TimeBlockEntity>,
    val hasFixed: Boolean,
    val hasRecurring: Boolean
) {
    val blockCount: Int get() = blocks.size
    val totalMinutes: Int get() = blocks.sumOf { ((it.endAt - it.startAt) / 60_000L).toInt() }
}

/** Aggregated summaries for a seven-day week starting on Monday. */
@Stable
data class WeekSummary(
    val start: LocalDate,
    val days: List<DaySummary>
) {
    val endExclusive: LocalDate get() = start.plusDays(7)
    val totalBlocks: Int get() = days.sumOf { it.blockCount }
    val totalMinutes: Int get() = days.sumOf { it.totalMinutes }
}

/** Aggregated summaries for one calendar month plus the visible 6×7 grid. */
@Stable
data class MonthSummary(
    val anchor: LocalDate,
    val grid: List<DaySummary>
) {
    val visibleStart: LocalDate get() = grid.first().date
    val visibleEndExclusive: LocalDate get() = grid.last().date.plusDays(1)
}

/**
 * Mutate the receiver by [days] in the local zone. Returns the new value.
 * Used by view-level navigation buttons.
 */
fun LocalDate.shiftBy(days: Long): LocalDate = plusDays(days)

/** Find the summary that matches [date] within a [WeekSummary] or [MonthSummary]. */
fun WeekSummary.dayOf(date: LocalDate): DaySummary? = days.firstOrNull { it.date == date }
fun MonthSummary.dayOf(date: LocalDate): DaySummary? = grid.firstOrNull { it.date == date }