package com.toolbox.app.ui.shizuku

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.log.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter

private const val TAG = "Shizuku"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var serverUid by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("id") }
    var output by remember { mutableStateOf("") }
    var executing by remember { mutableStateOf(false) }
    var showStartDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var showPairDialog by remember { mutableStateOf(false) }
    var wirelessDebuggingEnabled by remember { mutableStateOf(false) }
    var pairingCode by remember { mutableStateOf("") }
    var startMethod by remember { mutableStateOf("adb") } // adb, root, wireless

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
        
        // 检查无线调试状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val method = android.provider.Settings.Global::class.java
                    .getDeclaredMethod("getInt", android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType)
                val settings = context.contentResolver
                val enabled = method.invoke(null, settings, "wireless_debugging_enabled", 0) as Int
                wirelessDebuggingEnabled = enabled == 1
            } catch (e: Exception) {
                Log.e(TAG, "检查无线调试状态失败")
            }
        }
    }

    fun executeCommand(cmd: String) {
        if (!isRunning || !hasPermission) return
        executing = true
        output = ""
        Thread {
            try {
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

    fun startShizukuViaAdb() {
        val adbCommand = "adb shell sh /data/local/tmp/start.sh"
        // 尝试通过 ADB 启动
        try {
            val process = Runtime.getRuntime().exec(arrayOf("adb", "shell", "sh", "/data/local/tmp/start.sh"))
            process.waitFor()
            if (process.exitValue() == 0) {
                checkStatus()
            }
        } catch (e: Exception) {
            Log.e(TAG, "ADB 启动失败", e)
            // 显示指令给用户
            showInstructions = true
        }
    }

    fun startShizukuViaRoot() {
        if (!isRunning) {
            try {
                val process = Runtime.getRuntime().exec("su")
                val writer = PrintWriter(process.outputStream)
                writer.println("sh /data/local/tmp/start.sh")
                writer.flush()
                writer.close()
                process.waitFor()
                checkStatus()
            } catch (e: Exception) {
                Log.e(TAG, "Root 启动失败", e)
            }
        }
    }

    fun startShizukuViaWireless() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // 打开无线调试设置
                val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "打开设置失败", e)
            }
        }
    }

    // 主界面
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
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, null)
                    }
                    IconButton(onClick = {}) {
                        Icon(Icons.Filled.MoreVert, null)
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
                        Text(
                            "$serverUid · ${if (hasPermission) "已授权" else "待授权"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (!hasPermission) {
                            Button(onClick = { requestPermission() }, modifier = Modifier.fillMaxWidth()) {
                                Text("申请权限")
                            }
                        }
                    } else {
                        Text(
                            "请先启动 Shizuku 服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // 启动选项
            if (!isRunning) {
                // ADB 启动
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("通过连接电脑启动 (使用 ADB)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "对于没有 root 的设备需要借助 adb 来启动 Shizuku（需要连接电脑）。这个过程每次设备重新启动后需要重新进行。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { showInstructions = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Icon(Icons.Filled.Code, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("查看指令")
                        }
                    }
                }

                // Root 启动
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("启动 (针对已 root 设备)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Shizuku 可以在开机时自动启动。如果没有，请检查您的系统或是第三方工具是否限制了 Shizuku。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { startShizukuViaRoot() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("启动")
                        }
                    }
                }

                // 无线调试启动
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("通过无线调试启动", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "在 Android 11 或更高版本上，您可以直接从您的设备启用无线调试并启动 Shizuku，无需连接到计算机。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { startShizukuViaWireless() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("启动")
                            }
                        }
                    }
                }
            }

            // 命令执行区域
            if (isRunning && hasPermission) {
                Card(modifier = Modifier.fillMaxWidth()) {
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

                // 常用命令
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ls", "ps", "df -h", "cat /proc/version").forEach { cmd ->
                        OutlinedButton(onClick = { command = cmd }) { Text(cmd) }
                    }
                }
            }
        }
    }

    // 查看指令对话框
    if (showInstructions) {
        AlertDialog(
            onDismissRequest = { showInstructions = false },
            title = { Text("查看指令") },
            text = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text(
                        text = "adb shell sh /data/local/tmp/start.sh",
                        modifier = Modifier.padding(12.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { 
                    startShizukuViaAdb()
                    showInstructions = false 
                }) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { showInstructions = false }) { Text("取消") }
                TextButton(onClick = { 
                    android.content.ClipboardManager context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    showInstructions = false 
                }) { Text("复制") }
            }
        )
    }

    // 设置对话框
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 开机启动
                    SettingRow(
                        label = "开机启动 (root)",
                        description = "对于已 root 设备，Shizuku 可以开机启动",
                        trailing = {
                            if (isRunning) 
                                AndroidText("已开启", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            else 
                                AndroidText("已关闭", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    )
                    
                    // 语言
                    SettingRow(
                        label = "语言",
                        description = "跟随系统"
                    )
                    
                    // 参与翻译
                    SettingRow(
                        label = "参与翻译",
                        description = "帮助我们将 Shizuku 翻译至您的语言"
                    )
                    
                    // 深色主题
                    SettingRow(
                        label = "深色主题",
                        description = "跟随系统"
                    )
                    
                    // 黑色夜间主题
                    SettingRow(
                        label = "黑色夜间主题",
                        description = "当夜间模式启用时使用纯黑色主题",
                        trailing = {
                            if (isSystemInDarkTheme()) 
                                AndroidText("关闭", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            else 
                                AndroidText("开启", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun SettingRow(
    label: String,
    description: String,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
    Divider()
}

@Composable
private fun AndroidText(text: String, style: androidx.compose.ui.text.TextStyle, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = style, color = color)
}