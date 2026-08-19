package com.toolbox.app.ui.adb

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ConnectWithoutContact
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import java.io.File
import kotlin.concurrent.thread

private const val TAG = "AdbManager"

enum class AdbTab { DEVICES, FILES, COMMAND, APPS, INFO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val adb = remember { AdbManager(context) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showConnectDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.adb_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.adb_back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        thread {
                            val devices = adb.refreshDevices()
                            // State refresh is handled per-tab via LaunchedEffect
                        }
                    }) {
                        Icon(Icons.Filled.Cached, stringResource(R.string.adb_refresh))
                    }
                    IconButton(onClick = { showConnectDialog = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.adb_connect_device))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                AdbTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.label(context), style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }
            when (selectedTab) {
                0 -> DevicesTab(adb = adb, onConnect = { showConnectDialog = true })
                1 -> FilesTab(adb = adb)
                2 -> CommandTab(adb = adb)
                3 -> AppsTab(adb = adb)
                4 -> InfoTab(adb = adb)
            }
        }
    }

    if (showConnectDialog) {
        ConnectDeviceDialog(
            onDismiss = { showConnectDialog = false },
            onConnect = { host, port ->
                showConnectDialog = false
                thread { adb.connectDevice(host, port) }
            }
        )
    }
}

private fun AdbTab.label(context: android.content.Context): String = when (this) {
    AdbTab.DEVICES -> context.getString(R.string.adb_tab_devices)
    AdbTab.FILES -> context.getString(R.string.adb_tab_files)
    AdbTab.COMMAND -> context.getString(R.string.adb_tab_command)
    AdbTab.APPS -> context.getString(R.string.adb_tab_apps)
    AdbTab.INFO -> context.getString(R.string.adb_tab_info)
}

@Composable
private fun DevicesTab(adb: AdbManager, onConnect: () -> Unit) {
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        devices = adb.refreshDevices()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (devices.any { it.isConnected }) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (devices.any { it.isConnected }) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = null,
                        tint = if (devices.any { it.isConnected }) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (devices.any { it.isConnected }) stringResource(R.string.adb_devices_connected) else stringResource(R.string.adb_no_devices),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    "${devices.count { it.isConnected }} ${stringResource(R.string.adb_device_count)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.adb_no_devices_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(devices, key = { it.serial }) { device ->
                    DeviceRow(
                        device = device,
                        isSelected = adb.selectedDevice == device.serial,
                        onSelect = { adb.setSelectedDevice(device.serial) },
                        onDisconnect = {
                            thread {
                                adb.disconnectDevice(device.serial)
                                devices = adb.refreshDevices()
                            }
                        }
                    )
                }
            }
        }

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Icon(Icons.Filled.ConnectWithoutContact, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.adb_connect_wireless))
        }

        Button(
            onClick = {
                refreshing = true
                thread {
                    devices = adb.refreshDevices()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !refreshing,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            if (refreshing) {
                LinearProgressIndicator(modifier = Modifier.width(24.dp))
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Filled.Cached, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.adb_refresh))
        }
    }
}

@Composable
private fun DeviceRow(device: AdbDevice, isSelected: Boolean, onSelect: () -> Unit, onDisconnect: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onSelect() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (device.isConnected && isSelected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (device.isConnected && isSelected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(device.serial, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (device.isWireless) {
                    Text(stringResource(R.string.adb_wireless), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isSelected) {
                Text(stringResource(R.string.adb_selected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (device.isWireless) {
                IconButton(onClick = onDisconnect) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun FilesTab(adb: AdbManager) {
    var currentPath by remember { mutableStateOf("/sdcard") }
    var files by remember { mutableStateOf<List<AdbFile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        thread {
            val result = adb.listFiles(currentPath)
            files = result.getOrNull() ?: emptyList()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                currentPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            IconButton(onClick = {
                val parent = File(currentPath).parent ?: "/"
                currentPath = parent
                refresh()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
            }
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Filled.Cached, null)
            }
        }
        Divider()
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else if (files.isEmpty()) {
            Text(stringResource(R.string.adb_empty_dir), Modifier.align(Alignment.CenterHorizontally), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn {
                items(files) { file ->
                    FileRow(file = file, path = currentPath, onNavigate = {
                        currentPath = "$currentPath/${file.name}"
                        refresh()
                    })
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: AdbFile, path: String, onNavigate: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (file.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            if (!file.isDirectory) {
                Text("${file.size} B", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onNavigate) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
        }
    }
    Divider()
}

@Composable
private fun CommandTab(adb: AdbManager) {
    var command by remember { mutableStateOf("ls") }
    var output by remember { mutableStateOf("") }
    var executing by remember { mutableStateOf(false) }

    fun execute() {
        if (executing) return
        executing = true
        output = ""
        thread {
            val result = adb.shellCommand(command)
            output = result.getOrNull() ?: "Error: ${result.exceptionOrNull()?.message}"
            executing = false
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text(stringResource(R.string.adb_command_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { execute() }, enabled = !executing, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.adb_execute))
            }
            OutlinedButton(onClick = { output = "" }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Clear, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.adb_clear))
            }
        }
        Spacer(Modifier.height(8.dp))
        Card(
            Modifier.fillMaxWidth().weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.adb_output), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (executing) {
                        Spacer(Modifier.width(8.dp))
                        LinearProgressIndicator(modifier = Modifier.width(48.dp))
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                if (output.isEmpty()) {
                    Text(stringResource(R.string.adb_output_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(output.lines()) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsTab(adb: AdbManager) {
    var apps by remember { mutableStateOf<List<String>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        val result = adb.getAppList()
        apps = result.getOrNull()?.lineSequence()?.filter { it.startsWith("package:") }?.map { it.substringAfter("package:") }?.toList() ?: emptyList()
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            label = { Text(stringResource(R.string.adb_search_apps)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val filtered = if (search.isEmpty()) apps else apps.filter { it.contains(search, ignoreCase = true) }
            LazyColumn {
                items(filtered) { pkg ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Apps, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(pkg, style = MaterialTheme.typography.bodyMedium)
                            }
                            OutlinedButton(onClick = { /* 卸载 */ }) {
                                Text(stringResource(R.string.adb_uninstall))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoTab(adb: AdbManager) {
    var deviceInfo by remember { mutableStateOf("") }
    var batteryInfo by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        loading = true
        val infoResult = adb.getDeviceInfo()
        deviceInfo = infoResult.getOrNull() ?: ""
        val batteryResult = adb.getBatteryInfo()
        batteryInfo = batteryResult.getOrNull() ?: ""
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.adb_device_info), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(deviceInfo, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.adb_battery_info), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(batteryInfo, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

@Composable
private fun ConnectDeviceDialog(onDismiss: () -> Unit, onConnect: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5555") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.adb_connect_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.adb_connect_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text(stringResource(R.string.adb_connect_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (host.isNotEmpty()) {
                    onConnect(host, port.toIntOrNull() ?: 5555)
                }
            }) {
                Text(stringResource(R.string.adb_connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.adb_cancel))
            }
        }
    )
}
