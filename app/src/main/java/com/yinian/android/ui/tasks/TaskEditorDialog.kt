package com.yinian.android.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.entity.TaskEntity
import com.yinian.android.data.repo.DependencyCheck
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.util.DateUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Local task editor. Handles both creation and editing without leaving the
 * current screen. The dialog surfaces:
 *  - title, notes, priority (1..5), estimate in minutes, optional due date
 *  - explicit complete / reopen and archive / delete actions when editing
 *  - a Dependencies section with a sub-dialog for adding / removing prerequisites
 *
 * Completion here is an explicit user action and is independent of focus timer completion.
 */
@Composable
fun TaskEditorDialog(
    container: AppContainer,
    taskId: String? = null,
    onDismiss: () -> Unit
) {
    val repo = container.repository
    val scope = rememberCoroutineScope()

    val existingFlow: Flow<TaskEntity?> = remember(taskId) {
        if (taskId != null) repo.observeTask(taskId) else flowOf(null)
    }
    val existing by existingFlow.collectAsState(initial = null)

    val allTasks by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val relations by repo.observeRelations().collectAsState(initial = emptyList())

    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(3) }
    var estimateText by remember { mutableStateOf("30") }
    var dueEnabled by remember { mutableStateOf(false) }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var hydrated by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeps by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(existing?.id) {
        if (existing != null && !hydrated) {
            val t = existing!!
            title = t.title
            notes = t.notes
            priority = t.priority
            estimateText = t.estimateMinutes.toString()
            dueEnabled = t.dueAt != null
            dueAt = t.dueAt
            hydrated = true
        }
    }
    // Auto-close when the underlying task is deleted/archived from another path.
    LaunchedEffect(existing?.status, existing) {
        if (existing?.status == "archived" && hydrated) {
            onDismiss()
        }
    }

    val estimateMinutes = estimateText.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val canSave = title.isNotBlank()
    val isEditing = existing != null
    val loadingExisting = taskId != null && existing == null
    val isRecurringInstance = isEditing && existing!!.recurringRuleId != null && existing!!.instanceDate != null
    val prerequisites = remember(existing?.id, relations) {
        existing?.let { repo.blockingFor(it.id, relations) } ?: emptySet()
    }
    val prerequisiteNames = remember(prerequisites, allTasks) {
        prerequisites.mapNotNull { id -> allTasks.firstOrNull { it.id == id }?.title }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = canSave && !loadingExisting,
                onClick = {
                    if (loadingExisting) return@TextButton
                    scope.launch {
                        if (existing == null) {
                            repo.createTask(
                                title = title.trim(),
                                notes = notes.trim(),
                                priority = priority,
                                estimateMinutes = estimateMinutes,
                                dueAt = if (dueEnabled) dueAt else null
                            )
                            onDismiss()
                        } else if (isRecurringInstance) {
                            // V2-ME: editing a recurring instance writes the
                            // override row, not the parent rule. The parent
                            // rule and its other instances are not touched.
                            repo.setInstanceOverride(
                                ruleId = existing!!.recurringRuleId!!,
                                dayKey = existing!!.instanceDate!!,
                                title = title.trim(),
                                estimateMinutes = estimateMinutes
                            )
                            // Mirror the user's title/notes/priority/due back
                            // onto the generated task row so other screens
                            // (now, plan) see the latest values.
                            val updated = existing!!.copy(
                                title = title.trim(),
                                notes = notes.trim(),
                                priority = priority,
                                estimateMinutes = estimateMinutes,
                                dueAt = if (dueEnabled) dueAt else null
                            )
                            repo.updateTask(updated)
                            onDismiss()
                        } else {
                            val updated = existing!!.copy(
                                title = title.trim(),
                                notes = notes.trim(),
                                priority = priority,
                                estimateMinutes = estimateMinutes,
                                dueAt = if (dueEnabled) dueAt else null
                            )
                            repo.updateTask(updated)
                            onDismiss()
                        }
                    }
                }
            ) { Text(if (isEditing) "保存" else "创建") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isEditing && existing!!.status == "open") {
                    TextButton(onClick = {
                        scope.launch {
                            repo.completeTask(existing!!.id)
                            onDismiss()
                        }
                    }) { Text("完成") }
                }
                if (isEditing && existing!!.status == "done") {
                    TextButton(onClick = {
                        scope.launch {
                            repo.reopenTask(existing!!.id)
                            onDismiss()
                        }
                    }) { Text("撤销完成") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = {
            Text(
                if (isEditing) "编辑任务" else "新建任务",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            if (loadingExisting) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "正在加载任务…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isEditing) {
                    TaskStatusLine(existing!!)
                }
                if (isRecurringInstance) {
                    RecurringInstanceNotice(instanceDate = existing!!.instanceDate!!)
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("说明") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("优先级", style = MaterialTheme.typography.labelLarge)
                PriorityRow(priority = priority, onChange = { priority = it })

                OutlinedTextField(
                    value = estimateText,
                    onValueChange = { v -> estimateText = v.filter { it.isDigit() }.take(4) },
                    label = { Text("估时（分钟）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("截止日期", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = dueEnabled, onCheckedChange = {
                        dueEnabled = it
                        if (it && dueAt == null) dueAt = DateUtil.startOfDay(System.currentTimeMillis())
                    })
                }
                if (dueEnabled) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                            .clickable { showDatePicker = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .semantics { contentDescription = "截止日期" },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Event, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            dueAt?.let { DateUtil.dateTimeLabel(it) } ?: "选择日期",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isEditing) {
                    DividerLine()
                    Text("前置依赖", style = MaterialTheme.typography.labelLarge)
                    if (prerequisiteNames.isEmpty()) {
                        Text(
                            "没有前置依赖。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            prerequisiteNames.forEach { name ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PillBadge(name, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showDeps = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("管理依赖")
                        }
                    }
                    DividerLine()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (existing!!.status != "archived") {
                            TextButton(onClick = {
                                scope.launch {
                                    repo.archiveTask(existing!!.id)
                                    onDismiss()
                                }
                            }) {
                                Icon(Icons.Outlined.Archive, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("归档")
                            }
                        }
                        if (isRecurringInstance) {
                            // V2-ME: cancel this specific instance. The
                            // parent rule and other instances are not
                            // touched; only this dayKey is suppressed.
                            TextButton(onClick = {
                                scope.launch {
                                    repo.setInstanceOverride(
                                        ruleId = existing!!.recurringRuleId!!,
                                        dayKey = existing!!.instanceDate!!,
                                        cancelled = true
                                    )
                                    repo.archiveTask(existing!!.id)
                                    onDismiss()
                                }
                            }) {
                                Icon(Icons.Outlined.Close, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("取消本次")
                            }
                        }
                        TextButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(4.dp))
                            Text("删除", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
            }
        }
    )

    if (showDeps && existing != null) {
        DependenciesDialog(
            container = container,
            taskId = existing!!.id,
            onDismiss = { showDeps = false }
        )
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.deleteTask(existing!!.id)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.tertiary) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
            title = { Text("删除任务") },
            text = {
                Text("这条任务及其依赖、安排都会被删除，无法撤销。")
            }
        )
    }

    if (showDatePicker) {
        DueDatePicker(
            initialMillis = dueAt ?: System.currentTimeMillis(),
            onConfirm = { newMillis ->
                dueAt = newMillis?.let { DateUtil.startOfDay(it) }
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDatePicker(
    initialMillis: Long,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(state.selectedDateMillis) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun TaskStatusLine(task: TaskEntity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PillBadge(
            when (task.status) {
                "done" -> "已完成"
                "archived" -> "已归档"
                else -> "进行中"
            },
            background = when (task.status) {
                "done" -> MaterialTheme.colorScheme.secondaryContainer
                "archived" -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = when (task.status) {
                "done" -> MaterialTheme.colorScheme.onSecondaryContainer
                "archived" -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onPrimaryContainer
            }
        )
        if (task.derivedFromIdeaId != null) {
            PillBadge("来自闪念", background = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable
private fun PriorityRow(priority: Int, onChange: (Int) -> Unit) {
    val labels = listOf(1 to "最高", 2 to "较高", 3 to "中", 4 to "较低", 5 to "最低")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        labels.forEach { (value, label) ->
            val selected = priority == value
            Box(
                modifier = Modifier
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { onChange(value) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics { contentDescription = "优先级 $label" }
            ) {
                Text(
                    "$value $label",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DividerLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * V2-ME: notice shown when the user is editing a single instance produced by
 * a recurring rule. Surfaces the dayKey and reminds the user that saving
 * here only changes this one instance.
 */
@Composable
private fun RecurringInstanceNotice(instanceDate: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "这条任务来自重复规则 $instanceDate，保存仅影响本次" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        PillBadge(
            "本次实例 $instanceDate",
            background = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "保存仅修改本次；规则和其他实例不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}

/**
 * Manages prerequisites of a task: lists existing ones with a remove action,
 * and lets the user add a new prerequisite (rejected on self-link or cycle
 * using the repository validator).
 */
@Composable
fun DependenciesDialog(
    container: AppContainer,
    taskId: String,
    onDismiss: () -> Unit
) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    val allTasks by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val relations by repo.observeRelations().collectAsState(initial = emptyList())

    val current = remember(taskId, relations) {
        // depends_on direction: from -> to means "to" must finish before "from".
        // To find prerequisites of `taskId`, look at relations where fromId == taskId.
        relations.filter { it.kind == "depends_on" && it.fromId == taskId }
            .map { it.toId }.toSet()
    }
    val byId = remember(allTasks) { allTasks.associateBy { it.id } }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
        title = { Text("管理前置依赖") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "完成所有前置依赖后，这条任务才能开始。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                DividerLine()
                Text("当前前置依赖", style = MaterialTheme.typography.labelLarge)
                if (current.isEmpty()) {
                    Text("（无）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    current.forEach { id ->
                        val t = byId[id]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PillBadge(t?.title ?: id, modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                scope.launch {
                                    repo.removeDependency(taskId, id)
                                }
                            }) {
                                Icon(Icons.Outlined.Close, contentDescription = "移除依赖")
                            }
                        }
                    }
                }
                DividerLine()
                Text("添加前置依赖", style = MaterialTheme.typography.labelLarge)
                val candidates = allTasks.filter { it.id != taskId && it.id !in current }
                if (candidates.isEmpty()) {
                    Text(
                        "没有可添加的任务。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    candidates.forEach { cand ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PillBadge(cand.title, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    val check = repo.addDependency(taskId, cand.id)
                                    error = when (check) {
                                        DependencyCheck.Ok -> null
                                        DependencyCheck.SelfLink -> "不能把任务设为自己的前置依赖。"
                                        DependencyCheck.Cycle -> "添加后会形成循环依赖，已拒绝。"
                                    }
                                }
                            }) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("添加")
                            }
                        }
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    )
}
