package com.toolbox.app.ui.filebrowser

import com.toolbox.app.R
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long
)

/**
 * 文件操作抽象，由各后端（SFTP/FTP/对象存储）实现。
 * 所有方法在 IO 线程执行，进度回调在调用线程。
 */
interface FileOps {
    /** 列表；rootPath 由 backend 决定 */
    suspend fun list(path: String): Result<List<FileEntry>>
    suspend fun mkdir(path: String): Result<Unit>
    suspend fun delete(path: String): Result<Unit>
    suspend fun rename(oldPath: String, newName: String): Result<Unit>
    /** url 已经带上了远程路径 */
    suspend fun download(remotePath: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit>
    suspend fun upload(remoteDir: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit>
    /** 是否支持修改权限（仅 SFTP 支持） */
    val supportsChmod: Boolean get() = false
    /** 修改权限，mode 为 3 位八进制值（如 0o755） */
    suspend fun chmod(path: String, mode: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException("当前后端不支持修改权限"))
    fun rootPath(): String
    fun displayName(): String
    fun close()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    ops: FileOps,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var currentPath by remember { mutableStateOf(ops.rootPath()) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var transferring by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showNewDir by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf<FileEntry?>(null) }
    var showDelete by remember { mutableStateOf<FileEntry?>(null) }
    var showChmod by remember { mutableStateOf<FileEntry?>(null) }
    var chmodText by remember { mutableStateOf("") }
    var newDirName by remember { mutableStateOf("") }
    var renameTo by remember { mutableStateOf("") }

    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    fun refresh() {
        scope.launch {
            loading = true
            withContext(Dispatchers.IO) { ops.list(currentPath) }
                .onSuccess { entries = it }
                .onFailure { toast(context.getString(R.string.file_list_fail, it.message)) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                transferring = true
                progress = 0f
                withContext(Dispatchers.IO) { ops.upload(currentPath, uri) { p -> progress = p } }
                    .onSuccess { toast(context.getString(R.string.file_upload_done)) }
                    .onFailure { toast(context.getString(R.string.file_upload_fail, it.message)) }
                transferring = false
                refresh()
            }
        }
    }

    var lastClickedPathCont by remember { mutableStateOf("") }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val entry = entries.firstOrNull { it.path == lastClickedPathCont }
            if (entry == null) return@rememberLauncherForActivityResult
            scope.launch {
                transferring = true
                progress = 0f
                withContext(Dispatchers.IO) { ops.download(entry.path, uri) { p -> progress = p } }
                    .onSuccess { toast(context.getString(R.string.file_download_done)) }
                    .onFailure { toast(context.getString(R.string.file_download_fail, it.message)) }
                transferring = false
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ops.displayName(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            currentPath,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.file_back)) }
                },
                actions = {
                    IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.file_refresh)) }
                    IconButton(onClick = {
                        lastClickedPathCont = currentPath
                        uploadLauncher.launch(arrayOf("*/*"))
                    }) { Icon(Icons.Filled.Upload, stringResource(R.string.file_upload)) }
                    IconButton(onClick = { showNewDir = true }) { Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.file_new_dir)) }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                entries.isEmpty() -> Text(
                    stringResource(R.string.file_empty),
                    Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge
                )

                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(entries, key = { i, e -> e.path.ifBlank { "item_$i" } }) { _, entry ->
                        FileRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    currentPath = entry.path
                                    refresh()
                                } else {
                                    lastClickedPathCont = entry.path
                                    downloadLauncher.launch(entry.name)
                                }
                            },
                            onRename = { showRename = entry },
                            onDelete = { showDelete = entry },
                            chmodEnabled = ops.supportsChmod,
                            onChmod = {
                                showChmod = entry
                                chmodText = ""
                            }
                        )
                    }
                }
            }
            if (transferring) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.file_transferring, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    if (showNewDir) {
        SimpleInputDialog(
            title = stringResource(R.string.file_new_dir),
            value = newDirName,
            onValueChange = { newDirName = it },
            confirm = {
                scope.launch {
                    showNewDir = false
                    withContext(Dispatchers.IO) { ops.mkdir("${currentPath.trimEnd('/')}/${newDirName.trim()}") }
                        .onSuccess { toast(context.getString(R.string.file_created)); refresh() }
                        .onFailure { toast(context.getString(R.string.file_create_fail, it.message)) }
                    newDirName = ""
                }
            },
            dismiss = { showNewDir = false; newDirName = "" }
        )
    }

    showRename?.let { entry ->
        SimpleInputDialog(
            title = stringResource(R.string.file_rename),
            value = renameTo.ifEmpty { entry.name },
            onValueChange = { renameTo = it },
            confirm = {
                scope.launch {
                    showRename = null
                    withContext(Dispatchers.IO) {
                        ops.rename(entry.path, renameTo.trim())
                    }.onSuccess { toast(context.getString(R.string.file_renamed)); refresh() }
                        .onFailure { toast(context.getString(R.string.file_rename_fail, it.message)) }
                    renameTo = ""
                }
            },
            dismiss = { showRename = null; renameTo = "" }
        )
    }

    showDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text(stringResource(R.string.file_delete)) },
            text = { Text(stringResource(R.string.file_delete_confirm, entry.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        showDelete = null
                        withContext(Dispatchers.IO) { ops.delete(entry.path) }
                            .onSuccess { toast(context.getString(R.string.file_deleted)); refresh() }
                            .onFailure { toast(context.getString(R.string.file_delete_fail, it.message)) }
                    }
                }) { Text(stringResource(R.string.file_delete)) }
            },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text(stringResource(R.string.file_cancel)) } }
        )
    }

    showChmod?.let { entry ->
        AlertDialog(
            onDismissRequest = { showChmod = null; chmodText = "" },
            title = { Text(stringResource(R.string.file_chmod)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_chmod_hint, entry.name))
                    OutlinedTextField(
                        value = chmodText,
                        onValueChange = { chmodText = it.filter { c -> c in '0'..'7' }.take(3) },
                        singleLine = true,
                        label = { Text(stringResource(R.string.file_permission)) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = chmodText.length == 3,
                    onClick = {
                        scope.launch {
                            showChmod = null
                            val mode = chmodText.toInt(8)
                            withContext(Dispatchers.IO) { ops.chmod(entry.path, mode) }
                                .onSuccess { toast(context.getString(R.string.file_chmod_done)); refresh() }
                                .onFailure { toast(context.getString(R.string.file_chmod_fail, it.message)) }
                            chmodText = ""
                        }
                    }
                ) { Text(stringResource(R.string.file_ok)) }
            },
            dismissButton = { TextButton(onClick = { showChmod = null; chmodText = "" }) { Text(stringResource(R.string.file_cancel)) } }
        )
    }
}

@Composable
private fun SimpleInputDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    confirm: () -> Unit,
    dismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = onValueChange, singleLine = true) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.file_ok)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.file_cancel)) } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: FileEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    chmodEnabled: Boolean,
    onChmod: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth()
                .then(
                    if (chmodEnabled) Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true })
                    else Modifier.clickable(onClick = onClick)
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.isDirectory) {
                        stringResource(R.string.file_dir_with_size, sizeText(entry.size))
                    } else {
                        "${sizeText(entry.size)} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.modified * 1000))}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, stringResource(R.string.file_rename), Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, stringResource(R.string.file_delete), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
        if (chmodEnabled) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.file_chmod_menu)) }, onClick = { menu = false; onChmod() })
            }
        }
    }
}

fun sizeText(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", size / 1024.0)
    size < 1024 * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", size / 1024.0 / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", size / 1024.0 / 1024.0 / 1024.0)
}

fun logOps(ops: FileOps, action: String, path: String) {
    Log.i("FileOps", "${ops.displayName()} $action $path ({${ops.javaClass.simpleName}})")
}