package com.toolbox.app.ui.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

private const val TAG = "Shizuku"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var serverUid by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("id") }
    var output by remember { mutableStateOf("") }
    var executing by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var authPkg by remember { mutableStateOf("") }
    var authResult by remember { mutableStateOf<String?>(null) }
    var snackbarHostState = remember { SnackbarHostState() }

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
        scope.launch(Dispatchers.IO) {
            try {
                val clazz = Class.forName("rikka.shizuku.Shizuku")
                val method: Method = clazz.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
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
            withContext(Dispatchers.Main) { executing = false }
        }
    }

    fun requestPermission() {
        if (isRunning) {
            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    hasPermission = grantResult == PackageManager.PERMISSION_GRANTED
                }
            })
            Shizuku.requestPermission(0)
        }
    }

    fun openShizukuApp() {
        try {
            val intent = Intent().apply {
                setClassName("moe.shizuku.privileged.api", "moe.shizuku.privileged.api.activity.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            scope.launch { snackbarHostState.showSnackbar("未安装 Shizuku，请先安装") }
        }
    }

    fun showGrantDialog(pkg: String) {
        authPkg = pkg
        authResult = null
        showAuthDialog = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Shizuku", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
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
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { openShizukuApp() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                            ) {
                                Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("打开 Shizuku")
                            }
                            OutlinedButton(
                                onClick = { checkStatus() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("刷新")
                            }
                        }
                    } else {
                        Text(
                            "请先启动 Shizuku 服务",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Button(
                            onClick = { openShizukuApp() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("打开 Shizuku 启动服务")
                        }
                    }
                }
            }

            // 授权其他应用卡片
            if (isRunning) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("授权其他应用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "为其他应用申请 Shizuku 权限",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (hasPermission) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { showGrantDialog(context.packageName) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Security, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("管理授权应用")
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

    // 授权对话框
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = { Text("授权其他应用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = authPkg,
                        onValueChange = { authPkg = it },
                        label = { Text("包名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (authResult != null) {
                        Text(authResult ?: "", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (authPkg.isEmpty()) return@Button
                    try {
                        val intent = Intent().apply {
                            setClassName("moe.shizuku.privileged.api", "moe.shizuku.privileged.api.activity.PermissionActivity")
                            putExtra("pkg", authPkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        authResult = "已打开 Shizuku 授权界面"
                    } catch (e: Exception) {
                        authResult = "错误: ${e.message}"
                    }
                }) {
                    Text("授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAuthDialog = false }) { Text("取消") }
            }
        )
    }
}
