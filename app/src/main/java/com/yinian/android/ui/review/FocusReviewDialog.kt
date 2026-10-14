package com.yinian.android.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.repo.YinianRepository
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import com.yinian.android.util.DateUtil
import com.yinian.android.util.DurationFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * V2-07: weekly focus / plan-vs-actual review.
 *
 * The review surface only consumes the existing `FocusSessionEntity` rows.
 * No device-usage data, no application telemetry, no app-opening counters.
 *
 * - `activeMs`: total active ms accumulated before the current run plus the
 *   in-flight segment if `status == "active"`. Cancelled sessions are
 *   intentionally excluded from "实际投入" totals.
 * - "计划 vs 实际" is approximated by walking the `TimeBlockEntity` rows in
 *   the week and contrasting scheduled minutes against the focus minutes
 *   spent on the same `taskId`. Tasks with no fixed block are reported under
 *   "未排期实际专注".
 */
@Composable
fun FocusReviewDialog(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val repo = container.repository
    val zone = ZoneId.systemDefault()
    val today = remember { LocalDate.now(zone) }
    val weekStart = remember(today) { today.minusDays((today.dayOfWeek.value - 1).toLong()) }
    val weekStartMs = remember(weekStart) { weekStart.atStartOfDay(zone).toInstant().toEpochMilli() }
    val weekEndMs = remember(weekStart) { weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli() }

    val focus by repo.observeAllFocus().collectAsState(initial = emptyList())
    val tasks by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val blocks by repo.observeTimeBlocksBetween(weekStartMs, weekEndMs).collectAsState(initial = emptyList())
    val drafts by repo.observeScheduleDrafts().collectAsState(initial = emptyList())

    val weekFocus = focus.filter { it.startedAt in weekStartMs..weekEndMs }
    val totalActiveMs = weekFocus
        .filter { it.status == "completed" || it.status == "active" || it.status == "paused" }
        .sumOf { it.pausedMs }
    val perTaskActiveMs = weekFocus
        .filter { it.taskId != null && (it.status == "completed" || it.status == "active" || it.status == "paused") }
        .groupBy { it.taskId!! }
        .mapValues { (_, list) -> list.sumOf { it.pausedMs } }

    val totalScheduledMs = blocks.sumOf { it.endAt - it.startAt }
    val scheduledPerTask = blocks
        .filter { !it.taskId.isNullOrEmpty() }
        .groupBy { it.taskId!! }
        .mapValues { (_, list) -> list.sumOf { it.endAt - it.startAt } }

    val perTaskView = tasks
        .filter { it.status != "archived" }
        .map { task ->
            val scheduled = scheduledPerTask[task.id] ?: 0L
            val actual = perTaskActiveMs[task.id] ?: 0L
            Triple(task, scheduled, actual)
        }
        .sortedByDescending { it.second + it.third }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("专注回顾") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "统计 ${DateUtil.dayLabel(weekStartMs)} – ${DateUtil.dayLabel(weekEndMs - 1L)}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, edgeThickness = 2.dp) {
                    Text("本周实际专注", style = MaterialTheme.typography.labelLarge)
                    Text(DurationFormatter.formatMs(totalActiveMs), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已完成 ${weekFocus.count { it.status == "completed" }} · 进行中 ${weekFocus.count { it.status == "active" || it.status == "paused" }} · 放弃 ${weekFocus.count { it.status == "cancelled" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, edgeThickness = 2.dp) {
                    Text("本周计划容量", style = MaterialTheme.typography.labelLarge)
                    Text(DurationFormatter.formatMs(totalScheduledMs), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "排期块 ${blocks.size} · 应用过的草案 ${drafts.count { it.appliedAt != null }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("任务对比（计划 vs 实际）", style = MaterialTheme.typography.titleSmall)
                if (perTaskView.isEmpty()) {
                    Text(
                        "本周没有可见的任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                perTaskView.forEach { (task, scheduled, actual) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.title, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PillBadge("计划 ${DurationFormatter.formatMs(scheduled)}")
                                PillBadge(
                                    "实际 ${DurationFormatter.formatMs(actual)}",
                                    background = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "暂停/恢复按产品规则累加；跨午夜会话按 wall-clock 计；放弃的会话不计入。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
