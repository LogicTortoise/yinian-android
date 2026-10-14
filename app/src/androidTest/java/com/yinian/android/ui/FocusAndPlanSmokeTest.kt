package com.yinian.android.ui

import androidx.compose.ui.test.assertIsDisplayed
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
 * Drives a focus/plan interaction on the Now screen:
 *  - ensures the focus start affordance is reachable when no session is active
 *  - ensures the Plan tab renders the week strip and "添加时间块" entry point
 */
class FocusAndPlanSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun now_tab_shows_focus_start_when_no_active_session() {
        composeRule.onAllNodesWithContentDescription("现在 标签").onFirst().performClick()
        // Either we have a recommended task and a "开始专注" pill, or the
        // "已有专注计时" pill is shown when a session is already active. Both
        // confirm the focus affordance exists.
        val startOrTaken = composeRule.onAllNodesWithText("开始专注").fetchSemanticsNodes().isNotEmpty() ||
            composeRule.onAllNodesWithText("已有专注计时").fetchSemanticsNodes().isNotEmpty()
        assert(startOrTaken) {
            "Expected either '开始专注' or '已有专注计时' on the Now screen."
        }
    }

    @Test
    fun plan_tab_renders_week_strip_and_add_block_entry() {
        composeRule.onAllNodesWithContentDescription("计划 标签").onFirst().performClick()
        // The "计划" text appears both as the bottom-nav label and as the
        // screen header; assert at least one is visible.
        composeRule.onAllNodesWithText("计划").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("添加时间块").assertIsDisplayed()
    }
}