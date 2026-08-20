package com.toolbox.app.ui.shizuku

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.log.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import android.content.pm.PackageManager
import android.os.Process

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
    var showSettings by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var isRootAvailable by remember { mutableStateOf(false) }
    var autoStart by remember { mutableStateOf(false) }
    var rootAutoStart by remember { mutableStateOf(false) }
    var snackbarHostState = remember { SnackbarHostState() }

    fun checkStatus() {
        try {
            isRunning = Shizuku.pingBinder()
            hasPermission = if (isRunning) Shizuku.checkSelfPermission() == 0 else false
            serverUid = if (isRunning) "uid=${Shizuku.getUid()}" else ""
            
            // 检查 root 是否可用
            isRootAvailable = checkRootAvailable()
        } catch (e: Exception) {
            isRunning = false
            hasPermission = false
            serverUid = ""
            isRootAvailable = false
        }
    }

fun checkRootAvailable(): Boolean {
    return try {
        val process = Runtime.getRuntime().exec("su")
        process.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        process.destroy()
        true
    } catch (e: Exception) {
        false
    }
}

    LaunchedEffect(Unit) {
        checkStatus()
        Shizuku.addBinderReceivedListenerSticky { checkStatus() }
        Shizuku.addBinderDeadListener {
            isRunning = false
            hasPermission = false
            serverUid = ""
            isRootAvailable = false
        }
        
        // 检查自启动状态
        try {
            val pm = context.packageManager
            val component = android.content.ComponentName(context, ShizukuBootReceiver::class.java)
            val state = pm.getComponentEnabledSetting(component)
            autoStart = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (e: Exception) {
            Log.w(TAG, "检查自启动状态失败")
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
                    hasPermission = grantResult == PackageManager.PERMISSION_GRANTED
                }
            })
            Shizuku.requestPermission(0)
        }
    }

    // 申请 Root 权限
    fun requestRootPermission() {
        Thread {
            try {
                val process = Runtime.getRuntime().exec("su")
                val writer = PrintWriter(process.outputStream, true)
                writer.println("id")
                writer.println("exit")
                writer.flush()
                process.waitFor()
                
                if (process.exitValue() == 0) {
                    isRootAvailable = true
                    // 尝试启动 Shizuku
                    startShizukuViaRoot()
                } else {
                    isRootAvailable = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Root 权限申请失败", e)
                isRootAvailable = false
            }
        }.start()
    }

fun startShizukuViaRoot(context: Context) {
    try {
        val process = Runtime.getRuntime().exec("su")
        val writer = PrintWriter(process.outputStream)
        writer.println("sh /data/local/tmp/start.sh")
        writer.println("exit")
        writer.flush()
        process.waitFor()
    } catch (e: Exception) {
        Log.e(TAG, "Root 启动失败", e)
    }
}

    fun toggleAutoStart(enabled: Boolean) {
        try {
            val pm = context.packageManager
            val component = android.content.ComponentName(context, ShizukuBootReceiver::class.java)
            pm.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            autoStart = enabled
        } catch (e: Exception) {
            Log.e(TAG, "设置自启动失败", e)
        }
    }

    // 主界面
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            // Root 权限申请卡片
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = if (isRootAvailable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Root 权限", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (isRootAvailable) "已获取 Root 权限" else "设备支持 Root 或需要申请",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isRootAvailable) {
                            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    
                    if (!isRootAvailable && !isRunning) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { requestRootPermission() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Icon(Icons.Filled.LockOpen, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("申请 Root 权限")
                        }
                    } else if (isRootAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { startShizukuViaRoot() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("使用 Root 启动 Shizuku")
                        }
                    }
                }
            }

            // 启动选项
            if (!isRunning && !isRootAvailable) {
                // ADB 启动
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("通过连接电脑启动 (使用 ADB)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "对于没有 root 的设备需要借助 adb 来启动 Shizuku（需要连接电脑）。",
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

                // 无线调试启动
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("通过无线调试启动", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "在 Android 11 或更高版本上，您可以直接从您的设备启用无线调试并启动 Shizuku。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { 
                                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS)
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                },
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
                    // TODO: 实现 ADB 启动
                    showInstructions = false 
                }) { Text("发送") }
            },
            dismissButton = {
                TextButton(onClick = { showInstructions = false }) { Text("取消") }
                TextButton(onClick = { 
                    val cmd = "adb shell sh /data/local/tmp/start.sh"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Shizuku指令", cmd))
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
                    // Root 权限
                    SettingRow(
                        label = "Root 权限",
                        description = if (isRootAvailable) "已获取 Root 权限" else "点击申请 Root 权限",
                        trailing = {
                            if (isRootAvailable)
                                AndroidText("已获取", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            else
                                AndroidText("未获取", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                    )
                    
                    // 开机启动
                    SettingRow(
                        label = "开机启动",
                        description = "Shizuku 启动时自动运行",
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { toggleAutoStart(!autoStart) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (autoStart) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text(if (autoStart) "已开启" else "开启", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    )
                    
                    // 自动申请 Root
                    SettingRow(
                        label = "自动申请 Root",
                        description = "启动时自动申请 Root 权限",
                        trailing = {
                            Switch(
                                checked = rootAutoStart,
                                onCheckedChange = { rootAutoStart = it }
                            )
                        }
                    )
                    
                    // 语言
                    SettingRow(
                        label = "语言",
                        description = "跟随系统"
                    )
                    
                    // 深色主题
                    SettingRow(
                        label = "深色主题",
                        description = "跟随系统"
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

// Boot Receiver 用于开机自启动
class ShizukuBootReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == android.content.Intent.ACTION_BOOT_COMPLETED) {
            // 启动 Shizuku 服务
            Log.i(TAG, "开机自启动 Shizuku")
        }
    }
}