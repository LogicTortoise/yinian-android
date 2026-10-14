package com.yinian.android.ui.ideas

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
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.yinian.android.data.entity.IdeaEntity
import com.yinian.android.ui.common.PillBadge
import com.yinian.android.ui.common.SoftCard
import com.yinian.android.util.DateUtil
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun IdeasScreen(container: AppContainer) {
    val repo = container.repository
    val ideas by repo.observeIdeas().collectAsState(initial = emptyList())
    val tasks by repo.observeActiveTasks().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<IdeaEntity?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(Modifier.height(20.dp)) }
        item {
            Column {
                Text("闪念", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "原文、来源和后续整理都留在这里。示例数据是「开始」就有的种子。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (ideas.isEmpty()) {
            item {
                SoftCard(modifier = Modifier.fillMaxWidth()) {
                    Text("还没有任何闪念。", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "点下面的「说说你的想法」开始记录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(items = ideas, key = { it.id }) { idea ->
                IdeaRow(
                    idea = idea,
                    onClick = { detail = idea },
                    onConvert = {
                        scope.launch {
                            repo.convertIdeaToTask(idea.id)
                            detail = null
                        }
                    },
                    onDelete = {
                        scope.launch { repo.deleteIdea(idea) }
                        detail = null
                    }
                )
            }
        }
        item { Spacer(Modifier.height(140.dp)) }
    }

    detail?.let { idea ->
        IdeaDetailDialog(
            idea = idea,
            tasks = tasks,
            onDismiss = { detail = null },
            onConvert = {
                scope.launch {
                    repo.convertIdeaToTask(idea.id)
                    detail = null
                }
            },
            onDelete = {
                scope.launch {
                    repo.deleteIdea(idea)
                    detail = null
                }
            }
        )
    }
}

@Composable
private fun IdeaRow(
    idea: IdeaEntity,
    onClick: () -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit
) {
    SoftCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "闪念 ${idea.title}" }
    ) {
        Row(verticalAlignment = Alignment.Top) {
            SourceIcon(idea.source)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        DateUtil.dateTimeLabel(idea.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    PillBadge(when (idea.source) {
                        "voice" -> "语音"
                        "text" -> "文字"
                        else -> "系统"
                    })
                    if (idea.transcriptStatus == "disabled") {
                        Spacer(Modifier.width(6.dp))
                        PillBadge("转写未配置", background = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.tertiary)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(idea.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (idea.body.isNotBlank() && idea.body != idea.title) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        idea.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onConvert) {
                        Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("转为任务")
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceIcon(source: String) {
    val (bg, fg, icon) = when (source) {
        "voice" -> Triple(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer, Icons.Outlined.MicNone)
        else -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.AutoMirrored.Outlined.Subject)
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(bg, RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = fg)
    }
}

@Composable
private fun IdeaDetailDialog(
    idea: IdeaEntity,
    tasks: List<com.yinian.android.data.entity.TaskEntity>,
    onDismiss: () -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit
) {
    val linkedTask = tasks.firstOrNull { it.derivedFromIdeaId == idea.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConvert) { Text("转为任务") }
        },
        dismissButton = {
            TextButton(onClick = onDelete) { Text("删除") }
        },
        title = { Text(idea.title) },
        text = {
            Column {
                if (idea.body.isNotBlank()) {
                    Text(idea.body, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PillBadge(when (idea.source) { "voice" -> "语音"; else -> "文字" })
                    PillBadge(DateUtil.dateTimeLabel(idea.createdAt))
                    if (idea.transcriptStatus == "disabled") PillBadge("转写未配置")
                }
                if (idea.recordingPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "录音文件：" + (idea.recordingPath.substringAfterLast('/')),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (linkedTask != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已派生任务：${linkedTask.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
