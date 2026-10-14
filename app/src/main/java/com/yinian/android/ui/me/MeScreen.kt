package com.yinian.android.ui.me

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yinian.android.data.AppContainer
import com.yinian.android.data.prefs.PrefsRepository
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import com.yinian.android.util.DateUtil
import kotlinx.coroutines.launch

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
    val repo = container.repository
    val ideasCount by repo.observeIdeas().collectAsState(initial = emptyList())
    val tasksCount by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val focusHistory by repo.observeAllFocus().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var showWorkDialog by remember { mutableStateOf(false) }
    var showFocusDialog by remember { mutableStateOf(false) }
    var showCapabilitiesDialog by remember { mutableStateOf(false) }

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
            SettingRow(
                title = "查看当前能力",
                value = "未配置项",
                icon = Icons.Outlined.Tune,
                onClick = { showCapabilitiesDialog = true }
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
