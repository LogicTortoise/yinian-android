package com.yinian.android.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.entity.FocusSessionSegmentEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.repo.FocusAttribution
import com.yinian.android.data.repo.FocusDayTotal
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import com.yinian.android.util.DurationFormatter
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/**
 * V2-ME: focus / plan-vs-actual review.
 *
 * - Day / Week mode toggle, prev / next navigation, "今天" shortcut.
 * - Focus totals come from [FocusAttribution] which prefers explicit
 *   `focus_session_segments` rows and falls back to a documented V3 rule
 *   for sessions without segments.
 * - Cancelled sessions are excluded. Paused time is not double-counted.
 * - Cross-midnight sessions are split across local days exactly when
 *   segment history is present; the V3 fallback anchors a single virtual
 *   segment at the session's wall-clock end so the daily split remains
 *   conservative.
 * - The current active segment is included in live totals (the segment row
 *   has `endedAt = null`, which the attribution caps at "now").
 * - No device-usage or app-usage data is consulted.
 */
@Composable
fun FocusReviewDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val repo = container.repository
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }
    var mode by remember { mutableStateOf(ReviewMode.Week) }
    var anchor by remember { mutableStateOf(today) }

    val focus by repo.observeAllFocus().collectAsState(initial = emptyList())
    val tasks by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val segmentsState = remember { mutableStateOf<List<FocusSessionSegmentEntity>>(emptyList()) }
    // Refresh the segment snapshot whenever the observed focus rows change,
    // not just when the list size changes — a same-size status update (e.g.
    // a transition between "active" and "paused") would otherwise leave
    // stale open segment rows behind.
    LaunchedEffect(focus) {
        segmentsState.value = repo.focusSegmentsSnapshot()
    }
    // While any focus session is active, advance `nowMs` once per second so
    // the live segment's attribution cap visibly advances while this dialog
    // is open. The ticker stops the moment no session is active.
    val hasActiveSession = focus.any { it.status == "active" }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasActiveSession) {
        if (hasActiveSession) {
            nowMs = System.currentTimeMillis()
            while (true) {
                delay(1000L)
                nowMs = System.currentTimeMillis()
            }
        }
    }

    val windowStart: LocalDate
    val windowEndExclusive: LocalDate
    when (mode) {
        ReviewMode.Day -> {
            windowStart = anchor
            windowEndExclusive = anchor.plusDays(1)
        }
        ReviewMode.Week -> {
            windowStart = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
            windowEndExclusive = windowStart.plusDays(7)
        }
    }
    val windowStartMs = windowStart.atStartOfDay(zone).toInstant().toEpochMilli()
    val windowEndMs = windowEndExclusive.atStartOfDay(zone).toInstant().toEpochMilli()

    val blocks by repo.observeTimeBlocksBetween(windowStartMs, windowEndMs).collectAsState(initial = emptyList())
    val drafts by repo.observeScheduleDrafts().collectAsState(initial = emptyList())

    val segments = segmentsState.value
    val perDay = remember(focus, segments, windowStartMs, windowEndMs, nowMs) {
        FocusAttribution.totalsByDay(focus, segments, windowStartMs, windowEndMs, zone, nowMs = nowMs)
    }
    val totalActiveMs = perDay.sumOf { it.activeMs }
    val completedCount = focus.count {
        it.status == "completed" && it.startedAt in windowStartMs until windowEndMs
    }
    val activeCount = focus.count {
        (it.status == "active" || it.status == "paused") && it.startedAt in windowStartMs until windowEndMs
    }
    val cancelledCount = focus.count {
        it.status == "cancelled" && it.startedAt in windowStartMs until windowEndMs
    }

    val totalScheduledMs = blocks.sumOf { it.endAt - it.startAt }
    val scheduledPerTask = blocks
        .filter { !it.taskId.isNullOrEmpty() }
        .groupBy { it.taskId!! }
        .mapValues { (_, list) -> list.sumOf { it.endAt - it.startAt } }
    val actualPerTask = remember(focus, segments, windowStartMs, windowEndMs, nowMs) {
        FocusAttribution.actualMsByTask(focus, segments, windowStartMs, windowEndMs, zone, nowMs = nowMs)
    }

    val taskComparison = tasks
        .filter { t -> t.id in scheduledPerTask.keys || t.id in actualPerTask.keys }
        .map { task ->
            val scheduledMs = scheduledPerTask[task.id] ?: 0L
            val actualMs = actualPerTask[task.id] ?: 0L
            Triple(task, scheduledMs, actualMs)
        }
        .sortedByDescending { it.second + it.third }

    // 计划完成率: denominator is every task with a scheduled block in the
    // selected day/week window; numerator is those tasks whose
    // TaskEntity.status == "done". Completed focus-session IDs do not imply
    // the task itself is marked done, so the previous numerator was wrong.
    val taskStatusById = tasks.associate { it.id to it.status }
    val scheduledTaskIds = scheduledPerTask.keys
    val completionRatePct = if (scheduledTaskIds.isEmpty()) {
        null
    } else {
        val done = scheduledTaskIds.count { taskStatusById[it] == "done" }
        (done.toDouble() / scheduledTaskIds.size * 100).toInt()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("专注回顾") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ReviewHeader(
                    mode = mode,
                    onModeChange = { mode = it },
                    onPrev = {
                        anchor = when (mode) {
                            ReviewMode.Day -> anchor.minusDays(1)
                            ReviewMode.Week -> anchor.minusDays(7)
                        }
                    },
                    onNext = {
                        anchor = when (mode) {
                            ReviewMode.Day -> anchor.plusDays(1)
                            ReviewMode.Week -> anchor.plusDays(7)
                        }
                    },
                    onToday = { anchor = today }
                )
                Text(
                    rangeLabel(mode, anchor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                if (perDay.isEmpty() && totalScheduledMs == 0L) {
                    // Early `return@Column` from inside the AlertDialog.text
                    // composable lambda was crashing recomposition with
                    // SlotTable ArrayIndexOutOfBoundsException on subsequent
                    // openings (skipped trailing groups left the slot table
                    // unbalanced). Mirror the empty-state and the populated
                    // body in a balanced if/else so every branch closes its
                    // own groups before the Column ends.
                    Text(
                        emptyStateHint(mode),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, edgeThickness = 2.dp) {
                        Text("实际专注", style = MaterialTheme.typography.labelLarge)
                        Text(DurationFormatter.formatMs(totalActiveMs), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "已完成 $completedCount · 进行中 $activeCount · 放弃 $cancelledCount（不计入实际投入）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, edgeThickness = 2.dp) {
                        Text("计划 vs 实际", style = MaterialTheme.typography.labelLarge)
                        val plannedLabel = DurationFormatter.formatMs(totalScheduledMs)
                        val actualLabel = DurationFormatter.formatMs(totalActiveMs)
                        Text("计划 $plannedLabel / 实际 $actualLabel", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        val rate = completionRatePct
                        val rateText = if (rate == null) "无排期任务" else "完成率 $rate%"
                        Text(
                            "排期块 ${blocks.size} · 应用过的草案 ${drafts.count { it.appliedAt != null }} · $rateText",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    if (perDay.isNotEmpty()) {
                        Text("按日细分", style = MaterialTheme.typography.titleSmall)
                        perDay.forEach { row -> DayTotalRow(row) }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (taskComparison.isNotEmpty()) {
                        Text("任务对比（估时 vs 实际）", style = MaterialTheme.typography.titleSmall)
                        taskComparison.forEach { (task, scheduledMs, actualMs) ->
                            TaskComparisonRow(task, scheduledMs, actualMs)
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Text(
                        "暂停/恢复按产品规则累加；跨午夜会话按本地日期分摊；放弃的会话不计入；当前进行中的会话算入当日。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}

private enum class ReviewMode(val label: String) {
    Day("日"),
    Week("周")
}

@Composable
private fun ReviewHeader(
    mode: ReviewMode,
    onModeChange: (ReviewMode) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        ModeToggle(current = mode, onChange = onModeChange)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onPrev, modifier = Modifier.semantics { contentDescription = "上一段" }) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = null)
        }
        TextButton(onClick = onToday, modifier = Modifier.semantics { contentDescription = "回到今天" }) {
            Text("今天")
        }
        IconButton(onClick = onNext, modifier = Modifier.semantics { contentDescription = "下一段" }) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun ModeToggle(current: ReviewMode, onChange: (ReviewMode) -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(2.dp)
    ) {
        ReviewMode.entries.forEach { mode ->
            val selected = mode == current
            Box(
                modifier = Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { onChange(mode) }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { contentDescription = "${mode.label}视图" }
            ) {
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DayTotalRow(row: FocusDayTotal) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(row.dayKey, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        PillBadge(DurationFormatter.formatMs(row.activeMs))
    }
}

@Composable
private fun TaskComparisonRow(task: TaskEntity, scheduledMs: Long, actualMs: Long) {
    val estimateMs = task.estimateMinutes.toLong() * 60_000L
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillBadge("估时 ${DurationFormatter.formatMs(estimateMs)}")
                PillBadge("计划 ${DurationFormatter.formatMs(scheduledMs)}")
                PillBadge(
                    "实际 ${DurationFormatter.formatMs(actualMs)}",
                    background = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

private fun rangeLabel(mode: ReviewMode, anchor: LocalDate): String = when (mode) {
    ReviewMode.Day -> "${anchor} （${dowLabel(anchor.dayOfWeek.value)}）"
    ReviewMode.Week -> {
        val start = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
        val end = start.plusDays(6)
        "本周 ${start} – $end"
    }
}

private fun emptyStateHint(mode: ReviewMode): String = when (mode) {
    ReviewMode.Day -> "这一天还没有专注记录，也没有任何排期块。开始一次专注或加一个排期试试。"
    ReviewMode.Week -> "这一周还没有专注记录，也没有任何排期块。"
}

private fun dowLabel(dow: Int): String = when (dow) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "?"
}