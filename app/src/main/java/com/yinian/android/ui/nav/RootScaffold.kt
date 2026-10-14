package com.yinian.android.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.prefs.PrefsRepository
import com.yinian.android.ui.capture.CaptureSheet
import com.yinian.android.ui.common.BottomNav
import com.yinian.android.ui.common.CaptureDock
import com.yinian.android.ui.common.HomeTab
import com.yinian.android.ui.ideas.IdeasScreen
import com.yinian.android.ui.me.MeScreen
import com.yinian.android.ui.now.NowScreen
import com.yinian.android.ui.plan.PlanScreen

@Composable
fun RootScaffold(
    container: AppContainer,
    prefs: PrefsRepository
) {
    var tab by remember { mutableStateOf(HomeTab.Now) }
    var captureOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (tab) {
                    HomeTab.Now -> NowScreen(container = container, onJumpToIdeas = { tab = HomeTab.Ideas })
                    HomeTab.Ideas -> IdeasScreen(container = container)
                    HomeTab.Plan -> PlanScreen(container = container)
                    HomeTab.Me -> MeScreen(container = container, prefs = prefs)
                }
            }
            // Capture dock + bottom nav stacked. Dock above nav.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (tab != HomeTab.Now) {
                        CaptureDock(onTap = { captureOpen = true })
                        Spacer(Modifier.height(8.dp))
                    }
                    BottomNav(
                        current = tab,
                        onSelect = { tab = it }
                    )
                }
            }
        }
    }

    if (captureOpen) {
        CaptureSheet(
            container = container,
            onDismiss = { captureOpen = false }
        )
    }
}
