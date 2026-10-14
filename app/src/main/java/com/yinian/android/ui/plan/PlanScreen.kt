package com.yinian.android.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity
import com.yinian.android.data.repo.ApplyResult
import com.yinian.android.data.repo.CapacitySnapshot
import com.yinian.android.data.repo.ScheduleDraft
import com.yinian.android.data.repo.YinianRepository
import com.yinian.android.planner.Placement
import com.yinian.android.planner.UnscheduledReason
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import com.yinian.android.util.DateUtil
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun PlanScreen(container: AppContainer) {
    val repo = container.repository
    val tasks by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val now = remember { System.currentTimeMillis() }
    var selectedDay by remember { mutableStateOf(DateUtil.startOfDay(now)) }
    val (weekStart, weekEnd) = remember(now) { DateUtil.weekRange(now) }
    val blocks by repo.observeTimeBlocksBetween(weekStart, weekEnd).collectAsState(initial = emptyList())
    val dayBlocks = blocks.filter { it.startAt >= selectedDay && it.startAt < selectedDay + 24L * 3600_000L }
    val capacity by produceCapacity(repo) { repo.computeWeekCapacity(now) }
    val draftHolder = remember { mutableStateOf<ScheduleDraft?>(null) }
    val scope = rememberCoroutineScope()
    var addBlockFor by remember { mutableStateOf(false) }
    var blockConflict by remember { mutableStateOf<TimeBlockEntity?>(null) }
    var blockError by remember { mutableStateOf<String?>(null) }
    var applyMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("计划", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "周日期切换，单日时间块视图。手动安排会避开已固定的时间块。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        CapacityCard(capacity)
        Spacer(Modifier.height(12.dp))
        WeekStrip(
            weekStart = weekStart,
            selected = selectedDay,
            onSelect = { selectedDay = it }
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(DateUtil.dayLabel(selectedDay), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    val today = LocalDate.now()
                    val draft = repo.buildScheduleDraft(today, today.plusDays(7))
                    draftHolder.value = draft
                    applyMessage = "已生成 ${draft.result.placements.size} 条建议、${draft.result.unscheduled.size} 项未安排。"
                }
            }) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("生成排期草案")
            }
            TextButton(onClick = { addBlockFor = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("添加时间块")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (dayBlocks.isEmpty()) {
            SoftCard(modifier = Modifier.fillMaxWidth()) {
                Text("这一天还没有安排。", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "你可以在下面手动添加一个时间块，或在任务详情里安排。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = dayBlocks, key = { it.id }) { block ->
                    BlockRow(
                        block = block,
                        onRemove = { scope.launch { repo.removeTimeBlock(block.id) } }
                    )
                }
                item { Spacer(Modifier.height(140.dp)) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("未安排的任务", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val unscheduled = tasks.filter { it.status == "open" && blocks.none { b -> b.taskId == it.id } }
        if (unscheduled.isEmpty()) {
            Text("没有未安排的任务。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(items = unscheduled, key = { it.id }) { task ->
                    UnscheduledTaskRow(task = task)
                }
            }
        }
    }

    if (addBlockFor) {
        AddBlockDialog(
            tasks = tasks.filter { it.status == "open" },
            defaultDayStart = selectedDay,
            conflict = blockConflict,
            errorMessage = blockError,
            onDismiss = {
                addBlockFor = false
                blockConflict = null
                blockError = null
            },
            onConfirm = { taskId, title, startAt, endAt, fixed ->
                scope.launch {
                    when (val res = repo.addManualTimeBlock(taskId, title, startAt, endAt, fixed)) {
                        is com.yinian.android.data.repo.AddBlockResult.Ok -> {
                            addBlockFor = false
                            blockConflict = null
                            blockError = null
                        }
                        is com.yinian.android.data.repo.AddBlockResult.ConflictsWithFixed -> {
                            blockConflict = res.block
                            blockError = null
                        }
                        com.yinian.android.data.repo.AddBlockResult.InvalidRange -> {
                            blockConflict = null
                            blockError = "结束时间必须晚于开始时间。"
                        }
                    }
                }
            }
        )
    }

    val draft = draftHolder.value
    if (draft != null) {
        DraftReviewDialog(
            draft = draft,
            tasksById = tasks.associateBy { it.id },
            applyMessage = applyMessage,
            onDismiss = { draftHolder.value = null; applyMessage = null },
            onApply = {
                scope.launch {
                    val res = repo.applyScheduleDraft(draft)
                    applyMessage = when (res) {
                        is ApplyResult.Applied -> "已应用 ${res.placedBlocks} 个时间块。"
                        is ApplyResult.NoOp -> res.reason
                        is ApplyResult.Conflict -> "与「${res.fixedBlockTitle}」冲突，未应用。"
                    }
                }
            }
        )
    }
}

@Composable
private fun produceCapacity(
    @Suppress("UNUSED_PARAMETER") repo: YinianRepository,
    block: suspend () -> CapacitySnapshot
): androidx.compose.runtime.State<CapacitySnapshot?> {
    val state = remember { androidx.compose.runtime.mutableStateOf<CapacitySnapshot?>(null) }
    LaunchedEffect(Unit) {
        state.value = block()
    }
    return state
}

@Composable
private fun CapacityCard(capacity: CapacitySnapshot?) {
    SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, edgeThickness = 2.dp) {
        Text("本周容量", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (capacity == null) {
            Text(
                "加载中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val availableMin = capacity.totalAvailable
            val scheduledMin = capacity.totalScheduled
            val remaining = (capacity.totalCap - scheduledMin).coerceAtLeast(0)
            Text(
                "可用 ${formatMinutes(availableMin)} · 已安排 ${formatMinutes(scheduledMin)} · 剩余 ${formatMinutes(remaining)}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (scheduledMin > capacity.totalCap && capacity.totalCap > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已超出日上限 ${formatMinutes(scheduledMin - capacity.totalCap)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                capacity.days.take(7).forEach { d ->
                    val tone = when {
                        d.capMinutes == 0 -> "休"
                        d.scheduledMinutes > d.capMinutes -> "超"
                        else -> "${(d.scheduledMinutes * 100 / d.capMinutes.coerceAtLeast(1))}"
                    }
                    PillBadge("${d.dayKey.substringAfterLast('-')} $tone")
                }
            }
        }
    }
}

private fun formatMinutes(min: Int): String {
    if (min <= 0) return "0 分"
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}小时${m}分" else "${m}分"
}

@Composable
private fun WeekStrip(weekStart: Long, selected: Long, onSelect: (Long) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
            .padding(4.dp)
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (i in 0..6) {
            val day = DateUtil.addDays(weekStart, i)
            val isSelected = DateUtil.startOfDay(day) == selected
            Box(
                modifier = Modifier
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { onSelect(DateUtil.startOfDay(day)) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .semantics { contentDescription = "日期 " + DateUtil.dayLabel(day) }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        DateUtil.weekDayLabel(day),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        DateUtil.dayLabel(day).substringAfterLast("-"),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun BlockRow(block: TimeBlockEntity, onRemove: () -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, edgeThickness = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(block.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    DateUtil.timeLabel(block.startAt) + " - " + DateUtil.timeLabel(block.endAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (block.fixed) {
                PillBadge("固定", background = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(6.dp))
            } else if (block.source == "plan") {
                PillBadge("排期草案", background = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(6.dp))
            } else if (block.source == "recurring") {
                PillBadge("重复", background = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "移除时间块")
            }
        }
    }
}

@Composable
private fun UnscheduledTaskRow(task: TaskEntity) {
    SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, edgeThickness = 2.dp) {
        Column {
            Text(task.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "优先级 ${task.priority} · 约 ${task.estimateMinutes} 分钟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DraftReviewDialog(
    draft: ScheduleDraft,
    tasksById: Map<String, TaskEntity>,
    applyMessage: String?,
    onDismiss: () -> Unit,
    onApply: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onApply) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("放弃") } },
        title = { Text("排期草案") },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(
                    "本草案由本地规则生成，应用前请检查每条建议。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (draft.result.placements.isEmpty()) {
                    Text("没有可安排的项。", style = MaterialTheme.typography.bodySmall)
                }
                draft.result.placements.forEach { p ->
                    DraftPlacementRow(p, tasksById)
                }
                if (draft.result.unscheduled.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("未安排", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    draft.result.unscheduled.forEach { u ->
                        DraftUnscheduledRow(u)
                    }
                }
                applyMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    )
}

@Composable
private fun DraftPlacementRow(p: Placement.Scheduled, tasksById: Map<String, TaskEntity>) {
    val title = tasksById[p.itemId]?.title ?: "安排"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        PillBadge(DateUtil.timeLabel(p.startMs))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(p.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun unscheduledReasonText(reason: UnscheduledReason): String = when (reason) {
    UnscheduledReason.BlockedByDependencies -> "依赖未完成"
    UnscheduledReason.OutsideAvailableHours -> "没有合适的工作时段"
    UnscheduledReason.DailyCapacityReached -> "本日容量已满"
    UnscheduledReason.EstimateTooLong -> "估时超过可用时段"
    is UnscheduledReason.ConflictsWith -> "与已有安排冲突"
}

@Composable
private fun DraftUnscheduledRow(u: Placement.Unscheduled) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(u.title, style = MaterialTheme.typography.bodyMedium)
            Text(unscheduledReasonText(u.reason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun AddBlockDialog(
    tasks: List<TaskEntity>,
    defaultDayStart: Long,
    conflict: TimeBlockEntity?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (taskId: String?, title: String, startAt: Long, endAt: Long, fixed: Boolean) -> Unit
) {
    var title by remember { mutableStateOf("专注时间") }
    var startHour by remember { mutableStateOf(9) }
    var endHour by remember { mutableStateOf(10) }
    var fixed by remember { mutableStateOf(false) }
    var linkedTaskId by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (endHour <= startHour) {
                    localError = "结束时间必须晚于开始时间。"
                    return@TextButton
                }
                localError = null
                val startAt = defaultDayStart + startHour * 3600_000L
                val endAt = defaultDayStart + endHour * 3600_000L
                onConfirm(linkedTaskId, title.ifBlank { "时间块" }, startAt, endAt, fixed)
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加时间块") },
        text = {
            Column {
                val visibleError = localError ?: errorMessage
                visibleError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                }
                conflict?.let { fixedBlock ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "与固定时间块冲突",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "「${fixedBlock.title}」(${DateUtil.timeLabel(fixedBlock.startAt)}–${DateUtil.timeLabel(fixedBlock.endAt)}) 已固定，请换一个时间。固定块不会被覆盖。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开始 ${"%02d".format(startHour)}:00")
                    Spacer(Modifier.width(8.dp))
                    for (h in 0..23 step 4) {
                        TextButton(onClick = { startHour = h }) { Text("${"%02d".format(h)}") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("结束 ${"%02d".format(endHour)}:00")
                    Spacer(Modifier.width(8.dp))
                    for (h in 0..23 step 4) {
                        TextButton(onClick = { endHour = h }) { Text("${"%02d".format(h)}") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("固定（避开自动重排）")
                    Spacer(Modifier.weight(1f))
                    Switch(checked = fixed, onCheckedChange = { fixed = it })
                }
                if (tasks.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("关联任务（可选）", style = MaterialTheme.typography.labelMedium)
                    tasks.take(5).forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { linkedTaskId = if (linkedTaskId == t.id) null else t.id }) {
                                Text(if (linkedTaskId == t.id) "✓ ${t.title}" else t.title, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    )
}
