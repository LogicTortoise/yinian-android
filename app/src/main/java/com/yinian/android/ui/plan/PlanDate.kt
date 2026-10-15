package com.yinian.android.ui.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date helpers used by the Plan screen's calendar views. All conversions go
 * through [java.time] so leap years, DST transitions and the local timezone
 * are handled by the platform rather than by ad-hoc [java.util.Calendar]
 * arithmetic.
 */
object PlanDate {
    private val dayKeyFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dayLabelFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    private val monthLabelFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy 年 MM 月", Locale.US)
    private val weekdayHeaderFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE", Locale.US)

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun toLocalDate(timestampMs: Long, zone: ZoneId = zone()): LocalDate =
        Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()

    fun toStartOfDayMs(date: LocalDate, zone: ZoneId = zone()): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun toEndOfDayMs(date: LocalDate, zone: ZoneId = zone()): Long =
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayKey(date: LocalDate): String = dayKeyFormatter.format(date)

    fun parseDayKey(value: String): LocalDate = LocalDate.parse(value, dayKeyFormatter)

    fun dayLabel(date: LocalDate): String = dayLabelFormatter.format(date)

    fun monthLabel(date: LocalDate): String = monthLabelFormatter.format(date)

    fun weekdayLabel(date: LocalDate): String = weekdayHeaderFormatter.format(date)

    /** Start-of-week (Monday) for the given reference date. */
    fun startOfWeek(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    /** Half-open week range `[startOfWeek, startOfWeek + 7 days)`. */
    fun weekRange(date: LocalDate): Pair<LocalDate, LocalDate> {
        val start = startOfWeek(date)
        return start to start.plusDays(7)
    }

    /** Six-row month grid (always 42 cells) starting on Monday. */
    fun monthGrid(monthAnchor: LocalDate): List<LocalDate> {
        val firstOfMonth = monthAnchor.withDayOfMonth(1)
        val gridStart = startOfWeek(firstOfMonth)
        return (0 until 42).map { gridStart.plusDays(it.toLong()) }
    }

    /** True when [day] belongs to the same calendar month as [anchor]. */
    fun isSameMonth(anchor: LocalDate, day: LocalDate): Boolean =
        anchor.year == day.year && anchor.month == day.month

    fun shiftDays(date: LocalDate, days: Long): LocalDate = date.plusDays(days)
    fun shiftWeeks(date: LocalDate, weeks: Long): LocalDate = date.plusWeeks(weeks)
    fun shiftMonths(date: LocalDate, months: Long): LocalDate = date.plusMonths(months)
}