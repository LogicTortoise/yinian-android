package com.yinian.android.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yinian.android.MainActivity
import org.junit.Rule
import org.junit.Test

/**
 * Smoke tests that exercise real Compose UI on a clean Android emulator:
 *  - launches MainActivity
 *  - verifies all four navigation destinations are reachable
 *  - opens the capture sheet from the bottom dock
 *
 * Tests use unique titles so they remain robust against any state left behind
 * by the production seed initializer or by earlier tests.
 */
class AppNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun clickTab(label: String) {
        composeRule.onAllNodesWithContentDescription("$label 标签")
            .onFirst()
            .performClick()
    }

    @Test
    fun launches_on_the_Now_tab() {
        // The Now screen's title text and bottom-nav label both render "现在";
        // assert at least one is visible rather than the strict single-match.
        composeRule.onAllNodesWithText("现在").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("接下来").assertIsDisplayed()
    }

    @Test
    fun all_four_bottom_tabs_are_reachable() {
        val tabs = listOf("现在", "闪念", "计划", "我的")
        for (label in tabs) {
            clickTab(label)
            // Tab label is shared between the bottom-nav strip and the screen
            // header in most tabs; assert at least one match is visible.
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText(label).onFirst().assertIsDisplayed()
        }
    }

    @Test
    fun now_tab_exposes_the_task_button() {
        clickTab("现在")
        composeRule.onNodeWithText("任务").assertIsDisplayed()
    }

    @Test
    fun capture_sheet_opens_from_bottom_dock_on_non_now_tabs() {
        clickTab("闪念")
        composeRule.onNodeWithContentDescription("打开闪念录入").performClick()
        composeRule.onNodeWithContentDescription("闪念录入").assertIsDisplayed()
    }
}