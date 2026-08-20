package com.toolbox.app.easytier

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.easytier.jni.EasyTierJNI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "EasyTier"

@Keep
data class MyNodeInfo(
    val hostname: String = "未知",
    val virtualIp: String = "",
    val version: String = ""
)

@Keep
data class PeerInfo(
    val hostname: String = "",
    val virtualIp: String = "",
    val isDirect: Boolean = false,
    val latency: String = "",
    val natType: String = ""
)

@Keep
data class NetworkSnapshot(
    val isRunning: Boolean = false,
    val myNode: MyNodeInfo? = null,
    val peers: List<PeerInfo> = emptyList(),
    val error: String? = null
)

data class EasyTierConfig(
    val instanceName: String = "toolbox",
    val networkName: String = "",
    val networkSecret: String = "",
    val peers: String = "tcp://public.easytier.top:11010",
    val virtualIpv4: String = "",
    val dhcp: Boolean = true,
    val listenerUrls: String = "tcp://0.0.0.0:11010\nudp://0.0.0.0:11010",
    val acceptDns: Boolean = false,
    val disableP2p: Boolean = false,
    val relayNetworkWhitelist: String = "",
    val tldDnsZone: String = "",
    val hostname: String = ""
) {
    fun toToml(): String {
        val sb = StringBuilder()
        sb.appendLine("[network_identity]")
        sb.appendLine("network_name = \"${networkName}\"")
        if (networkSecret.isNotEmpty()) {
            sb.appendLine("network_secret = \"${networkSecret.replace("\"", "\\\"")}\"")
        }
        sb.appendLine()

        if (peers.isNotBlank()) {
            peers.split("\n").filter { it.isNotBlank() }.forEach { uri ->
                sb.appendLine("[[peer]]")
                sb.appendLine("uri = \"$uri\"")
            }
            sb.appendLine()
        }

        if (!dhcp && virtualIpv4.isNotBlank()) {
            sb.appendLine("ipv4 = \"$virtualIpv4/24\"")
        } else if (dhcp) {
            sb.appendLine("dhcp = true")
        }
        sb.appendLine()

        sb.appendLine("[flags]")
        sb.appendLine("accept_dns = $acceptDns")
        sb.appendLine("disable_p2p = $disableP2p")
        sb.appendLine("bind_device = true")
        sb.appendLine("multi_thread = true")
        sb.appendLine("enable_encryption = true")
        if (relayNetworkWhitelist.isNotEmpty()) sb.appendLine("relay_network_whitelist = \"$relayNetworkWhitelist\"")
        if (tldDnsZone.isNotEmpty()) sb.appendLine("tld_dns_zone = \"$tldDnsZone\"")
        if (hostname.isNotBlank()) sb.appendLine("hostname = \"$hostname\"")
        sb.appendLine()

        sb.appendLine("[[listener]]")
        listenerUrls.split("\n").filter { it.isNotBlank() }.forEach { url ->
            sb.appendLine("url = \"$url\"")
        }

        return sb.toString()
    }
}

class EasyTierManager(private val context: Context) {
    private var isRunning = false
    private var currentConfig: EasyTierConfig? = null

    private val prefs = context.getSharedPreferences("easytier_config", Context.MODE_PRIVATE)

    fun loadConfig(): EasyTierConfig = EasyTierConfig(
        instanceName = prefs.getString("instance_name", "toolbox") ?: "toolbox",
        networkName = prefs.getString("network_name", "") ?: "",
        networkSecret = prefs.getString("network_secret", "") ?: "",
        peers = prefs.getString("peers", "tcp://public.easytier.top:11010") ?: "tcp://public.easytier.top:11010",
        virtualIpv4 = prefs.getString("virtual_ipv4", "") ?: "",
        dhcp = prefs.getBoolean("dhcp", true),
        listenerUrls = prefs.getString("listener_urls", "tcp://0.0.0.0:11010\nudp://0.0.0.0:11010") ?: "tcp://0.0.0.0:11010\nudp://0.0.0.0:11010",
        acceptDns = prefs.getBoolean("accept_dns", false),
        disableP2p = prefs.getBoolean("disable_p2p", false),
        relayNetworkWhitelist = prefs.getString("relay_whitelist", "") ?: "",
        tldDnsZone = prefs.getString("tld_dns_zone", "") ?: "",
        hostname = prefs.getString("hostname", "") ?: ""
    )

