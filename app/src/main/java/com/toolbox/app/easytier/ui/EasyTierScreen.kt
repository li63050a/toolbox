package com.toolbox.app.easytier.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.EasyTierVpnService
import com.toolbox.app.easytier.EasyTierConfig
import com.toolbox.app.easytier.EasyTierManager
import com.toolbox.app.easytier.NetworkSnapshot
import com.toolbox.app.easytier.PeerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyTierScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mgr = remember { EasyTierManager(context) }
    val snackbar = remember { SnackbarHostState() }

    var config by remember { mutableStateOf(mgr.loadConfig()) }
    var status by remember { mutableStateOf<NetworkSnapshot?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }
    var vpnAuthorized by remember { mutableStateOf(false) }

    val vpnLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        vpnAuthorized = true
    }

    LaunchedEffect(Unit) {
        vpnAuthorized = android.net.VpnService.prepare(context) == null
        scope.launch {
            val snap = mgr.getStatus()
            status = snap
            isRunning = snap.isRunning
        }
    }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isRunning) {
                withContext(Dispatchers.IO) {
                    val snap = mgr.getStatus()
                    status = snap
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    fun start() {
        if (!vpnAuthorized) {
            val intent = android.net.VpnService.prepare(context)
            if (intent != null) {
                vpnLauncher.launch(intent)
            } else {
                vpnAuthorized = true
                scope.launch {
                    loading = true
                    val result = mgr.start(config)
                    result.fold(
                        onSuccess = {
                            isRunning = true
                            loading = false
                            val vpnIntent = Intent(context, EasyTierVpnService::class.java).apply {
                                putExtra(EasyTierVpnService.EXTRA_INSTANCE_NAME, config.instanceName)
                                putExtra(EasyTierVpnService.EXTRA_IPV4_ADDRESS, if (config.dhcp) "10.64.0.1/24" else "${config.virtualIpv4.split('/')[0]}/24")
                            }
                            context.startForegroundService(vpnIntent)
                        },
                        onFailure = {
                            scope.launch { snackbar.showSnackbar(result.exceptionOrNull()?.message ?: "启动失败") }
                            loading = false
                        }
                    )
                }
            }
            return
        }
        scope.launch {
            loading = true
            val result = mgr.start(config)
            result.fold(
                onSuccess = {
                    isRunning = true
                    loading = false
                    val vpnIntent = Intent(context, EasyTierVpnService::class.java).apply {
                        putExtra(EasyTierVpnService.EXTRA_INSTANCE_NAME, config.instanceName)
                        putExtra(EasyTierVpnService.EXTRA_IPV4_ADDRESS, if (config.dhcp) "10.64.0.1/24" else "${config.virtualIpv4.split('/')[0]}/24")
                    }
                    context.startForegroundService(vpnIntent)
                },
                onFailure = {
                    scope.launch { snackbar.showSnackbar(result.exceptionOrNull()?.message ?: "启动失败") }
                    loading = false
                }
            )
        }
    }

    fun stop() {
        scope.launch {
            mgr.stop()
            isRunning = false
            status = mgr.getStatus()
            val vpnIntent = Intent(context, EasyTierVpnService::class.java).apply {
                action = EasyTierVpnService.ACTION_STOP
            }
            context.stopService(vpnIntent)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("EasyTier 组网", fontWeight = FontWeight.Bold) },
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = activeTab) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("状态") })
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("配置") })
            }
            when (activeTab) {
                0 -> StatusTab(status, isRunning, mgr, scope, snackbar, ::start, ::stop, loading)
                1 -> ConfigTab(config, onChange = { config = it }, onSave = { mgr.saveConfig(config); scope.launch { snackbar.showSnackbar("已保存") } })
            }
        }
    }

    if (showSettings) {
        SettingsDialog(config, { showSettings = false }, { c -> config = c; mgr.saveConfig(c) })
    }
}

