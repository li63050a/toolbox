package com.toolbox.app.ui.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.toolbox.app.R
import com.toolbox.app.RepositoryProvider
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.ui.filebrowser.FileBrowserScreen
import com.toolbox.app.ui.filebrowser.FileOps
import com.toolbox.app.ftp.FtpClient
import com.toolbox.app.ftp.FtpFileOps
import com.toolbox.app.log.Log
import com.toolbox.app.ssh.SftpFileOps
import com.toolbox.app.ssh.SshEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface FsTarget {
    data object Local : FsTarget
    data class Ssh(val cfg: ConnectionConfig.Ssh) : FsTarget
    data class Ftp(val cfg: ConnectionConfig.Ftp) : FsTarget
    data class Oss(val cfg: ConnectionConfig) : FsTarget

    fun label(): String = when (this) {
        is Local -> "本地"
        is Ssh -> "SSH · ${cfg.name.ifEmpty { cfg.host }}"
        is Ftp -> "FTP · ${cfg.name.ifEmpty { cfg.host }}"
        is Oss -> "OSS · ${cfg.name.ifEmpty { "对象存储" }}"
    }
}

private fun hasAllFilesAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

private fun requestAllFilesAccess(
    context: Context,
    runtimeLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
        }
    } else {
        runtimeLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val repo = RepositoryProvider.connections
    val all by repo.connections.collectAsState(initial = emptyList())

    var leftTarget by remember { mutableStateOf<FsTarget>(FsTarget.Local) }
    var leftPath by remember { mutableStateOf("/") }
    var leftOps by remember { mutableStateOf<FileOps?>(null) }
    var leftLoading by remember { mutableStateOf(false) }
    var leftError by remember { mutableStateOf<String?>(null) }

    var rightTarget by remember { mutableStateOf<FsTarget>(FsTarget.Local) }
    var rightPath by remember { mutableStateOf("/") }
    var rightOps by remember { mutableStateOf<FileOps?>(null) }
    var rightLoading by remember { mutableStateOf(false) }
    var rightError by remember { mutableStateOf<String?>(null) }

    var editingPane by remember { mutableStateOf<String?>(null) }
    var pathInput by remember { mutableStateOf("") }

    val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    var checkTick by remember { mutableStateOf(0) }
    var autoRequested by remember { mutableStateOf(false) }

    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { checkTick++ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(leftTarget, rightTarget) {
        val hasLocal = leftTarget is FsTarget.Local || rightTarget is FsTarget.Local
        if (hasLocal && !hasAllFilesAccess(context) && !autoRequested) {
            autoRequested = true
            requestAllFilesAccess(context, runtimePermLauncher)
        }
    }

    fun refreshLeft() {
        leftLoading = true
        leftError = null
        leftOps = null
        when (val t = leftTarget) {
            is FsTarget.Local -> {
                leftOps = com.toolbox.app.ui.files.LocalFileOps(context)
                leftLoading = false
            }
            is FsTarget.Ssh -> {
                scope.launch {
                    withContext(Dispatchers.IO) { SshEngine(t.cfg).connect() }
                        .onSuccess { session ->
                            leftOps = SftpFileOps(context, session, t.cfg.name.ifEmpty { t.cfg.host })
                            leftLoading = false
                            leftError = null
                        }
                        .onFailure {
                            leftError = it.message
                            leftLoading = false
                            Log.e("SFTP", "连接失败", it)
                        }
                }
            }
            is FsTarget.Ftp -> {
                scope.launch {
                    withContext(Dispatchers.IO) { FtpClient(t.cfg).connect() }
                        .onSuccess { client ->
                            leftOps = FtpFileOps(context, client, t.cfg.name.ifEmpty { t.cfg.host })
                            leftLoading = false
                            leftError = null
                        }
                        .onFailure {
                            leftError = it.message
                            leftLoading = false
                            Log.e("FTP", "连接失败", it)
                        }
                }
            }
            is FsTarget.Oss -> {
                leftOps = null
                leftLoading = false
                leftError = context.getString(R.string.file_oss_unsupported)
            }
        }
    }

    fun refreshRight() {
        rightLoading = true
        rightError = null
        rightOps = null
        when (val t = rightTarget) {
            is FsTarget.Local -> {
                rightOps = com.toolbox.app.ui.files.LocalFileOps(context)
                rightLoading = false
            }
            is FsTarget.Ssh -> {
                scope.launch {
                    withContext(Dispatchers.IO) { SshEngine(t.cfg).connect() }
                        .onSuccess { session ->
                            rightOps = SftpFileOps(context, session, t.cfg.name.ifEmpty { t.cfg.host })
                            rightLoading = false
                            rightError = null
                        }
                        .onFailure {
                            rightError = it.message
                            rightLoading = false
                            Log.e("SFTP", "连接失败", it)
                        }
                }
            }
            is FsTarget.Ftp -> {
                scope.launch {
                    withContext(Dispatchers.IO) { FtpClient(t.cfg).connect() }
                        .onSuccess { client ->
                            rightOps = FtpFileOps(context, client, t.cfg.name.ifEmpty { t.cfg.host })
                            rightLoading = false
                            rightError = null
                        }
                        .onFailure {
                            rightError = it.message
                            rightLoading = false
                            Log.e("FTP", "连接失败", it)
                        }
                }
            }
            is FsTarget.Oss -> {
                rightOps = null
                rightLoading = false
                rightError = context.getString(R.string.file_oss_unsupported)
            }
        }
    }

    LaunchedEffect(leftTarget) { refreshLeft() }
    LaunchedEffect(rightTarget) { refreshRight() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_local)) },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                    onClick = {
                        leftTarget = FsTarget.Local
                        leftPath = "/"
                        rightTarget = FsTarget.Local
                        rightPath = "/"
                        scope.launch { drawerState.close() }
                    }
                )
                val sshList = all.filterIsInstance<ConnectionConfig.Ssh>()
                val ftpList = all.filterIsInstance<ConnectionConfig.Ftp>()
                val ossList = all.filter { it is ConnectionConfig.S3 || it is ConnectionConfig.Oss || it is ConnectionConfig.Cos }
                if (sshList.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.files_sec_ssh),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    sshList.forEach { conn ->
                        DropdownMenuItem(
                            text = { Text(conn.name.ifEmpty { conn.host }) },
                            leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                            onClick = {
                                leftTarget = FsTarget.Ssh(conn)
                                leftPath = "/"
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
                if (ftpList.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "FTP/FTPS",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ftpList.forEach { conn ->
                        DropdownMenuItem(
                            text = { Text(conn.name.ifEmpty { conn.host }) },
                            leadingIcon = { Icon(Icons.Filled.Folder, null) },
                            onClick = {
                                leftTarget = FsTarget.Ftp(conn)
                                leftPath = "/"
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
                if (ossList.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.files_sec_oss),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ossList.forEach { conn ->
                        DropdownMenuItem(
                            text = { Text(conn.name.ifEmpty { "OSS" }) },
                            leadingIcon = { Icon(Icons.Filled.Cloud, null) },
                            onClick = {
                                leftTarget = FsTarget.Oss(conn)
                                leftPath = "/"
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.home_files_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.file_back)) }
                    }
                )
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = leftTarget.label(),
                            modifier = Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = leftPath,
                            modifier = Modifier.weight(1f)
                                .clickable { editingPane = "left"; pathInput = leftPath },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            leftPath = File(leftPath).parent ?: "/"
                        }) {
                            Icon(Icons.Filled.ArrowDropUp, contentDescription = stringResource(R.string.file_back))
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        if (leftOps != null) {
                            FileBrowserScreen(
                                ops = leftOps!!,
                                onBack = {},
                                path = leftPath,
                                onPathChange = { leftPath = it },
                                showAppBar = false
                            )
                        } else if (leftLoading) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        } else {
                            Text(
                                text = leftError ?: stringResource(R.string.file_empty),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
                VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = rightTarget.label(),
                            modifier = Modifier.padding(end = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = rightPath,
                            modifier = Modifier.weight(1f)
                                .clickable { editingPane = "right"; pathInput = rightPath },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            rightPath = File(rightPath).parent ?: "/"
                        }) {
                            Icon(Icons.Filled.ArrowDropUp, contentDescription = stringResource(R.string.file_back))
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        if (rightOps != null) {
                            FileBrowserScreen(
                                ops = rightOps!!,
                                onBack = {},
                                path = rightPath,
                                onPathChange = { rightPath = it },
                                showAppBar = false
                            )
                        } else if (rightLoading) {
                            CircularProgressIndicator(Modifier.align(Alignment.Center))
                        } else {
                            Text(
                                text = rightError ?: stringResource(R.string.file_empty),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
        }
    }

    if (editingPane != null) {
        AlertDialog(
            onDismissRequest = { editingPane = null },
            title = { Text(stringResource(R.string.file_path_edit_title)) },
            text = {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text(stringResource(R.string.file_path)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editingPane == "left") leftPath = pathInput else rightPath = pathInput
                    editingPane = null
                    pathInput = ""
                }) { Text(stringResource(R.string.file_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editingPane = null; pathInput = "" }) { Text(stringResource(R.string.file_cancel)) }
            }
        )
    }
}