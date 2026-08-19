package com.toolbox.app.ui.shizuku

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.PlayArrow
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

private const val TAG = "Shizuku"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShizukuScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var isRunning by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(false) }
    var serverUid by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("id") }
    var output by remember { mutableStateOf("") }
    var executing by remember { mutableStateOf(false) }

    fun checkStatus() {
        try {
            isRunning = Shizuku.ping()
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
        if (!isRunning || !hasPermission) {
            Toast.makeText(context, R.string.shizuku_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        executing = true
        output = ""
        Thread {
            try {
                val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val errReader = BufferedReader(InputStreamReader(proc.errorStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) sb.append(line).append("\n")
                while (errReader.readLine().also { line = it } != null) sb.append("ERR: ").append(line).append("\n")
                proc.waitFor()
                output = sb.toString().trim()
                if (output.isEmpty()) output = "(无输出)"
            } catch (e: Exception) {
                Log.e(TAG, "命令执行失败", e)
                output = "Error: ${e.message}"
            }
            executing = false
        }.start()
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
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    if (isRunning) {
                        Text(
                            "$serverUid · ${if (hasPermission) stringResource(R.string.shizuku_perm_granted) else stringResource(R.string.shizuku_perm_pending)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Text(
                            stringResource(R.string.shizuku_status_no),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shizuku_command_test), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(stringResource(R.string.shizuku_command_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Button(
                        onClick = { executeCommand(command) },
                        enabled = isRunning && hasPermission && !executing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.shizuku_execute))
                    }
                    if (output.isNotEmpty()) {
                        Card(
                            Modifier.fillMaxWidth(),
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

            Button(
                onClick = { showDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !executing
            ) {
                Text(if (isRunning) stringResource(R.string.shizuku_disconnect) else stringResource(R.string.shizuku_connect))
            }

            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.shizuku_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(stringResource(R.string.shizuku_confirm_title)) },
                text = { Text(stringResource(R.string.shizuku_confirm_text, if (isRunning) stringResource(R.string.shizuku_action_disconnect) else stringResource(R.string.shizuku_action_connect))) },
                confirmButton = {
                    Button(onClick = {
                        showDialog = false
                        try {
                            if (isRunning) {
                                Shizuku.unbind(Shizuku.OnBinderSentListener {}, false)
                                Log.i(TAG, "Shizuku 已断开")
                            } else {
                                val intent = Intent("rikka.shizuku.intent.action.START_NEW_INSTANCE")
                                intent.setPackage("moe.shizuku.privileged.api")
                                context.startActivity(intent)
                                Log.i(TAG, "请求启动 Shizuku")
                                checkStatus()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "操作失败", e)
                            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(if (isRunning) stringResource(R.string.shizuku_action_disconnect) else stringResource(R.string.shizuku_action_connect))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
                        Text(stringResource(R.string.shizuku_cancel))
                    }
                }
            )
        }
    }
}