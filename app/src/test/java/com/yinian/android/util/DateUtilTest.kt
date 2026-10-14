package com.yinian.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateUtilTest {

    /** Build a Calendar timestamp at a specific local-time hour/min. */
    private fun ts(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long {
        val cal = Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun `startOfDay zeroes the time component`() {
        val input = ts(2026, 6, 14, 13, 45)
        val sod = DateUtil.startOfDay(input)
        val cal = Calendar.getInstance().apply { timeInMillis = sod }
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
        assertEquals(0, cal.get(Calendar.MILLISECOND))
    }

    @Test
    fun `endOfDay is 24 hours after startOfDay`() {
        val input = ts(2026, 6, 14, 13, 45)
        val sod = DateUtil.startOfDay(input)
        assertEquals(sod + 24L * 3600_000L, DateUtil.endOfDay(input))
    }

    @Test
    fun `startOfWeek places the timestamp on a Monday at midnight`() {
        val input = ts(2026, 6, 17, 10, 0) // a Wednesday in 2026
        val sow = DateUtil.startOfWeek(input)
        val cal = Calendar.getInstance().apply { timeInMillis = sow }
        assertEquals(Calendar.MONDAY, cal.get(Calendar.DAY_OF_WEEK))
        assertEquals(0, cal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, cal.get(Calendar.MINUTE))
    }

    @Test
    fun `addDays shifts the date by N whole days`() {
        val base = ts(2026, 6, 14, 9, 0)
        val plus3 = DateUtil.addDays(base, 3)
        val minus2 = DateUtil.addDays(base, -2)
        assertEquals(ts(2026, 6, 17, 9, 0), plus3)
        assertEquals(ts(2026, 6, 12, 9, 0), minus2)
    }

    @Test
    fun `weekRange returns a 7-day window beginning on the same week's Monday`() {
        val reference = ts(2026, 6, 17, 14, 30) // a Wednesday
        val (start, end) = DateUtil.weekRange(reference)
        assertEquals(DateUtil.startOfWeek(reference), start)
        assertEquals(start + 7L * 24 * 3600_000L, end)
    }

    @Test
    fun `formatMinutesOfDay zero-pads both fields`() {
        assertEquals("00:00", DateUtil.formatMinutesOfDay(0))
        assertEquals("09:05", DateUtil.formatMinutesOfDay(9 * 60 + 5))
        assertEquals("23:59", DateUtil.formatMinutesOfDay(23 * 60 + 59))
    }

    @Test
    fun `isoDay round-trips with parseIsoDay`() {
        val input = ts(2026, 1, 9, 12, 30)
        val iso = DateUtil.isoDay(input)
        assertEquals("2026-01-09", iso)
        val parsed = DateUtil.parseIsoDay(iso)!!
        assertEquals(DateUtil.startOfDay(input), parsed)
    }

    @Test
    fun `parseIsoDay returns null for garbage`() {
        assertEquals(null, DateUtil.parseIsoDay("not a date"))
    }
}

class DurationFormatterTest {

    @Test
    fun `formatMs formats sub-minute durations as mm_ss`() {
        assertEquals("00:00", DurationFormatter.formatMs(0L))
        assertEquals("00:09", DurationFormatter.formatMs(9_000L))
        assertEquals("01:00", DurationFormatter.formatMs(60_000L))
        assertEquals("12:34", DurationFormatter.formatMs(12 * 60_000L + 34_000L))
    }

    @Test
    fun `formatMs formats hour-plus durations as h_mm_ss`() {
        assertEquals("1:00:00", DurationFormatter.formatMs(3_600_000L))
        assertEquals("1:02:03", DurationFormatter.formatMs(3_600_000L + 2 * 60_000L + 3_000L))
        assertEquals("10:00:00", DurationFormatter.formatMs(10L * 3_600_000L))
    }

    @Test
    fun `formatMs clamps negative input to zero`() {
        assertEquals("00:00", DurationFormatter.formatMs(-5_000L))
    }

    @Test
    fun `formatMs handles sub-second precision by truncation`() {
        // 1.999 seconds -> "00:01"
        assertEquals("00:01", DurationFormatter.formatMs(1_999L))
    }
}