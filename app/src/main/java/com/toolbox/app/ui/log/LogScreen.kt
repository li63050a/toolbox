package com.toolbox.app.ui.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.toolbox.app.log.Log
import com.toolbox.app.log.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CrashLogInfo(val file: File, val name: String, val sizeText: String)

private fun levelText(level: Int): String = when (level) {
    Log.LEVEL_DEBUG -> "D"
    Log.LEVEL_INFO -> "I"
    Log.LEVEL_WARN -> "W"
    else -> "E"
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1024) String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else "$bytes B"

private fun timestampText(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

private fun scanCrashLogs(): List<CrashLogInfo> {
    val dir = Log.logDirFile() ?: return emptyList()
    return dir.listFiles { f -> f.isFile && f.name.startsWith("crash_") && f.name.endsWith(".log") }
        ?.sortedByDescending { it.lastModified() }
        ?.map { CrashLogInfo(it, it.name, formatSize(it.length())) }
        ?: emptyList()
}

private fun buildExportText(entries: List<LogEntry>): String {
    val sb = StringBuilder()
    for (e in entries) {
        sb.append(e.timeText).append("  ").append(e.levelText).append('/')
            .append(e.tag).append(": ").append(e.message).append('\n')
        e.throwable?.let { sb.append(it).append('\n') }
    }
    return sb.toString()
}

private fun shareTextFile(context: Context, file: File, mime: String, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle)
    chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(chooser)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var filter by remember { mutableStateOf<Int?>(null) }
    val allEntries by Log.entries.collectAsState()
    val visible = remember(allEntries, filter) {
        if (filter == null) allEntries else allEntries.filter { it.level == filter }
    }

    var crashLogs by remember { mutableStateOf<List<CrashLogInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        crashLogs = withContext(Dispatchers.IO) { scanCrashLogs() }
    }

    var showClearDialog by remember { mutableStateOf(false) }
    var crashDialog by remember { mutableStateOf<CrashLogInfo?>(null) }
    var crashContent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(crashDialog) {
        val crash = crashDialog ?: return@LaunchedEffect
        crashContent = null
        crashContent = withContext(Dispatchers.IO) {
            runCatching { crash.file.readText() }.getOrDefault("读取失败")
        }
    }

    val expandedStacks = remember { mutableStateMapOf<LogEntry, Boolean>() }

    val listState = rememberLazyListState()
    var lastCount by remember { mutableIntStateOf(-1) }
    LaunchedEffect(visible.size) {
        if (lastCount != -1 && visible.size > lastCount) {
            listState.animateScrollToItem(visible.size - 1)
        }
        lastCount = visible.size
    }

    fun exportCurrent() {
        if (visible.isEmpty()) {
            scope.launch { snackbar.showSnackbar("没有可导出的日志") }
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = buildExportText(visible)
                    val file = File(context.cacheDir, "app_log_${timestampText()}.txt")
                    file.writeText(text)
                    file
                }
            }
            result.onSuccess { file ->
                runCatching { shareTextFile(context, file, "text/plain", "分享日志") }
                    .onSuccess { snackbar.showSnackbar("已导出 ${visible.size} 条日志") }
                    .onFailure { e ->
                        Log.e("LogScreen", "分享日志失败", e)
                        snackbar.showSnackbar("分享失败：${e.message}")
                    }
            }.onFailure { e ->
                Log.e("LogScreen", "导出日志失败", e)
                snackbar.showSnackbar("导出失败：${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { exportCurrent() }) {
                        Icon(Icons.Filled.Share, "导出")
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.DeleteSweep, "清空")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val filters = listOf<Int?>(null, Log.LEVEL_DEBUG, Log.LEVEL_INFO, Log.LEVEL_WARN, Log.LEVEL_ERROR)
                filters.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(f?.let { levelText(it) } ?: "全部") }
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                if (crashLogs.isNotEmpty()) {
                    item(key = "crash_header") {
                        Text(
                            "崩溃日志",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(crashLogs, key = { it.name }) { info ->
                        CrashCard(info) { crashDialog = info }
                    }
                    item(key = "crash_divider") {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                }
                item(key = "log_header") {
                    Text(
                        "运行日志",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                if (visible.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                        )
                    }
                } else {
                    itemsIndexed(visible) { index, entry ->
                        LogRow(
                            entry = entry,
                            expanded = expandedStacks[entry] == true,
                            onToggle = { expandedStacks[entry] = expandedStacks[entry] != true }
                        )
                        if (index < visible.size - 1) {
                            HorizontalDivider(
                                Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
                item(key = "hint") {
                    Text(
                        "日志保存在 /data/data/${context.packageName}/files/logs/ （崩溃日志 crash_*.log 亦在此）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("确定要清空所有日志吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    Log.clear()
                    showClearDialog = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    crashDialog?.let { crash ->
        AlertDialog(
            onDismissRequest = {
                crashDialog = null
                crashContent = null
            },
            title = { Text(crash.name) },
            text = {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 420.dp)
                ) {
                    Text(
                        "大小：${crash.sizeText}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        crashContent ?: "加载中…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    crashDialog = null
                    crashContent = null
                }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(onClick = {
                    runCatching { shareTextFile(context, crash.file, "text/plain", "分享崩溃日志") }
                        .onFailure { e ->
                            Log.e("LogScreen", "分享崩溃日志失败", e)
                            scope.launch { snackbar.showSnackbar("分享失败：${e.message}") }
                        }
                }) { Text("分享") }
            }
        )
    }
}

@Composable
private fun CrashCard(info: CrashLogInfo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                info.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                info.sizeText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, expanded: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                entry.timeText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LevelBadge(entry.level)
            Text(
                entry.tag,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
        entry.throwable?.let { stack ->
            Spacer(Modifier.height(4.dp))
            Text(
                stack,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun LevelBadge(level: Int) {
    val container = when (level) {
        Log.LEVEL_DEBUG -> MaterialTheme.colorScheme.surfaceVariant
        Log.LEVEL_INFO -> MaterialTheme.colorScheme.primary
        Log.LEVEL_WARN -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val content = when (level) {
        Log.LEVEL_DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        Log.LEVEL_INFO -> MaterialTheme.colorScheme.onPrimary
        Log.LEVEL_WARN -> MaterialTheme.colorScheme.onTertiary
        else -> MaterialTheme.colorScheme.onError
    }
    Box(
        Modifier
            .background(container, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
    ) {
        Text(
            levelText(level),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            fontWeight = FontWeight.Bold
        )
    }
}
