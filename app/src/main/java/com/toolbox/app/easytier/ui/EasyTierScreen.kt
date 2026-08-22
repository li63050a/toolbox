package com.toolbox.app.easytier.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easytier.jni.EasyTierJNI
import com.easytier.jni.EasyTierVpnService
import com.toolbox.app.easytier.ConfigRepository
import com.toolbox.app.easytier.EasyTierConfig
import com.toolbox.app.easytier.DetailedNetworkInfo
import com.toolbox.app.easytier.MyNodeInfo
import com.toolbox.app.easytier.NetworkSnapshot
import com.toolbox.app.easytier.PeerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EasyTierScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ConfigRepository(context) }
    var allConfigs by remember { mutableStateOf(repo.configs) }
    var activeConfigId by remember { mutableStateOf<String?>(repo.activeId) }
    var showConfigEditor by remember { mutableStateOf(false) }
    var editingConfig by remember { mutableStateOf<EasyTierConfig?>(null) }
    var showAddConfig by remember { mutableStateOf(false) }
    var vpnAuthorized by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vpnAuthorized = true
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(it)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: run {
                        snackbarHostState.showSnackbar("导入失败: 文件为空或无法读取")
                        return@launch
                    }
                    val config = repo.importConfig(content, "导入配置")
                    showAddConfig = false
                    repo.addConfig(config)
                    allConfigs = repo.configs
                    repo.saveActiveConfig(config.id)
                    activeConfigId = config.id
                    snackbarHostState.showSnackbar("已导入: ${config.name}")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入失败: ${e.message}")
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val cfg = allConfigs.firstOrNull { it.id == activeConfigId } ?: allConfigs.firstOrNull() ?: EasyTierConfig.defaultConfig()
                    val toml = cfg.toToml()
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(toml.toByteArray(Charsets.UTF_8))
                    }
                    snackbarHostState.showSnackbar("已导出: ${cfg.name}")
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导出失败: ${e.message}")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        vpnAuthorized = android.net.VpnService.prepare(context) == null
        repo.reload()
        allConfigs = repo.configs
        activeConfigId = repo.activeId
    }

    val activeConfig = allConfigs.firstOrNull { it.id == activeConfigId } ?: allConfigs.firstOrNull() ?: EasyTierConfig.defaultConfig()

    var status by remember { mutableStateOf<NetworkSnapshot?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var snackbarMsg by remember { mutableStateOf<String?>(null) }
    var eventLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    var activeTab by remember { mutableStateOf(0) }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (isRunning) {
                withContext(Dispatchers.IO) {
                    status = mgrGetStatus(activeConfig)
                }
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    fun showSnack(msg: String) { scope.launch { snackbarMsg = msg } }

    fun startConfig(cfg: EasyTierConfig) {
        if (!vpnAuthorized) {
            val intent = android.net.VpnService.prepare(context)
            if (intent != null) { vpnLauncher.launch(intent); return }
            vpnAuthorized = true
        }
        scope.launch {
            loading = true
            val toml = cfg.toToml()
            val result = withContext(Dispatchers.IO) { runCatching { EasyTierJNI.runNetworkInstance(toml) } }
            if (result.isSuccess && result.getOrNull() == 0) {
                isRunning = true
                loading = false
                val ipv4 = if (cfg.dhcp) "10.64.0.1/24" else "${cfg.virtualIpv4.split('/')[0]}/${cfg.networkLength}"
                val vpnIntent = Intent(context, EasyTierVpnService::class.java).apply {
                    putExtra(EasyTierVpnService.EXTRA_INSTANCE_NAME, cfg.instanceName)
                    putExtra(EasyTierVpnService.EXTRA_IPV4_ADDRESS, ipv4)
                }
                context.startForegroundService(vpnIntent)
                showSnack("启动成功")
            } else {
                showSnack("启动失败: ${result.exceptionOrNull()?.message}")
                loading = false
            }
        }
    }

    fun stopConfig() {
        scope.launch {
            withContext(Dispatchers.IO) { EasyTierJNI.stopAllInstances() }
            context.stopService(Intent(context, EasyTierVpnService::class.java))
            isRunning = false
            status = mgrGetStatus(activeConfig)
            showSnack("已停止")
        }
    }

    fun switchConfig(cfg: EasyTierConfig) {
        if (cfg.id == activeConfigId) return
        activeConfigId = cfg.id
        scope.launch { repo.saveActiveConfig(cfg.id) }
        if (isRunning) {
            scope.launch {
                withContext(Dispatchers.IO) { EasyTierJNI.stopAllInstances() }
                context.stopService(Intent(context, EasyTierVpnService::class.java))
                isRunning = false
                status = mgrGetStatus(cfg)
                startConfig(cfg)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("EasyTier 组网", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, null) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // 配置选择栏
            ConfigurationBar(
                configs = allConfigs, activeId = activeConfigId,
                onSwitch = { switchConfig(it) },
                onAdd = { showAddConfig = true },
                onEdit = { editingConfig = it; showConfigEditor = true },
                onDelete = { id ->
                    scope.launch {
                        repo.deleteConfig(id)
                        allConfigs = repo.configs
                        activeConfigId = repo.activeId
                        if (isRunning) { stopConfig() }
                    }
                }
            )

            TabRow(selectedTabIndex = activeTab) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("控制") })
                Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("状态") })
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }, text = { Text("日志") })
            }

            when (activeTab) {
                0 -> ControlTabContent(
                    config = activeConfig, isRunning = isRunning, loading = loading,
                    onStart = { startConfig(activeConfig) }, onStop = ::stopConfig,
                    onEdit = { editingConfig = activeConfig; showConfigEditor = true }
                )
                1 -> StatusTabContent(status = status, isRunning = isRunning, onRefresh = {
                    scope.launch {
                        status = mgrGetStatus(activeConfig)
                    }
                })
                2 -> LogTabContent(events = eventLog, onClear = { eventLog = emptyList() })
            }
        }
    }

    if (showConfigEditor) {
        ConfigEditorDialog(
            config = editingConfig ?: EasyTierConfig.defaultConfig(),
            onDismiss = { showConfigEditor = false },
            onSave = { c ->
                scope.launch {
                    repo.updateConfig(c)
                    allConfigs = repo.configs
                    if (activeConfigId == c.id) activeConfigId = c.id
                    showConfigEditor = false
                }
            }
        )
    }

    if (showAddConfig) {
        var newName by remember { mutableStateOf("配置${allConfigs.size + 1}") }
        AlertDialog(
            onDismissRequest = { showAddConfig = false },
            title = { Text("新建配置") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("配置名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isBlank()) {
                        scope.launch { snackbarHostState.showSnackbar("名称不能为空") }
                        return@Button
                    }
                    val new = EasyTierConfig(
                        name = newName.trim(),
                        instanceName = "toolbox-${UUID.randomUUID().toString().take(8)}"
                    )
                    scope.launch {
                        repo.addConfig(new)
                        allConfigs = repo.configs
                        repo.saveActiveConfig(new.id)
                        activeConfigId = new.id
                    }
                    showAddConfig = false
                    editingConfig = new
                    showConfigEditor = true
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showAddConfig = false }) { Text("取消") } }
        )
    }

    if (showSettings) {
        SettingsDialog(
            config = activeConfig,
            onDismiss = { showSettings = false },
            onImport = { importLauncher.launch(arrayOf("text/plain", "text/*", "application/octet-stream", "*/*")) },
            onExport = { cfg -> exportLauncher.launch("easytier_${cfg.name}.toml") }
        )
    }
}

