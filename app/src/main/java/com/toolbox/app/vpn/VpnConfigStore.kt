package com.toolbox.app.vpn

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toolbox.app.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val Context.vpnDataStore by preferencesDataStore(name = "vpn_config")

/**
 * VPN 配置持久化：DataStore 存 JSON 字符串（kotlinx.serialization）。
 */
object VpnConfigStore {

    private const val TAG = "VPN"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val key = stringPreferencesKey("config")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var context: Context? = null

    private val _config = MutableStateFlow(VpnConfig())
    val config: StateFlow<VpnConfig> = _config

    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        scope.launch {
            try {
                val prefs = context.applicationContext.vpnDataStore.data.first()
                val raw = prefs[key]
                if (raw != null) {
                    val parsed = json.decodeFromString(VpnConfig.serializer(), raw)
                    _config.value = parsed
                    Log.i(TAG, "VPN 配置已加载")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "读取 VPN 配置失败，使用默认配置", t)
            }
        }
    }

    suspend fun mutate(block: (VpnConfig) -> VpnConfig) {
        val ctx = context
        if (ctx == null) {
            val next = block(_config.value)
            _config.value = next
            return
        }
        val next = block(_config.value)
        _config.value = next
        runCatching {
            ctx.vpnDataStore.edit { prefs ->
                prefs[key] = json.encodeToString(VpnConfig.serializer(), next)
            }
        }.onFailure { t ->
            Log.e(TAG, "保存 VPN 配置失败", t)
        }
    }

    /**
     * SAF 读文本 hosts 文件（"IP 域名 [域名...]"），忽略 # 注释与空行，按 domain 去重合并，
     * 返回新增条数。
     */
    suspend fun importHostsFile(context: Context, uri: Uri): Int {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrElse { t ->
            Log.e(TAG, "读取 hosts 文件失败", t)
            null
        }
        if (text.isNullOrBlank()) return 0

        val parsed = mutableListOf<Pair<String, String>>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val tokens = line.split(Regex("\\s+"))
            val ip = tokens[0]
            if (tokens.size < 2) continue
            if (!ip.contains('.') && !ip.contains(':')) continue
            for (i in 1 until tokens.size) {
                val domain = tokens[i].trimEnd('.').lowercase()
                if (domain.isNotEmpty() && !domain.contains('/')) {
                    parsed.add(domain to ip)
                }
            }
        }
        if (parsed.isEmpty()) return 0

        val existing = _config.value.hostsRules
        val existingDomains = existing.mapTo(HashSet()) { it.domain.lowercase() }
        val added = mutableListOf<HostsRule>()
        for ((domain, ip) in parsed) {
            if (existingDomains.add(domain)) {
                added.add(HostsRule(domain, ip))
            }
        }
        if (added.isEmpty()) return 0

        mutate { cfg -> cfg.copy(hostsRules = cfg.hostsRules + added) }
        Log.i(TAG, "导入 hosts：新增 ${added.size} 条规则")
        return added.size
    }
}
