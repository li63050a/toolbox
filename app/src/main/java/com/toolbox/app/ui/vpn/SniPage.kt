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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
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
                            mutateConfig(context, scope, snackbar, if (v) context.getString(R.string.sni_frag_enable) else context.getString(R.string.sni_frag_disable)) {
                                it.copy(frag = it.frag.copy(enabled = v))
                            }
                        }
                    )
                    Column {
                        Text(stringResource(R.string.sni_frag_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.sni_frag_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (config.frag.enabled) {
                    Text(stringResource(R.string.sni_frag_mode), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.frag.mode == FragMode.SPLIT,
                            onClick = {
                                mutateConfig(context, scope, snackbar, context.getString(R.string.sni_mode_split_msg)) {
                                    it.copy(frag = it.frag.copy(mode = FragMode.SPLIT))
                                }
                            },
                            label = { Text(stringResource(R.string.sni_mode_split)) }
                        )
                        FilterChip(
                            selected = config.frag.mode == FragMode.DELAY,
                            onClick = {
                                mutateConfig(context, scope, snackbar, context.getString(R.string.sni_mode_delay_msg)) {
                                    it.copy(frag = it.frag.copy(mode = FragMode.DELAY))
                                }
                            },
                            label = { Text(stringResource(R.string.sni_mode_delay)) }
                        )
                    }
                    NumberField(
                        label = stringResource(R.string.sni_first_fragment_label),
                        value = config.frag.firstFragment,
                        range = 1..32
                    ) { v ->
                        mutateConfig(context, scope, snackbar, context.getString(R.string.sni_first_fragment_msg, v)) {
                            it.copy(frag = it.frag.copy(firstFragment = v))
                        }
                    }
                    NumberField(
                        label = stringResource(R.string.sni_chunk_label),
                        value = config.frag.chunk,
                        range = 8..256
                    ) { v ->
                        mutateConfig(context, scope, snackbar, context.getString(R.string.sni_chunk_msg, v)) {
                            it.copy(frag = it.frag.copy(chunk = v))
                        }
                    }
                    NumberField(
                        label = stringResource(R.string.sni_delay_label),
                        value = config.frag.delayMs,
                        range = 0..100,
                        enabled = config.frag.mode == FragMode.DELAY
                    ) { v ->
                        mutateConfig(context, scope, snackbar, context.getString(R.string.sni_delay_msg, v)) {
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
                            mutateConfig(context, scope, snackbar, if (v) context.getString(R.string.sni_spoof_enable) else context.getString(R.string.sni_spoof_disable)) {
                                it.copy(spoof = it.spoof.copy(enabled = v))
                            }
                        }
                    )
                    Column {
                        Text(stringResource(R.string.sni_spoof_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.sni_spoof_desc),
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
                                mutateConfig(context, scope, snackbar, context.getString(R.string.sni_fake_sni_msg, new)) { c ->
                                    c.copy(spoof = c.spoof.copy(fakeSni = new))
                                }
                            }
                        },
                        label = { Text(stringResource(R.string.sni_label_fake_sni)) },
                        placeholder = { Text(stringResource(R.string.sni_placeholder_fake_sni)) },
                        singleLine = true,
                        supportingText = {
                            Text(stringResource(R.string.sni_spoof_support))
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
                        mutateConfig(context, scope, snackbar, if (v) context.getString(R.string.sni_mitm_enable) else context.getString(R.string.sni_mitm_disable)) {
                            it.copy(spoof = it.spoof.copy(mitmFallback = v))
                        }
                    }
                )
                Column {
                    Text(stringResource(R.string.sni_mitm_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.sni_mitm_desc),
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
                            mutateConfig(context, scope, snackbar, if (v) context.getString(R.string.sni_full_mitm_enable) else context.getString(R.string.sni_full_mitm_disable)) {
                                it.copy(mitmEnabled = v)
                            }
                        }
                    )
                    Column {
                        Text(stringResource(R.string.sni_full_mitm_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            stringResource(R.string.sni_full_mitm_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (config.mitmEnabled && !hasCa) {
                    Text(
                        stringResource(R.string.sni_no_ca_hint),
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