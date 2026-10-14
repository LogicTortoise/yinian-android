package com.yinian.android.ui.me

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yinian.android.data.AppContainer
import com.yinian.android.data.entity.WorkPolicyEntity
import com.yinian.android.data.prefs.PrefsRepository
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import com.yinian.android.util.DateUtil
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun MeScreen(
    container: AppContainer,
    prefs: PrefsRepository
) {
    val themeMode by prefs.themeMode.collectAsState(initial = PrefsRepository.THEME_SYSTEM)
    val workStart by prefs.workStartMin.collectAsState(initial = 9 * 60)
    val workEnd by prefs.workEndMin.collectAsState(initial = 18 * 60)
    val focusDefault by prefs.focusDefaultMin.collectAsState(initial = 25)
    val autoSuggest by prefs.autoSuggest.collectAsState(initial = false)
    val calendarEnabled by prefs.calendarEnabled.collectAsState(initial = false)
    val calendarGranted by prefs.calendarGranted.collectAsState(initial = false)
    val repo = container.repository
    val context = LocalContext.current
    val workPolicies by repo.observeWorkPolicies().collectAsState(initial = emptyList())
    val rules by repo.observeActiveRecurringRules().collectAsState(initial = emptyList())
    val ideasCount by repo.observeIdeas().collectAsState(initial = emptyList())
    val tasksCount by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val focusHistory by repo.observeAllFocus().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var showWorkDialog by remember { mutableStateOf(false) }
    var showFocusDialog by remember { mutableStateOf(false) }
    var showCapabilitiesDialog by remember { mutableStateOf(false) }
    var showWorkPoliciesDialog by remember { mutableStateOf(false) }
    var showRecurringDialog by remember { mutableStateOf(false) }
    var showReviewDialog by remember { mutableStateOf(false) }

    val calendarLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch { prefs.setCalendarGranted(granted) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text("我的", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        SoftCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("一念", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("本地优先", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "录音、任务和偏好都存在这台设备上。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillBadge("闪念 ${ideasCount.size}")
                PillBadge("任务 ${tasksCount.size}")
                PillBadge("专注 ${focusHistory.count { it.status == "completed" }}")
            }
        }
        Spacer(Modifier.height(12.dp))

        GroupCard(title = "工作时间") {
            SettingRow(
                title = "工作时段",
                value = DateUtil.formatMinutesOfDay(workStart) + " - " + DateUtil.formatMinutesOfDay(workEnd),
                icon = Icons.Outlined.Schedule,
                onClick = { showWorkDialog = true }
            )
            Divider()
            SettingRow(
                title = "每周工作规则",
                value = workPolicies.takeIf { it.isNotEmpty() }
                    ?.joinToString("、") { dowLabel(it.dayOfWeek) + " " + DateUtil.formatMinutesOfDay(it.startMin) + "-" + DateUtil.formatMinutesOfDay(it.endMin) }
                    ?: "未配置",
                icon = Icons.Outlined.CalendarMonth,
                onClick = { showWorkPoliciesDialog = true }
            )
        }
        Spacer(Modifier.height(10.dp))

        GroupCard(title = "重复事项") {
            SettingRow(
                title = "重复规则",
                value = if (rules.isEmpty()) "无" else "${rules.size} 条",
                icon = Icons.Outlined.Repeat,
                onClick = { showRecurringDialog = true }
            )
        }
        Spacer(Modifier.height(10.dp))

        GroupCard(title = "专注") {
            SettingRow(
                title = "默认专注时长",
                value = "$focusDefault 分钟",
                icon = Icons.Outlined.SelfImprovement,
                onClick = { showFocusDialog = true }
            )
        }
        Spacer(Modifier.height(10.dp))

        GroupCard(title = "主题") {
            ThemeRow("跟随系统", themeMode == PrefsRepository.THEME_SYSTEM) {
                scope.launch { prefs.setThemeMode(PrefsRepository.THEME_SYSTEM) }
            }
            Divider()
            ThemeRow("浅色", themeMode == PrefsRepository.THEME_LIGHT) {
                scope.launch { prefs.setThemeMode(PrefsRepository.THEME_LIGHT) }
            }
            Divider()
            ThemeRow("深色", themeMode == PrefsRepository.THEME_DARK) {
                scope.launch { prefs.setThemeMode(PrefsRepository.THEME_DARK) }
            }
        }
        Spacer(Modifier.height(10.dp))

        GroupCard(title = "能力") {
            SwitchRow(
                title = "本地启发式推荐",
                description = "在「现在」页根据优先级、截止、依赖和估时给出一个有理由的推荐。本地运行，不上传。",
                checked = autoSuggest,
                onChange = { scope.launch { prefs.setAutoSuggest(it) } }
            )
            Divider()
            // ASR is not configured in V1. We expose a state row (not a switch) so the
            // user can see the current state without being misled into thinking a toggle
            // would activate speech recognition.
            AsrStatusRow()
            Divider()
            SwitchRow(
                title = "只读日历忙碌时间",
                description = if (calendarEnabled && calendarGranted) {
                    "已开启。系统日历里的事件会被视为忙碌，不会写入。"
                } else if (calendarEnabled && !calendarGranted) {
                    "未授权。日历开关仍可保留，不会写入日历。"
                } else {
                    "关闭后只使用本地计划；授权可随时撤销。"
                },
                checked = calendarEnabled && calendarGranted,
                onChange = { enabled ->
                    scope.launch {
                        if (enabled) {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.READ_CALENDAR
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                prefs.setCalendarGranted(true)
                                prefs.setCalendarEnabled(true)
                            } else {
                                prefs.setCalendarEnabled(true)
                                calendarLauncher.launch(Manifest.permission.READ_CALENDAR)
                            }
                        } else {
                            prefs.setCalendarEnabled(false)
                        }
                    }
                }
            )
            Divider()
            SettingRow(
                title = "查看当前能力",
                value = "未配置项",
                icon = Icons.Outlined.Tune,
                onClick = { showCapabilitiesDialog = true }
            )
        }
        Spacer(Modifier.height(10.dp))

        GroupCard(title = "回顾") {
            SettingRow(
                title = "本周专注回顾",
                value = "本周实际 / 计划容量",
                icon = Icons.Outlined.SelfImprovement,
                onClick = { showReviewDialog = true }
            )
        }
        Spacer(Modifier.height(140.dp))
    }

    if (showWorkDialog) {
        TimeRangeDialog(
            title = "工作时段",
            initialStartMin = workStart,
            initialEndMin = workEnd,
            onDismiss = { showWorkDialog = false },
            onConfirm = { s, e ->
                scope.launch {
                    prefs.setWorkStart(s)
                    prefs.setWorkEnd(e)
                }
                showWorkDialog = false
            }
        )
    }
    if (showFocusDialog) {
        NumberDialog(
            title = "默认专注时长（分钟）",
            initial = focusDefault,
            onDismiss = { showFocusDialog = false },
            onConfirm = {
                scope.launch { prefs.setFocusDefault(it) }
                showFocusDialog = false
            }
        )
    }
    if (showCapabilitiesDialog) {
        AlertDialog(
            onDismissRequest = { showCapabilitiesDialog = false },
            confirmButton = { TextButton(onClick = { showCapabilitiesDialog = false }) { Text("好") } },
            title = { Text("未配置的能力") },
            text = {
                Column {
                    Text("V1 不假装接入了下列能力：", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "云端语音转写（ASR）",
                        "云端 LLM 整理 / 挑战",
                        "跨设备同步与账号",
                        "服务器排期 / MCP 接入",
                        "可靠定时提醒（依赖系统能力）",
                        "应用使用情况采集"
                    ).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        )
    }

    if (showWorkPoliciesDialog) {
        WorkPoliciesDialog(
            initial = workPolicies,
            onDismiss = { showWorkPoliciesDialog = false },
            onSave = { list ->
                scope.launch {
                    repo.saveWorkPolicies(list)
                    showWorkPoliciesDialog = false
                }
            }
        )
    }

    if (showRecurringDialog) {
        RecurringRulesDialog(
            container = container,
            initial = rules,
            onDismiss = { showRecurringDialog = false }
        )
    }

    if (showReviewDialog) {
        com.yinian.android.ui.review.FocusReviewDialog(
            container = container,
            onDismiss = { showReviewDialog = false }
        )
    }
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

@Composable
private fun GroupCard(title: String, content: @Composable () -> Unit) {
    SoftCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18.dp, edgeThickness = 2.dp) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun SettingRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp)
            .semantics { contentDescription = "$title，当前 $value" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp)
            .semantics { contentDescription = "$title，当前 ${if (checked) "开" else "关"}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Capability row for cloud ASR. V1 has no ASR provider wired up, so we do not
 * expose a switch — a switch would imply that flipping it activates speech
 * recognition. Instead we show the stored preference as informational state
 * and a clear "未配置" badge so the user sees what is and isn't wired up.
 */
@Composable
private fun AsrStatusRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics { contentDescription = "语音转写能力，未配置" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("语音转写（云端 ASR）", style = MaterialTheme.typography.bodyLarge)
            Text(
                "V1 没有接入 ASR 服务商。打开偏好不会激活转写；录音仍会保存，应用不会伪造转写文字。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PillBadge(
            text = "未配置",
            background = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.tertiary
        )
    }
}

@Composable
private fun ThemeRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 10.dp)
            .semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (selected) PillBadge("当前")
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
private fun TimeRangeDialog(
    title: String,
    initialStartMin: Int,
    initialEndMin: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var startMin by remember { mutableStateOf(initialStartMin) }
    var endMin by remember { mutableStateOf(initialEndMin) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(startMin, endMin) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            Column {
                Text("开始 " + DateUtil.formatMinutesOfDay(startMin))
                SliderRow(value = startMin, range = 0..1430, step = 30, onChange = { startMin = it })
                Text("结束 " + DateUtil.formatMinutesOfDay(endMin))
                SliderRow(value = endMin, range = 0..1430, step = 30, onChange = { endMin = it })
            }
        }
    )
}