// ─────────────── 配置选择栏 ───────────────
@Composable
private fun ConfigurationBar(
    configs: List<EasyTierConfig>, activeId: String?,
    onSwitch: (EasyTierConfig) -> Unit,
    onAdd: () -> Unit,
    onEdit: (EasyTierConfig) -> Unit,
    onDelete: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                val active = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull() ?: return@OutlinedButton
                Icon(Icons.Filled.NetworkCheck, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(active.name, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                configs.forEach { cfg ->
                    DropdownMenuItem(
                        text = { Text(cfg.name + if (cfg.id == activeId) " ✓" else "") },
                        onClick = { onSwitch(cfg); expanded = false }
                    )
                }
                DropdownMenuItem(
                    text = { Text("新建配置", color = MaterialTheme.colorScheme.primary) },
                    onClick = { onAdd(); expanded = false }
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        val active = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull() ?: return@Row
        IconButton(onClick = { onEdit(active) }) { Icon(Icons.Filled.Edit, null) }
        if (configs.size > 1) {
            IconButton(onClick = { onDelete(active.id) }) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}

// ─────────────── 控制页 ───────────────
@Composable
private fun ControlTabContent(
    config: EasyTierConfig, isRunning: Boolean, loading: Boolean,
    onStart: () -> Unit, onStop: () -> Unit,
    onEdit: () -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(12.dp), shape = CircleShape,
                        color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error) {}
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRunning) "运行中" else "已停止",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("实例名", config.instanceName)
                    InfoRow("网络名", config.networkName.ifEmpty { "(未设置)" })
                    InfoRow("对等节点", config.peers.lines().firstOrNull { it.isNotBlank() }?.substringAfterLast("/") ?: "public.easytier.top")
                    InfoRow("DHCP", if (config.dhcp) "自动" else "手动 ${config.virtualIpv4}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isRunning) {
                        Button(onClick = onStop, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("停止")
                        }
                    } else {
                        Button(onClick = onStart, enabled = !loading, modifier = Modifier.weight(1f)) {
                            if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            else { Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                            Text(if (loading) "启动中..." else "启动")
                        }
                    }
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Edit, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("编辑")
                    }
                }
            }
        }

        // TOML 预览
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("TOML 配置预览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val toml by remember(config) { derivedStateOf { config.toToml() } }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(toml, modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row { Text("$label: ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodySmall) }
}

