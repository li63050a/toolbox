package com.toolbox.app.ui.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.toolbox.app.R
import com.toolbox.app.RepositoryProvider
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ftp.FtpClient
import com.toolbox.app.ftp.FtpFileOps
import com.toolbox.app.ssh.SftpFileOps
import com.toolbox.app.ssh.SshEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

sealed interface FsTarget {
    data object Local : FsTarget
    data class Ssh(val cfg: ConnectionConfig.Ssh) : FsTarget
    data class Ftp(val cfg: ConnectionConfig.Ftp) : FsTarget

    fun label(): String = when (this) {
        is Local -> "本地存储"
        is Ssh -> "SSH · ${cfg.name.ifEmpty { cfg.host }}"
        is Ftp -> "FTP · ${cfg.name.ifEmpty { cfg.host }}"
    }
    
    fun icon() = when (this) {
        is Local -> Icons.Filled.FolderOpen
        is Ssh -> Icons.Filled.Terminal
        is Ftp -> Icons.Filled.Folder
    }
}

data class TransferAction(val type: TransferType, val entry: FileEntry, val fromSide: String) {
    enum class TransferType { DOWNLOAD, MOVE, COPY }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = RepositoryProvider.connections
    val all by repo.connections.collectAsState(initial = emptyList())
    
