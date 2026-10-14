package com.yinian.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.yinian.android.MainActivity
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/**
 * Drives a task creation from the Now screen, then verifies the new task
 * surfaces in the Ideas / Plan flows. We tag every run with a UUID so the
 * assertions don't collide with the production seed data.
 */
class TaskCreationFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun creating_a_task_from_Now_persists_it_for_subsequent_screens() {
        // Land on Now.
        composeRule.onAllNodesWithContentDescription("现在 标签").onFirst().performClick()

        // Open the task editor.
        composeRule.onNodeWithText("任务").performClick()

        // Type a unique title.
        val uniqueTitle = "T-" + UUID.randomUUID().toString().take(8)
        composeRule.onNodeWithText("标题").performTextInput(uniqueTitle)

        // Save.
        composeRule.onNodeWithText("创建").performClick()

        // The new task should appear in the Recommendation card or the Upcoming
        // list once the tasks Flow has propagated. Wait up to 5 seconds for it
        // to become visible on either surface; if the recommendation hasn't
        // picked it up yet we look on the Plan tab.
        val visibleOnNow = waitForText(uniqueTitle, timeoutMs = 5_000L)
        if (!visibleOnNow) {
            composeRule.onAllNodesWithContentDescription("计划 标签").onFirst().performClick()
            composeRule.waitForIdle()
        }
        val visibleAfterNav = waitForText(uniqueTitle, timeoutMs = 5_000L)
        assert(visibleOnNow || visibleAfterNav) {
            "Expected the created task '$uniqueTitle' to appear on a screen."
        }
    }

    private fun waitForText(text: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()) {
                return true
            }
            composeRule.waitForIdle()
        }
        return false
    }
}