@Composable
private fun SliderRow(value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit) {
    androidx.compose.material3.Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = ((range.last - range.first) / step) - 1
    )
}

@Composable
private fun NumberDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var v by remember { mutableStateOf(initial.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(v.toIntOrNull() ?: initial) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(value = v, onValueChange = { v = it.filter { c -> c.isDigit() }.take(3) })
        }
    )
}

/**
 * Per-weekday work-hours editor. Each weekday has an enabled toggle, a
 * working window (startMin / endMin), a rest-buffer, and a daily cap.
 * Defaults are derived from the V1 single-window preference so the first
 * save doesn't reset the user's existing setting.
 */
@Composable
private fun WorkPoliciesDialog(
    initial: List<WorkPolicyEntity>,
    onDismiss: () -> Unit,
    onSave: (List<WorkPolicyEntity>) -> Unit
) {
    val initialMap = initial.associateBy { it.dayOfWeek }
    fun defaultsFor(dow: Int): WorkPolicyEntity {
        return initialMap[dow] ?: WorkPolicyEntity(
            dayOfWeek = dow,
            enabled = dow in 1..5,
            startMin = 9 * 60,
            endMin = 18 * 60,
            bufferMinutes = 0,
            dailyCapMinutes = 0
        )
    }
    var mon by remember { mutableStateOf(defaultsFor(1)) }
    var tue by remember { mutableStateOf(defaultsFor(2)) }
    var wed by remember { mutableStateOf(defaultsFor(3)) }
    var thu by remember { mutableStateOf(defaultsFor(4)) }
    var fri by remember { mutableStateOf(defaultsFor(5)) }
    var sat by remember { mutableStateOf(defaultsFor(6)) }
    var sun by remember { mutableStateOf(defaultsFor(7)) }

    val rows = listOf(mon, tue, wed, thu, fri, sat, sun)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(rows) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("每周工作规则") },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Text(
                    "每天单独配置：可用时段、休息缓冲、当日上限。休息日留为关闭即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                DayRow("周一", mon) { mon = it }
                DayRow("周二", tue) { tue = it }
                DayRow("周三", wed) { wed = it }
                DayRow("周四", thu) { thu = it }
                DayRow("周五", fri) { fri = it }
                DayRow("周六", sat) { sat = it }
                DayRow("周日", sun) { sun = it }
            }
        }
    )
}

