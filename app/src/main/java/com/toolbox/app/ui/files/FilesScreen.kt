package com.toolbox.app.ui.files

import android.Manifest
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
import com.toolbox.app.ui.filebrowser.FileEntry
import java.io.File
import kotlin.concurrent.thread

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var leftPath by remember { mutableStateOf(getStoragePath(context)) }
    var rightPath by remember { mutableStateOf(getStoragePath(context)) }
    var leftEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var rightEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var showTargetMenu by remember { mutableStateOf(false) }
    var showActionMenu by remember { mutableStateOf(false) }
    var selectedEntry by remember { mutableStateOf<FileEntry?>(null) }
    var hasPermission by remember { mutableStateOf(checkPermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermission = perms.values.all { it }
        if (hasPermission) {
            loadEntries(leftPath, true)
            loadEntries(rightPath, false)
        }
    }

    fun checkPermission(ctx: android.content.Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            } catch (e: Exception) {
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }
    }

    fun getStoragePath(ctx: android.content.Context): String =
        Environment.getExternalStorageDirectory().absolutePath

    fun loadEntries(path: String, isLeft: Boolean) {
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

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            loadEntries(leftPath, true)
            loadEntries(rightPath, false)
        }
    }

    if (!hasPermission) {
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

    if (showTargetMenu) {
        ModalBottomSheet(onDismissRequest = { showTargetMenu = false }) {
            Column(Modifier.padding(16.dp)) {
                Text("选择存储位置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                StorageItem(Icons.Filled.FolderOpen, "内部存储", getStoragePath(context)) {
                    leftPath = it; rightPath = it; showTargetMenu = false
                }
                StorageItem(Icons.Filled.Download, "Download", "$${getStoragePath(context)}/Download") {
                    leftPath = it; rightPath = it; showTargetMenu = false
                }
                StorageItem(Icons.Filled.Picture, "DCIM", "$${getStoragePath(context)}/DCIM") {
                    leftPath = it; rightPath = it; showTargetMenu = false
                }
            }
        }
    }

    if (showActionMenu && selectedEntry != null) {
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
            dismissButton = { TextButton(onClick = { showActionMenu = false }) { Text("删除", color = MaterialTheme.colorScheme.error) } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_files_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.file_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showTargetMenu = true }) {
                        Icon(Icons.Filled.Menu, stringResource(R.string.file_more))
                    }
                }
            )
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            FilePanel("左", leftPath, leftEntries) { leftPath = it } { selectedEntry = it } { selectedEntry = it; showActionMenu = true }
            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
            FilePanel("右", rightPath, rightEntries) { rightPath = it } { selectedEntry = it } { selectedEntry = it; showActionMenu = true }
        }
    }
}

@Composable
private fun FilePanel(side: String, path: String, entries: List<FileEntry>, onNavigate: (String) -> Unit, onClick: (FileEntry) -> Unit, onLongClick: (FileEntry) -> Unit) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("$side 面板", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { onNavigate(File(path).parent ?: "/") }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
            if (path != "/") item { FileRow("..", true, 0, 0) { onNavigate(File(path).parent ?: "/") } }
            items(entries) { entry ->
                FileRow(entry.name, entry.isDirectory, entry.size, entry.modified) {
                    if (entry.isDirectory) onNavigate(entry.path) else onClick(entry)
                } onLongClick = { onLongClick(entry) }
            }
        }
    }
}

@Composable
private fun FileRow(name: String, isDirectory: Boolean, size: Long, modified: Long, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).longClickable(onClick = onLongClick).padding(horizontal = 12.dp, vertical = 10.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (isDirectory) Icons.Filled.Folder else getFileIcon(name), null, tint = if (isDirectory) Color(0xFFFFA000) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!isDirectory) Text(formatSize(size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isDirectory) Icon(Icons.Filled.ChevronRight, null, modifier = Modifier.size(18.dp), tint = Color.Gray.copy(alpha = 0.6f))
    }
}

private fun getFileIcon(name: String) = when {
    name.endsWith(".mp3", true) || name.endsWith(".wav", true) -> Icons.Filled.MusicNote
    name.endsWith(".mp4", true) || name.endsWith(".avi", true) -> Icons.Filled.VideoLibrary
    name.endsWith(".jpg", true) || name.endsWith(".png", true) -> Icons.Filled.Image
    name.endsWith(".pdf", true) -> Icons.Filled.PictureAsPdf
    name.endsWith(".zip", true) || name.endsWith(".rar", true) -> Icons.Filled.Compress
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

@Composable
private fun StorageItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, path: String, onClick: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onClick(path) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}