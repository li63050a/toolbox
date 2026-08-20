package com.toolbox.app.easytier

import androidx.annotation.Keep
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
@Keep
data class EasyTierConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "默认",
    val instanceName: String = "toolbox",
    // 基本
    val networkName: String = "",
    val networkSecret: String = "",
    val peers: String = "tcp://public.easytier.top:11010",
    val virtualIpv4: String = "",
    val networkLength: Int = 24,
    val dhcp: Boolean = true,
    // 监听
    val listenerUrls: String = "tcp://0.0.0.0:11010\nudp://0.0.0.0:11010",
    val mappedListeners: String = "",
    // 高级
    val hostname: String = "",
    val proxyNetworks: String = "",
    val exitNodes: String = "",
    val routes: String = "",
    val acceptDns: Boolean = false,
    val tldDnsZone: String = "",
    val disableP2p: Boolean = false,
    val enableExitNode: Boolean = false,
    val privateMode: Boolean = false,
    val relayNetworkWhitelist: String = "",
    val enableRelayNetworkWhitelist: Boolean = false,
    val multiThread: Boolean = true,
    val bindDevice: Boolean = true,
    val noTun: Boolean = false,
    val mtu: String = "",
    val devName: String = "",
    val disableIpv6: Boolean = false,
    val ipv6: String = "",
    val stunServers: String = "",
    val stunServersV6: String = "",
    // 安全
    val secureMode: Boolean = false,
    val localPrivateKey: String = "",
    val localPublicKey: String = "",
    val encryptionAlgorithm: String = "",
    // 其他
    val socks5Port: Int = 1080,
    val enableSocks5: Boolean = false,
    val latencyFirst: Boolean = false,
    val p2pOnly: Boolean = false,
    val lazyP2p: Boolean = false,
    val needP2p: Boolean = false,
    val disableEncryption: Boolean = false,
    val proxyForwardBySystem: Boolean = false,
    val enableUdpBroadcastRelay: Boolean = false,
) {
    fun toToml(): String {
        val sb = StringBuilder()
        sb.appendLine("[network_identity]")
        sb.appendLine("network_name = \"${networkName}\"")
        if (networkSecret.isNotEmpty()) sb.appendLine("network_secret = \"${networkSecret}\"")
        sb.appendLine()

        if (peers.isNotBlank()) {
            peers.lines().filter { it.isNotBlank() }.forEach { uri ->
                sb.appendLine("[[peer]]")
                sb.appendLine("uri = \"$uri\"")
            }
            sb.appendLine()
        }

        if (!dhcp && virtualIpv4.isNotBlank()) {
            sb.appendLine("ipv4 = \"$virtualIpv4/$networkLength\"")
        } else if (dhcp) {
            sb.appendLine("dhcp = true")
        }
        if (ipv6.isNotBlank()) sb.appendLine("ipv6 = \"$ipv6\"")
        sb.appendLine()

        if (listenerUrls.isNotBlank()) {
            sb.appendLine("[[listener]]")
            listenerUrls.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("url = \"$it\"") }
            sb.appendLine()
        }
        if (mappedListeners.isNotBlank()) {
            sb.appendLine("[[mapped_listener]]")
            mappedListeners.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("url = \"$it\"") }
            sb.appendLine()
        }

        if (exitNodes.isNotBlank()) {
            sb.appendLine("[[exit_node]]")
            exitNodes.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("name = \"$it\"") }
            sb.appendLine()
        }
        if (routes.isNotBlank()) {
            sb.appendLine("[[route]]")
            routes.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("cidr = \"$it\"") }
            sb.appendLine()
        }
        if (proxyNetworks.isNotBlank()) {
            sb.appendLine("[[proxy_network]]")
            proxyNetworks.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("cidr = \"$it\"") }
            sb.appendLine()
        }

        sb.appendLine("[flags]")
        sb.appendLine("accept_dns = $acceptDns")
        sb.appendLine("disable_p2p = $disableP2p")
        sb.appendLine("bind_device = $bindDevice")
        sb.appendLine("multi_thread = $multiThread")
        sb.appendLine("no_tun = $noTun")
        sb.appendLine("private_mode = $privateMode")
        sb.appendLine("enable_exit_node = $enableExitNode")
        sb.appendLine("disable_encryption = $disableEncryption")
        sb.appendLine("latency_first = $latencyFirst")
        sb.appendLine("p2p_only = $p2pOnly")
        sb.appendLine("lazy_p2p = $lazyP2p")
        sb.appendLine("need_p2p = $needP2p")
        sb.appendLine("proxy_forward_by_system = $proxyForwardBySystem")
        sb.appendLine("enable_udp_broadcast_relay = $enableUdpBroadcastRelay")
        sb.appendLine("disable_ipv6 = $disableIpv6")
        if (mtu.isNotBlank()) sb.appendLine("mtu = ${mtu.toIntOrNull() ?: 1400}")
        if (devName.isNotBlank()) sb.appendLine("dev_name = \"$devName\"")
        if (tldDnsZone.isNotBlank()) sb.appendLine("tld_dns_zone = \"$tldDnsZone\"")
        if (enableRelayNetworkWhitelist && relayNetworkWhitelist.isNotBlank()) {
            sb.appendLine("relay_network_whitelist = \"$relayNetworkWhitelist\"")
        }
        if (secureMode) {
            sb.appendLine("[secure_mode]")
            sb.appendLine("enabled = true")
            if (localPrivateKey.isNotEmpty()) sb.appendLine("local_private_key = \"$localPrivateKey\"")
            if (localPublicKey.isNotEmpty()) sb.appendLine("local_public_key = \"$localPublicKey\"")
            sb.appendLine()
        }
        if (stunServers.isNotBlank()) {
            sb.appendLine("[stun_servers]")
            stunServers.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("\"$it\"") }
            sb.appendLine()
        }
        if (stunServersV6.isNotBlank()) {
            sb.appendLine("[stun_servers_v6]")
            stunServersV6.lines().filter { it.isNotBlank() }.forEach { sb.appendLine("\"$it\"") }
            sb.appendLine()
        }
        if (hostname.isNotBlank()) sb.appendLine("hostname = \"$hostname\"")
        if (enableSocks5) sb.appendLine("socks5_proxy = \"socks5://0.0.0.0:${socks5Port}\"")
        if (encryptionAlgorithm.isNotEmpty()) sb.appendLine("encryption_algorithm = \"$encryptionAlgorithm\"")

        return sb.toString()
    }

    companion object {
        fun defaultConfig(name: String = "默认"): EasyTierConfig = EasyTierConfig(name = name)
        fun fromToml(toml: String, name: String = "导入"): EasyTierConfig {
            val c = EasyTierConfig(name = name)
            var curSection = ""
            for (line in toml.lines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
                    curSection = trimmed.removePrefix("[[").removeSuffix("]]")
                    continue
                }
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    curSection = trimmed.removePrefix("[").removeSuffix("]")
                    continue
                }
                val eq = trimmed.indexOf('=')
                if (eq < 0) continue
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
                when ("$curSection.$key") {
                    ".network_name" -> c.copy(networkName = value)
                    ".network_secret" -> c.copy(networkSecret = value)
                    ".hostname" -> c.copy(hostname = value)
                    ".accept_dns" -> c.copy(acceptDns = value.toBoolean())
                    ".disable_p2p" -> c.copy(disableP2p = value.toBoolean())
                    ".private_mode" -> c.copy(privateMode = value.toBoolean())
                    ".multi_thread" -> c.copy(multiThread = value.toBoolean())
                    ".bind_device" -> c.copy(bindDevice = value.toBoolean())
                    ".no_tun" -> c.copy(noTun = value.toBoolean())
                    ".enable_exit_node" -> c.copy(enableExitNode = value.toBoolean())
                    ".disable_encryption" -> c.copy(disableEncryption = value.toBoolean())
                    ".latency_first" -> c.copy(latencyFirst = value.toBoolean())
                    ".p2p_only" -> c.copy(p2pOnly = value.toBoolean())
                    ".lazy_p2p" -> c.copy(lazyP2p = value.toBoolean())
                    ".need_p2p" -> c.copy(needP2p = value.toBoolean())
                    ".tld_dns_zone" -> c.copy(tldDnsZone = value)
                    ".mtu" -> c.copy(mtu = value)
                    ".dev_name" -> c.copy(devName = value)
                    ".disable_ipv6" -> c.copy(disableIpv6 = value.toBoolean())
                    ".relay_network_whitelist" -> c.copy(relayNetworkWhitelist = value)
                    ".enable_relay_network_whitelist" -> c.copy(enableRelayNetworkWhitelist = value.toBoolean())
                    ".local_private_key" -> c.copy(localPrivateKey = value)
                    ".local_public_key" -> c.copy(localPublicKey = value)
                    ".encryption_algorithm" -> c.copy(encryptionAlgorithm = value)
                    "peer.uri" -> c.copy(peers = if (c.peers.isEmpty()) value else "${c.peers}\n$value")
                    "listener.url" -> c.copy(listenerUrls = if (c.listenerUrls.isEmpty()) value else "${c.listenerUrls}\n$value")
                    "mapped_listener.url" -> c.copy(mappedListeners = if (c.mappedListeners.isEmpty()) value else "${c.mappedListeners}\n$value")
                    "exit_node.name" -> c.copy(exitNodes = if (c.exitNodes.isEmpty()) value else "${c.exitNodes}\n$value")
                    "route.cidr" -> c.copy(routes = if (c.routes.isEmpty()) value else "${c.routes}\n$value")
                    "proxy_network.cidr" -> c.copy(proxyNetworks = if (c.proxyNetworks.isEmpty()) value else "${c.proxyNetworks}\n$value")
                    "stun_servers.${value}" -> {} // handled below
                    else -> {}
                }
            }
            return c
        }
    }
}

@Serializable
@Keep
data class MyNodeInfo(
    val hostname: String = "未知",
    val virtualIp: String = "",
    val version: String = ""
)

@Serializable
@Keep
data class PeerInfo(
    val hostname: String = "",
    val virtualIp: String = "",
    val isDirect: Boolean = false,
    val latency: String = "",
    val natType: String = "",
    val traffic: String = ""
)

@Serializable
@Keep
data class DetailedNetworkInfo(
    val myNode: MyNodeInfo? = null,
    val peers: List<PeerInfo> = emptyList(),
    val events: List<String> = emptyList(),
    val error: String? = null
)

@Serializable
@Keep
data class NetworkSnapshot(
    val isRunning: Boolean = false,
    val detailed: DetailedNetworkInfo? = null
)
