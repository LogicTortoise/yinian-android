package com.yinian.android.ui.now

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.entity.FocusSessionEntity
import com.yinian.android.data.entity.RelationEntity
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import com.yinian.android.ui.tasks.TaskEditorDialog
import com.yinian.android.util.DateUtil
import com.yinian.android.util.DurationFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun NowScreen(
    container: AppContainer,
    onJumpToIdeas: () -> Unit
) {
    val repo = container.repository
    val tasks by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val relations by repo.observeRelations().collectAsState(initial = emptyList())
    val activeFocus by repo.observeActiveFocus().collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var editingTaskId by remember { mutableStateOf<String?>(null) }
    var creatingNewTask by remember { mutableStateOf(false) }

    val (recommendation, reasons) = remember(tasks, relations) {
        pickRecommendation(tasks, relations)
    }
    val upcoming = remember(tasks, recommendation?.id) {
        tasks.filter { it.id != recommendation?.id && it.status == "open" }
            .sortedWith(compareBy({ it.priority }, { it.dueAt ?: Long.MAX_VALUE }))
    }

    var elapsed by remember { mutableLongStateOf(activeFocus?.let { elapsedFor(it) } ?: 0L) }
    val ticking = activeFocus?.status == "active"
    LaunchedEffect(activeFocus?.id, activeFocus?.activeStartedAt, ticking) {
        if (activeFocus != null && ticking) {
            while (true) {
                elapsed = elapsedFor(activeFocus!!)
                delay(500)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(20.dp)) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("现在", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        DateUtil.weekDayLabel(System.currentTimeMillis()) + " · " +
                            SimpleDateFormat("M月d日", Locale.US).format(Date()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { creatingNewTask = true }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("任务")
                }
            }
        }

        if (activeFocus != null) {
            item {
                FocusCard(
                    session = activeFocus!!,
                    elapsedMs = elapsed,
                    onPause = { scope.launch { repo.pauseFocus(activeFocus!!.id) } },
                    onResume = { scope.launch { repo.resumeFocus(activeFocus!!.id) } },
                    onEnd = { scope.launch { repo.endFocus(activeFocus!!.id) } },
                    onCancel = { scope.launch { repo.cancelFocus(activeFocus!!.id) } }
                )
            }
        }

        item {
            RecommendationCard(
                task = recommendation,
                reasons = reasons,
                canStartFocus = activeFocus == null,
                onStartFocus = { scope.launch { repo.startFocus(recommendation?.id) } },
                onSkip = onJumpToIdeas,
                onOpen = { recommendation?.id?.let { editingTaskId = it } }
            )
        }

        item { Text("接下来", style = MaterialTheme.typography.titleMedium) }

        if (upcoming.isEmpty()) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "没有更多待办了。可以记一个闪念，或在「计划」页手动安排。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(items = upcoming, key = { it.id }) { task ->
                UpcomingRow(
                    task = task,
                    relations = relations,
                    tasks = tasks,
                    onOpen = { editingTaskId = task.id }
                )
            }
        }
        item { Spacer(Modifier.height(140.dp)) }
    }

    if (creatingNewTask) {
        TaskEditorDialog(
            container = container,
            taskId = null,
            onDismiss = { creatingNewTask = false }
        )
    }
    editingTaskId?.let { id ->
        TaskEditorDialog(
            container = container,
            taskId = id,
            onDismiss = { editingTaskId = null }
        )
    }
}

private fun elapsedFor(s: FocusSessionEntity): Long {
    // pausedMs holds the total active ms accumulated before the current segment.
    val start = s.activeStartedAt ?: return s.pausedMs
    val delta = System.currentTimeMillis() - start
    return s.pausedMs + (if (delta > 0) delta else 0L)
}

@Composable
private fun FocusCard(
    session: FocusSessionEntity,
    elapsedMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit
) {
    val paused = session.status == "paused"
    SoftCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("专注中", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (paused) "已暂停" else "进行中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(DurationFormatter.formatMs(elapsedMs), style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "计时本身不标记任务完成；结束时请你确认是否完成。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) PillAction("继续", Icons.Outlined.PlayArrow, onClick = onResume)
            else PillAction("暂停", Icons.Outlined.Pause, onClick = onPause)
            PillAction("结束", Icons.Outlined.Stop, onClick = onEnd)
            PillAction("放弃", Icons.Outlined.Close, onClick = onCancel)
        }
    }
}

