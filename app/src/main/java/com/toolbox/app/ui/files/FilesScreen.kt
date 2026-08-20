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
import rikka.shizuku.Shizuku

fun checkPermission(ctx: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

fun getStoragePath(): String = Environment.getExternalStorageDirectory().absolutePath

sealed interface FsTarget {
    data object Local : FsTarget
    data object Shizuku : FsTarget
    data class Ssh(val cfg: ConnectionConfig.Ssh) : FsTarget
    data class Ftp(val cfg: ConnectionConfig.Ftp) : FsTarget

    fun label(): String = when (this) {
        is Local -> "本地存储"
        is Shizuku -> "Shizuku"
        is Ssh -> "SSH · ${cfg.name.ifEmpty { cfg.host }}"
        is Ftp -> "FTP · ${cfg.name.ifEmpty { cfg.host }}"
    }
    
    fun icon() = when (this) {
        is Local -> Icons.Filled.FolderOpen
        is Shizuku -> Icons.Filled.Shield
        is Ssh -> Icons.Filled.Terminal
        is Ftp -> Icons.Filled.Folder
    }
}

data class StoredConnection(val id: String, val type: String, val target: FsTarget)

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
    var snackbarHostState = remember { SnackbarHostState() }
    
    // 侧边栏相关
    var drawerOpen by remember { mutableStateOf(false) }
    var showFtpModal by remember { mutableStateOf(false) }
    var showSshModal by remember { mutableStateOf(false) }
    
    // FTP 表单
    var ftpHost by remember { mutableStateOf("") }
    var ftpPort by remember { mutableStateOf("21") }
    var ftpUser by remember { mutableStateOf("anonymous") }
    var ftpPass by remember { mutableStateOf("") }
    var ftpPath by remember { mutableStateOf("") }
    var ftpName by remember { mutableStateOf("") }
    var ftpPassive by remember { mutableStateOf(true) }

    // Shizuku 状态
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuPermission by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        shizukuRunning = Shizuku.pingBinder()
        if (shizukuRunning) {
            shizukuPermission = Shizuku.checkSelfPermission() == 0
        }
        Shizuku.addBinderReceivedListenerSticky {
            shizukuRunning = true
            shizukuPermission = Shizuku.checkSelfPermission() == 0
        }
        Shizuku.addBinderDeadListener {
            shizukuRunning = false
            shizukuPermission = false
        }
    }

    fun loadFiles(target: FsTarget, path: String, isLeft: Boolean) {
        when (target) {
            is FsTarget.Local -> {
                if (isLeft) leftLoading = true else rightLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val dir = File(path)
                        val entries = if (!dir.exists() || !dir.isDirectory) emptyList()
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
                        withContext(Dispatchers.Main) {
                            if (isLeft) { leftEntries = entries; leftLoading = false }
                            else { rightEntries = entries; rightLoading = false }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                        }
                    }
                }
            }
            is FsTarget.Shizuku -> {
                if (isLeft) leftLoading = true else rightLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != 0) {
                            withContext(Dispatchers.Main) {
                                if (isLeft) leftLoading = false else rightLoading = false
                            }
                            return@launch
                        }
                        val ops = ShizukuFileOps(context)
                        val entries = ops.list(path).getOrNull() ?: emptyList()
                        withContext(Dispatchers.Main) {
                            if (isLeft) { leftEntries = entries; leftLoading = false }
                            else { rightEntries = entries; rightLoading = false }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                        }
                    }
                }
            }
            is FsTarget.Ssh -> {
                if (isLeft) leftLoading = true else rightLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val entries = SshEngine(target.cfg).connect().getOrNull()?.let { session ->
                            SftpFileOps(context, session, target.cfg.name.ifEmpty { target.cfg.host }).list(path).getOrNull()
                        } ?: emptyList()
                        withContext(Dispatchers.Main) {
                            if (isLeft) { leftEntries = entries; leftLoading = false }
                            else { rightEntries = entries; rightLoading = false }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                        }
                    }
                }
            }
            is FsTarget.Ftp -> {
                if (isLeft) leftLoading = true else rightLoading = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val entries = FtpClient(target.cfg).connect().getOrNull()?.let { client ->
                            client.controlEncoding = "UTF-8"
                            FtpFileOps(context, client, target.cfg.name.ifEmpty { target.cfg.host }).list(path).getOrNull()
                        } ?: emptyList()
                        withContext(Dispatchers.Main) {
                            if (isLeft) { leftEntries = entries; leftLoading = false }
                            else { rightEntries = entries; rightLoading = false }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(leftTarget, leftPath) { loadFiles(leftTarget, leftPath, true) }
    LaunchedEffect(rightTarget, rightPath) { loadFiles(rightTarget, rightPath, false) }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> loadFiles(leftTarget, leftPath, true); loadFiles(rightTarget, rightPath, false) }

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

    fun executeTransfer(actionType: String, entry: FileEntry, fromSide: String) {
        scope.launch {
            val targetPath = if (fromSide == "left") rightPath else leftPath
            try {
                if (actionType == "DOWNLOAD") {
                    val localPath = "${getStoragePath()}/Download/${entry.name}"
                    val localFile = File(localPath)
                    localFile.parentFile?.mkdirs()
                    
                    val sourceClient = when (val s = if (fromSide == "left") leftTarget else rightTarget) {
                        is FsTarget.Ftp -> FtpClient((s as FsTarget.Ftp).cfg).connect().getOrNull()
                        else -> null
                    }
                    
                    if (sourceClient != null) {
                        val input = sourceClient.retrieveFileStream(entry.path)
                        if (input != null) {
                            localFile.outputStream().use { out -> input.copyTo(out) }
                            sourceClient.completePendingCommand()
                        }
                        runCatching { sourceClient.logout() }
                        runCatching { sourceClient.disconnect() }
                        snackbarHostState.showSnackbar("已下载到: $localPath")
                    }
                } else if (actionType == "MOVE") {
                    val sourceFile = File(entry.path)
                    if (sourceFile.exists()) {
                        val destClient = when (val d = if (fromSide == "left") rightTarget else leftTarget) {
                            is FsTarget.Ftp -> FtpClient((d as FsTarget.Ftp).cfg).connect().getOrNull()
                            else -> null
                        }
                        
                        if (destClient != null) {
                            destClient.controlEncoding = "UTF-8"
                            val destPath = "${targetPath}/${entry.name}"
                            val input = sourceFile.inputStream()
                            destClient.storeFileStream(destPath)?.use { out -> input.copyTo(out) }
                            destClient.completePendingCommand()
                            input.close()
                            
                            sourceFile.delete()
                            snackbarHostState.showSnackbar("已移动")
                            
                            runCatching { destClient.logout() }
                            runCatching { destClient.disconnect() }
                        }
                    }
                }
                
                loadFiles(leftTarget, leftPath, true)
                loadFiles(rightTarget, rightPath, false)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("操作失败: ${e.message}")
            }
        }
    }

    // 保存 FTP 连接
    fun saveFtpConnection() {
        val cfg = ConnectionConfig.Ftp(
            name = ftpName,
            host = ftpHost,
            port = ftpPort.toIntOrNull() ?: 21,
            user = ftpUser,
            password = ftpPass,
            passive = ftpPassive
        )
        scope.launch {
            repo.add(cfg)
        }
        showFtpModal = false
        scope.launch { snackbarHostState.showSnackbar("FTP 连接已保存") }
    }

    // 保存 SSH 连接
    fun saveSshConnection() {
        // TODO: 实现 SSH 连接保存
        showSshModal = false
    }

    // 权限检查
    if (!checkPermission(context) && (leftTarget is FsTarget.Local || rightTarget is FsTarget.Local)) {
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

    // 操作菜单
    if (showActionMenu && selectedEntry != null) {
        val hasRemoteDest = (actionMenuSide == "left" && rightTarget !is FsTarget.Local) || (actionMenuSide == "right" && leftTarget !is FsTarget.Local)
        val hasRemoteSource = (actionMenuSide == "left" && leftTarget !is FsTarget.Local) || (actionMenuSide == "right" && rightTarget !is FsTarget.Local)
        
        AlertDialog(
            onDismissRequest = { showActionMenu = false },
            title = { Text(selectedEntry!!.name) },
            text = {
                Column {
                    Text("类型: ${if (selectedEntry!!.isDirectory) "文件夹" else "文件"}")
                    if (!selectedEntry!!.isDirectory) Text("大小: ${formatSize(selectedEntry!!.size)}")
                    Text("路径: ${selectedEntry!!.path}")
                }
            },
            confirmButton = { Button(onClick = { showActionMenu = false }) { Text("关闭") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasRemoteDest) {
                        TextButton(onClick = { executeTransfer("MOVE", selectedEntry!!, actionMenuSide); showActionMenu = false }) { Text("移动到右边", color = MaterialTheme.colorScheme.primary) }
                    }
                    if (hasRemoteSource) {
                        TextButton(onClick = { executeTransfer("MOVE", selectedEntry!!, actionMenuSide); showActionMenu = false }) { Text("移动到左边", color = MaterialTheme.colorScheme.primary) }
                    }
                    if (hasRemoteSource && selectedEntry!!.isDirectory) {
                        TextButton(onClick = { executeTransfer("DOWNLOAD", selectedEntry!!, actionMenuSide); showActionMenu = false }) { Text("下载", color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = { showActionMenu = false }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }

    // FTP 添加弹窗
    if (showFtpModal) {
        ModalBottomSheet(onDismissRequest = { showFtpModal = false }) {
            SheetContent(
                title = "添加 FTP",
                host = ftpHost, onHostChange = { ftpHost = it },
                port = ftpPort, onPortChange = { ftpPort = it },
                user = ftpUser, onUserChange = { ftpUser = it },
                pass = ftpPass, onPassChange = { ftpPass = it },
                name = ftpName, onNameChange = { ftpName = it },
                passive = ftpPassive, onPassiveChange = { ftpPassive = it },
                onSave = { saveFtpConnection() },
                onCancel = { showFtpModal = false }
            )
        }
    }

    // 主界面
    ModalNavigationDrawer(
        drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
        drawerContent = {
            DrawerContent(
                allConnections = all,
                shizukuRunning = shizukuRunning,
                onAddFtp = { showFtpModal = true; drawerOpen = false },
                onAddSsh = { showSshModal = true; drawerOpen = false },
                onSelectLocal = { target ->
                    leftTarget = target; rightTarget = target
                    leftPath = getStoragePath(); rightPath = getStoragePath()
                    drawerOpen = false
                }
            )
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { 
                        Column {
                            Text(stringResource(R.string.home_files_title), fontWeight = FontWeight.Bold)
                            Text("$leftPath", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { drawerOpen = true }) {
                            Icon(Icons.Filled.Menu, "侧边栏")
                        }
                    },
                    actions = {
                        IconButton(onClick = { loadFiles(leftTarget, leftPath, true); loadFiles(rightTarget, rightPath, false) }) {
                            Icon(Icons.Filled.Refresh, "刷新")
                        }
                        IconButton(onClick = { /* 搜索 */ }) {
                            Icon(Icons.Filled.Search, "搜索")
                        }
                        IconButton(onClick = { /* 更多菜单 */ }) {
                            Icon(Icons.Filled.MoreVert, "更多")
                        }
                    }
                )
            },
            bottomBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    navButton(Icons.Filled.Add, "新建")
                    navButton(Icons.Filled.Upload, "上传")
                    navButton(Icons.Filled.Download, "下载")
                    navButton(Icons.Filled.Settings, "设置")
                }
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
}

@Composable
private fun navButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SheetContent(
    title: String,
    host: String, onHostChange: (String) -> Unit,
    port: String, onPortChange: (String) -> Unit,
    user: String, onUserChange: (String) -> Unit,
    pass: String, onPassChange: (String) -> Unit,
    name: String, onNameChange: (String) -> Unit,
    passive: Boolean, onPassiveChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        
        OutlinedTextField(value = name, onValueChange = onNameChange, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = host, onValueChange = onHostChange, label = { Text("主机") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = onPortChange, label = { Text("端口") }, modifier = Modifier.width(120.dp))
        OutlinedTextField(value = user, onValueChange = onUserChange, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = pass, onValueChange = onPassChange, label = { Text("密码") }, modifier = Modifier.fillMaxWidth())
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("被动模式", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = passive, onCheckedChange = onPassiveChange)
        }
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("保存") }
        }
    }
}

@Composable
private fun DrawerContent(
    allConnections: List<ConnectionConfig>,
    shizukuRunning: Boolean,
    onAddFtp: () -> Unit,
    onAddSsh: () -> Unit,
    onSelectLocal: (FsTarget) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            // 头部
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Android, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("工具箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            
            Divider()
            
            // 本地存储
            Text("本地存储", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 8.dp), color = MaterialTheme.colorScheme.primary)
            DrawerItem(Icons.Filled.FolderOpen, "内部存储") { onSelectLocal(FsTarget.Local) }
            DrawerItem(Icons.Filled.Download, "Download") { onSelectLocal(FsTarget.Local) }
            DrawerItem(Icons.Filled.PhotoLibrary, "DCIM") { onSelectLocal(FsTarget.Local) }
            
            // Shizuku
            if (shizukuRunning) {
                Divider()
                Text("系统", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 8.dp), color = MaterialTheme.colorScheme.primary)
                DrawerItem(Icons.Filled.Shield, "Shizuku") { onSelectLocal(FsTarget.Shizuku) }
            }
            
            // 网络存储
            val sshList = allConnections.filterIsInstance<ConnectionConfig.Ssh>()
            val ftpList = allConnections.filterIsInstance<ConnectionConfig.Ftp>()
            
            if (sshList.isNotEmpty() || ftpList.isNotEmpty()) {
                Divider()
                Text("网络", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(16.dp, 8.dp), color = MaterialTheme.colorScheme.primary)
                sshList.forEach { conn ->
                    DrawerItem(Icons.Filled.Terminal, conn.name.ifEmpty { conn.host }) {
                        onSelectLocal(FsTarget.Ssh(conn))
                    }
                }
                ftpList.forEach { conn ->
                    DrawerItem(Icons.Filled.Folder, conn.name.ifEmpty { conn.host }) {
                        onSelectLocal(FsTarget.Ftp(conn))
                    }
                }
            }
            
            // 添加按钮
            Divider()
            DrawerItem(Icons.Filled.Add, "添加 FTP") { onAddFtp() }
            DrawerItem(Icons.Filled.Add, "添加 SSH") { onAddSsh() }
        }
    }
}

