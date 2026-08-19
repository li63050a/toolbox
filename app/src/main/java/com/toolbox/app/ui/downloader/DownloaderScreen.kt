package com.toolbox.app.ui.downloader

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toolbox.app.R
import com.toolbox.app.downloader.DlStatus
import com.toolbox.app.downloader.DownloadManager
import com.toolbox.app.downloader.DownloadTask
import kotlinx.coroutines.launch

private val Context.dlStore by preferencesDataStore(name = "downloader")
private val KEY_DIR = stringPreferencesKey("dir")

private fun defaultDir(): String = "/storage/emulated/0/Download"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    DownloadManager.init(context)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tasks by DownloadManager.tasks.collectAsState()
    val dirError by DownloadManager.dirError.collectAsState()
    var dir by remember { mutableStateOf(defaultDir()) }
    var showDirDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        context.dlStore.data.collect { prefs ->
            dir = prefs[KEY_DIR] ?: defaultDir()
        }
    }
    LaunchedEffect(dirError) {
        dirError?.let {
            snackbar.showSnackbar(it)
            DownloadManager.clearDirError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dl_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dl_back)) }
                },
                actions = {
                    IconButton(onClick = { showDirDialog = true }) {
                        Icon(Icons.Filled.Folder, stringResource(R.string.dl_set_dir))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, stringResource(R.string.dl_add))
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.dl_dir_is, dir),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            if (tasks.isEmpty()) {
                Text(
                    stringResource(R.string.dl_empty),
                    Modifier.fillMaxWidth().padding(top = 48.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    itemsIndexed(tasks, key = { i, t -> t.id.ifBlank { "task_$i" } }) { _, task ->
                        DownloadTaskCard(task, onPauseResume = {
                            if (task.status == DlStatus.RUNNING) DownloadManager.pause(task.id) else DownloadManager.start(task.id)
                        }, onDelete = { DownloadManager.remove(task.id) })
                    }
                }
            }
        }
    }

    if (showDirDialog) {
        DirDialog(initial = dir, onDismiss = { showDirDialog = false }, onConfirm = { newDir ->
            dir = newDir
            scope.launch { context.dlStore.edit { it[KEY_DIR] = newDir } }
            showDirDialog = false
        })
    }
    if (showAddDialog) {
        AddDialog(
            dir = dir,
            onDismiss = { showAddDialog = false },
            onConfirm = { url, threads ->
                DownloadManager.add(url, dir, threads)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onPauseResume: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.fileName.ifBlank { task.url },
                    Modifier.weight(1f).padding(end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onPauseResume, Modifier.size(32.dp)) {
                    Icon(
                        if (task.status == DlStatus.RUNNING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null
                    )
                }
                IconButton(onClick = onDelete, Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                }
            }
            if (task.total > 0) {
                val progress = (task.downloaded.toFloat() / task.total).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.dl_progress, formatSize(task.downloaded), formatSize(task.total)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        speedText(task),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                statusText(task),
                style = MaterialTheme.typography.labelSmall,
                color = when (task.status) {
                    DlStatus.RUNNING -> MaterialTheme.colorScheme.primary
                    DlStatus.DONE -> Color(0xFF4CAF50)
                    DlStatus.FAILED -> MaterialTheme.colorScheme.error
                    DlStatus.PAUSED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun statusText(task: DownloadTask): String = when (task.status) {
    DlStatus.RUNNING -> stringResource(R.string.dl_state_running)
    DlStatus.PAUSED -> stringResource(R.string.dl_state_paused)
    DlStatus.DONE -> stringResource(R.string.dl_state_done)
    DlStatus.FAILED -> stringResource(R.string.dl_state_failed, task.error ?: stringResource(R.string.dl_unknown_error))
}

@Composable
private fun speedText(task: DownloadTask): String =
    if (task.status == DlStatus.RUNNING) stringResource(R.string.dl_speed, formatSize(task.speed)) else ""

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

@Composable
private fun DirDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dl_dir_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.dl_dir_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.dl_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dl_cancel)) }
        }
    )
}

@Composable
private fun AddDialog(dir: String, onDismiss: () -> Unit, onConfirm: (String, Int) -> Unit) {
    var url by remember { mutableStateOf("") }
    var threads by remember { mutableIntStateOf(4) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dl_add_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.dl_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.dl_threads_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { threads = (threads - 1).coerceAtLeast(1) }) { Text("−") }
                    Text("$threads", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { threads = (threads + 1).coerceAtMost(16) }) { Text("+") }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, Modifier.size(16.dp))
                    Text(
                        dir,
                        Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(url, threads) }) { Text(stringResource(R.string.dl_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dl_cancel)) }
        }
    )
}