    var leftTarget by remember { mutableStateOf<FsTarget>(FsTarget.Local) }
    var rightTarget by remember { mutableStateOf<FsTarget>(FsTarget.Local) }
    var leftPath by remember { mutableStateOf(getStoragePath()) }
    var rightPath by remember { mutableStateOf(getStoragePath()) }
    var leftEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var rightEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var leftLoading by remember { mutableStateOf(false) }
    var rightLoading by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showActionMenu by remember { mutableStateOf(false) }
    var actionMenuSide by remember { mutableStateOf("left") }
    var showTargetMenu by remember { mutableStateOf<String?>(null) }
    var pendingTransfer by remember { mutableStateOf<TransferAction?>(null) }
    var snackbarHostState = remember { SnackbarHostState() }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> refreshFiles(leftTarget, leftPath, true); refreshFiles(rightTarget, rightPath, false) }

    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                })
            } catch (e: Exception) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            runtimePermLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    fun getStoragePath(): String = Environment.getExternalStorageDirectory().absolutePath

    fun loadLocalFiles(path: String, isLeft: Boolean) {
        thread {
            val entries = try {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) emptyList()
                else dir.listFiles()?.filter { it.name != "." && it.name != ".." }
                    ?.map { f ->
                        FileEntry(
                            path = f.absolutePath,
                            name = f.name,
                            isDirectory = f.isDirectory,
                            size = if (f.isFile) f.length() else 0L,
                            modified = f.lastModified()
                        )
                    }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            if (isLeft) leftEntries = entries else rightEntries = entries
        }.start()
    }

    fun loadRemoteFiles(target: FsTarget, path: String, isLeft: Boolean) {
        if (isLeft) leftLoading = true else rightLoading = true
        scope.launch(Dispatchers.IO) {
            try {
                val entries: List<FileEntry>? = when (target) {
                    is FsTarget.Ssh -> {
                        SshEngine(target.cfg).connect().getOrNull()?.let { session ->
                            SftpFileOps(context, session, target.cfg.name.ifEmpty { target.cfg.host }).list(path).getOrNull()
                        }
                    }
                    is FsTarget.Ftp -> {
                        FtpClient(target.cfg).connect().getOrNull()?.let { client ->
                            client.controlEncoding = "UTF-8"
                            FtpFileOps(context, client, target.cfg.name.ifEmpty { target.cfg.host }).list(path).getOrNull()
                        }
                    }
                    else -> null
                }
                withContext(Dispatchers.Main) {
                    if (isLeft) { leftEntries = entries ?: emptyList(); leftLoading = false }
                    else { rightEntries = entries ?: emptyList(); rightLoading = false }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isLeft) leftLoading = false else rightLoading = false
                }
            }
        }
    }

    fun refreshFiles(target: FsTarget, path: String, isLeft: Boolean) {
        when (target) {
            is FsTarget.Local -> loadLocalFiles(path, isLeft)
            else -> loadRemoteFiles(target, path, isLeft)
        }
    }

    LaunchedEffect(leftTarget, leftPath) { refreshFiles(leftTarget, leftPath, true) }
    LaunchedEffect(rightTarget, rightPath) { refreshFiles(rightTarget, rightPath, false) }

    // 执行传输
    fun executeTransfer(action: TransferAction) {
        scope.launch {
            val targetPath = if (action.fromSide == "left") rightPath else leftPath
            val targetOps = if (action.fromSide == "left") getOps(rightTarget, rightPath) else getOps(leftTarget, leftPath)
            
            if (targetOps == null) {
                snackbarHostState.showSnackbar("目标不可用")
                return@launch
            }

            val localUri = if (action.fromSide == "left") {
                Uri.parse(action.entry.path)
            } else {
                Uri.parse(action.entry.path)
            }

            val remotePath = if (action.type == TransferAction.TransferType.DOWNLOAD) {
                // 远程下载到本地
                action.entry.path
            } else {
                // 本地上传到远程
                "$targetPath/${action.entry.name}"
            }

            try {
                when (action.type) {
                    TransferAction.TransferType.DOWNLOAD -> {
                        // 从远程下载到本地
                        val localPath = "${getStoragePath()}/Download/${action.entry.name}"
                        val localFile = File(localPath)
                        localFile.parentFile?.mkdirs()
                        
                        val targetClient = when (val t = if (action.fromSide == "left") leftTarget else rightTarget) {
                            is FsTarget.Ftp -> FtpClient((t as FsTarget.Ftp).cfg).connect().getOrNull()
                            is FsTarget.Ssh -> SshEngine((t as FsTarget.Ssh).cfg).connect().getOrNull()
                            else -> null
                        }
                        
                        if (targetClient != null) {
                            when (targetClient) {
                                is org.apache.commons.net.ftp.FTPClient -> {
                                    val out = java.io.FileOutputStream(localFile)
                                    targetClient.retrieveFileStream(remotePath)?.use { input ->
                                        input.copyTo(out)
                                    }
                                    out.close()
                                    targetClient.completePendingCommand()
                                }
                            }
                            runCatching { targetClient.logout() }
                            runCatching { targetClient.disconnect() }
                        }
                        
                        snackbarHostState.showSnackbar("已下载到: $localPath")
                    }
                    TransferAction.TransferType.MOVE, TransferAction.TransferType.COPY -> {
                        // 从本地上传到远程
                        val sourceClient = when (val s = if (action.fromSide == "left") leftTarget else rightTarget) {
                            is FsTarget.Ftp -> FtpClient((s as FsTarget.Ftp).cfg).connect().getOrNull()
                            is FsTarget.Ssh -> SshEngine((s as FsTarget.Ssh).cfg).connect().getOrNull()
                            else -> null
                        }
                        
                        val destClient = when (val d = if (action.fromSide == "left") rightTarget else leftTarget) {
                            is FsTarget.Ftp -> FtpClient((d as FsTarget.Ftp).cfg).connect().getOrNull()
                            is FsTarget.Ssh -> SshEngine((d as FsTarget.Ssh).cfg).connect().getOrNull()
                            else -> null
                        }
                        
                        if (sourceClient != null && destClient != null) {
                            val localFile = File(action.entry.path)
                            if (localFile.exists()) {
                                val destPath = when (destClient) {
                                    is org.apache.commons.net.ftp.FTPClient -> {
                                        destClient.controlEncoding = "UTF-8"
                                        "${targetPath}/${action.entry.name}"
                                    }
                                    else -> "$targetPath/${action.entry.name}"
                                }
                                
                                val input = localFile.inputStream()
                                when (destClient) {
                                    is org.apache.commons.net.ftp.FTPClient -> {
                                        destClient.storeFileStream(destPath)?.use { out ->
                                            input.copyTo(out)
                                        }
                                        destClient.completePendingCommand()
                                    }
                                }
                                input.close()
                                
                                if (action.type == TransferAction.TransferType.MOVE) {
                                    localFile.delete()
                                }
                                
                                snackbarHostState.showSnackbar(if (action.type == TransferAction.TransferType.MOVE) "已移动到远程" else "已复制到远程")
                            }
                            
                            runCatching { sourceClient.logout() }
                            runCatching { sourceClient.disconnect() }
                            runCatching { destClient.logout() }
                            runCatching { destClient.disconnect() }
                        }
                    }
                }
                
                // 刷新列表
                refreshFiles(leftTarget, leftPath, true)
                refreshFiles(rightTarget, rightPath, false)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("操作失败: ${e.message}")
            }
        }
    }

    fun getOps(target: FsTarget, path: String): Any? = null

    // 目标选择菜单
    if (showTargetMenu != null) {
        AlertDialog(
            onDismissRequest = { showTargetMenu = null },
            title = { Text(if (showTargetMenu == "left") "选择左侧存储" else "选择右侧存储") },
            text = {
                Column {
                    StorageOption(Icons.Filled.FolderOpen, "本地存储", FsTarget.Local) { target ->
                        if (showTargetMenu == "left") {
                            leftTarget = target
                            leftPath = getStoragePath()
                        } else {
                            rightTarget = target
                            rightPath = getStoragePath()
                        }
                        showTargetMenu = null
                    }
                    Divider()
                    val sshList = all.filterIsInstance<ConnectionConfig.Ssh>()
                    val ftpList = all.filterIsInstance<ConnectionConfig.Ftp>()
                    if (sshList.isNotEmpty()) {
                        Text("SSH/SFTP", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
                        sshList.forEach { conn ->
                            StorageOption(Icons.Filled.Terminal, conn.name.ifEmpty { conn.host }, FsTarget.Ssh(conn)) { target ->
                                if (showTargetMenu == "left") leftTarget = target else rightTarget = target
                                showTargetMenu = null
                            }
                        }
                        Divider()
                    }
                    if (ftpList.isNotEmpty()) {
                        Text("FTP", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
                        ftpList.forEach { conn ->
                            StorageOption(Icons.Filled.Folder, conn.name.ifEmpty { conn.host }, FsTarget.Ftp(conn)) { target ->
                                if (showTargetMenu == "left") leftTarget = target else rightTarget = target
                                showTargetMenu = null
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTargetMenu = null }) { Text("取消") }
            }
        )
    }

    // 操作菜单 - 显示下载/移动选项
    if (showActionMenu && selectedEntry != null) {
        val isRemoteSource = (actionMenuSide == "left" && leftTarget !is FsTarget.Local) || (actionMenuSide == "right" && rightTarget !is FsTarget.Local)
        val isRemoteDest = (actionMenuSide == "left" && rightTarget !is FsTarget.Local) || (actionMenuSide == "right" && leftTarget !is FsTarget.Local)
        
        AlertDialog(
            onDismissRequest = { showActionMenu = false },
            title = { Text(selectedEntry!!.name) },
            text = {
                Column {
                    Text("类型: ${if (selectedEntry!!.isDirectory) "文件夹" else "文件"}")
                    if (!selectedEntry!!.isDirectory) Text("大小: ${formatSize(selectedEntry!!.size)}")
                    Text("路径: ${selectedEntry!!.path}")
                    
                    if (isRemoteSource || isRemoteDest) {
                        Spacer(Modifier.height(12.dp))
                        Text("可用操作:", style = MaterialTheme.typography.titleSmall)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showActionMenu = false }) { Text("关闭") }
                    if (isRemoteSource || isRemoteDest) {
                        Button(
                            onClick = { 
                                pendingTransfer = TransferAction(TransferAction.TransferType.DOWNLOAD, selectedEntry!!, actionMenuSide)
                                showActionMenu = false
                                executeTransfer(pendingTransfer!!)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) { Text("下载") }
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isRemoteSource || isRemoteDest) {
                        TextButton(onClick = { 
                            pendingTransfer = TransferAction(TransferAction.TransferType.MOVE, selectedEntry!!, actionMenuSide)
                            showActionMenu = false
                            executeTransfer(pendingTransfer!!)
                        }) { Text("移动", color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = { showActionMenu = false }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }

    // 权限不足提示
    if (!hasPermission() && (leftTarget is FsTarget.Local || rightTarget is FsTarget.Local)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Lock, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(16.dp))
                Text("需要存储权限", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("请授予权限以浏览文件", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { requestPermission() }) { Text("请求权限") }
            }
        }
        return
    }

    // 主界面
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_files_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.file_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showTargetMenu = "left" }) {
                        Icon(Icons.Filled.Menu, stringResource(R.string.file_more))
                    }
                }
            )
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            FilePanel(
                side = "左",
                target = leftTarget,
                path = leftPath,
                entries = leftEntries,
                loading = leftLoading,
                onNavigate = { leftPath = it },
                onClick = { selectedEntry = it },
                onLongClick = { selectedEntry = it; actionMenuSide = "left"; showActionMenu = true }
            )
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            FilePanel(
                side = "右",
                target = rightTarget,
                path = rightPath,
                entries = rightEntries,
                loading = rightLoading,
                onNavigate = { rightPath = it },
                onClick = { selectedEntry = it },
                onLongClick = { selectedEntry = it; actionMenuSide = "right"; showActionMenu = true }
            )
        }
    }
}

@Composable
private fun FilePanel(
    side: String,
    target: FsTarget,
    path: String,
    entries: List<FileEntry>,
    loading: Boolean,
    onNavigate: (String) -> Unit,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit
) {
    Column(modifier = Modifier.weight(1f).fillMaxSize()) {
        // 路径栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                target.icon(),
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                target.label(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                path,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = { onNavigate(File(path).parent ?: "/") }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                if (path != "/") {
                    item {
                        FileRow(
                            name = "..",
                            isDirectory = true,
                            size = 0,
                            modified = 0,
                            onClick = { onNavigate(File(path).parent ?: "/") },
                            onLongClick = {}
                        )
                    }
                }
                items(entries) { entry ->
                    FileRow(
                        name = entry.name,
                        isDirectory = entry.isDirectory,
                        size = entry.size,
                        modified = entry.modified,
                        onClick = { 
                            if (entry.isDirectory) onNavigate(entry.path) else onClick(entry)
                        },
                        onLongClick = { onLongClick(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    name: String,
    isDirectory: Boolean,
    size: Long,
    modified: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .longClickable(onClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isDirectory) Icons.Filled.Folder else getFileIcon(name),
            contentDescription = null,
            tint = if (isDirectory) Color(0xFFFFA000) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!isDirectory) {
                Text(
                    formatSize(size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        if (isDirectory) {
            Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(18.dp), tint = Color.Gray.copy(alpha = 0.6f))
        }
    }
}

private fun getFileIcon(name: String) = when {
    name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".flac", true) -> Icons.Filled.MusicNote
    name.endsWith(".mp4", true) || name.endsWith(".avi", true) || name.endsWith(".mkv", true) -> Icons.Filled.VideoLibrary
    name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".gif", true) -> Icons.Filled.Image
    name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    name.endsWith(".zip", true) || name.endsWith(".rar", true) || name.endsWith(".7z", true) -> Icons.Filled.Compress
    name.endsWith(".txt", true) || name.endsWith(".md", true) -> Icons.Filled.Description
    name.endsWith(".apk", true) -> Icons.Filled.Android
    name.endsWith(".doc", true) || name.endsWith(".docx", true) -> Icons.Filled.Description
    name.endsWith(".xls", true) || name.endsWith(".xlsx", true) -> Icons.Filled.TableChart
    name.endsWith(".ppt", true) || name.endsWith(".pptx", true) -> Icons.Filled.Slide
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
    size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", size / 1024.0 / 1024.0 / 1024.0)
}

@Composable
private fun StorageOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, target: FsTarget, onClick: (FsTarget) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(target) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}