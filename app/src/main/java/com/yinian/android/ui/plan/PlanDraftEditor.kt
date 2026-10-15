package com.yinian.android.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yinian.android.planner.Placement
import com.yinian.android.ui.common.SoftCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Stable test tags for the edit dialog controls. */
object DraftDialogTestTags {
    const val APPLY = "draft-apply"
    const val DISCARD = "draft-discard"
    const val ROW_PREFIX = "draft-row-"
    const val REMOVE_PREFIX = "draft-remove-"
}

/**
 * Editable preview of a schedule draft. Lets the user adjust the date /
 * start time / end time of any suggestion, or remove it entirely. Apply
 * defers all writes until the user confirms; nothing is persisted until
 * the apply call completes successfully.
 */
@Composable
fun DraftEditDialog(
    editable: EditableDraft,
    applyMessage: String?,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    onCancelRow: (EditablePlacement) -> Unit,
    onRestoreRow: (EditablePlacement) -> Unit,
    onChangeRowTime: (EditablePlacement, newStartMs: Long, newEndMs: Long) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val windowStart = remember(editable) { LocalDate.parse(editable.original.windowStartDay) }
    val windowEnd = remember(editable) { LocalDate.parse(editable.original.windowEndDay) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("排期草案（可编辑）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "本草案由本地规则生成，逐条确认后再应用。预览和编辑不会写入数据库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (editable.rows.isEmpty()) {
                    Text("没有可安排的项。", style = MaterialTheme.typography.bodySmall)
                }
                editable.rows.forEach { row ->
                    EditablePlacementRow(
                        row = row,
                        zone = zone,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        onCancel = { onCancelRow(row) },
                        onRestore = { onRestoreRow(row) },
                        onChangeTime = { newStart, newEnd ->
                            onChangeRowTime(row, newStart, newEnd)
                        }
                    )
                }
                if (editable.unscheduled.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("未安排", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    for (u in editable.unscheduled) {
                        UnscheduledRow(u)
                    }
                }
                applyMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onApply,
                enabled = editable.activeRows.isNotEmpty(),
                modifier = Modifier
                    .semantics { contentDescription = "应用排期草案" }
                    .testTag(DraftDialogTestTags.APPLY)
            ) { Text("应用") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .semantics { contentDescription = "放弃排期草案" }
                    .testTag(DraftDialogTestTags.DISCARD)
            ) { Text("放弃") }
        }
    )
}

@Composable
private fun EditablePlacementRow(
    row: EditablePlacement,
    zone: ZoneId,
    windowStart: LocalDate,
    windowEnd: LocalDate,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
    onChangeTime: (Long, Long) -> Unit
) {
    val rowTestTag = DraftDialogTestTags.ROW_PREFIX + row.itemId
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag(rowTestTag),
        cornerRadius = 14.dp,
        edgeThickness = 2.dp
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (row.removed) "${row.title}（已移除）" else row.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (row.removed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        row.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (row.removed) {
                    TextButton(onClick = onRestore) {
                        Text("恢复")
                    }
                } else {
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .semantics { contentDescription = "移除「${row.title}」" }
                            .testTag(DraftDialogTestTags.REMOVE_PREFIX + row.itemId)
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = null)
                    }
                }
            }
            if (!row.removed) {
                Spacer(Modifier.height(6.dp))
                DateTimeEditor(
                    startMs = row.startMs,
                    endMs = row.endMs,
                    zone = zone,
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    onChange = onChangeTime
                )
            }
        }
    }
}

@Composable
private fun UnscheduledRow(u: Placement.Unscheduled) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(u.title, style = MaterialTheme.typography.bodyMedium)
            Text(
                unscheduledReasonText(u.reason),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}

/**
 * Inline editor for a row's date / start / end. The user can pick a
 * different date inside the planning window via [+/-] buttons, or nudge
 * start/end by ±15 minutes.
 */
@Composable
private fun DateTimeEditor(
    startMs: Long,
    endMs: Long,
    zone: ZoneId,
    windowStart: LocalDate,
    windowEnd: LocalDate,
    onChange: (Long, Long) -> Unit
) {
    val dateFmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US) }
    val timeFmt = remember { DateTimeFormatter.ofPattern("HH:mm", Locale.US) }
    var startDate by remember(startMs) {
        mutableStateOf(Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate())
    }
    var startTime by remember(startMs) {
        mutableStateOf(Instant.ofEpochMilli(startMs).atZone(zone).toLocalTime())
    }
    var endTime by remember(endMs) {
        mutableStateOf(Instant.ofEpochMilli(endMs).atZone(zone).toLocalTime())
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun push() {
        val newStartMs = startDate.atTime(startTime).atZone(zone).toInstant().toEpochMilli()
        val newEndMs = startDate.atTime(endTime).atZone(zone).toInstant().toEpochMilli()
        if (newEndMs <= newStartMs) {
            error = "结束时间必须晚于开始时间。"
            return
        }
        error = null
        onChange(newStartMs, newEndMs)
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("日期", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(dateFmt.format(startDate), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    val next = startDate.minusDays(1)
                    if (!next.isBefore(windowStart)) {
                        startDate = next
                        push()
                    }
                }
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = "前一天")
            }
            IconButton(
                onClick = {
                    val next = startDate.plusDays(1)
                    if (!next.isAfter(windowEnd)) {
                        startDate = next
                        push()
                    }
                }
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "后一天")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开始", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(timeFmt.format(startTime), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    startTime = startTime.minusMinutes(15).coerceAtLeast(LocalTime.MIN)
                    push()
                }
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = "开始时间提前 15 分")
            }
            IconButton(
                onClick = {
                    startTime = startTime.plusMinutes(15)
                    push()
                }
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "开始时间推迟 15 分")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("结束", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(8.dp))
            Text(timeFmt.format(endTime), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    endTime = endTime.minusMinutes(15)
                    push()
                }
            ) {
                Icon(Icons.Outlined.Remove, contentDescription = "结束时间提前 15 分")
            }
            IconButton(
                onClick = {
                    endTime = endTime.plusMinutes(15).coerceAtMost(LocalTime.MAX)
                    push()
                }
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "结束时间推迟 15 分")
            }
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}