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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.toolbox.app.R
import com.toolbox.app.RepositoryProvider
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.ui.filebrowser.FileBrowserScreen
import com.toolbox.app.ui.ftp.FtpFilesScreen
import com.toolbox.app.ui.oss.OssFilesScreen
import com.toolbox.app.ui.ssh.SshFilesScreen

sealed interface FsTarget {
    data object Local : FsTarget
    data class Ssh(val cfg: ConnectionConfig.Ssh) : FsTarget
    data class Ftp(val cfg: ConnectionConfig.Ftp) : FsTarget
    data class Oss(val cfg: ConnectionConfig) : FsTarget
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

@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val all by RepositoryProvider.connections.connections.collectAsState(initial = emptyList())
    val sshList = all.filterIsInstance<ConnectionConfig.Ssh>()
    val ftpList = all.filterIsInstance<ConnectionConfig.Ftp>()
    val ossList = all.filter { it is ConnectionConfig.S3 || it is ConnectionConfig.Oss || it is ConnectionConfig.Cos }

    var target by remember { mutableStateOf<FsTarget>(FsTarget.Local) }
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

    LaunchedEffect(checkTick, target) {
        if (target is FsTarget.Local && !hasAllFilesAccess(context) && !autoRequested) {
            autoRequested = true
            requestAllFilesAccess(context, runtimePermLauncher)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FsSelectorBar(
            sshList = sshList,
            ftpList = ftpList,
            ossList = ossList,
            target = target,
            onSelect = { target = it }
        )
        Box(Modifier.weight(1f)) {
            when (val t = target) {
                FsTarget.Local -> {
                    if (hasAllFilesAccess(context)) {
                        val ops = remember(context) { LocalFileOps(context) }
                        FileBrowserScreen(ops = ops, onBack = onBack)
                    } else {
                        PermissionGate(
                            onRetry = {
                                autoRequested = false
                                checkTick++
                            }
                        )
                    }
                }

                is FsTarget.Ssh -> SshFilesScreen(t.cfg, onBack = { target = FsTarget.Local })
                is FsTarget.Ftp -> FtpFilesScreen(t.cfg, onBack = { target = FsTarget.Local })
                is FsTarget.Oss -> OssFilesScreen(t.cfg, onBack = { target = FsTarget.Local })
            }
        }
    }
}

@Composable
private fun FsSelectorBar(
    sshList: List<ConnectionConfig.Ssh>,
    ftpList: List<ConnectionConfig.Ftp>,
    ossList: List<ConnectionConfig>,
    target: FsTarget,
    onSelect: (FsTarget) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    @Composable
    fun label(t: FsTarget): String = when (t) {
        FsTarget.Local -> stringResource(R.string.files_local)
        is FsTarget.Ssh -> t.cfg.name.ifEmpty { t.cfg.host }
        is FsTarget.Ftp -> t.cfg.name.ifEmpty { t.cfg.host }
        is FsTarget.Oss -> t.cfg.name.ifEmpty { "OSS" }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                FilledTonalButton(onClick = { open = true }) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(label(target), maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_local)) },
                        leadingIcon = { Icon(Icons.Filled.FolderOpen, null) },
                        onClick = { open = false; onSelect(FsTarget.Local) }
                    )
                    if (sshList.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.files_sec_ssh))
                        sshList.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name.ifEmpty { c.host }) },
                                leadingIcon = { Icon(Icons.Filled.Terminal, null) },
                                onClick = { open = false; onSelect(FsTarget.Ssh(c)) }
                            )
                        }
                    }
                    if (ftpList.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.files_sec_ftp))
                        ftpList.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name.ifEmpty { c.host }) },
                                leadingIcon = { Icon(Icons.Filled.Folder, null) },
                                onClick = { open = false; onSelect(FsTarget.Ftp(c)) }
                            )
                        }
                    }
                    if (ossList.isNotEmpty()) {
                        SectionHeader(stringResource(R.string.files_sec_oss))
                        ossList.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name.ifEmpty { "OSS" }) },
                                leadingIcon = { Icon(Icons.Filled.Cloud, null) },
                                onClick = { open = false; onSelect(FsTarget.Oss(c)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun PermissionGate(onRetry: () -> Unit) {
    val context = LocalContext.current
    val runtimeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Lock,
            null,
            Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            stringResource(R.string.files_permission_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            stringResource(R.string.files_permission_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
        Button(
            onClick = {
                onRetry()
                requestAllFilesAccess(context, runtimeLauncher)
            },
            modifier = Modifier.padding(top = 24.dp)
        ) { Text(stringResource(R.string.files_grant)) }
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.files_retry))
        }
    }
}