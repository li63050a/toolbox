package com.toolbox.app.ui.vpn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.DnsType
import com.toolbox.app.vpn.DnsUpstream
import com.toolbox.app.vpn.VpnConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "VpnUI"

@Composable
fun DnsPage(scope: CoroutineScope, snackbar: SnackbarHostState) {
    val config by VpnConfigStore.config.collectAsState()
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (config.dnsServers.isEmpty()) {
            Text(
                stringResource(R.string.dns_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        config.dnsServers.forEach { dns ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        dnsTypeText(dns.type, stringResource(R.string.dns_plain_udp)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        "${dns.host}:${dns.port}",
                        Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = {
                        scope.launch {
                            runCatching {
                                VpnConfigStore.mutate { it.copy(dnsServers = it.dnsServers.filter { x -> x != dns }) }
                            }.onSuccess {
                                Log.i(TAG, "Deleted DNS upstream ${dns.host}:${dns.port}")
                                snack(scope, snackbar, context.getString(R.string.dns_deleted, dnsTypeText(dns.type, context.getString(R.string.dns_plain_udp)), "${dns.host}:${dns.port}"))
                            }.onFailure {
                                Log.e(TAG, "Failed to delete DNS upstream", it)
                                snack(scope, snackbar, context.getString(R.string.dns_delete_failed, it.message))
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.dns_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Card(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.dns_add_server), fontWeight = FontWeight.Medium)
            }
        }
        Text(
            stringResource(R.string.dns_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showAdd) {
        AddDnsDialog(scope = scope, snackbar = snackbar, onDismiss = { showAdd = false })
    }
}

@Composable
private fun AddDnsDialog(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var dnsType by remember { mutableStateOf(DnsType.PLAIN) }
    var host by remember { mutableStateOf("") }
    var portText by remember { mutableStateOf("53") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dnsType) {
        portText = when (dnsType) {
            DnsType.PLAIN -> "53"
            DnsType.DOT -> "853"
            DnsType.DOH -> "443"
        }
    }

    fun confirm() {
        val h = host.trim()
        val p = portText.trim().toIntOrNull()
        when {
            h.isEmpty() -> error = context.getString(R.string.dns_err_addr)
            p == null || p !in 1..65535 -> error = context.getString(R.string.dns_err_port)
            else -> {
                error = null
                onDismiss()
                mutateConfig(context, scope, snackbar, context.getString(R.string.dns_added, dnsTypeText(dnsType, context.getString(R.string.dns_plain_udp)), "$h:$p")) {
                    it.copy(dnsServers = it.dnsServers + DnsUpstream(dnsType, h, p))
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dns_add_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = dnsType == DnsType.PLAIN,
                        onClick = { dnsType = DnsType.PLAIN },
                        label = { Text(stringResource(R.string.dns_plain_udp)) }
                    )
                    FilterChip(
                        selected = dnsType == DnsType.DOT,
                        onClick = { dnsType = DnsType.DOT },
                        label = { Text("DoT") }
                    )
                    FilterChip(
                        selected = dnsType == DnsType.DOH,
                        onClick = { dnsType = DnsType.DOH },
                        label = { Text("DoH") }
                    )
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(stringResource(R.string.dns_label_addr)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { s -> portText = s.filter { c -> c.isDigit() } },
                    label = { Text(stringResource(R.string.dns_label_port)) },
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
        confirmButton = { TextButton(onClick = { confirm() }) { Text(stringResource(R.string.dns_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dns_cancel)) } }
    )
}

private fun dnsTypeText(t: DnsType, plainUdp: String): String = when (t) {
    DnsType.PLAIN -> plainUdp
    DnsType.DOT -> "DoT"
    DnsType.DOH -> "DoH"
}