package com.yinian.android.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yinian.android.audio.AudioRecorder
import com.yinian.android.data.prefs.PrefsRepository

/**
 * Build a repository using the device's real app context (obtained via
 * `ApplicationProvider`). PrefsRepository and AudioRecorder get real Android
 * Contexts — neither property is touched by the repository code under test, so
 * no file IO is performed.
 */
internal fun makeRepository(): YinianRepository {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return YinianRepository(
        ideaDao = FakeIdeaDao(),
        taskDao = FakeTaskDao(),
        relationDao = FakeRelationDao(),
        timeBlockDao = FakeTimeBlockDao(),
        focusDao = FakeFocusDao(),
        prefs = PrefsRepository(context),
        audioRecorder = AudioRecorder(context)
    )
}

/**
 * Build a repository whose AudioRecorder is constructed with the same Context
 * used by the production app, so `deleteIdea` correctly resolves and removes
 * the recording file under the app's private files directory.
 */
internal fun makeRepositoryForFiles(): YinianRepository = makeRepository()
