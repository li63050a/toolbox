package com.toolbox.app.ui.vpn

import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

enum class VPAGE(val title: String) {
    MAIN("VPN"),
    DNS("DNS 服务器"),
    HOSTS("hosts 规则"),
    SNI("SNI 防阻断与伪装"),
    APPS("排除应用"),
    CA("CA 证书")
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
                title = { Text(page.title) },
                navigationIcon = {
                    IconButton(onClick = { if (page == VPAGE.MAIN) onBack() else page = VPAGE.MAIN }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
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

    fun startVpn() {
        try {
            VpnController.start(context)
            Log.i(TAG, "请求启动 VPN")
            snack(scope, snackbar, "启动中… 若弹出系统授权窗口请点击允许")
        } catch (e: SecurityException) {
            Log.e(TAG, "启动 VPN 失败：未获系统授权", e)
            snack(scope, snackbar, "未获 VPN 授权，请在系统弹窗中允许后重新启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动 VPN 失败", e)
            snack(scope, snackbar, "启动失败：${e.message}")
        }
    }

    fun stopVpn() {
        runCatching { VpnController.stop(context) }
        Log.i(TAG, "请求停止 VPN")
        snack(scope, snackbar, "已停止 VPN")
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
                                VpnStatus.ON -> "VPN 运行中"
                                VpnStatus.STARTING -> "正在启动…"
                                VpnStatus.ERROR -> "VPN 运行异常"
                                else -> "VPN 已停止"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            when (status) {
                                VpnStatus.ON -> "已连接 ${formatDuration(elapsed)}"
                                VpnStatus.STARTING -> "请稍候，正在建立隧道"
                                else -> "点击开关或下方按钮启动"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (status == VpnStatus.ERROR) {
                    Text(
                        lastError ?: "未知错误",
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
                        "实时流量",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Text(
                        "上行 ${formatBytes(txBytes)} · 下行 ${formatBytes(rxBytes)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                when (status) {
                    VpnStatus.OFF -> Button(onClick = { startVpn() }, Modifier.fillMaxWidth()) { Text("启动 VPN") }
                    VpnStatus.STARTING -> Button(onClick = {}, Modifier.fillMaxWidth(), enabled = false) { Text("正在启动…") }
                    VpnStatus.ON -> OutlinedButton(onClick = { stopVpn() }, Modifier.fillMaxWidth()) { Text("停止 VPN") }
                    VpnStatus.ERROR -> Button(onClick = { startVpn() }, Modifier.fillMaxWidth()) { Text("重新启动") }
                }
            }
        }

        EntryRow(
            Icons.Filled.Dns,
            "DNS 服务器",
            "已配置 ${config.dnsServers.size} 个上游"
        ) { onOpen(VPAGE.DNS) }
        EntryRow(
            Icons.Filled.FormatListBulleted,
            "hosts 规则",
            "启用 ${config.hostsRules.count { it.enabled }} / ${config.hostsRules.size} 条"
        ) { onOpen(VPAGE.HOSTS) }
        EntryRow(
            Icons.Filled.Lock,
            "SNI 防阻断与伪装",
            if (config.frag.enabled || config.spoof.enabled || config.mitmEnabled) "已开启" else "未开启"
        ) { onOpen(VPAGE.SNI) }
        EntryRow(
            Icons.Filled.AppBlocking,
            "排除应用",
            "${config.blockedApps.size} 个应用不走 VPN"
        ) { onOpen(VPAGE.APPS) }
        EntryRow(
            Icons.Filled.VerifiedUser,
            "CA 证书",
            if (hasCa) "已生成" else "未生成"
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
                .onFailure { Log.e(TAG, "读取 CA 证书失败", it) }
                .getOrDefault(false)
        }
    }
    return has
}

internal fun snack(scope: CoroutineScope, snackbar: SnackbarHostState, msg: String) {
    scope.launch { runCatching { snackbar.showSnackbar(msg) } }
}

internal fun mutateConfig(
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    action: String,
    block: (VpnConfig) -> VpnConfig
) {
    scope.launch {
        runCatching { VpnConfigStore.mutate(block) }
            .onSuccess { Log.i(TAG, action); snack(scope, snackbar, action) }
            .onFailure { Log.e(TAG, "$action 失败", it); snack(scope, snackbar, "$action 失败：${it.message}") }
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