package com.toolbox.app.ui.ftp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toolbox.app.RepositoryProvider
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.data.FtpSecurity
import com.toolbox.app.ftp.FtpClient
import com.toolbox.app.ftp.FtpFileOps
import com.toolbox.app.log.Log
import com.toolbox.app.ui.filebrowser.FileBrowserScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FtpPage { LIST, FORM, FILES }

@Composable
fun FtpHomeScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(FtpPage.LIST) }
    var editing by remember { mutableStateOf<ConnectionConfig.Ftp?>(null) }
    var active by remember { mutableStateOf<ConnectionConfig.Ftp?>(null) }

    when (page) {
        FtpPage.LIST -> FtpListPage(
            onBack = onBack,
            onNew = { editing = null; page = FtpPage.FORM },
            onEdit = { editing = it; page = FtpPage.FORM },
            onOpen = { active = it; page = FtpPage.FILES }
        )
        FtpPage.FORM -> FtpFormPage(initial = editing, onBack = { editing = null; page = FtpPage.LIST })
        FtpPage.FILES -> active?.let { conn ->
            FtpFilesScreen(config = conn, onBack = { active = null; page = FtpPage.LIST })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FtpListPage(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (ConnectionConfig.Ftp) -> Unit,
    onOpen: (ConnectionConfig.Ftp) -> Unit
) {
    val repo = RepositoryProvider.connections
    val all by repo.connections.collectAsState(initial = emptyList())
    val ftpList = all.filterIsInstance<ConnectionConfig.Ftp>()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var showIoMenu by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                if (text == null) {
                    snackbar.showSnackbar("读取文件失败")
                    return@launch
                }
                runCatching { withContext(Dispatchers.IO) { repo.importJson(text) } }
                    .onSuccess { snackbar.showSnackbar("已导入 $it 条连接") }
                    .onFailure { snackbar.showSnackbar("导入失败: ${it.message}") }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = withContext(Dispatchers.IO) { repo.exportJson() }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                    }.getOrDefault(false)
                }
                snackbar.showSnackbar(if (ok) "已导出 ${ftpList.size} 条连接" else "导出失败")
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("FTP/FTPS") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showIoMenu = true }) { Icon(Icons.Filled.MoreVert, "导入导出") }
                        DropdownMenu(expanded = showIoMenu, onDismissRequest = { showIoMenu = false }) {
                            DropdownMenuItem(text = { Text("导入连接（JSON）") }, onClick = {
                                showIoMenu = false
                                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            })
                            DropdownMenuItem(text = { Text("导出连接（JSON）") }, onClick = {
                                showIoMenu = false
                                exportLauncher.launch("connections.json")
                            })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "新建") }
        }
    ) { padding ->
        if (ftpList.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { Text("暂无 FTP 连接，点击右下角新建") }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(ftpList, key = { it.id }) { conn ->
                    var menu by remember { mutableStateOf(false) }
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable { onOpen(conn) }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(conn.name.ifEmpty { conn.host }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${conn.user}@${conn.host}:${conn.port} · ${securityText(conn.security)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                        }
                    }
                    if (menu) {
                        DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("打开") }, onClick = { menu = false; onOpen(conn) })
                            DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit(conn) })
                            DropdownMenuItem(text = { Text("测试连接") }, onClick = {
                                menu = false
                                scope.launch {
                                    val msg = withContext(Dispatchers.IO) { FtpClient(conn).test() }
                                        .getOrElse { "测试失败: ${it.message}" }
                                    snackbar.showSnackbar(msg)
                                }
                            })
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; scope.launch { repo.delete(conn.id) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun securityText(s: FtpSecurity): String = when (s) {
    FtpSecurity.FTP -> "FTP"
    FtpSecurity.FTPS_EXPLICIT -> "FTPS 显式"
    FtpSecurity.FTPS_IMPLICIT -> "FTPS 隐式"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FtpFormPage(initial: ConnectionConfig.Ftp?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "21") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var security by remember { mutableStateOf(initial?.security ?: FtpSecurity.FTP) }
    var passive by remember { mutableStateOf(initial?.passive ?: true) }

    fun save() {
        val cfg = ConnectionConfig.Ftp(
            id = initial?.id ?: "",
            name = name.ifEmpty { host },
            host = host.trim(),
            port = port.toIntOrNull() ?: 21,
            user = user.trim(),
            password = password,
            security = security,
            passive = passive
        )
        scope.launch {
            if (initial == null) RepositoryProvider.connections.add(cfg)
            else RepositoryProvider.connections.update(cfg)
            snackbar.showSnackbar("已保存")
            onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "新建 FTP 连接" else "编辑 FTP 连接") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("主机地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("安全模式", style = MaterialTheme.typography.titleSmall)
            Column {
                FtpSecurity.entries.forEach { s ->
                    Row(Modifier.clickable { security = s }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = security == s, onClick = { security = s })
                        Text(securityText(s))
                    }
                }
            }
            Row(Modifier.clickable { passive = !passive }, verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = passive, onCheckedChange = { passive = it })
                Text("被动模式（PAVS）", Modifier.padding(start = 8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val cfg = ConnectionConfig.Ftp(
                        id = initial?.id ?: "", name = name.ifEmpty { host }, host = host.trim(),
                        port = port.toIntOrNull() ?: 21, user = user.trim(), password = password,
                        security = security, passive = passive
                    )
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { FtpClient(cfg).test() }
                            .getOrElse { "测试失败: ${it.message}" }
                        snackbar.showSnackbar(msg)
                    }
                }) { Text("测试连接") }
                Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("保存") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FtpFilesScreen(config: ConnectionConfig.Ftp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clientHolder = remember { mutableStateOf<org.apache.commons.net.ftp.FTPClient?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(true) }

    fun connect() {
        scope.launch {
            connecting = true
            withContext(Dispatchers.IO) { FtpClient(config).connect() }
                .onSuccess {
                    clientHolder.value = it
                    connecting = false
                }
                .onFailure {
                    error = it.message
                    connecting = false
                    Log.e("FTP", "连接失败", it)
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            clientHolder.value?.let {
                runCatching { it.logout() }
                runCatching { it.disconnect() }
            }
        }
    }

    LaunchedEffect(Unit) { connect() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(config.name.ifEmpty { config.host }) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                }
            )
        }
    ) { padding ->
        when {
            connecting -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Text("连接中…", Modifier.padding(top = 12.dp))
            }
            error != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("连接失败: $error")
                Button(onClick = { error = null; connect() }) { Text("重试") }
            }
            else -> {
                val client = clientHolder.value!!
                val ops = remember(client) { FtpFileOps(context, client, config.name.ifEmpty { config.host }) }
                FileBrowserScreen(ops = ops, onBack = {
                    ops.close()
                    onBack()
                })
            }
        }
    }
}