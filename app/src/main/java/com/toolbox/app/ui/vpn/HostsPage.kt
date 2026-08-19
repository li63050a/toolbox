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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
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
                    Log.i(TAG, "Hosts file import complete, added $count rules")
                    snack(scope, snackbar, context.getString(R.string.hosts_import_done, count))
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to import hosts file", e)
                    snack(scope, snackbar, context.getString(R.string.hosts_import_failed, e.message))
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
                        mutateConfig(context, scope, snackbar, if (v) context.getString(R.string.hosts_enable) else context.getString(R.string.hosts_disable)) {
                            it.copy(hostsEnabled = v)
                        }
                    }
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.hosts_enable_title), fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.hosts_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (config.hostsRules.isEmpty()) {
            Text(
                stringResource(R.string.hosts_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        config.hostsRules.forEach { rule ->
            HostsRuleCard(
                rule = rule,
                onToggle = {
                    mutateConfig(context, scope, snackbar, if (rule.enabled) context.getString(R.string.hosts_rule_disable, rule.domain) else context.getString(R.string.hosts_rule_enable, rule.domain)) {
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
            Text(stringResource(R.string.hosts_add_rule), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("text/plain", "text/*", "*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Upload, null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.hosts_import), modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            stringResource(R.string.hosts_block_hint),
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
                mutateConfig(context, scope, snackbar, context.getString(R.string.hosts_added, domain, ip)) {
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
                mutateConfig(context, scope, snackbar, context.getString(R.string.hosts_edited, rule.domain, domain, ip)) {
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
            title = { Text(stringResource(R.string.hosts_delete_title)) },
            text = { Text(stringResource(R.string.hosts_delete_confirm, rule.domain)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    mutateConfig(context, scope, snackbar, context.getString(R.string.hosts_deleted, rule.domain)) {
                        it.copy(
                            hostsRules = it.hostsRules.filterNot { r -> r.domain == rule.domain && r.ip == rule.ip }
                        )
                    }
                }) { Text(stringResource(R.string.hosts_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.hosts_cancel)) } }
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
                        stringResource(R.string.hosts_blocked),
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
                Icon(Icons.Filled.Edit, stringResource(R.string.hosts_edit), Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Delete, stringResource(R.string.hosts_delete), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
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
    val context = LocalContext.current
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var ip by remember { mutableStateOf(initial?.ip ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    fun confirm() {
        val d = domain.trim()
        val i = ip.trim()
        when {
            d.isEmpty() -> error = context.getString(R.string.hosts_err_domain)
            d.any { it.isWhitespace() } -> error = context.getString(R.string.hosts_err_domain_space)
            i.isEmpty() -> error = context.getString(R.string.hosts_err_ip)
            i.any { it.isWhitespace() } -> error = context.getString(R.string.hosts_err_ip_space)
            else -> {
                error = null
                onConfirm(d, i)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial != null) R.string.hosts_edit_rule else R.string.hosts_add_rule)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text(stringResource(R.string.hosts_label_domain)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text(stringResource(R.string.hosts_label_ip)) },
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
        confirmButton = { TextButton(onClick = { confirm() }) { Text(stringResource(R.string.hosts_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.hosts_cancel)) } }
    )
}