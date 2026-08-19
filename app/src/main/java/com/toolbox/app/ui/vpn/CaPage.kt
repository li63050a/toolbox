package com.toolbox.app.ui.vpn

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.mitm.CertManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.security.auth.x500.X500Principal

private const val TAG = "VpnUI"

private data class CertInfo(val issuer: String, val notAfter: Long, val fingerprint: String)

@Composable
fun CaPage(context: Context, scope: CoroutineScope, snackbar: SnackbarHostState) {
    var cert by remember { mutableStateOf<CertInfo?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        loading = true
        cert = withContext(Dispatchers.IO) {
            runCatching { CertManager.caCertificate(context)?.let { certInfoOf(it) } }
                .onFailure { Log.e(TAG, "读取 CA 证书失败", it) }
                .getOrNull()
        }
        loading = false
    }

    fun export() {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { CertManager.exportCa(context) }
                    .onFailure { Log.e(TAG, "导出 CA 证书失败", it) }
                    .getOrNull()
            }
            if (uri == null) {
                Log.e(TAG, "导出 CA 证书失败：返回空 Uri")
                snack(scope, snackbar, "导出失败：未生成证书或写入失败")
            } else {
                runCatching { shareCa(context, uri) }
                    .onSuccess { Log.i(TAG, "已导出并分享 CA 证书: $uri") }
                    .onFailure { e ->
                        Log.e(TAG, "分享 CA 证书失败", e)
                        snack(scope, snackbar, "分享失败：${e.message}")
                    }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            cert == null -> Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("未生成 CA 证书", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "完整 MITM 模式需要自签 CA 证书。证书生成后，本页可查看信息、导出文件并引导安装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                cert?.let { info ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("CA 证书信息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            InfoRow("颁发者", info.issuer)
                            InfoRow("有效期至", dateText(info.notAfter))
                            InfoRow("SHA-256 指纹", info.fingerprint)
                        }
                    }
                    Button(onClick = { export() }, modifier = Modifier.fillMaxWidth()) {
                        Text("导出证书文件")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("安装指引", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("1. 打开系统「设置」→「安全」→「加密与凭据」→「安装证书」", style = MaterialTheme.typography.bodySmall)
                Text("2. 选择「CA 证书」", style = MaterialTheme.typography.bodySmall)
                Text("3. 选择刚导出的文件，证书名称可随意填写", style = MaterialTheme.typography.bodySmall)
                Text("4. 回到本页，「完整 MITM 模式」即可生效", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "提示：证书的卸载/重置请到系统设置的「加密与凭据」中操作，本应用不提供。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp)
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun certInfoOf(cert: X509Certificate): CertInfo = CertInfo(
    issuerCn(cert),
    cert.notAfter.time,
    sha256Hex(cert.encoded).chunked(8).joinToString(" ")
)

private fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02X".format(it) }

private fun issuerCn(cert: X509Certificate): String {
    val name = cert.subjectX500Principal.getName(X500Principal.RFC2253)
    return name.split(",")
        .map { it.trim() }
        .firstOrNull { it.substringBefore('=').trim().equals("CN", ignoreCase = true) }
        ?.substringAfter('=')?.trim() ?: name
}

private fun dateText(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun shareCa(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/x-x509-ca-cert"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享 CA 证书"))
}