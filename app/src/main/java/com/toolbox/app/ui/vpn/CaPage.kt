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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
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
                .onFailure { Log.e(TAG, "Failed to read CA certificate", it) }
                .getOrNull()
        }
        loading = false
    }

    fun export() {
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching { CertManager.exportCa(context) }
                    .onFailure { Log.e(TAG, "Failed to export CA certificate", it) }
                    .getOrNull()
            }
            if (uri == null) {
                Log.e(TAG, "Failed to export CA certificate: null Uri returned")
                snack(scope, snackbar, context.getString(R.string.ca_export_failed))
            } else {
                runCatching { shareCa(context, uri) }
                    .onSuccess { Log.i(TAG, "Exported and shared CA certificate: $uri") }
                    .onFailure { e ->
                        Log.e(TAG, "Failed to share CA certificate", e)
                        snack(scope, snackbar, context.getString(R.string.ca_share_failed, e.message))
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
                    Text(stringResource(R.string.ca_not_generated), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.ca_not_generated_desc),
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
                            Text(stringResource(R.string.ca_info_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            InfoRow(stringResource(R.string.ca_issuer), info.issuer)
                            InfoRow(stringResource(R.string.ca_not_after), dateText(info.notAfter))
                            InfoRow(stringResource(R.string.ca_fingerprint), info.fingerprint)
                        }
                    }
                    Button(onClick = { export() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ca_export_btn))
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(stringResource(R.string.ca_install_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.ca_install_step1), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ca_install_step2), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ca_install_step3), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ca_install_step4), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            stringResource(R.string.ca_reset_hint),
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
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.ca_share_chooser)))
}