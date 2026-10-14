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
    }

    val themeMode: Flow<String> = context.dataStore.data.map { it[KEY_THEME] ?: THEME_SYSTEM }
    val workStartMin: Flow<Int> = context.dataStore.data.map { it[KEY_WORK_START] ?: 9 * 60 }
    val workEndMin: Flow<Int> = context.dataStore.data.map { it[KEY_WORK_END] ?: 18 * 60 }
    val focusDefaultMin: Flow<Int> = context.dataStore.data.map { it[KEY_FOCUS_DEFAULT] ?: 25 }
    val autoSuggest: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_SUGGEST] ?: false }
    val asrEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_ASR_ENABLED] ?: false }

    suspend fun setThemeMode(value: String) { context.dataStore.edit { it[KEY_THEME] = value } }
    suspend fun setWorkStart(min: Int) { context.dataStore.edit { it[KEY_WORK_START] = min } }
    suspend fun setWorkEnd(min: Int) { context.dataStore.edit { it[KEY_WORK_END] = min } }
    suspend fun setFocusDefault(min: Int) { context.dataStore.edit { it[KEY_FOCUS_DEFAULT] = min } }
    suspend fun setAutoSuggest(value: Boolean) { context.dataStore.edit { it[KEY_AUTO_SUGGEST] = value } }
    suspend fun setAsrEnabled(value: Boolean) { context.dataStore.edit { it[KEY_ASR_ENABLED] = value } }
}
