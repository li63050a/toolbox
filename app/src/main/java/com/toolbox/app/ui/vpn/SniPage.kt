package com.toolbox.app.ui.vpn

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.toolbox.app.vpn.FragMode
import com.toolbox.app.vpn.VpnConfigStore
import kotlinx.coroutines.CoroutineScope

@Composable
fun SniPage(context: Context, scope: CoroutineScope, snackbar: SnackbarHostState) {
    val config by VpnConfigStore.config.collectAsState()
    val hasCa = rememberHasCa(context)
    var fakeSni by remember { mutableStateOf(config.spoof.fakeSni) }
    LaunchedEffect(config.spoof.fakeSni) {
        if (fakeSni != config.spoof.fakeSni) fakeSni = config.spoof.fakeSni
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(
                        checked = config.frag.enabled,
                        onCheckedChange = { v ->
                            mutateConfig(scope, snackbar, if (v) "开启 SNI 分片" else "关闭 SNI 分片") {
                                it.copy(frag = it.frag.copy(enabled = v))
                            }
                        }
                    )
                    Column {
                        Text("SNI 分片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "将 TLS 握手包拆片发送，规避按特征干扰",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (config.frag.enabled) {
                    Text("分片模式", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.frag.mode == FragMode.SPLIT,
                            onClick = {
                                mutateConfig(scope, snackbar, "SNI 分片模式设为仅分片") {
                                    it.copy(frag = it.frag.copy(mode = FragMode.SPLIT))
                                }
                            },
                            label = { Text("仅分片") }
                        )
                        FilterChip(
                            selected = config.frag.mode == FragMode.DELAY,
                            onClick = {
                                mutateConfig(scope, snackbar, "SNI 分片模式设为分片+延时") {
                                    it.copy(frag = it.frag.copy(mode = FragMode.DELAY))
                                }
                            },
                            label = { Text("分片+延时") }
                        )
                    }
                    NumberField(
                        label = "首片字节数",
                        value = config.frag.firstFragment,
                        range = 1..32
                    ) { v ->
                        mutateConfig(scope, snackbar, "SNI 首片字节数设为 $v") {
                            it.copy(frag = it.frag.copy(firstFragment = v))
                        }
                    }
                    NumberField(
                        label = "分片大小",
                        value = config.frag.chunk,
                        range = 8..256
                    ) { v ->
                        mutateConfig(scope, snackbar, "SNI 分片大小设为 $v") {
                            it.copy(frag = it.frag.copy(chunk = v))
                        }
                    }
                    NumberField(
                        label = "片间延时(ms)",
                        value = config.frag.delayMs,
                        range = 0..100,
                        enabled = config.frag.mode == FragMode.DELAY
                    ) { v ->
                        mutateConfig(scope, snackbar, "SNI 片间延时设为 ${v}ms") {
                            it.copy(frag = it.frag.copy(delayMs = v))
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(
                        checked = config.spoof.enabled,
                        onCheckedChange = { v ->
                            mutateConfig(scope, snackbar, if (v) "开启 SNI 伪装" else "关闭 SNI 伪装") {
                                it.copy(spoof = it.spoof.copy(enabled = v))
                            }
                        }
                    )
                    Column {
                        Text("SNI 伪装", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "改写 TLS 握手 SNI，规避按域名阻断",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (config.spoof.enabled) {
                    OutlinedTextField(
                        value = fakeSni,
                        onValueChange = { new ->
                            fakeSni = new
                            if (new.isNotBlank() && new != config.spoof.fakeSni) {
                                mutateConfig(scope, snackbar, "伪装域名改为 $new") { c ->
                                    c.copy(spoof = c.spoof.copy(fakeSni = new))
                                }
                            }
                        },
                        label = { Text("伪装域名") },
                        placeholder = { Text("默认 www.apple.com") },
                        singleLine = true,
                        supportingText = {
                            Text("仅对不校验证书的服务器生效，严格站点自动升级 MITM")
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Switch(
                    checked = config.spoof.mitmFallback,
                    onCheckedChange = { v ->
                        mutateConfig(scope, snackbar, if (v) "开启自动 MITM 升级" else "关闭自动 MITM 升级") {
                            it.copy(spoof = it.spoof.copy(mitmFallback = v))
                        }
                    }
                )
                Column {
                    Text("自动 MITM 升级", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "严格校验证书的站点自动升级为 MITM 本地终结，避免被阻断",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(
                        checked = config.mitmEnabled,
                        onCheckedChange = { v ->
                            mutateConfig(scope, snackbar, if (v) "开启完整 MITM 模式" else "关闭完整 MITM 模式") {
                                it.copy(mitmEnabled = v)
                            }
                        }
                    )
                    Column {
                        Text("完整 MITM 模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "所有 443 流量经本地终结，出站伪装 SNI；需要安装 CA 证书（见证书管理页）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (config.mitmEnabled && !hasCa) {
                    Text(
                        "提示：未检测到已安装的 CA 证书，请先到「CA 证书」页导出并安装，MITM 才能正常解密",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean = true,
    onChange: (Int) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    val parsed = text.toIntOrNull()
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() }
            text.toIntOrNull()?.takeIf { it in range }?.let(onChange)
        },
        label = { Text("$label（${range.first}-${range.last}）") },
        singleLine = true,
        enabled = enabled,
        isError = text.isNotEmpty() && parsed != null && parsed !in range,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}