@Composable
private fun PillAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun RecommendationCard(
    task: TaskEntity?,
    reasons: List<String>,
    canStartFocus: Boolean,
    onStartFocus: () -> Unit,
    onSkip: () -> Unit,
    onOpen: () -> Unit
) {
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = task != null, onClick = onOpen)
            .semantics { contentDescription = task?.title?.let { "推荐：$it，点开编辑" } ?: "" },
        cornerRadius = 25.dp,
        edgeThickness = 4.dp
    ) {
        if (task == null) {
            Text("没有可以推荐的事项", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "在没有任务、依赖或时间约束时，本地不会强行制造一个「下一步」。" +
                    "先记个闪念，或者去「计划」页手动排一下。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(task.title, style = MaterialTheme.typography.titleLarge)
            if (task.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(task.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillBadge("优先级 ${task.priority}")
                if (task.estimateMinutes > 0) PillBadge("约 ${task.estimateMinutes} 分钟")
                if (task.dueAt != null) PillBadge("截止 " + DateUtil.dateTimeLabel(task.dueAt))
            }
            if (reasons.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("为什么是这条", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                reasons.forEach { r ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("· ", style = MaterialTheme.typography.bodySmall)
                        Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canStartFocus) {
                    PillAction("开始专注", Icons.Outlined.PlayArrow, onClick = onStartFocus)
                } else {
                    PillBadge("已有专注计时")
                }
                PillAction("先不想", Icons.Outlined.Close, onClick = onSkip)
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    task: TaskEntity,
    relations: List<RelationEntity>,
    tasks: List<TaskEntity>,
    onOpen: () -> Unit
) {
    val blocking = remember(task.id, relations) { blockingFor(task.id, relations) }
    val blocked = blocking.isNotEmpty()
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { contentDescription = "任务 ${task.title}，点开编辑" },
        cornerRadius = 18.dp,
        edgeThickness = 2.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (blocked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(task.title, style = MaterialTheme.typography.titleSmall)
                if (blocked) {
                    val names = blocking.mapNotNull { id -> tasks.firstOrNull { it.id == id }?.title }
                    Text(
                        "等待：" + names.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val parts = mutableListOf<String>()
                    parts += "优先级 ${task.priority}"
                    if (task.estimateMinutes > 0) parts += "约 ${task.estimateMinutes} 分钟"
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun blockingFor(taskId: String, relations: List<RelationEntity>): Set<String> =
    relations.filter { it.kind == "depends_on" && it.fromId == taskId }.map { it.toId }.toSet()

/**
 * Deterministic recommendation. Considers user priority, due pressure, and unlock value.
 * Tasks blocked by unfinished dependencies are skipped. If every open task is blocked,
 * returns null with a reason so the user understands why.
 */
private fun pickRecommendation(
    tasks: List<TaskEntity>,
    relations: List<RelationEntity>
): Pair<TaskEntity?, List<String>> {
    val open = tasks.filter { it.status == "open" }
    if (open.isEmpty()) return null to emptyList()

    val blockedBy = mutableMapOf<String, Set<String>>()
    relations.filter { it.kind == "depends_on" }.forEach { r ->
        blockedBy[r.fromId] = (blockedBy[r.fromId] ?: emptySet()) + r.toId
    }
    val notDoneIds = open.map { it.id }.toSet()
    val blockedIds = blockedBy.filterValues { ids -> ids.any { it in notDoneIds } }.keys

    val now = System.currentTimeMillis()
    val candidates = open.filter { it.id !in blockedIds }
    if (candidates.isEmpty()) {
        return null to listOf("所有进行中的任务都被前置依赖阻塞。可以在任务详情中查看依赖。")
    }

    data class Scored(val task: TaskEntity, val score: Double, val reasons: List<String>)
    val scored = candidates.map { t ->
        var score = 0.0
        val reasons = mutableListOf<String>()
        score += (6 - t.priority) * 10.0
        reasons += "用户优先级 ${t.priority}"
        t.dueAt?.let { due ->
            val hours = (due - now) / 3_600_000.0
            val pressure = when {
                hours < 0 -> 30.0
                hours < 24 -> 25.0
                hours < 72 -> 15.0
                else -> 5.0
            }
            score += pressure
            reasons += if (hours < 0) "已经超过截止时间" else "还有 ${"%.0f".format(hours)} 小时到期"
        }
        val unblocks = relations.count { it.kind == "depends_on" && it.toId == t.id }
        if (unblocks > 0) {
            score += unblocks * 6.0
            reasons += "完成后可解锁 $unblocks 个任务"
        }
        if (t.estimateMinutes in 1..45) {
            score += 4.0
            reasons += "估时 ${t.estimateMinutes} 分钟，可快速完成"
        }
        Scored(t, score, reasons)
    }.sortedByDescending { it.score }

    return scored.first().task to scored.first().reasons
}
