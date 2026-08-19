package com.toolbox.app.ui.shizuku

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ConnectWithoutContact
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.log.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread

private const val TAG = "Shizuku"

data class AdbDevice(val serial: String, val state: String) {
    val isConnected: Boolean get() = state == "device"
}

class AdbManager(private val context: Context) {
    var devices = mutableListOf<AdbDevice>()
    var selectedDevice: String? = null

    fun refreshDevices(): Result<Unit> {
        return try {
            val proc = ProcessBuilder("adb", "devices").start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            devices.clear()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.startsWith("List") == true) continue
                val parts = line?.split("\t") ?: continue
                if (parts.size >= 2) devices.add(AdbDevice(parts[0], parts[1]))
            }
            proc.waitFor()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "adb devices 失败", e)
            Result.failure(e)
        }
    }

    fun connectDevice(host: String, port: Int = 5555): Result<Unit> {
        return try {
            val proc = ProcessBuilder("adb", "connect", "$host:$port").start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val output = reader.readLine()
            proc.waitFor()
            Log.i(TAG, "adb connect $host:$port -> $output")
            if (output?.contains("connected") == true || output?.contains("already connected") == true) {
                refreshDevices()
                Result.success(Unit)
            } else Result.failure(Exception(output ?: "连接失败"))
        } catch (e: Exception) {
            Log.e(TAG, "连接设备失败", e)
            Result.failure(e)
        }
    }

    fun disconnectDevice(serial: String): Result<Unit> {
        return try {
            if (serial.contains(":")) {
                val proc = ProcessBuilder("adb", "disconnect", serial).start()
                proc.waitFor()
                refreshDevices()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "断开设备失败", e)
            Result.failure(e)
        }
    }

    fun executeCommand(cmd: String, onOutput: (String) -> Unit = {}, onComplete: (Boolean, String) -> Unit = { _, _ -> }): Thread {
        return thread(name = "adb-${cmd.take(20)}") {
            try {
                val args = buildList {
                    add("adb")
                    selectedDevice?.let { add("-s"); add(it) }
                    add("shell")
                    add(cmd)
                }
                val proc = ProcessBuilder(*args.toTypedArray()).start()
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val errReader = BufferedReader(InputStreamReader(proc.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) onOutput(line)
                while (errReader.readLine().also { line = it } != null) onOutput("ERR: $line")
                proc.waitFor()
                onComplete(proc.exitValue() == 0, "exit=${proc.exitValue()}")
            } catch (e: Exception) {
                Log.e(TAG, "执行命令失败", e)
                onComplete(false, e.message ?: "未知错误")
            }
        }.apply { start() }
    }
}

enum class ShizukuPage { MAIN, ADB }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(ShizukuPage.MAIN) }
    when (page) {
        ShizukuPage.MAIN -> MainShizukuPage(onBack = { if (page == ShizukuPage.MAIN) onBack() else page = ShizukuPage.MAIN })
        ShizukuPage.ADB -> AdbPage(onBack = { page = ShizukuPage.MAIN })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShizukuPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var serverInfo by remember { mutableStateOf("") }

    fun checkStatus() {
        try {
            isRunning = Shizuku.checkSelfPermission() == 0 || Shizuku.checkSelfPermission() == -1
        } catch (e: Exception) { isRunning = false }
        hasPermission = if (isRunning) Shizuku.checkSelfPermission() == 0 else false
        serverInfo = if (isRunning) "uid=${Shizuku.getUid()}" else ""
    }

    LaunchedEffect(Unit) {
        checkStatus()
        Shizuku.addBinderReceivedListenerSticky { checkStatus() }
        Shizuku.addBinderDeadListener {
            isRunning = false
            hasPermission = false
            serverInfo = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shizuku_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.shizuku_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isRunning) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isRunning) stringResource(R.string.shizuku_running) else stringResource(R.string.shizuku_not_running),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        if (isRunning) "${serverInfo} · ${if (hasPermission) stringResource(R.string.shizuku_perm_granted) else stringResource(R.string.shizuku_perm_pending)}"
                        else stringResource(R.string.shizuku_status_no),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Button(
                onClick = { /* 跳往 ADB 页面 */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.ConnectWithoutContact, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shizuku_open_adb))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.shizuku_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdbPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val adb = remember { AdbManager(context) }
    var command by remember { mutableStateOf("ls") }
    var output by remember { mutableStateOf("") }
    var executing by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var connectHost by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("5555") }
    var lastError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { adb.refreshDevices() }

    fun refresh() = adb.refreshDevices()

    fun execute() {
        if (executing) return
        executing = true
        output = ""
        adb.executeCommand(command) { line -> output = output + line + "\n" } { success, msg ->
            executing = false
            if (!success) lastError = msg
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shizuku_adb_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.shizuku_back))
                    }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Cached, stringResource(R.string.shizuku_refresh))
                    }
                    IconButton(onClick = { showConnectDialog = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.shizuku_connect_device))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 设备列表
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(stringResource(R.string.shizuku_devices), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (adb.devices.isEmpty()) {
                        Text(stringResource(R.string.shizuku_no_devices), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        adb.devices.forEach { device ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable {
                                    if (device.isConnected) adb.selectedDevice = device.serial
                                },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (device.isConnected) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = if (device.isConnected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(device.serial, style = MaterialTheme.typography.bodyMedium)
                                if (device.isConnected && adb.selectedDevice == device.serial) {
                                    Spacer(Modifier.weight(1f))
                                    Text(stringResource(R.string.shizuku_selected), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                if (device.serial.contains(":")) {
                                    IconButton(onClick = { adb.disconnectDevice(device.serial) }) {
                                        Icon(Icons.Filled.Delete, null, Modifier.size(16.dp))
                                    }
                                }
                            }
                            Divider()
                        }
                    }
                }
            }

            // 命令输入
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.shizuku_command_hint)) },
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
                            Text(stringResource(R.string.shizuku_run))
                        }
                        OutlinedButton(onClick = { output = "" }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Clear, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.shizuku_clear))
                        }
                    }
                }
            }

            // 输出区
            Card(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.shizuku_output), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        if (executing) {
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        }
                    }
                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    if (output.isEmpty() && lastError == null) {
                        Text(stringResource(R.string.shizuku_output_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            items(output.lines()) { line ->
                                Text(line, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            }
                        }
                    }
                    if (lastError != null) {
                        Text("Error: $lastError", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    if (showConnectDialog) {
        AlertDialog(
            onDismissRequest = { showConnectDialog = false },
            title = { Text(stringResource(R.string.shizuku_connect_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = connectHost, onValueChange = { connectHost = it }, label = { Text(stringResource(R.string.shizuku_connect_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = connectPort, onValueChange = { connectPort = it }, label = { Text(stringResource(R.string.shizuku_connect_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    showConnectDialog = false
                    if (connectHost.isNotEmpty()) {
                        adb.connectDevice(connectHost, connectPort.toIntOrNull() ?: 5555)
                        connectHost = ""
                        connectPort = "5555"
                    }
                }) { Text(stringResource(R.string.shizuku_connect)) }
            },
            dismissButton = {
                TextButton(onClick = { showConnectDialog = false }) { Text(stringResource(R.string.shizuku_cancel)) }
            }
        )
    }
}