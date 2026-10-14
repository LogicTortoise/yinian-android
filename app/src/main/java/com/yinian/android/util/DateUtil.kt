package com.yinian.android.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtil {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.US)
    private val weekDayFormat = SimpleDateFormat("EEE", Locale.US)

    fun startOfDay(timestamp: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun endOfDay(timestamp: Long): Long = startOfDay(timestamp) + 24L * 3600_000L

    fun dayLabel(timestamp: Long): String = dayFormat.format(Date(timestamp))
    fun timeLabel(timestamp: Long): String = timeFormat.format(Date(timestamp))
    fun dateTimeLabel(timestamp: Long): String = dateTimeFormat.format(Date(timestamp))
    fun weekDayLabel(timestamp: Long): String = weekDayFormat.format(Date(timestamp))

    fun startOfWeek(timestamp: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun addDays(timestamp: Long, days: Int): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        cal.add(Calendar.DAY_OF_YEAR, days)
        return cal.timeInMillis
    }

    fun weekRange(reference: Long): Pair<Long, Long> {
        val start = startOfWeek(reference)
        return start to addDays(start, 7)
    }

    fun formatMinutesOfDay(min: Int): String {
        val h = min / 60
        val m = min % 60
        return "%02d:%02d".format(h, m)
    }

    fun localNow(): Long = System.currentTimeMillis()

    fun isoDay(timestamp: Long): String = dayFormat.format(Date(timestamp))
    fun parseIsoDay(value: String): Long? = runCatching { dayFormat.parse(value)?.time }.getOrNull()
}

object DurationFormatter {
    fun formatMs(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}

object Clock {
    fun now(): Long = System.currentTimeMillis()
    fun tz(): TimeZone = TimeZone.getDefault()
}
