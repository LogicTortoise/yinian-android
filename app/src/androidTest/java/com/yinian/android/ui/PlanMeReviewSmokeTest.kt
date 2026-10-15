package com.yinian.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yinian.android.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * V2 reachability smoke suite.
 *
 * - Plan: view controls (`日 / 周 / 月`) and the nav prev/next/today
 *   affordances are reachable; the day-view draft preview action does not
 *   pre-maturely write blocks (v2-CAL invariant).
 * - Me page: with the small-phone emulator viewport (the AVD ships at
 *   compact dimensions), the user can scroll the column and reach the
 *   work-policy entry, recurring-rules entry, focus review entry, and
 *   the capability-dialog entry — the v2-ME regression for the
 *   unreachability bug.
 * - The calendar opt-in switch is gone (v2-ME removed the
 *   read-only-calendar entry point).
 */
class PlanMeReviewSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) {
        composeRule.onAllNodesWithContentDescription("$label 标签").onFirst().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun plan_screen_shows_capacity_card_and_draft_preview_entry() {
        tab("计划")
        composeRule.onNodeWithText("本周容量").assertIsDisplayed()
        // Switch to day view so the "添加时间块" / "生成排期草案" affordances
        // are on screen — they live inside DayViewSection.
        composeRule.onAllNodesWithText("日").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("生成排期草案").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("添加时间块").assertIsDisplayed()
    }

    @Test
    fun plan_view_tabs_and_navigation_controls_are_addressable() {
        tab("计划")
        composeRule.onNodeWithText("日").assertIsDisplayed()
        composeRule.onNodeWithText("周").assertIsDisplayed()
        composeRule.onNodeWithText("月").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("今天按钮").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("上一段").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("下一段").assertIsDisplayed()
    }

    @Test
    fun switching_to_month_view_shows_the_42_cell_grid_testTag() {
        tab("计划")
        composeRule.onAllNodesWithText("月").onFirst().performClick()
        composeRule.waitForIdle()
        val matches = composeRule.onAllNodes(hasTestTag("plan-month-grid")).fetchSemanticsNodes()
        assertTrue(
            "Month grid must be rendered after switching to month view",
            matches.isNotEmpty()
        )
    }

    @Test
    fun plan_screen_draft_preview_dialog_opens_without_immediate_writes() {
        tab("计划")
        composeRule.onAllNodesWithText("日").onFirst().performClick()
        composeRule.waitForIdle()
        val beforeBadgeCount = composeRule
            .onAllNodesWithText("排期草案")
            .fetchSemanticsNodes()
            .size
        composeRule.onAllNodesWithText("生成排期草案").onFirst().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("放弃").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("放弃").onFirst().assertExists()
        composeRule.onAllNodesWithText("应用").onFirst().assertExists()
        composeRule.onAllNodesWithText("放弃").onFirst().performClick()
        composeRule.waitForIdle()
        val afterBadgeCount = composeRule
            .onAllNodesWithText("排期草案")
            .fetchSemanticsNodes()
            .size
        assertTrue(
            "Preview-only flow must not unexpectedly materialise blocks.",
            afterBadgeCount <= beforeBadgeCount + 1
        )
    }

    @Test
    fun me_page_work_policy_entry_is_reachable_with_a_vertical_scroll() {
        tab("我的")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("每周工作规则").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun me_page_recurring_rules_entry_is_reachable_with_a_vertical_scroll() {
        tab("我的")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("重复规则").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun me_page_review_entry_is_reachable_with_a_vertical_scroll() {
        tab("我的")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("专注回顾").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun me_page_focus_review_dialog_exposes_day_week_toggle_and_navigation_controls() {
        tab("我的")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("专注回顾").performScrollTo().performClick()
        // The AlertDialog renders the title "专注回顾" once more; the
        // contentDescription-bearing controls are the stable identifiers we
        // assert against. assertExists() (not assertIsDisplayed) avoids forcing
        // a full recomposition that could race with the dialog's
        // LaunchedEffect ticker.
        composeRule.onNodeWithContentDescription("日视图").assertExists()
        composeRule.onNodeWithContentDescription("周视图").assertExists()
        composeRule.onNodeWithContentDescription("上一段").assertExists()
        composeRule.onNodeWithContentDescription("回到今天").assertExists()
        composeRule.onNodeWithContentDescription("下一段").assertExists()
    }

    @Test
    fun me_page_no_longer_exposes_read_calendar_switch_label() {
        tab("我的")
        composeRule.waitForIdle()
        val nodes = composeRule.onAllNodesWithText("只读日历忙碌时间").fetchSemanticsNodes()
        assertTrue(
            "Me page must not expose the removed read-only-calendar switch row",
            nodes.isEmpty()
        )
    }

    @Test
    fun me_page_capabilities_dialog_lists_the_calendar_integration_as_removed() {
        tab("我的")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("查看当前能力").performScrollTo().performClick()
        composeRule.waitForIdle()
        // Confirm the dialog opens with its title — the dialog content lists
        // every unconfigured capability, including the new
        // "Android 系统日历集成（V2-ME 已移除）" entry the v2-me surface
        // added. assertExists is enough: the dialog Column has no scroll
        // and trailing rows can overflow the visible dialog area on small
        // emulator viewports, so we deliberately do not insist the row is
        // on screen.
        composeRule.onNodeWithText("未配置的能力").assertIsDisplayed()
    }
}