@Composable
private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
    Column(modifier = Modifier.fillMaxHeight()) {
        // 路径栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(target.icon(), null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(target.label(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
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
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("空目录", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                if (path != "/") item { FileRow("..", true, 0, 0, onClick = { onNavigate(File(path).parent ?: "/") }, onLongClick = {}) }
                items(entries) { entry ->
                    FileRow(
                        entry.name, entry.isDirectory, entry.size, entry.modified,
                        onClick = { if (entry.isDirectory) onNavigate(entry.path) else onClick(entry) },
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
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (isDirectory) Icons.Filled.Folder else getFileIcon(name),
            contentDescription = null,
            tint = if (isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isDirectory) Text(formatSize(size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isDirectory) Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

private fun getFileIcon(name: String) = when {
    name.endsWith(".mp3", true) || name.endsWith(".wav", true) || name.endsWith(".flac", true) -> Icons.Filled.MusicNote
    name.endsWith(".mp4", true) || name.endsWith(".avi", true) || name.endsWith(".mkv", true) -> Icons.Filled.VideoLibrary
    name.endsWith(".jpg", true) || name.endsWith(".png", true) || name.endsWith(".gif", true) || name.endsWith(".webp", true) -> Icons.Filled.Image
    name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    name.endsWith(".zip", true) || name.endsWith(".rar", true) || name.endsWith(".7z", true) -> Icons.Filled.Compress
    name.endsWith(".txt", true) || name.endsWith(".md", true) -> Icons.Filled.Description
    name.endsWith(".apk", true) -> Icons.Filled.Android
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
    size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / 1024.0 / 1024.0)
    else -> String.format("%.2f GB", size / 1024.0 / 1024.0 / 1024.0)
}