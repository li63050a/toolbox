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
import kotlinx.coroutines.delay
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

data class BookmarkedPath(val path: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = RepositoryProvider.connections
    val all by repo.connections.collectAsState(initial = emptyList())
    
    // 基础状态
    var leftTarget by remember { mutableStateOf<FsTarget>(FsTarget.Local) }
    var rightTarget by remember { mutableStateOf<FsTarget>(FsTarget.Local) }
    var leftPath by remember { mutableStateOf(getStoragePath()) }
    var rightPath by remember { mutableStateOf(getStoragePath()) }
    var leftEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var rightEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var leftLoading by remember { mutableStateOf(false) }
    var rightLoading by remember { mutableStateOf(false) }
    
    // 搜索状态
    var leftSearchQuery by remember { mutableStateOf("") }
    var rightSearchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    
    // 批量操作状态
    var leftSelectionMode by remember { mutableStateOf(false) }
    var rightSelectionMode by remember { mutableStateOf(false) }
    var selectedLeftFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedRightFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    
    // 其他状态
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var showActionMenu by remember { mutableStateOf(false) }
    var actionMenuSide by remember { mutableStateOf("left") }
    var snackbarHostState = remember { SnackbarHostState() }
    
    // 侧边栏相关
    var drawerOpen by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    
    // 书签和最近访问
    var bookmarks by remember { mutableStateOf<List<BookmarkedPath>>(emptyList()) }
    var recentPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    
    // Shizuku 状态
    var shizukuRunning by remember { mutableStateOf(false) }
    var shizukuPermission by remember { mutableStateOf(false) }

    // Shizuku 状态监听
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
            connectionStatus = "Shizuku 已断开"
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
                            connectionStatus = null
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                            connectionStatus = "加载失败"
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
                                connectionStatus = "Shizuku 未授权"
                            }
                            return@launch
                        }
                        val ops = ShizukuFileOps(context)
                        val entries = ops.list(path).getOrNull() ?: emptyList()
                        withContext(Dispatchers.Main) {
                            if (isLeft) { leftEntries = entries; leftLoading = false }
                            else { rightEntries = entries; rightLoading = false }
                            connectionStatus = null
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                            connectionStatus = "连接失败"
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
                            connectionStatus = null
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                            connectionStatus = "SSH 连接失败"
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
                            connectionStatus = null
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            if (isLeft) leftLoading = false else rightLoading = false
                            connectionStatus = "FTP 连接失败"
                        }
                    }
                }
            }
        }
    }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        loadFiles(leftTarget, leftPath, true)
        loadFiles(rightTarget, rightPath, false)
    }

    LaunchedEffect(leftTarget, leftPath) { loadFiles(leftTarget, leftPath, true) }
    LaunchedEffect(rightTarget, rightPath) { loadFiles(rightTarget, rightPath, false) }

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

    // 搜索过滤
    val filteredLeftEntries = leftEntries.filter { entry ->
        leftSearchQuery.isEmpty() || entry.name.contains(leftSearchQuery, ignoreCase = true)
    }
    val filteredRightEntries = rightEntries.filter { entry ->
        rightSearchQuery.isEmpty() || entry.name.contains(rightSearchQuery, ignoreCase = true)
    }

    // 批量操作
    fun toggleSelectLeft(entry: FileEntry) {
        selectedLeftFiles = if (selectedLeftFiles.contains(entry.path)) {
            selectedLeftFiles - entry.path
        } else {
            selectedLeftFiles + entry.path
        }
    }
    
    fun toggleSelectRight(entry: FileEntry) {
        selectedRightFiles = if (selectedRightFiles.contains(entry.path)) {
            selectedRightFiles - entry.path
        } else {
            selectedRightFiles + entry.path
        }
    }

    // 书签功能
    fun toggleBookmark(side: String, path: String, name: String) {
        val exists = bookmarks.any { it.path == path }
        if (exists) {
            bookmarks = bookmarks.filter { it.path != path }
            scope.launch { snackbarHostState.showSnackbar("已取消书签") }
        } else {
            bookmarks = bookmarks + BookmarkedPath(path, name)
            scope.launch { snackbarHostState.showSnackbar("已添加书签") }
        }
    }
    
    fun jumpToBookmark(path: String) {
        if (actionMenuSide == "left") leftPath = path else rightPath = path
        showBookmarks = false
    }

    // 最近访问
    fun addToRecent(path: String) {
        recentPaths = listOf(path) + recentPaths.filter { it != path }.take(9)
    }

    fun jumpToRecent(path: String) {
        if (actionMenuSide == "left") leftPath = path else rightPath = path
        showRecent = false
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

    // 主界面
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("文件管理器", fontWeight = FontWeight.Bold)
                        if (connectionStatus != null) {
                            Text(connectionStatus ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Filled.Menu, "菜单")
                    }
                },
                actions = {
                    if (leftSelectionMode || rightSelectionMode) {
                        IconButton(onClick = { 
                            leftSelectionMode = false
                            rightSelectionMode = false
                            selectedLeftFiles = emptySet()
                            selectedRightFiles = emptySet()
                        }) {
                            Icon(Icons.Filled.Close, null)
                        }
                        Text("${selectedLeftFiles.size + selectedRightFiles.size}", color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        IconButton(onClick = { showSearch = !showSearch }) {
                            Icon(Icons.Filled.Search, "搜索")
                        }
                        IconButton(onClick = { showBookmarks = true }) {
                            Icon(Icons.Filled.Bookmark, "书签")
                        }
                        IconButton(onClick = { showRecent = true }) {
                            Icon(Icons.Filled.RecentActors, "最近")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (leftSelectionMode || rightSelectionMode) {
                BottomAppBar {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text("已选择 ${selectedLeftFiles.size + selectedRightFiles.size} 个文件")
                    }
                    IconButton(onClick = { 
                        scope.launch {
                            val paths = selectedLeftFiles + selectedRightFiles
                            paths.forEach { File(it).deleteRecursively() }
                            loadFiles(leftTarget, leftPath, true)
                            loadFiles(rightTarget, rightPath, false)
                            leftSelectionMode = false
                            rightSelectionMode = false
                            selectedLeftFiles = emptySet()
                            selectedRightFiles = emptySet()
                            snackbarHostState.showSnackbar("已删除 ${paths.size} 个文件")
                        }
                    }) {
                        Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 搜索框
            if (showSearch) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = leftSearchQuery,
                        onValueChange = { leftSearchQuery = it },
                        placeholder = { Text("搜索左侧...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) }
                    )
                    OutlinedTextField(
                        value = rightSearchQuery,
                        onValueChange = { rightSearchQuery = it },
                        placeholder = { Text("搜索右侧...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp)) }
                    )
                    IconButton(onClick = { showSearch = false }) {
                        Icon(Icons.Filled.Close, null)
                    }
                }
            }
            
            // 文件面板
            Row(Modifier.fillMaxSize()) {
                FilePanelWithRefresh(
                    side = "左",
                    target = leftTarget,
                    path = leftPath,
                    entries = filteredLeftEntries,
                    loading = leftLoading,
                    isSelectionMode = leftSelectionMode,
                    selectedFiles = selectedLeftFiles,
                    onRefresh = { loadFiles(leftTarget, leftPath, true) },
                    onNavigate = { 
                        leftPath = it
                        addToRecent(it)
                    },
                    onClick = { entry ->
                        if (leftSelectionMode) {
                            toggleSelectLeft(entry)
                        } else {
                            if (entry.isDirectory) {
                                leftPath = entry.path
                                addToRecent(entry.path)
                            } else {
                                selectedEntry = entry
                                showActionMenu = true
                                actionMenuSide = "left"
                            }
                        }
                    },
                    onLongClick = { entry ->
                        leftSelectionMode = true
                        toggleSelectLeft(entry)
                    }
                )
                VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                FilePanelWithRefresh(
                    side = "右",
                    target = rightTarget,
                    path = rightPath,
                    entries = filteredRightEntries,
                    loading = rightLoading,
                    isSelectionMode = rightSelectionMode,
                    selectedFiles = selectedRightFiles,
                    onRefresh = { loadFiles(rightTarget, rightPath, false) },
                    onNavigate = { 
                        rightPath = it
                        addToRecent(it)
                    },
                    onClick = { entry ->
                        if (rightSelectionMode) {
                            toggleSelectRight(entry)
                        } else {
                            if (entry.isDirectory) {
                                rightPath = entry.path
                                addToRecent(entry.path)
                            } else {
                                selectedEntry = entry
                                showActionMenu = true
                                actionMenuSide = "right"
                            }
                        }
                    },
                    onLongClick = { entry ->
                        rightSelectionMode = true
                        toggleSelectRight(entry)
                    }
                )
            }
        }
    }

    // 操作菜单
    if (showActionMenu && selectedEntry != null) {
        val hasRemoteDest = (actionMenuSide == "left" && rightTarget !is FsTarget.Local) || (actionMenuSide == "right" && leftTarget !is FsTarget.Local)
        
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
                        TextButton(onClick = { showActionMenu = false }) { Text("移动到右边", color = MaterialTheme.colorScheme.primary) }
                    }
                    TextButton(onClick = { showActionMenu = false }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        )
    }

    // 书签对话框
    if (showBookmarks) {
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("书签") },
            text = {
                if (bookmarks.isEmpty()) {
                    Text("暂无书签", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        items(bookmarks) { bookmark ->
                            BookmarkItem(bookmark) { jumpToBookmark(bookmark.path) }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedEntry != null) {
                        TextButton(onClick = { 
                            toggleBookmark(actionMenuSide, selectedEntry!!.path, selectedEntry!!.name)
                            showBookmarks = false
                        }) { 
                            Text(if (bookmarks.any { it.path == selectedEntry!!.path }) "取消书签" else "添加书签") 
                        }
                    }
                    TextButton(onClick = { showBookmarks = false }) { Text("关闭") }
                }
            }
        )
    }

    // 最近访问对话框
    if (showRecent) {
        AlertDialog(
            onDismissRequest = { showRecent = false },
            title = { Text("最近访问") },
            text = {
                if (recentPaths.isEmpty()) {
                    Text("暂无历史记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn {
                        items(recentPaths) { path ->
                            RecentPathItem(path) { jumpToRecent(path) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecent = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun FilePanelWithRefresh(
    side: String,
    target: FsTarget,
    path: String,
    entries: List<FileEntry>,
    loading: Boolean,
    isSelectionMode: Boolean,
    selectedFiles: Set<String>,
    onRefresh: () -> Unit,
    onNavigate: (String) -> Unit,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit
) {
    var refreshing by remember { mutableStateOf(false) }

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
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                IconButton(onClick = { refreshing = true; onRefresh(); refreshing = false }) {
                    Icon(Icons.Filled.Refresh, "刷新")
                }
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
                            isSelected = false,
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
                        isSelected = selectedFiles.contains(entry.path),
                        onClick = { onClick(entry) },
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
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        
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

@Composable
private fun BookmarkItem(bookmark: BookmarkedPath, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Bookmark, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(bookmark.name, style = MaterialTheme.typography.bodyLarge)
    }
    Divider()
}

@Composable
private fun RecentPathItem(path: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.RecentActors, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(12.dp))
        Text(path, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Divider()
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