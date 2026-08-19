package com.toolbox.app.ui.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AppBlocking
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.VpnConfig
import com.toolbox.app.vpn.VpnConfigStore
import com.toolbox.app.vpn.VpnController
import com.toolbox.app.vpn.VpnStatus
import com.toolbox.app.vpn.mitm.CertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

enum class VPAGE(@StringRes val titleRes: Int) {
    MAIN(R.string.vpn_main),
    DNS(R.string.vpn_dns_server),
    HOSTS(R.string.vpn_hosts_rules),
    SNI(R.string.vpn_sni_title),
    APPS(R.string.vpn_excluded_apps_title),
    CA(R.string.vpn_ca_title)
}

private const val TAG = "VpnUI"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VpnScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var page by remember { mutableStateOf(VPAGE.MAIN) }

    val status by VpnController.status.collectAsState()
    var connectedAt by remember { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(status) {
        if (status == VpnStatus.ON) {
            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
        } else {
            connectedAt = 0L
        }
        while (status == VpnStatus.ON && connectedAt > 0L) {
            elapsed = System.currentTimeMillis() - connectedAt
            delay(1000)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(page.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = { if (page == VPAGE.MAIN) onBack() else page = VPAGE.MAIN }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.vpn_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                VPAGE.MAIN -> MainPage(status, elapsed, context, scope, snackbar) { page = it }
                VPAGE.DNS -> DnsPage(scope, snackbar)
                VPAGE.HOSTS -> HostsPage(context, scope, snackbar)
                VPAGE.SNI -> SniPage(context, scope, snackbar)
                VPAGE.APPS -> AppsPage(context, scope, snackbar)
                VPAGE.CA -> CaPage(context, scope, snackbar)
            }
        }
    }
}

@Composable
private fun MainPage(
    status: VpnStatus,
    elapsed: Long,
    context: Context,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onOpen: (VPAGE) -> Unit
) {
    val txBytes by VpnController.txBytes.collectAsState()
    val rxBytes by VpnController.rxBytes.collectAsState()
    val lastError by VpnController.lastError.collectAsState()
    val config by VpnConfigStore.config.collectAsState()
    val hasCa = rememberHasCa(context)

    val running = status == VpnStatus.ON

    // 系统 VPN 授权回调：用户在授权页点击允许/拒绝后，这里自动重试启动
    val vpnAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (VpnService.prepare(context) == null) {
            VpnController.start(context)
        } else {
            snack(scope, snackbar, context.getString(R.string.vpn_start_no_permission))
        }
    }

    fun startVpn() {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            Log.i(TAG, "请求系统 VPN 授权")
            vpnAuthLauncher.launch(prepareIntent)
            snack(scope, snackbar, context.getString(R.string.vpn_starting_message))
            return
        }
        try {
            VpnController.start(context)
            Log.i(TAG, "Requesting VPN start")
            snack(scope, snackbar, context.getString(R.string.vpn_starting_message))
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to start VPN: no system permission", e)
            snack(scope, snackbar, context.getString(R.string.vpn_start_no_permission))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            snack(scope, snackbar, context.getString(R.string.vpn_start_failed, e.message))
        }
    }

    fun stopVpn() {
        runCatching { VpnController.stop(context) }
        Log.i(TAG, "Requesting VPN stop")
        snack(scope, snackbar, context.getString(R.string.vpn_stopped))
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(
                        checked = running,
                        onCheckedChange = { want -> if (want) startVpn() else stopVpn() },
                        enabled = status != VpnStatus.STARTING
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (status) {
                                VpnStatus.ON -> stringResource(R.string.vpn_status_running)
                                VpnStatus.STARTING -> stringResource(R.string.vpn_starting)
                                VpnStatus.ERROR -> stringResource(R.string.vpn_status_error)
                                else -> stringResource(R.string.vpn_status_stopped)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when (status) {
                                VpnStatus.ON -> stringResource(R.string.vpn_connected_duration, formatDuration(elapsed))
                                VpnStatus.STARTING -> stringResource(R.string.vpn_tunnel_starting)
                                else -> stringResource(R.string.vpn_start_hint)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (status == VpnStatus.ERROR) {
                    Text(
                        lastError ?: stringResource(R.string.vpn_unknown_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Dns,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        stringResource(R.string.vpn_realtime_traffic),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Text(
                        stringResource(R.string.vpn_traffic_up_down, formatBytes(txBytes), formatBytes(rxBytes)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                when (status) {
                    VpnStatus.OFF -> Button(onClick = { startVpn() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vpn_button_start)) }
                    VpnStatus.STARTING -> Button(onClick = {}, Modifier.fillMaxWidth(), enabled = false) { Text(stringResource(R.string.vpn_starting)) }
                    VpnStatus.ON -> OutlinedButton(onClick = { stopVpn() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vpn_button_stop)) }
                    VpnStatus.ERROR -> Button(onClick = { startVpn() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.vpn_restart)) }
                }
            }
        }

        EntryRow(
            Icons.Filled.Dns,
            stringResource(R.string.vpn_dns_server),
            stringResource(R.string.vpn_dns_count, config.dnsServers.size)
        ) { onOpen(VPAGE.DNS) }
        EntryRow(
            Icons.Filled.FormatListBulleted,
            stringResource(R.string.vpn_hosts_rules),
            stringResource(R.string.vpn_hosts_count, config.hostsRules.count { it.enabled }, config.hostsRules.size)
        ) { onOpen(VPAGE.HOSTS) }
        EntryRow(
            Icons.Filled.Lock,
            stringResource(R.string.vpn_sni_title),
            stringResource(if (config.frag.enabled || config.spoof.enabled || config.mitmEnabled) R.string.vpn_enabled else R.string.vpn_disabled)
        ) { onOpen(VPAGE.SNI) }
        EntryRow(
            Icons.Filled.AppBlocking,
            stringResource(R.string.vpn_excluded_apps_title),
            stringResource(R.string.vpn_excluded_apps_count, config.blockedApps.size)
        ) { onOpen(VPAGE.APPS) }
        EntryRow(
            Icons.Filled.VerifiedUser,
            stringResource(R.string.vpn_ca_title),
            stringResource(if (hasCa) R.string.vpn_generated else R.string.vpn_not_generated)
        ) { onOpen(VPAGE.CA) }
    }
}

@Composable
private fun EntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun rememberHasCa(context: Context): Boolean {
    var has by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        has = withContext(Dispatchers.IO) {
            runCatching { CertManager.caCertificate(context) != null }
                .onFailure { Log.e(TAG, "Failed to read CA certificate", it) }
                .getOrDefault(false)
        }
    }
    return has
}

internal fun snack(scope: CoroutineScope, snackbar: SnackbarHostState, msg: String) {
    scope.launch { runCatching { snackbar.showSnackbar(msg) } }
}

internal fun mutateConfig(
    context: Context,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    action: String,
    block: (VpnConfig) -> VpnConfig
) {
    scope.launch {
        runCatching { VpnConfigStore.mutate(block) }
            .onSuccess { Log.i(TAG, action); snack(scope, snackbar, action) }
            .onFailure { Log.e(TAG, "$action failed", it); snack(scope, snackbar, context.getString(R.string.vpn_action_failed, action, it.message)) }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}

internal fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}