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
import org.junit.Test
import org.junit.Rule
import java.util.UUID

/**
 * Drives the text-idea capture flow:
 *  - open capture sheet from bottom dock
 *  - type an idea
 *  - save as idea
 *  - verify the idea surfaces in the Ideas list.
 *
 * Tagged with a UUID so the test is robust against seed data.
 */
class IdeaCaptureFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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

    @Test
    fun text_idea_capture_surfaces_in_Ideas_screen() {
        // Open capture sheet via Ideas dock.
        composeRule.onAllNodesWithContentDescription("闪念 标签").onFirst().performClick()
        composeRule.onNodeWithContentDescription("打开闪念录入").performClick()
        composeRule.onNodeWithContentDescription("闪念录入").assertIsDisplayed()

        val uniqueBody = "Idea-" + UUID.randomUUID().toString().take(8)
        composeRule.onNodeWithText("随手记下一个想法…").performTextInput(uniqueBody)

        composeRule.onAllNodesWithText("保存为闪念").onFirst().performClick()

        // Capture sheet should be dismissed. Re-open the Ideas tab and confirm
        // the body shows up within a few seconds (Flow propagation).
        composeRule.onAllNodesWithContentDescription("闪念 标签").onFirst().performClick()
        val visible = waitForText(uniqueBody, timeoutMs = 5_000L)
        assert(visible) { "Expected idea '$uniqueBody' to appear on the Ideas screen." }
    }
}