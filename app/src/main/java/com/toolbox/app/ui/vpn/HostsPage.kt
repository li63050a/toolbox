package com.toolbox.app.ui.vpn

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.HostsRule
import com.toolbox.app.vpn.VpnConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "VpnUI"

@Composable
fun HostsPage(context: Context, scope: CoroutineScope, snackbar: SnackbarHostState) {
    val config by VpnConfigStore.config.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HostsRule?>(null) }
    var confirmDelete by remember { mutableStateOf<HostsRule?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            runCatching { VpnConfigStore.importHostsFile(context, uri) }
                .onSuccess { count ->
                    Log.i(TAG, "导入 hosts 文件完成，新增 $count 条")
                    snack(scope, snackbar, "导入完成，新增 $count 条规则")
                }
                .onFailure { e ->
                    Log.e(TAG, "导入 hosts 文件失败", e)
                    snack(scope, snackbar, "导入失败：${e.message}")
                }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Switch(
                    checked = config.hostsEnabled,
                    onCheckedChange = { v ->
                        mutateConfig(scope, snackbar, if (v) "开启 hosts 规则" else "关闭 hosts 规则") {
                            it.copy(hostsEnabled = v)
                        }
                    }
                )
                Column(Modifier.weight(1f)) {
                    Text("启用 hosts 规则", fontWeight = FontWeight.Medium)
                    Text(
                        "命中规则优先于 DNS 上游返回",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (config.hostsRules.isEmpty()) {
            Text(
                "暂无 hosts 规则，可手动添加或导入 hosts 文件",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        config.hostsRules.forEach { rule ->
            HostsRuleCard(
                rule = rule,
                onToggle = {
                    mutateConfig(scope, snackbar, if (rule.enabled) "停用规则 ${rule.domain}" else "启用规则 ${rule.domain}") {
                        it.copy(
                            hostsRules = it.hostsRules.map { r ->
                                if (r.domain == rule.domain && r.ip == rule.ip) r.copy(enabled = !r.enabled) else r
                            }
                        )
                    }
                },
                onEdit = { editing = rule },
                onDelete = { confirmDelete = rule }
            )
        }

        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Text("添加规则", modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Upload, null, modifier = Modifier.size(18.dp))
            Text("导入 hosts 文件", modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "屏蔽域名请将 IP 填为 0.0.0.0，列表中以红色「屏蔽」标示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showAdd) {
        HostsRuleDialog(
            initial = null,
            onDismiss = { showAdd = false },
            onConfirm = { domain, ip ->
                showAdd = false
                mutateConfig(scope, snackbar, "添加 hosts 规则 $domain -> $ip") {
                    it.copy(hostsRules = it.hostsRules + HostsRule(domain, ip, true))
                }
            }
        )
    }
    editing?.let { rule ->
        HostsRuleDialog(
            initial = rule,
            onDismiss = { editing = null },
            onConfirm = { domain, ip ->
                editing = null
                mutateConfig(scope, snackbar, "编辑 hosts 规则 ${rule.domain} 为 $domain -> $ip") {
                    it.copy(
                        hostsRules = it.hostsRules.map { r ->
                            if (r.domain == rule.domain && r.ip == rule.ip) r.copy(domain = domain, ip = ip) else r
                        }
                    )
                }
            }
        )
    }
    confirmDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除规则") },
            text = { Text("确定删除 ${rule.domain} 的规则？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    mutateConfig(scope, snackbar, "删除 hosts 规则 ${rule.domain}") {
                        it.copy(
                            hostsRules = it.hostsRules.filterNot { r -> r.domain == rule.domain && r.ip == rule.ip }
                        )
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun HostsRuleCard(
    rule: HostsRule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(rule.domain, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (rule.ip == "0.0.0.0" || rule.ip == "::") {
                    Text(
                        "屏蔽",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        rule.ip,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Edit, "编辑", Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun HostsRuleDialog(
    initial: HostsRule?,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var ip by remember { mutableStateOf(initial?.ip ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        val d = domain.trim()
        val i = ip.trim()
        when {
            d.isEmpty() -> error = "请输入域名"
            d.any { it.isWhitespace() } -> error = "域名不能包含空格"
            i.isEmpty() -> error = "请输入 IP 地址"
            i.any { it.isWhitespace() } -> error = "IP 不能包含空格"
            else -> {
                error = null
                onConfirm(d, i)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "编辑规则" else "添加规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("域名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP（屏蔽填 0.0.0.0）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { confirm() }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}