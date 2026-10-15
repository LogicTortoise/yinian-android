package com.yinian.android.ui.plan

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.entity.TimeBlockEntity
import com.yinian.android.data.repo.ApplyResult
import com.yinian.android.data.repo.CapacitySnapshot
import com.yinian.android.data.repo.YinianRepository
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
    val zone = remember { PlanDate.zone() }
    val today = remember(now) { PlanDate.toLocalDate(now, zone) }

    var viewMode by remember { mutableStateOf(PlanViewMode.Week) }
    var selectedDate by remember { mutableStateOf(today) }

    val (weekStart, _) = remember(selectedDate) { PlanDate.weekRange(selectedDate) }

    // The data window we query from the repository depends on the view
    // mode: Day and Week use the same week-only range they always have, but
    // Month must cover the full 42-day grid (including the previous- and
    // next-month dates) so every cell renders its correct block count and
    // markers. Without this, Month mode shows empty cells beyond the
    // selected week.
    val (windowStartDate, windowEndExclusive) = remember(viewMode, selectedDate) {
        when (viewMode) {
            PlanViewMode.Day -> selectedDate to selectedDate.plusDays(1)
            PlanViewMode.Week -> {
                val (s, e) = PlanDate.weekRange(selectedDate)
                s to e
            }
            PlanViewMode.Month -> {
                val monthAnchor = selectedDate.withDayOfMonth(1)
                val grid = PlanDate.monthGrid(monthAnchor)
                grid.first() to grid.last().plusDays(1)
            }
        }
    }
    val windowStartMs = remember(windowStartDate) { PlanDate.toStartOfDayMs(windowStartDate, zone) }
    val windowEndMs = remember(windowEndExclusive) { PlanDate.toStartOfDayMs(windowEndExclusive, zone) }
    val blocks by repo.observeTimeBlocksBetween(windowStartMs, windowEndMs)
        .collectAsState(initial = emptyList())
    val capacity by produceCapacity(repo) { repo.computeWeekCapacity(now) }

    val draftHolder = remember { mutableStateOf<EditableDraft?>(null) }
    var applyMessage by remember { mutableStateOf<String?>(null) }
    var draftGenerationMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var addBlockFor by remember { mutableStateOf(false) }
    var blockConflict by remember { mutableStateOf<TimeBlockEntity?>(null) }
    var blockError by remember { mutableStateOf<String?>(null) }

    val title = remember(viewMode, selectedDate) {
        when (viewMode) {
            PlanViewMode.Day -> PlanDate.dayLabel(selectedDate)
            PlanViewMode.Week -> {
                val (s, e) = PlanDate.weekRange(selectedDate)
                "${PlanDate.dayLabel(s)} – ${PlanDate.dayLabel(e.minusDays(1))}"
            }
            PlanViewMode.Month -> PlanDate.monthLabel(selectedDate)
        }
    }

    val daySummaries = remember(selectedDate, blocks) {
        buildWeekSummaries(selectedDate, blocks)
    }
    val daySummary = daySummaries.dayOf(selectedDate) ?: run {
        // Re-compute for the selected day using a wider window if the week
        // window happens to miss it (e.g. cross-month selection).
        val dayBlocks = blocks.filter { block ->
            val d = PlanDate.toLocalDate(block.startAt, zone)
            d == selectedDate
        }
        DaySummary(selectedDate, dayBlocks, dayBlocks.any { it.fixed }, dayBlocks.any { it.source == "recurring" })
    }

    val dayStartMs = remember(selectedDate) { PlanDate.toStartOfDayMs(selectedDate, zone) }
    val dayEndMs = remember(selectedDate) { PlanDate.toEndOfDayMs(selectedDate, zone) }
    val dayBlocksForDay = blocks.filter { it.startAt < dayEndMs && it.endAt > dayStartMs }
        .sortedBy { it.startAt }

    val monthSummary = remember(selectedDate, blocks) {
        val monthAnchor = selectedDate.withDayOfMonth(1)
        val grid = PlanDate.monthGrid(monthAnchor)
        val byDay = blocks.groupBy { PlanDate.toLocalDate(it.startAt, zone) }
        val days = grid.map { date ->
            val dayBlocks = byDay[date].orEmpty()
            DaySummary(
                date = date,
                blocks = dayBlocks,
                hasFixed = dayBlocks.any { it.fixed },
                hasRecurring = dayBlocks.any { it.source == "recurring" }
            )
        }
        MonthSummary(anchor = monthAnchor, grid = days)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("计划", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "日、周、月三视图。数据来自本地时间块，不接入系统日历。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        PlanViewHeader(
            mode = viewMode,
            onSelectMode = {
                viewMode = it
                // selectedDate is preserved across view switches.
            },
            title = title,
            onPrev = {
                selectedDate = when (viewMode) {
                    PlanViewMode.Day -> selectedDate.minusDays(1)
                    PlanViewMode.Week -> selectedDate.minusWeeks(1)
                    PlanViewMode.Month -> selectedDate.minusMonths(1)
                }
            },
            onNext = {
                selectedDate = when (viewMode) {
                    PlanViewMode.Day -> selectedDate.plusDays(1)
                    PlanViewMode.Week -> selectedDate.plusWeeks(1)
                    PlanViewMode.Month -> selectedDate.plusMonths(1)
                }
            },
            onToday = {
                selectedDate = today
            },
            isToday = selectedDate == today
        )
        Spacer(Modifier.height(12.dp))
        CapacityCard(capacity)
        Spacer(Modifier.height(12.dp))
        Column(modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
        ) {
        when (viewMode) {
            PlanViewMode.Day -> DayViewSection(
                summary = daySummary,
                dayBlocks = dayBlocksForDay,
                tasks = tasks,
                blocks = blocks,
                onRemoveBlock = { block -> scope.launch { repo.removeTimeBlock(block.id) } },
                onAddBlock = { addBlockFor = true },
                onGenerateDraft = {
                    scope.launch {
                        val draft = repo.buildScheduleDraft(today, today.plusDays(7))
                        draftHolder.value = EditableDraft.from(draft) { id ->
                            tasksById(tasks, id)
                        }
                        val d = draftHolder.value!!
                        applyMessage = null
                        draftGenerationMessage =
                            "已生成 ${d.activeRows.size} 条建议、${d.unscheduled.size} 项未安排。"
                    }
                }
            )
            PlanViewMode.Week -> WeekViewSection(
                summary = WeekSummary(weekStart, daySummaries.days),
                today = today,
                selectedDate = selectedDate,
                onPickDay = {
                    selectedDate = it
                    viewMode = PlanViewMode.Day
                }
            )
            PlanViewMode.Month -> MonthViewSection(
                summary = monthSummary,
                today = today,
                selectedDate = selectedDate,
                onPickDay = {
                    selectedDate = it
                    viewMode = PlanViewMode.Day
                }
            )
        }
        Spacer(Modifier.height(16.dp))
        }
    }

    if (addBlockFor) {
        AddBlockDialog(
            tasks = tasks.filter { it.status == "open" },
            defaultDayStart = dayStartMs,
            conflict = blockConflict,
            errorMessage = blockError,
            onDismiss = {
                addBlockFor = false
                blockConflict = null
                blockError = null
            },
            onConfirm = { taskId, blockTitle, startAt, endAt, fixed ->
                scope.launch {
                    when (val res = repo.addManualTimeBlock(taskId, blockTitle, startAt, endAt, fixed)) {
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

    draftHolder.value?.let { editable ->
        DraftEditDialog(
            editable = editable,
            applyMessage = applyMessage,
            onApply = {
                scope.launch {
                    // Snapshot the mutable dialog state into an immutable
                    // apply request so the data layer never depends on
                    // Compose snapshot state.
                    val res = repo.applyEditedScheduleDraft(editable.toApplyRequest())
                    applyMessage = when (res) {
                        is ApplyResult.Applied -> "已应用 ${res.placedBlocks} 个时间块。"
                        is ApplyResult.NoOp -> res.reason
                        is ApplyResult.Conflict -> "与「${res.fixedBlockTitle}」冲突，未应用。"
                        is ApplyResult.InvalidProposal -> res.reason
                    }
                }
            },
            onDismiss = {
                draftHolder.value = null
                applyMessage = null
                draftGenerationMessage = null
            },
            onCancelRow = { row -> row.setRemoved(true) },
            onRestoreRow = { row -> row.setRemoved(false) },
            onChangeRowTime = { row, newStart, newEnd -> row.setTime(newStart, newEnd) }
        )
    }

    // Surface the generation message as a Snackbar-like text band on the day view.
    draftGenerationMessage?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

private fun tasksById(tasks: List<TaskEntity>, id: String): String =
    tasks.firstOrNull { it.id == id }?.title ?: "安排"

private fun buildWeekSummaries(
    selected: LocalDate,
    blocks: List<TimeBlockEntity>
): WeekSummary {
    val (start, _) = PlanDate.weekRange(selected)
    val zone = PlanDate.zone()
    val byDay = blocks.groupBy { PlanDate.toLocalDate(it.startAt, zone) }
    val days = (0 until 7).map { i ->
        val d = start.plusDays(i.toLong())
        val dayBlocks = byDay[d].orEmpty()
        DaySummary(
            date = d,
            blocks = dayBlocks,
            hasFixed = dayBlocks.any { it.fixed },
            hasRecurring = dayBlocks.any { it.source == "recurring" }
        )
    }
    return WeekSummary(start, days)
}

@Composable
private fun DayViewSection(
    summary: DaySummary,
    dayBlocks: List<TimeBlockEntity>,
    tasks: List<TaskEntity>,
    blocks: List<TimeBlockEntity>,
    onRemoveBlock: (TimeBlockEntity) -> Unit,
    onAddBlock: () -> Unit,
    onGenerateDraft: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(PlanDate.dayLabel(summary.date), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onGenerateDraft) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("生成排期草案")
            }
            TextButton(onClick = onAddBlock) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("添加时间块")
            }
        }
        Spacer(Modifier.height(8.dp))
        DayBlocksPane(
            blocks = dayBlocks,
            onRemoveBlock = onRemoveBlock
        )
        Spacer(Modifier.height(12.dp))
        Text("未安排的任务", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val unscheduled = tasks.filter { it.status == "open" && blocks.none { b -> b.taskId == it.id } }
        if (unscheduled.isEmpty()) {
            Text(
                "没有未安排的任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            ) {
                items(items = unscheduled, key = { it.id }) { task ->
                    UnscheduledTaskRow(task)
                }
            }
        }
    }
}

@Composable
private fun WeekViewSection(
    summary: WeekSummary,
    today: LocalDate,
    selectedDate: LocalDate,
    onPickDay: (LocalDate) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("本周安排", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        WeekStripView(
            summary = summary,
            today = today,
            selectedDate = selectedDate,
            onPickDay = onPickDay
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "共 ${summary.totalBlocks} 个时间块，" +
                "合计 ${formatMinutes(summary.totalMinutes)}。点选某一天查看详情。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MonthViewSection(
    summary: MonthSummary,
    today: LocalDate,
    selectedDate: LocalDate,
    onPickDay: (LocalDate) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("本月", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        MonthGridView(
            summary = summary,
            today = today,
            selectedDate = selectedDate,
            onPickDay = onPickDay
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "格子里的数字是该日本地时间块的数量；点选切到日视图。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun produceCapacity(
    @Suppress("UNUSED_PARAMETER") repo: YinianRepository,
    block: suspend () -> CapacitySnapshot
): androidx.compose.runtime.State<CapacitySnapshot?> {
    val state = remember { androidx.compose.runtime.mutableStateOf<CapacitySnapshot?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
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

internal fun formatMinutes(min: Int): String {
    if (min <= 0) return "0 分"
    val h = min / 60
    val m = min % 60
    return if (h > 0) "${h}小时${m}分" else "${m}分"
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
private fun AddBlockDialog(
    tasks: List<TaskEntity>,
    defaultDayStart: Long,
    conflict: TimeBlockEntity?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (taskId: String?, title: String, startAt: Long, endAt: Long, fixed: Boolean) -> Unit
) {
    var blockTitle by remember { mutableStateOf("专注时间") }
    var startHour by remember { mutableStateOf(9) }
    var endHour by remember { mutableStateOf(10) }
    var fixed by remember { mutableStateOf(false) }
    var linkedTaskId by remember { mutableStateOf<String?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
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
                onConfirm(linkedTaskId, blockTitle.ifBlank { "时间块" }, startAt, endAt, fixed)
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("添加时间块") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
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
                                androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
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
                OutlinedTextField(value = blockTitle, onValueChange = { blockTitle = it }, label = { Text("标题") })
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