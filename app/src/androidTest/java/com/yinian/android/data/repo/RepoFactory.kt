package com.yinian.android.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yinian.android.audio.AudioRecorder
import com.yinian.android.calendar.CalendarBusyReader
import com.yinian.android.data.prefs.PrefsRepository

/**
 * Build a repository using the device's real app context (obtained via
 * `ApplicationProvider`). PrefsRepository and AudioRecorder get real Android
 * Contexts — neither property is touched by the repository code under test, so
 * no file IO is performed.
 *
 * The factory is intentionally thin; the fake DAOs are defined in `TestFakes.kt`
 * alongside the V1 suites. V2 added four DAOs (work policy, recurring rule,
 * recurring instance, schedule draft) plus a calendar busy reader wired
 * against the device context — the emulator has no calendars so
 * `readBusyIntervals` returns an empty list.
 */
internal fun makeRepository(): YinianRepository {
    val context: Context = ApplicationProvider.getApplicationContext()
    return YinianRepository(
        ideaDao = FakeIdeaDao(),
        taskDao = FakeTaskDao(),
        relationDao = FakeRelationDao(),
        timeBlockDao = FakeTimeBlockDao(),
        focusDao = FakeFocusDao(),
        workPolicyDao = FakeWorkPolicyDao(),
        recurringRuleDao = FakeRecurringRuleDao(),
        recurringInstanceDao = FakeRecurringInstanceDao(),
        scheduleDraftDao = FakeScheduleDraftDao(),
        prefs = PrefsRepository(context),
        audioRecorder = AudioRecorder(context),
        calendarBusyReader = CalendarBusyReader(context)
    )
}

/**
 * Build a repository whose AudioRecorder is constructed with the same Context
 * used by the production app, so `deleteIdea` correctly resolves and removes
 * the recording file under the app's private files directory.
 */
internal fun makeRepositoryForFiles(): YinianRepository = makeRepository()
