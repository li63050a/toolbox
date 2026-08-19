package com.toolbox.app.ui.oss

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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toolbox.app.RepositoryProvider
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.data.typeName
import com.toolbox.app.log.Log
import com.toolbox.app.oss.CosBackend
import com.toolbox.app.oss.OssBackend
import com.toolbox.app.oss.S3Backend
import com.toolbox.app.ui.filebrowser.FileBrowserScreen
import com.toolbox.app.ui.filebrowser.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class OssType { S3, OSS, COS }
private enum class OssPage { LIST, FORM, FILES }

@Composable
fun OssHomeScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(OssPage.LIST) }
    var editing by remember { mutableStateOf<ConnectionConfig?>(null) }
    var active by remember { mutableStateOf<ConnectionConfig?>(null) }

    when (page) {
        OssPage.LIST -> OssListPage(
            onBack = onBack,
            onNew = { type ->
                editing = when (type) {
                    OssType.S3 -> ConnectionConfig.S3()
                    OssType.OSS -> ConnectionConfig.Oss()
                    OssType.COS -> ConnectionConfig.Cos()
                }
                page = OssPage.FORM
            },
            onEdit = { editing = it; page = OssPage.FORM },
            onOpen = { active = it; page = OssPage.FILES }
        )
        OssPage.FORM -> OssFormPage(initial = editing, onBack = { editing = null; page = OssPage.LIST })
        OssPage.FILES -> active?.let { conn ->
            OssFilesScreen(config = conn, onBack = { active = null; page = OssPage.LIST })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OssListPage(
    onBack: () -> Unit,
    onNew: (OssType) -> Unit,
    onEdit: (ConnectionConfig) -> Unit,
    onOpen: (ConnectionConfig) -> Unit
) {
    val repo = RepositoryProvider.connections
    val all by repo.connections.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf<OssType?>(null) }
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
                snackbar.showSnackbar(if (ok) "已导出 ${all.size} 条连接" else "导出失败")
            }
        }
    }

    val filtered = all.filter { c ->
        when (filter) {
            OssType.S3 -> c is ConnectionConfig.S3
            OssType.OSS -> c is ConnectionConfig.Oss
            OssType.COS -> c is ConnectionConfig.Cos
            null -> c is ConnectionConfig.S3 || c is ConnectionConfig.Oss || c is ConnectionConfig.Cos
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("对象存储") },
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
            FloatingActionButton(onClick = { onNew(filter ?: OssType.S3) }) { Icon(Icons.Filled.Add, "新建") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("全部") })
                FilterChip(selected = filter == OssType.S3, onClick = { filter = OssType.S3 }, label = { Text("S3 兼容") })
                FilterChip(selected = filter == OssType.OSS, onClick = { filter = OssType.OSS }, label = { Text("阿里云 OSS") })
                FilterChip(selected = filter == OssType.COS, onClick = { filter = OssType.COS }, label = { Text("腾讯云 COS") })
            }
            if (filtered.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("暂无连接，点击右下角新建")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { conn ->
                        var menu by remember { mutableStateOf(false) }
                        Card(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                                .clickable { onOpen(conn) }
                        ) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(conn.name.ifEmpty { summary(conn) }, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${conn.typeName} · ${summary(conn)}",
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
                                DropdownMenuItem(text = { Text("删除") }, onClick = {
                                    menu = false
                                    scope.launch { repo.delete(conn.id) }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun summary(c: ConnectionConfig): String = when (c) {
    is ConnectionConfig.S3 -> c.endpoint
    is ConnectionConfig.Oss -> c.endpoint
    is ConnectionConfig.Cos -> c.region
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OssFormPage(initial: ConnectionConfig?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var name by remember { mutableStateOf(initial?.name ?: "") }

    var s3Endpoint by remember { mutableStateOf((initial as? ConnectionConfig.S3)?.endpoint ?: "") }
    var s3Region by remember { mutableStateOf((initial as? ConnectionConfig.S3)?.region ?: "") }
    var ak by remember { mutableStateOf((initial as? ConnectionConfig.S3)?.accessKeyId ?: (initial as? ConnectionConfig.Oss)?.accessKeyId ?: (initial as? ConnectionConfig.Cos)?.secretId ?: "") }
    var sk by remember { mutableStateOf((initial as? ConnectionConfig.S3)?.secretKey ?: (initial as? ConnectionConfig.Oss)?.accessKeySecret ?: (initial as? ConnectionConfig.Cos)?.secretKey ?: "") }
    var bucket by remember { mutableStateOf((initial as? ConnectionConfig.S3)?.bucket ?: (initial as? ConnectionConfig.Oss)?.bucket ?: (initial as? ConnectionConfig.Cos)?.bucket ?: "") }
    var pathStyle by remember { mutableStateOf((initial as? ConnectionConfig.S3)?.pathStyle ?: true) }
    var https by remember { mutableStateOf((initial as? ConnectionConfig.S3)?.https ?: true) }
    var ossEndpoint by remember { mutableStateOf((initial as? ConnectionConfig.Oss)?.endpoint ?: "") }
    var cosRegion by remember { mutableStateOf((initial as? ConnectionConfig.Cos)?.region ?: "") }

    fun save() {
        val cfg: ConnectionConfig = when (initial) {
            is ConnectionConfig.S3 -> initial.copy(
                name = name, endpoint = s3Endpoint, region = s3Region,
                accessKeyId = ak, secretKey = sk, bucket = bucket, pathStyle = pathStyle, https = https
            )
            is ConnectionConfig.Oss -> initial.copy(
                name = name, endpoint = ossEndpoint, accessKeyId = ak, accessKeySecret = sk, bucket = bucket
            )
            is ConnectionConfig.Cos -> initial.copy(
                name = name, region = cosRegion, secretId = ak, secretKey = sk, bucket = bucket
            )
            else -> ConnectionConfig.S3(name = name, endpoint = s3Endpoint, region = s3Region, accessKeyId = ak, secretKey = sk, bucket = bucket, pathStyle = pathStyle, https = https)
        }
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
                title = { Text(if (initial == null) "新建连接" else "编辑连接") },
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
            Text(
                when (initial) {
                    is ConnectionConfig.Oss -> "阿里云 OSS 配置"
                    is ConnectionConfig.Cos -> "腾讯云 COS 配置"
                    else -> "S3 兼容配置"
                },
                style = MaterialTheme.typography.titleMedium
            )
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            when (initial) {
                is ConnectionConfig.Oss -> {
                    OutlinedTextField(value = ossEndpoint, onValueChange = { ossEndpoint = it }, label = { Text("Endpoint（如 oss-cn-hangzhou.aliyuncs.com）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                is ConnectionConfig.Cos -> {
                    OutlinedTextField(value = cosRegion, onValueChange = { cosRegion = it }, label = { Text("地域（如 ap-guangzhou）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    OutlinedTextField(value = s3Endpoint, onValueChange = { s3Endpoint = it }, label = { Text("Endpoint（如 s3.example.com:9000）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = s3Region, onValueChange = { s3Region = it }, label = { Text("Region（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.clickable { pathStyle = !pathStyle }, verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = pathStyle, onCheckedChange = { pathStyle = it })
                        Text("Path-Style 寻址", Modifier.padding(start = 8.dp))
                    }
                    Row(Modifier.clickable { https = !https }, verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = https, onCheckedChange = { https = it })
                        Text("使用 HTTPS", Modifier.padding(start = 8.dp))
                    }
                }
            }
            OutlinedTextField(value = ak, onValueChange = { ak = it }, label = { Text(if (initial is ConnectionConfig.Cos) "SecretId" else "AccessKeyId") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = sk, onValueChange = { sk = it }, label = { Text(if (initial is ConnectionConfig.Cos) "SecretKey" else "AccessKeySecret") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = bucket, onValueChange = { bucket = it }, label = { Text("默认桶（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OssFilesScreen(config: ConnectionConfig, onBack: () -> Unit) {
    val context = LocalContext.current
    val ops = remember(config.id) {
        when (config) {
            is ConnectionConfig.S3 -> S3Backend(context, config)
            is ConnectionConfig.Oss -> OssBackend(context, config)
            is ConnectionConfig.Cos -> CosBackend(context, config)
            else -> null
        }
    }
    if (ops == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("对象存储") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                    }
                )
            }
        ) { padding -> Text("不支持的连接类型", Modifier.padding(padding)) }
        return
    }
    val fileOps: FileOps = ops
    FileBrowserScreen(ops = fileOps, onBack = {
        ops.close()
        onBack()
    })
}