package com.toolbox.app.ui.ssh

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.toolbox.app.R
import com.toolbox.app.RepositoryProvider
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.data.SshAuth
import com.toolbox.app.log.Log
import com.toolbox.app.ssh.SftpFileOps
import com.toolbox.app.ssh.SshEngine
import com.toolbox.app.ui.filebrowser.FileBrowserScreen
import com.toolbox.app.ui.term.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

private enum class SshPage { LIST, FORM, TERM, FILES }

@Composable
fun SshHomeScreen(onBack: () -> Unit) {
    var page by remember { mutableStateOf(SshPage.LIST) }
    var editing by remember { mutableStateOf<ConnectionConfig.Ssh?>(null) }
    var active by remember { mutableStateOf<ConnectionConfig.Ssh?>(null) }

    when (page) {
        SshPage.LIST -> SshListPage(
            onBack = onBack,
            onNew = { editing = null; page = SshPage.FORM },
            onEdit = { editing = it; page = SshPage.FORM },
            onTerm = { active = it; page = SshPage.TERM },
            onFiles = { active = it; page = SshPage.FILES }
        )
        SshPage.FORM -> SshFormPage(
            initial = editing,
            onBack = { editing = null; page = SshPage.LIST }
        )
        SshPage.TERM -> active?.let { conn ->
            SshTerminalScreen(config = conn, onBack = { active = null; page = SshPage.LIST })
        }
        SshPage.FILES -> active?.let { conn ->
            SshFilesScreen(config = conn, onBack = { active = null; page = SshPage.LIST })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshListPage(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (ConnectionConfig.Ssh) -> Unit,
    onTerm: (ConnectionConfig.Ssh) -> Unit,
    onFiles: (ConnectionConfig.Ssh) -> Unit
) {
    val repo = RepositoryProvider.connections
    val all by repo.connections.collectAsState(initial = emptyList())
    val sshList = all.filterIsInstance<ConnectionConfig.Ssh>()
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
                    snackbar.showSnackbar(context.getString(R.string.ssh_import_fail))
                    return@launch
                }
                runCatching { withContext(Dispatchers.IO) { repo.importJson(text) } }
                    .onSuccess { snackbar.showSnackbar(context.getString(R.string.ssh_imported, it)) }
                    .onFailure { snackbar.showSnackbar(context.getString(R.string.ssh_import_error, it.message)) }
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
                snackbar.showSnackbar(if (ok) context.getString(R.string.ssh_exported, all.size) else context.getString(R.string.ssh_export_fail))
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ssh_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ssh_back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showIoMenu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.ssh_import_export)) }
                        DropdownMenu(expanded = showIoMenu, onDismissRequest = { showIoMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ssh_import_json)) }, onClick = {
                                showIoMenu = false
                                importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ssh_export_json)) }, onClick = {
                                showIoMenu = false
                                exportLauncher.launch("connections.json")
                            })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, stringResource(R.string.ssh_new)) }
        }
    ) { padding ->
        if (sshList.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(stringResource(R.string.ssh_empty))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                itemsIndexed(sshList, key = { i, c -> c.id.ifBlank { "conn_$i" } }) { _, conn ->
                    var menu by remember { mutableStateOf(false) }
                    Card(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable { onTerm(conn) }
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Terminal, null, tint = MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(conn.name.ifEmpty { conn.host }, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${conn.user}@${conn.host}:${conn.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.ssh_more)) }
                        }
                    }
                    if (menu) {
                        DropdownMenu(expanded = true, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ssh_open_term)) }, onClick = { menu = false; onTerm(conn) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ssh_file_browser)) }, onClick = { menu = false; onFiles(conn) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ssh_edit)) }, onClick = { menu = false; onEdit(conn) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ssh_test_conn)) }, onClick = {
                                menu = false
                                scope.launch {
                                    val msg = withContext(Dispatchers.IO) { SshEngine(conn).test() }
                                        .getOrElse { context.getString(R.string.ssh_test_fail, it.message) }
                                    snackbar.showSnackbar(msg)
                                }
                            })
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ssh_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    scope.launch { repo.delete(conn.id) }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SshFormPage(initial: ConnectionConfig.Ssh?, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var auth by remember { mutableStateOf(initial?.auth ?: SshAuth.PASSWORD) }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var privateKey by remember { mutableStateOf(initial?.privateKey ?: "") }
    var passphrase by remember { mutableStateOf(initial?.passphrase ?: "") }

    fun save() {
        val cfg = ConnectionConfig.Ssh(
            id = initial?.id ?: "",
            name = name.ifEmpty { host },
            host = host.trim(),
            port = port.toIntOrNull() ?: 22,
            user = user.trim(),
            auth = auth,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase
        )
        scope.launch {
            if (initial == null) RepositoryProvider.connections.add(cfg)
            else RepositoryProvider.connections.update(cfg)
            snackbar.showSnackbar(context.getString(R.string.ssh_saved))
            onBack()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (initial == null) stringResource(R.string.ssh_new_conn) else stringResource(R.string.ssh_edit_conn))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ssh_back)) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.ssh_name_opt)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.ssh_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text(stringResource(R.string.ssh_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.ssh_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text(stringResource(R.string.ssh_auth_method), style = MaterialTheme.typography.titleSmall)
            Row {
                SshAuth.entries.forEach { a ->
                    Row(
                        Modifier.clickable { auth = a }.padding(end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = auth == a, onClick = { auth = a })
                        Text(if (a == SshAuth.PASSWORD) stringResource(R.string.ssh_password) else stringResource(R.string.ssh_private_key))
                    }
                }
            }
            if (auth == SshAuth.PASSWORD) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(stringResource(R.string.ssh_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = privateKey, onValueChange = { privateKey = it },
                    label = { Text(stringResource(R.string.ssh_key_content)) },
                    minLines = 6, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.ssh_passphrase)) }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    val cfg = ConnectionConfig.Ssh(
                        id = initial?.id ?: "",
                        name = name.ifEmpty { host }, host = host.trim(),
                        port = port.toIntOrNull() ?: 22, user = user.trim(),
                        auth = auth, password = password, privateKey = privateKey, passphrase = passphrase
                    )
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) { SshEngine(cfg).test() }
                            .getOrElse { context.getString(R.string.ssh_test_fail, it.message) }
                        snackbar.showSnackbar(msg)
                    }
                }) { Text(stringResource(R.string.ssh_test_conn)) }
                Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.ssh_save)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshTerminalScreen(config: ConnectionConfig.Ssh, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connectingText = stringResource(R.string.ssh_connecting)
    var status by remember { mutableStateOf(connectingText) }
    val engine = remember { SshEngine(config) }
    val sessionHolder = remember { mutableStateOf<com.jcraft.jsch.Session?>(null) }
    val channelHolder = remember { mutableStateOf<com.jcraft.jsch.ChannelShell?>(null) }
    val reading = remember { AtomicBoolean(false) }
    val terminalHolder = remember { mutableStateOf<TerminalView?>(null) }

    fun disconnect() {
        reading.set(false)
        runCatching { channelHolder.value?.disconnect() }
        runCatching { sessionHolder.value?.disconnect() }
        channelHolder.value = null
        sessionHolder.value = null
    }

    DisposableEffect(Unit) {
        onDispose { disconnect() }
    }

    fun connect() {
        scope.launch {
            status = context.getString(R.string.ssh_connecting)
            disconnect()
            val session = withContext(Dispatchers.IO) { engine.connect() }
                .getOrElse {
                    status = context.getString(R.string.ssh_conn_fail, it.message)
                    Log.e("SSH", "连接失败", it)
                    return@launch
                }
            sessionHolder.value = session
            val channel = withContext(Dispatchers.IO) {
                SshEngine.openShell(session).getOrElse { null }
            } ?: run {
                status = context.getString(R.string.ssh_open_term_fail)
                disconnect()
                return@launch
            }
            channelHolder.value = channel
            status = context.getString(R.string.ssh_connected, config.user, config.host)
            reading.set(true)
            withContext(Dispatchers.IO) {
                val input = channel.inputStream
                val buf = ByteArray(64 * 1024)
                while (reading.get()) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    terminalHolder.value?.write(buf.copyOf(n))
                }
                if (reading.get()) {
                    status = context.getString(R.string.ssh_disconnected)
                }
            }
        }
    }

    var inputText by remember { mutableStateOf("") }
    var fontSize by remember { mutableStateOf(14f) }

    LaunchedEffect(Unit) { connect() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(config.name.ifEmpty { config.host }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { disconnect(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ssh_disconnect)) }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx).also { tv ->
                        tv.setFontSize(fontSize)
                        tv.stdinConsumer = { data ->
                            runCatching { channelHolder.value?.outputStream?.write(data) }
                        }
                        terminalHolder.value = tv
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                funKey(stringResource(R.string.ssh_key_esc), "\u001B")
                funKey(stringResource(R.string.ssh_key_tab), "\t")
                funKey(stringResource(R.string.ssh_key_up), "\u001B[A", channelHolder)
                funKey(stringResource(R.string.ssh_key_down), "\u001B[B", channelHolder)
                funKey(stringResource(R.string.ssh_key_left), "\u001B[D", channelHolder)
                funKey(stringResource(R.string.ssh_key_right), "\u001B[C", channelHolder)
                funKey(stringResource(R.string.ssh_key_tilde), "~", channelHolder)
                funKey(stringResource(R.string.ssh_key_ctrl), "\u0000", channelHolder)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).onKeyEvent { event ->
                        val ne = event.nativeKeyEvent
                        if (ne.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ne.action == android.view.KeyEvent.ACTION_DOWN) {
                            val cmd = inputText
                            inputText = ""
                            runCatching { channelHolder.value?.outputStream?.write((cmd + "\r").toByteArray()) }
                            true
                        } else false
                    },
                    placeholder = { Text(stringResource(R.string.ssh_input_hint)) }
                )
                IconButton(onClick = {
                    runCatching { channelHolder.value?.outputStream?.write(inputText.toByteArray()) }
                    inputText = ""
                }) { Text(stringResource(R.string.ssh_send), style = MaterialTheme.typography.labelMedium) }
                IconButton(onClick = {
                    fontSize = (fontSize - 1f).coerceAtLeast(8f)
                    terminalHolder.value?.setFontSize(fontSize)
                }) { Text(stringResource(R.string.ssh_font_smaller), style = MaterialTheme.typography.labelMedium) }
                IconButton(onClick = {
                    fontSize = (fontSize + 1f).coerceAtMost(26f)
                    terminalHolder.value?.setFontSize(fontSize)
                }) { Text(stringResource(R.string.ssh_font_bigger), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowScope.funKey(
    label: String,
    chars: String,
    channel: MutableState<com.jcraft.jsch.ChannelShell?>? = null
) {
    Button(
        onClick = {
            channel?.value?.let { runCatching { it.outputStream.write(chars.toByteArray()) } }
        },
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.weight(1f)
    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshFilesScreen(config: ConnectionConfig.Ssh, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val engine = remember { SshEngine(config) }
    val sessionHolder = remember { mutableStateOf<com.jcraft.jsch.Session?>(null) }

    fun connect() {
        scope.launch {
            withContext(Dispatchers.IO) { engine.connect() }
                .onSuccess {
                    sessionHolder.value = it
                    ready = true
                }
                .onFailure {
                    error = it.message
                    Log.e("SSH", "文件浏览连接失败", it)
                }
        }
    }

    LaunchedEffect(Unit) { connect() }

    when {
        error != null -> Scaffold(
            topBar = {
                TopAppBar(title = { Text(stringResource(R.string.ssh_sftp_title)) }, navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ssh_back)) }
                })
            }
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.ssh_conn_fail, error ?: ""))
                Button(onClick = { error = null; connect() }) { Text(stringResource(R.string.ssh_retry)) }
            }
        }
        !ready -> Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.ssh_sftp_title)) }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
                Text(stringResource(R.string.ssh_connecting), Modifier.padding(top = 12.dp))
            }
        }
        else -> {
            val session = sessionHolder.value!!
            val ops = remember(session) { SftpFileOps(context, session, config.name.ifEmpty { config.host }) }
            FileBrowserScreen(ops = ops, onBack = {
                ops.close()
                onBack()
            })
        }
    }
}