@Composable
private fun DayRow(label: String, policy: WorkPolicyEntity, onChange: (WorkPolicyEntity) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.width(48.dp))
        Switch(checked = policy.enabled, onCheckedChange = { onChange(policy.copy(enabled = it)) })
        if (policy.enabled) {
            Text(DateUtil.formatMinutesOfDay(policy.startMin) + "–" + DateUtil.formatMinutesOfDay(policy.endMin), style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.Slider(
                value = policy.startMin.toFloat(),
                onValueChange = { onChange(policy.copy(startMin = it.toInt().coerceAtMost(policy.endMin - 30))) },
                valueRange = 0f..1380f,
                steps = 45
            )
            androidx.compose.material3.Slider(
                value = policy.endMin.toFloat(),
                onValueChange = { onChange(policy.copy(endMin = it.toInt().coerceAtLeast(policy.startMin + 30))) },
                valueRange = 60f..1440f,
                steps = 45
            )
            Text("缓冲 ${policy.bufferMinutes} 分钟", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.Slider(
                value = policy.bufferMinutes.toFloat(),
                onValueChange = { onChange(policy.copy(bufferMinutes = it.toInt())) },
                valueRange = 0f..60f,
                steps = 11
            )
            val capLabel = if (policy.dailyCapMinutes == 0) "无" else "${policy.dailyCapMinutes / 60}h${policy.dailyCapMinutes % 60}m"
            Text("上限 $capLabel", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.Slider(
                value = policy.dailyCapMinutes.toFloat(),
                onValueChange = { onChange(policy.copy(dailyCapMinutes = it.toInt())) },
                valueRange = 0f..720f,
                steps = 23
            )
        }
    }
}

/**
 * Recurring-rule list and quick editor. The dialog lets the user add a
 * daily / weekday / weekly rule that immediately becomes a candidate for
 * the planner. Existing rules can be archived (which stops future instance
 * generation but keeps history).
 */
@Composable
private fun RecurringRulesDialog(
    container: AppContainer,
    initial: List<com.yinian.android.data.entity.RecurringRuleEntity>,
    onDismiss: () -> Unit
) {
    val repo = container.repository
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var estimateText by remember { mutableStateOf("30") }
    var priority by remember { mutableStateOf(3) }
    var kind by remember { mutableStateOf("daily") }
    var byDayMask by remember { mutableStateOf(0b11111) } // Mon..Fri

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) return@TextButton
                scope.launch {
                    repo.createRecurringRule(
                        title = title.trim(),
                        estimateMinutes = estimateText.toIntOrNull()?.coerceAtLeast(0) ?: 30,
                        priority = priority,
                        domain = "general",
                        kind = kind,
                        byDayMask = byDayMask,
                        startDayKey = LocalDate.now().toString(),
                        horizonDays = 14
                    )
                    title = ""
                    estimateText = "30"
                    priority = 3
                }
            }) { Text("新增") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        title = { Text("重复规则") },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                if (initial.isEmpty()) {
                    Text(
                        "还没有规则。下面新增一个后，V2 排期会自动按规则生成实例。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    initial.forEach { rule ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rule.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${rule.kind} · 估时 ${rule.estimateMinutes} 分 · 优先级 ${rule.priority}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                scope.launch { repo.archiveRecurringRule(rule.id) }
                            }) { Text("归档") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("新增规则", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("标题") })
                OutlinedTextField(
                    value = estimateText,
                    onValueChange = { estimateText = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("估时（分钟）") }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("优先级")
                    for (p in 1..5) {
                        TextButton(onClick = { priority = p }) {
                            Text(if (priority == p) "✓ $p" else "$p")
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("频率")
                    TextButton(onClick = { kind = "daily" }) { Text(if (kind == "daily") "✓ 每天" else "每天") }
                    TextButton(onClick = { kind = "weekday" }) { Text(if (kind == "weekday") "✓ 工作日" else "工作日") }
                    TextButton(onClick = { kind = "weekly" }) { Text(if (kind == "weekly") "✓ 每周" else "每周") }
                }
                if (kind == "weekly") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { idx, label ->
                            val bit = 1 shl idx
                            TextButton(onClick = { byDayMask = byDayMask xor bit }) {
                                Text(if (byDayMask and bit != 0) "✓ $label" else label)
                            }
                        }
                    }
                }
            }
        }
    )
}