@Composable
private fun StatusTab(
    status: NetworkSnapshot?,
    isRunning: Boolean,
    mgr: EasyTierManager,
    scope: CoroutineScope,
    snackbar: SnackbarHostState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    loading: Boolean,
    ctx: android.content.Context = LocalContext.current
) {
    val peers = status?.peers ?: emptyList()
    val myNode = status?.myNode

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(12.dp),
                        shape = CircleShape,
                        color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isRunning) "运行中" else "已停止",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (myNode != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusRow("主机名", myNode.hostname)
                        StatusRow("虚拟 IP", myNode.virtualIp)
                        StatusRow("版本", myNode.version)
                    }
                }
                if (status?.error != null) {
                    Text(status.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isRunning) {
                        Button(onClick = { onStop() }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("停止")
                        }
                    } else {
                        Button(
                            onClick = { onStart() },
                            enabled = !loading,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else {
                                Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(if (loading) "启动中..." else "启动")
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.People, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("对端节点 (${peers.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                if (peers.isEmpty()) {
                    Text("暂无对端节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(peers) { peer -> PeerRow(peer) }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val info = status?.myNode?.virtualIp ?: "无IP"
                    val clip = android.content.ClipData.newPlainText("EasyTier IP", info)
                    (ctx.getSystemService(Activity.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(clip)
                    scope.launch { snackbar.showSnackbar("IP 已复制: $info") }
                },
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制IP") }
            OutlinedButton(
                onClick = { scope.launch { snackbar.showSnackbar("配置请通过设置保存后重新启动") } },
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新") }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerInfo) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            shape = CircleShape,
            color = if (peer.isDirect) Color(0xFF4CAF50) else Color(0xFFFFA000)
        ) {}
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.hostname, style = MaterialTheme.typography.bodyMedium)
            Text(peer.virtualIp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (peer.latency.isNotBlank()) Text(peer.latency, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (peer.natType.isNotBlank() && peer.natType != "未知") Text(peer.natType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigTab(config: EasyTierConfig, onChange: (EasyTierConfig) -> Unit, onSave: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("基本信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = config.instanceName,
                    onValueChange = { onChange(config.copy(instanceName = it)) },
                    label = { Text("实例名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = config.networkName,
                    onValueChange = { onChange(config.copy(networkName = it)) },
                    label = { Text("网络名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = config.networkSecret,
                    onValueChange = { onChange(config.copy(networkSecret = it)) },
                    label = { Text("网络密钥") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = config.peers,
                    onValueChange = { onChange(config.copy(peers = it)) },
                    label = { Text("对等节点（每行一个）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("网络设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = config.dhcp, onCheckedChange = { onChange(config.copy(dhcp = it)) })
                    Spacer(Modifier.width(8.dp))
                    Text("自动分配IP (DHCP)")
                }
                if (!config.dhcp) {
                    OutlinedTextField(
                        value = config.virtualIpv4,
                        onValueChange = { onChange(config.copy(virtualIpv4 = it)) },
                        label = { Text("虚拟IPv4") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = config.listenerUrls,
                    onValueChange = { onChange(config.copy(listenerUrls = it)) },
                    label = { Text("监听地址（每行一个）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("高级选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = config.acceptDns, onCheckedChange = { onChange(config.copy(acceptDns = it)) })
                    Spacer(Modifier.width(8.dp))
                    Text("魔法DNS（hostname.et访问）")
                }
                if (config.acceptDns) {
                    OutlinedTextField(
                        value = config.tldDnsZone,
                        onValueChange = { onChange(config.copy(tldDnsZone = it)) },
                        label = { Text("TLD 域名后缀（如 et）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = config.disableP2p, onCheckedChange = { onChange(config.copy(disableP2p = it)) })
                    Spacer(Modifier.width(8.dp))
                    Text("禁用P2P（仅中转）")
                }
                OutlinedTextField(
                    value = config.relayNetworkWhitelist,
                    onValueChange = { onChange(config.copy(relayNetworkWhitelist = it)) },
                    label = { Text("中转网络白名单（空=全转发）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = config.hostname,
                    onValueChange = { onChange(config.copy(hostname = it)) },
                    label = { Text("主机名（留空=系统主机名）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TOML 配置预览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val toml by remember(config) { derivedStateOf { config.toToml() } }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        toml,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }

        Button(
            onClick = { onSave() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Filled.Save, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("保存配置")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    config: EasyTierConfig,
    onDismiss: () -> Unit,
    onSave: (EasyTierConfig) -> Unit
) {
    var c by remember { mutableStateOf(config) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更多设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = c.instanceName, onValueChange = { c = c.copy(instanceName = it) },
                    label = { Text("实例名称") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = c.networkName, onValueChange = { c = c.copy(networkName = it) },
                    label = { Text("网络名称") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = c.networkSecret, onValueChange = { c = c.copy(networkSecret = it) },
                    label = { Text("网络密钥") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = c.peers, onValueChange = { c = c.copy(peers = it) },
                    label = { Text("对等节点（每行一个）") }, minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = c.dhcp, onCheckedChange = { c = c.copy(dhcp = it) })
                    Spacer(Modifier.width(8.dp)); Text("自动分配IP")
                }
                if (!c.dhcp) {
                    OutlinedTextField(
                        value = c.virtualIpv4, onValueChange = { c = c.copy(virtualIpv4 = it) },
                        label = { Text("虚拟IPv4") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = c.listenerUrls, onValueChange = { c = c.copy(listenerUrls = it) },
                    label = { Text("监听地址") }, minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Divider()
                Text("加密与传输", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = c.acceptDns, onCheckedChange = { c = c.copy(acceptDns = it) })
                    Spacer(Modifier.width(8.dp)); Text("魔法DNS")
                }
                if (c.acceptDns) {
                    OutlinedTextField(
                        value = c.tldDnsZone, onValueChange = { c = c.copy(tldDnsZone = it) },
                        label = { Text("TLD后缀") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = c.disableP2p, onCheckedChange = { c = c.copy(disableP2p = it) })
                    Spacer(Modifier.width(8.dp)); Text("禁用P2P（纯中转）")
                }
                OutlinedTextField(
                    value = c.relayNetworkWhitelist, onValueChange = { c = c.copy(relayNetworkWhitelist = it) },
                    label = { Text("中转白名单（* = 全转发）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = c.hostname, onValueChange = { c = c.copy(hostname = it) },
                    label = { Text("主机名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(c); onDismiss() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
