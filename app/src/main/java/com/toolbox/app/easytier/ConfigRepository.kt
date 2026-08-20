package com.toolbox.app.easytier

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

class ConfigRepository(private val context: Context) {
    var configs by mutableStateOf<List<EasyTierConfig>>(listOf(EasyTierConfig.defaultConfig()))
        private set
    var activeId by mutableStateOf<String?>(null)
        private set

    init { reload() }

    fun reload() {
        val sp = context.getSharedPreferences("easytier", Context.MODE_PRIVATE)
        try {
            val json = sp.getString("all_configs", null)
            configs = if (json != null) parseConfigs(json) else listOf(EasyTierConfig.defaultConfig())
        } catch (_: Exception) { configs = listOf(EasyTierConfig.defaultConfig()) }
        activeId = sp.getString("active_id", null)
    }

    private fun persist() {
        val sp = context.getSharedPreferences("easytier", Context.MODE_PRIVATE)
        val arr = JSONArray()
        configs.forEach { arr.put(toJson(it)) }
        sp.edit().putString("all_configs", JSONObject().put("configs", arr).toString()).apply()
        sp.edit().putString("active_id", activeId).apply()
    }

    private fun parseConfigs(json: String): List<EasyTierConfig> =
        try {
            val arr = JSONObject(json).getJSONArray("configs")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                EasyTierConfig(
                    id = o.optString("id"), name = o.optString("name", "默认"),
                    instanceName = o.optString("instance_name", "toolbox"),
                    networkName = o.optString("network_name", ""), networkSecret = o.optString("network_secret", ""),
                    peers = o.optString("peers", "tcp://public.easytier.top:11010"),
                    virtualIpv4 = o.optString("virtual_ipv4", ""), networkLength = o.optInt("network_length", 24),
                    dhcp = o.optBoolean("dhcp", true),
                    listenerUrls = o.optString("listener_urls", "tcp://0.0.0.0:11010\nudp://0.0.0.0:11010"),
                    mappedListeners = o.optString("mapped_listeners", ""),
                    hostname = o.optString("hostname", ""), proxyNetworks = o.optString("proxy_networks", ""),
                    exitNodes = o.optString("exit_nodes", ""), routes = o.optString("routes", ""),
                    acceptDns = o.optBoolean("accept_dns", false), tldDnsZone = o.optString("tld_dns_zone", ""),
                    disableP2p = o.optBoolean("disable_p2p", false), enableExitNode = o.optBoolean("enable_exit_node", false),
                    privateMode = o.optBoolean("private_mode", false),
                    relayNetworkWhitelist = o.optString("relay_network_whitelist", ""),
                    enableRelayNetworkWhitelist = o.optBoolean("enable_relay_network_whitelist", false),
                    multiThread = o.optBoolean("multi_thread", true), bindDevice = o.optBoolean("bind_device", true),
                    noTun = o.optBoolean("no_tun", false), mtu = o.optString("mtu", ""), devName = o.optString("dev_name", ""),
                    disableIpv6 = o.optBoolean("disable_ipv6", false), ipv6 = o.optString("ipv6", ""),
                    stunServers = o.optString("stun_servers", ""), stunServersV6 = o.optString("stun_servers_v6", ""),
                    secureMode = o.optBoolean("secure_mode", false),
                    localPrivateKey = o.optString("local_private_key", ""), localPublicKey = o.optString("local_public_key", ""),
                    encryptionAlgorithm = o.optString("encryption_algorithm", ""),
                    socks5Port = o.optInt("socks5_port", 1080), enableSocks5 = o.optBoolean("enable_socks5", false),
                    latencyFirst = o.optBoolean("latency_first", false), p2pOnly = o.optBoolean("p2p_only", false),
                    lazyP2p = o.optBoolean("lazy_p2p", false), needP2p = o.optBoolean("need_p2p", false),
                    disableEncryption = o.optBoolean("disable_encryption", false),
                    proxyForwardBySystem = o.optBoolean("proxy_forward_by_system", false),
                    enableUdpBroadcastRelay = o.optBoolean("enable_udp_broadcast_relay", false),
                )
            }.ifEmpty { listOf(EasyTierConfig.defaultConfig()) }
        } catch (_: Exception) { listOf(EasyTierConfig.defaultConfig()) }

    private fun toJson(c: EasyTierConfig): JSONObject = JSONObject().apply {
        put("id", c.id); put("name", c.name)
        put("instance_name", c.instanceName)
        put("network_name", c.networkName); put("network_secret", c.networkSecret)
        put("peers", c.peers); put("virtual_ipv4", c.virtualIpv4)
        put("network_length", c.networkLength); put("dhcp", c.dhcp)
        put("listener_urls", c.listenerUrls); put("mapped_listeners", c.mappedListeners)
        put("hostname", c.hostname); put("proxy_networks", c.proxyNetworks)
        put("exit_nodes", c.exitNodes); put("routes", c.routes)
        put("accept_dns", c.acceptDns); put("tld_dns_zone", c.tldDnsZone)
        put("disable_p2p", c.disableP2p); put("enable_exit_node", c.enableExitNode)
        put("private_mode", c.privateMode); put("relay_network_whitelist", c.relayNetworkWhitelist)
        put("enable_relay_network_whitelist", c.enableRelayNetworkWhitelist)
        put("multi_thread", c.multiThread); put("bind_device", c.bindDevice)
        put("no_tun", c.noTun); put("mtu", c.mtu); put("dev_name", c.devName)
        put("disable_ipv6", c.disableIpv6); put("ipv6", c.ipv6)
        put("stun_servers", c.stunServers); put("stun_servers_v6", c.stunServersV6)
        put("secure_mode", c.secureMode); put("local_private_key", c.localPrivateKey)
        put("local_public_key", c.localPublicKey); put("encryption_algorithm", c.encryptionAlgorithm)
        put("socks5_port", c.socks5Port); put("enable_socks5", c.enableSocks5)
        put("latency_first", c.latencyFirst); put("p2p_only", c.p2pOnly)
        put("lazy_p2p", c.lazyP2p); put("need_p2p", c.needP2p)
        put("disable_encryption", c.disableEncryption); put("proxy_forward_by_system", c.proxyForwardBySystem)
        put("enable_udp_broadcast_relay", c.enableUdpBroadcastRelay)
    }

    fun addConfig(config: EasyTierConfig) { configs = configs + config; persist() }
    fun updateConfig(config: EasyTierConfig) { configs = configs.map { if (it.id == config.id) config else it }; persist() }
    fun deleteConfig(id: String) {
        val list = configs.filter { it.id != id }.toMutableList()
        if (list.isEmpty()) list.add(EasyTierConfig.defaultConfig())
        configs = list; persist()
        if (activeId == id) activeId = configs.first().id
    }
    fun saveActiveConfig(id: String) { activeId = id; persist() }
    fun getActiveConfig(): EasyTierConfig = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull() ?: EasyTierConfig.defaultConfig()
    fun importConfig(toml: String, name: String): EasyTierConfig = EasyTierConfig.fromToml(toml, name)
}