// ─────────────── 状态页 ───────────────
@Composable
private fun StatusTabContent(status: NetworkSnapshot?, isRunning: Boolean, onRefresh: () -> Unit) {
    val detailed = status?.detailed
    val myNode = detailed?.myNode
    val peers = detailed?.peers ?: emptyList()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 状态卡片
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(12.dp), shape = CircleShape,
                        color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline) {}
                    Spacer(Modifier.width(8.dp))
                    Text(if (isRunning) "已连接" else "未连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (myNode != null) {
                    InfoRow("主机名", myNode.hostname)
                    InfoRow("虚拟 IP", myNode.virtualIp)
                    InfoRow("版本", myNode.version)
                } else {
                    Text("暂无节点信息", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (detailed?.error != null) {
                    Text("错误: ${detailed.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row {
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("刷新")
                    }
                }
            }
        }

        // 对端节点
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.People, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("对端节点 (${peers.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                if (peers.isEmpty()) {
                    Text("暂无对端节点，请确保对方也在线", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(peers) { peer -> PeerRow(peer) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerRow(peer: PeerInfo) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape,
            color = if (peer.isDirect) Color(0xFF4CAF50) else Color(0xFFFFA000)) {}
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.hostname, style = MaterialTheme.typography.bodyMedium)
            Text(peer.virtualIp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (peer.latency.isNotBlank()) Text(peer.latency, style = MaterialTheme.typography.labelSmall)
            if (peer.traffic.isNotBlank()) Text(peer.traffic, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─────────────── 日志页 ───────────────
@Composable
private fun LogTabContent(events: List<String>, onClear: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("运行日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (events.isNotEmpty()) OutlinedButton(onClick = onClear) { Text("清空") }
        }
        Spacer(Modifier.height(8.dp))
        if (events.isEmpty()) {
            Text("暂无日志", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxSize(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } else {
            val listState = rememberLazyListState()
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(events.reversed()) { event ->
                    Text(event, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp))
                }
            }
        }
    }
}

// ─────────────── 配置编辑器 ───────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigEditorDialog(config: EasyTierConfig, onDismiss: () -> Unit, onSave: (EasyTierConfig) -> Unit) {
    var c by remember { mutableStateOf(config) }
    var expandedSection by remember { mutableStateOf("basic") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑配置") }, text = {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(value = c.name, onValueChange = { c = c.copy(name = it) }, label = { Text("配置名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.instanceName, onValueChange = { c = c.copy(instanceName = it) }, label = { Text("实例名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.networkName, onValueChange = { c = c.copy(networkName = it) }, label = { Text("网络名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.networkSecret, onValueChange = { c = c.copy(networkSecret = it) }, label = { Text("网络密钥") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.peers, onValueChange = { c = c.copy(peers = it) }, label = { Text("对等节点（每行一个）") }, minLines = 2, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = c.dhcp, onCheckedChange = { c = c.copy(dhcp = it) })
                Spacer(Modifier.width(8.dp)); Text("DHCP 自动分配 IP")
            }
            if (!c.dhcp) {
                OutlinedTextField(value = c.virtualIpv4, onValueChange = { c = c.copy(virtualIpv4 = it) }, label = { Text("虚拟IPv4") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(value = c.listenerUrls, onValueChange = { c = c.copy(listenerUrls = it) }, label = { Text("监听地址（每行一个）") }, minLines = 2, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("高级选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = c.acceptDns, onCheckedChange = { c = c.copy(acceptDns = it) })
                Spacer(Modifier.width(8.dp)); Text("魔法DNS")
            }
            if (c.acceptDns) {
                OutlinedTextField(value = c.tldDnsZone, onValueChange = { c = c.copy(tldDnsZone = it) }, label = { Text("TLD后缀（如 et）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = c.disableP2p, onCheckedChange = { c = c.copy(disableP2p = it) })
                Spacer(Modifier.width(8.dp)); Text("禁用P2P（纯中转）")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = c.enableExitNode, onCheckedChange = { c = c.copy(enableExitNode = it) })
                Spacer(Modifier.width(8.dp)); Text("出口节点")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = c.secureMode, onCheckedChange = { c = c.copy(secureMode = it) })
                Spacer(Modifier.width(8.dp)); Text("安全模式")
            }
            if (c.secureMode) {
                OutlinedTextField(value = c.localPrivateKey, onValueChange = { c = c.copy(localPrivateKey = it) }, label = { Text("私钥") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = c.localPublicKey, onValueChange = { c = c.copy(localPublicKey = it) }, label = { Text("公钥") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(value = c.exitNodes, onValueChange = { c = c.copy(exitNodes = it) }, label = { Text("出口节点列表") }, minLines = 2, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.proxyNetworks, onValueChange = { c = c.copy(proxyNetworks = it) }, label = { Text("子网代理（每行一个CIDR）") }, minLines = 2, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.routes, onValueChange = { c = c.copy(routes = it) }, label = { Text("手动路由（每行一个CIDR）") }, minLines = 2, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.hostname, onValueChange = { c = c.copy(hostname = it) }, label = { Text("主机名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = c.relayNetworkWhitelist, onValueChange = { c = c.copy(relayNetworkWhitelist = it) }, label = { Text("中转白名单（* = 全转发）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        Button(onClick = { onSave(c) }) { Text("保存") }
    }, dismissButton = {
        TextButton(onClick = onDismiss) { Text("取消") }
    })
}

// ─────────────── 设置对话框 ───────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(config: EasyTierConfig, onDismiss: () -> Unit, onImport: () -> Unit, onExport: (EasyTierConfig) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("设置") }, text = {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("配置文件管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ImportContacts, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导入配置文件 (.toml)")
            }
            Text("当前配置: ${config.name}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { onExport(config) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.SaveAlt, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导出当前配置 (.toml)")
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

// ─────────────── 工具函数 ───────────────
private suspend fun mgrGetStatus(cfg: EasyTierConfig): NetworkSnapshot = withContext(Dispatchers.IO) {
    runCatching {
        val info = EasyTierJNI.collectNetworkInfos(20)
        if (info.isNullOrBlank()) return@runCatching NetworkSnapshot()
        val root = JSONObject(info)
        val mapObj = root.optJSONObject("map") ?: return@runCatching NetworkSnapshot()
        val inst = mapObj.optJSONObject(cfg.instanceName) ?: mapObj.optJSONObject("easytier") ?: return@runCatching NetworkSnapshot()

        val myNodeJson = inst.optJSONObject("my_node_info")
        val myNode = if (myNodeJson != null) {
            val addrJson = myNodeJson.optJSONObject("virtual_ipv4")?.optJSONObject("address")
            val addr = addrJson?.optInt("addr", 0) ?: 0
            val netLen = myNodeJson.optJSONObject("virtual_ipv4")?.optInt("network_length", 24) ?: 24
            val ip = if (addr != 0) "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}" else ""
            MyNodeInfo(myNodeJson.optString("hostname", "未知"), "$ip/$netLen", myNodeJson.optString("version", ""))
        } else null

        val peers = mutableListOf<PeerInfo>()
        val routesArr = inst.optJSONArray("routes")
        if (routesArr != null) {
            for (i in 0 until routesArr.length()) {
                val r = routesArr.getJSONObject(i)
                val pid = r.optLong("peer_id", -1)
                if (pid < 0) continue
                val ph = r.optString("hostname", "节点${pid.toString().takeLast(4)}")
                val rip = r.optJSONObject("ipv4_addr")?.optJSONObject("address")?.optInt("addr", 0) ?: 0
                val vip = if (rip != 0) "${(rip ushr 24) and 0xFF}.${(rip ushr 16) and 0xFF}.${(rip ushr 8) and 0xFF}.${rip and 0xFF}" else "未知"
                val stats = r.optJSONObject("stats")
                val lat = if (stats != null) "${stats.optLong("latency_us", 0) / 1000} ms" else ""
                val natRaw = r.opt("nat_type")
                val nat = when { natRaw is String -> natRaw; natRaw is Int -> listOf("未知","开放互联网","","完全锥形","","端口限制锥形","对称型")[natRaw.coerceIn(0,6)]; else -> "未知" }
                peers.add(PeerInfo(ph, vip, r.optLong("next_hop_peer_id", -1) == pid, lat, nat))
            }
        }

        val events = mutableListOf<String>()
        val eventsArr = inst.optJSONArray("events")
        if (eventsArr != null) {
            for (i in 0 until eventsArr.length()) {
                try { events.add(eventsArr.getString(i)) } catch (_: Exception) {}
            }
        }

        val errorMsg = inst.optString("error_msg")
        val error = if (!inst.optBoolean("running", true) && errorMsg.isNotEmpty()) errorMsg else null
        NetworkSnapshot(isRunning = inst.optBoolean("running", false), DetailedNetworkInfo(myNode, peers, events, error))
    }.getOrNull() ?: NetworkSnapshot(detailed = DetailedNetworkInfo(error = "解析失败"))
}