    fun saveConfig(config: EasyTierConfig) {
        prefs.edit().apply {
            putString("instance_name", config.instanceName)
            putString("network_name", config.networkName)
            putString("network_secret", config.networkSecret)
            putString("peers", config.peers)
            putString("virtual_ipv4", config.virtualIpv4)
            putBoolean("dhcp", config.dhcp)
            putString("listener_urls", config.listenerUrls)
            putBoolean("accept_dns", config.acceptDns)
            putBoolean("disable_p2p", config.disableP2p)
            putString("relay_whitelist", config.relayNetworkWhitelist)
            putString("tld_dns_zone", config.tldDnsZone)
            putString("hostname", config.hostname)
            apply()
        }
    }

    suspend fun start(config: EasyTierConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val toml = config.toToml()
            val result = EasyTierJNI.runNetworkInstance(toml)
            if (result == 0) {
                isRunning = true
                currentConfig = config
                Log.i(TAG, "EasyTier 启动成功")
                "success"
            } else {
                val err = EasyTierJNI.getLastError() ?: "错误码: $result"
                Log.e(TAG, "EasyTier 启动失败: $err")
                throw IllegalStateException(err)
            }
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            EasyTierJNI.stopAllInstances()
            isRunning = false
            currentConfig = null
            Log.i(TAG, "EasyTier 已停止")
            Unit
        }
    }

    suspend fun getStatus(): NetworkSnapshot = withContext(Dispatchers.IO) {
        val info = EasyTierJNI.collectNetworkInfos(20)
        if (info.isNullOrBlank()) return@withContext NetworkSnapshot(isRunning = isRunning)
        try {
            parseNetworkInfo(info)
        } catch (e: Exception) {
            Log.e(TAG, "解析网络信息失败", e)
            NetworkSnapshot(isRunning = isRunning, error = e.message)
        }
    }

    private fun parseNetworkInfo(json: String): NetworkSnapshot {
        val root = JSONObject(json)
        val mapObj = root.optJSONObject("map") ?: return NetworkSnapshot(isRunning = isRunning)
        val inst = mapObj.optJSONObject(currentConfig?.instanceName ?: "toolbox")
            ?: mapObj.optJSONObject("easytier")
            ?: return NetworkSnapshot(isRunning = isRunning)

        val myNodeJson = inst.optJSONObject("my_node_info")
        val myNode = if (myNodeJson != null) {
            val addrJson = myNodeJson.optJSONObject("virtual_ipv4")?.optJSONObject("address")
            val addr = addrJson?.optInt("addr", 0) ?: 0
            val netLen = myNodeJson.optJSONObject("virtual_ipv4")?.optInt("network_length", 24) ?: 24
            val ip = "${(addr ushr 24) and 0xFF}.${(addr ushr 16) and 0xFF}.${(addr ushr 8) and 0xFF}.${addr and 0xFF}"
            MyNodeInfo(
                hostname = myNodeJson.optString("hostname", "未知"),
                virtualIp = "$ip/$netLen",
                version = myNodeJson.optString("version", "")
            )
        } else null

        val peers = mutableListOf<PeerInfo>()
        val routesArr = inst.optJSONArray("routes")
        if (routesArr != null) {
            for (i in 0 until routesArr.length()) {
                val route = routesArr.getJSONObject(i)
                val peerId = route.optLong("peer_id", -1)
                if (peerId < 0) continue
                val peerJson = route.optJSONObject("peer_info")
                val peerHostname = peerJson?.optString("hostname", "") ?: route.optString("hostname", "节点${peerId.toString().takeLast(4)}")
                val routeIp = route.optJSONObject("ipv4_addr")?.optJSONObject("address")?.optInt("addr", 0) ?: 0
                val virtIp = if (routeIp != 0) {
                    "${(routeIp ushr 24) and 0xFF}.${(routeIp ushr 16) and 0xFF}.${(routeIp ushr 8) and 0xFF}.${routeIp and 0xFF}"
                } else "未知"
                val statsJson = route.optJSONObject("stats")
                val latency = if (statsJson != null) "${statsJson.optLong("latency_us", 0) / 1000} ms" else ""
                val natRaw = route.opt("nat_type")
                val nat = when {
                    natRaw is String -> natRaw
                    natRaw is Int -> when (natRaw) {
                        1 -> "开放互联网"; 3 -> "完全锥形"; 5 -> "端口限制锥形"
                        6 -> "对称型"; else -> "未知"
                    }
                    else -> "未知"
                }
                peers.add(PeerInfo(peerHostname, virtIp, route.optLong("next_hop_peer_id", -1) == peerId, latency, nat))
            }
        }

        return NetworkSnapshot(
            isRunning = isRunning,
            myNode = myNode,
            peers = peers,
            error = if (!inst.optBoolean("running", true)) inst.optString("error_msg", "实例未运行") else null
        )
    }
}
