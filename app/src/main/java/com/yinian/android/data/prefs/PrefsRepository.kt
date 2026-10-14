package com.yinian.android.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("yinian_prefs")

class PrefsRepository(private val context: Context) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_WORK_START = intPreferencesKey("work_start_min")
        val KEY_WORK_END = intPreferencesKey("work_end_min")
        val KEY_FOCUS_DEFAULT = intPreferencesKey("focus_default_min")
        val KEY_AUTO_SUGGEST = booleanPreferencesKey("auto_suggest")
        val KEY_ASR_ENABLED = booleanPreferencesKey("asr_enabled")
        /** V2: user opt-in to reading the system calendar as busy intervals. */
        val KEY_CALENDAR_ENABLED = booleanPreferencesKey("calendar_enabled")
        /** V2: cached read-only permission state for the system calendar. */
        val KEY_CALENDAR_GRANTED = booleanPreferencesKey("calendar_granted")
        /** V2: persist the most recently observed "no available calendars" state. */
        val KEY_CALENDAR_LAST_EMPTY = booleanPreferencesKey("calendar_last_empty")
    }

    val themeMode: Flow<String> = context.dataStore.data.map { it[KEY_THEME] ?: THEME_SYSTEM }
    val workStartMin: Flow<Int> = context.dataStore.data.map { it[KEY_WORK_START] ?: 9 * 60 }
    val workEndMin: Flow<Int> = context.dataStore.data.map { it[KEY_WORK_END] ?: 18 * 60 }
    val focusDefaultMin: Flow<Int> = context.dataStore.data.map { it[KEY_FOCUS_DEFAULT] ?: 25 }
    val autoSuggest: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_SUGGEST] ?: false }
    val asrEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_ASR_ENABLED] ?: false }
    val calendarEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALENDAR_ENABLED] ?: false }
    val calendarGranted: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALENDAR_GRANTED] ?: false }
    val calendarLastEmpty: Flow<Boolean> = context.dataStore.data.map { it[KEY_CALENDAR_LAST_EMPTY] ?: false }

    suspend fun setThemeMode(value: String) { context.dataStore.edit { it[KEY_THEME] = value } }
    suspend fun setWorkStart(min: Int) { context.dataStore.edit { it[KEY_WORK_START] = min } }
    suspend fun setWorkEnd(min: Int) { context.dataStore.edit { it[KEY_WORK_END] = min } }
    suspend fun setFocusDefault(min: Int) { context.dataStore.edit { it[KEY_FOCUS_DEFAULT] = min } }
    suspend fun setAutoSuggest(value: Boolean) { context.dataStore.edit { it[KEY_AUTO_SUGGEST] = value } }
    suspend fun setAsrEnabled(value: Boolean) { context.dataStore.edit { it[KEY_ASR_ENABLED] = value } }
    suspend fun setCalendarEnabled(value: Boolean) { context.dataStore.edit { it[KEY_CALENDAR_ENABLED] = value } }
    suspend fun setCalendarGranted(value: Boolean) { context.dataStore.edit { it[KEY_CALENDAR_GRANTED] = value } }
    suspend fun setCalendarLastEmpty(value: Boolean) { context.dataStore.edit { it[KEY_CALENDAR_LAST_EMPTY] = value } }
}
