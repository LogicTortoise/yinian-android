package com.yinian.android.data

import android.content.Context
import com.yinian.android.audio.AudioRecorder
import com.yinian.android.calendar.CalendarBusyReader
import com.yinian.android.data.db.AppDatabase
import com.yinian.android.data.prefs.PrefsRepository
import com.yinian.android.data.repo.YinianRepository

interface AppContainer {
    val database: AppDatabase
    val repository: YinianRepository
    val prefsRepo: PrefsRepository
    val audioRecorder: AudioRecorder
    val calendarBusyReader: CalendarBusyReader
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val appContext = context.applicationContext
    override val database: AppDatabase = AppDatabase.get(appContext)
    private val prefs = PrefsRepository(appContext)
    override val audioRecorder: AudioRecorder = AudioRecorder(appContext)
    override val prefsRepo: PrefsRepository = prefs
    override val calendarBusyReader: CalendarBusyReader = CalendarBusyReader(appContext)
    override val repository: YinianRepository = YinianRepository(
        ideaDao = database.ideaDao(),
        taskDao = database.taskDao(),
        relationDao = database.relationDao(),
        timeBlockDao = database.timeBlockDao(),
        focusDao = database.focusDao(),
        workPolicyDao = database.workPolicyDao(),
        recurringRuleDao = database.recurringRuleDao(),
        recurringInstanceDao = database.recurringInstanceDao(),
        scheduleDraftDao = database.scheduleDraftDao(),
        prefs = prefs,
        audioRecorder = audioRecorder,
        calendarBusyReader = calendarBusyReader
    )
}
