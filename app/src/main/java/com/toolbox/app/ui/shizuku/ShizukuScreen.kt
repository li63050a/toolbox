package com.toolbox.app.ui.shizuku

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private const val TAG = "Shizuku"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(onBack: () -> Unit) {
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var serverUid by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("id") }
    var output by remember { mutableStateOf("") }
    var executing by remember { mutableStateOf(false) }
    var showAppList by remember { mutableStateOf(false) }
    var appList by remember { mutableStateOf<List<String>>(emptyList()) }

    fun checkStatus() {
        try {
            isRunning = Shizuku.pingBinder()
            hasPermission = if (isRunning) Shizuku.checkSelfPermission() == 0 else false
            serverUid = if (isRunning) "uid=${Shizuku.getUid()}" else ""
        } catch (e: Exception) {
            isRunning = false
            hasPermission = false
            serverUid = ""
        }
    }

    LaunchedEffect(Unit) {
        checkStatus()
        Shizuku.addBinderReceivedListenerSticky { checkStatus() }
        Shizuku.addBinderDeadListener {
            isRunning = false
            hasPermission = false
            serverUid = ""
        }
    }

    fun executeCommand(cmd: String) {
        if (!isRunning || !hasPermission) return
        executing = true
        output = ""
        Thread {
            try {
                // 通过反射调用 Shizuku.newProcess（包私有方法）
                val clazz = Class.forName("rikka.shizuku.Shizuku")
                val method = clazz.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                method.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val process = method.invoke(null, arrayOf("sh", "-c", cmd), emptyArray<String>(), null) as java.lang.Process
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val errReader = BufferedReader(InputStreamReader(process.errorStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line).append("\n")
                while (errReader.readLine().also { line = it } != null) sb.append("ERR: ").append(line).append("\n")
                process.waitFor()
                output = sb.toString().trim()
                if (output.isEmpty()) output = "(无输出)"
                process.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "命令执行失败", e)
                output = "Error: ${e.message}"
            }
            executing = false
        }.start()
    }

    fun requestPermission() {
        if (isRunning) {
            Shizuku.addRequestPermissionResultListener(object : rikka.shizuku.Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    hasPermission = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
            })
            Shizuku.requestPermission(0)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shizuku", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                },
                actions = {
                    if (isRunning) {
                        IconButton(onClick = { showAppList = !showAppList }) {
                            Icon(Icons.Filled.Apps, null)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
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
                            if (isRunning) "Shizuku 运行中" else "Shizuku 未运行",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (isRunning) {
                        Text("$serverUid · ${if (hasPermission) "已授权" else "待授权"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (!hasPermission) {
                            Button(onClick = { requestPermission() }, modifier = Modifier.fillMaxWidth()) {
                                Text("申请权限")
                            }
                        }
                    } else {
                        Text("请先启动 Shizuku 服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // 命令输入
            if (isRunning && hasPermission) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = command,
                            onValueChange = { command = it },
                            label = { Text("输入命令") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = false,
                            minLines = 2,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                        Button(
                            onClick = { executeCommand(command) },
                            enabled = !executing,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("执行")
                        }
                        if (output.isNotEmpty()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Text(
                                    output,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                                )
                            }
                        }
                    }
                }
            }

            // 常用命令
            if (isRunning) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ls", "ps", "df -h", "cat /proc/version").forEach { cmd ->
                        OutlinedButton(onClick = { command = cmd }) { Text(cmd) }
                    }
                }
            }
        }
    }
}