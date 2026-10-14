package com.yinian.android.ui.capture

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.yinian.android.data.AppContainer
import com.yinian.android.util.DurationFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Capture sheet: text and voice. Truthful behavior:
 *  - Voice requires RECORD_AUDIO permission. If denied, shows a clear message and a "how to fix" hint.
 *  - When stopped, the recording is finalized in app-private storage. If it fails, the partial file
 *    is deleted and the user sees an error rather than a fake success.
 *  - No ASR is performed. The saved idea carries transcriptStatus = "disabled" so the UI can
 *    show "transcription not configured" until a real provider is integrated.
 */
@Composable
fun CaptureSheet(
    container: AppContainer,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf<CaptureMode>(CaptureMode.Idle) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var elapsedMs by remember { mutableStateOf(0L) }
    val recorder = container.audioRecorder

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val file = recorder.start()
            if (file == null) {
                error = "录音启动失败"
                mode = CaptureMode.Idle
            } else {
                mode = CaptureMode.Recording
            }
        } else {
            error = "需要麦克风权限才能录音"
            mode = CaptureMode.Idle
        }
    }

    // Ticking elapsed counter while recording.
    LaunchedEffect(mode) {
        if (mode == CaptureMode.Recording) {
            val start = recorder.startedAt
            while (mode == CaptureMode.Recording) {
                elapsedMs = System.currentTimeMillis() - start
                delay(200)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x5520281C))
            .clickable(enabled = true, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 20.dp, shape = RoundedCornerShape(topStart = 29.dp, topEnd = 29.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 29.dp, topEnd = 29.dp))
                .clickable(enabled = false, onClick = {})
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp, bottom = 24.dp)
                .semantics { contentDescription = "闪念录入" }
        ) {
            // Handle
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("记录闪念", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("关闭")
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("随手记下一个想法…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                shape = RoundedCornerShape(16.dp)
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        if (text.isBlank()) {
                            error = "内容不能为空"
                            return@TextButton
                        }
                        scope.launch {
                            container.repository.createIdea(
                                title = text.take(40).trim(),
                                body = text.trim(),
                                source = "text"
                            )
                            onDismiss()
                        }
                    }
                ) {
                    Icon(Icons.Outlined.Check, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("保存为闪念")
                }
            }

            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Spacer(Modifier.height(12.dp))

            when (mode) {
                CaptureMode.Idle -> {
                    Text(
                        "或用一段话记下来",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        BigMicButton(
                            onClick = {
                                error = null
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    val file = recorder.start()
                                    if (file == null) error = "录音启动失败"
                                    else mode = CaptureMode.Recording
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "转写未配置；录音会保存到闪念条目，可查看和删除。V1 暂不提供播放。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                CaptureMode.Recording -> {
                    Text(
                        "正在录音… ${DurationFormatter.formatMs(elapsedMs)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .clickable {
                                    val file: File? = recorder.stop()
                                    if (file == null) {
                                        error = "录音失败，原文件已删除"
                                        mode = CaptureMode.Idle
                                    } else {
                                        mode = CaptureMode.Review(file.absolutePath)
                                    }
                                }
                                .semantics { contentDescription = "停止并保存录音" },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Stop, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.width(24.dp))
                        TextButton(onClick = {
                            recorder.cancel()
                            mode = CaptureMode.Idle
                            elapsedMs = 0L
                        }) { Text("放弃") }
                    }
                }
                is CaptureMode.Review -> {
                    val recordedPath = (mode as CaptureMode.Review).path
                    Text("录音已保存", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "转写未配置。音频已保存到应用私有目录，可在「闪念」页回看和删除。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ReviewActions(
                        filePathProvider = { recordedPath },
                        onSaveAsIdea = { path ->
                            scope.launch {
                                container.repository.createIdea(
                                    title = "语音闪念 ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())}",
                                    body = "",
                                    source = "voice",
                                    recordingPath = path,
                                    transcriptStatus = "disabled"
                                )
                                onDismiss()
                            }
                        },
                        onRedo = {
                            recorder.cancel()
                            mode = CaptureMode.Idle
                            elapsedMs = 0L
                        }
                    )
                }
            }
            error?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            status?.let { msg ->
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private sealed class CaptureMode {
    data object Idle : CaptureMode()
    data object Recording : CaptureMode()
    data class Review(val path: String) : CaptureMode()
}

@Composable
private fun BigMicButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(80.dp)
            .shadow(elevation = 8.dp, shape = CircleShape)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "开始录音" },
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Outlined.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun ReviewActions(
    filePathProvider: () -> String?,
    onSaveAsIdea: (String) -> Unit,
    onRedo: () -> Unit
) {
    val path = filePathProvider()
    if (path == null) {
        Text("录音文件不可用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onSaveAsIdea(path) }) {
            Icon(Icons.Outlined.Check, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("保存为闪念")
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRedo) {
            Text("重录")
        }
    }
}
