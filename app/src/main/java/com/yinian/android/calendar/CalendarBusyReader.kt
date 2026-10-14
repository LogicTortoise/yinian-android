package com.yinian.android.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.yinian.android.planner.BusyInterval
import com.yinian.android.planner.BusySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only system calendar adapter.
 *
 * V2 only reads `CalendarContract.Instances` to surface external events as
 * busy intervals; it never writes back to the calendar and never adds
 * `WRITE_CALENDAR` to the manifest. Permission is requested only after the
 * user explicitly turns the integration on via the Me screen.
 *
 * Title/description are stripped before the rows cross into the planner —
 * the planner only sees a `BusyInterval` with no free-text payload.
 */
class CalendarBusyReader(private val context: Context) {

    /**
     * Reads calendar events in the half-open window `[startMs, endMs)`.
     * Returns an empty list when:
     *   - the user has not enabled the integration,
     *   - the `READ_CALENDAR` permission has not been granted,
     *   - there are no calendars or no events in the window.
     */
    suspend fun readBusyIntervals(startMs: Long, endMs: Long): List<BusyInterval> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()
        val resolver: ContentResolver = context.contentResolver
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMs.toString())
            .appendPath(endMs.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END
        )
        val out = mutableListOf<BusyInterval>()
        runCatching {
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                while (cursor.moveToNext()) {
                    val begin = cursor.getLong(beginIdx)
                    val end = cursor.getLong(endIdx)
                    if (end > begin) {
                        out += BusyInterval(begin, end, BusySource.Calendar)
                    }
                }
            }
        }
        out
    }

    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
}
