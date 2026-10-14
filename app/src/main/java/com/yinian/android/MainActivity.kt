package com.yinian.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yinian.android.ui.nav.RootScaffold
import com.yinian.android.ui.theme.YinianTheme
import com.yinian.android.data.prefs.PrefsRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as YinianApp
        val prefs = app.container.prefsRepo
        setContent {
            // collectAsStateWithLifecycle ties the Flow collection to the activity
            // lifecycle (Dispatchers.Main.immediate) so DataStore's internal
            // Dispatchers.IO upstream can never push a MutableState write off-main.
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(
                initialValue = PrefsRepository.THEME_SYSTEM
            )
            YinianTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    RootScaffold(
                        container = app.container,
                        prefs = prefs
                    )
                }
            }
        }
    }
}
