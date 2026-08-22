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
        sb.appendLine("instance_name = \"${instanceName}\"")
        sb.appendLine()
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
            val ipPart = virtualIpv4.substringBefore("/")
            val lenPart = virtualIpv4.substringAfter("/", networkLength.toString()).toIntOrNull() ?: networkLength
            sb.appendLine("ipv4 = \"$ipPart/$lenPart\"")
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
        fun defaultConfig(name: String = "默认"): EasyTierConfig = EasyTierConfig(
            name = name,
            instanceName = "toolbox-${UUID.randomUUID().toString().take(8)}"
        )
        fun fromToml(toml: String, name: String = "导入"): EasyTierConfig {
            var c = EasyTierConfig(name = name, instanceName = "toolbox-${UUID.randomUUID().toString().take(8)}")
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
                if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                    val bare = trimmed.removeSurrounding("\"")
                    when (curSection) {
                        "stun_servers" -> c = c.copy(stunServers = if (c.stunServers.isBlank()) bare else "${c.stunServers}\n$bare")
                        "stun_servers_v6" -> c = c.copy(stunServersV6 = if (c.stunServersV6.isBlank()) bare else "${c.stunServersV6}\n$bare")
                    }
                    continue
                }
                val eq = trimmed.indexOf('=')
                if (eq < 0) continue
                val key = trimmed.substring(0, eq).trim()
                val value = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
                when (key) {
                    "instance_name" -> c = c.copy(instanceName = value)
                    "network_name" -> c = c.copy(networkName = value)
                    "network_secret" -> c = c.copy(networkSecret = value)
                    "hostname" -> c = c.copy(hostname = value)
                    "dhcp" -> c = c.copy(dhcp = value.toBoolean())
                    "ipv4" -> {
                        val ip = value.substringBefore("/")
                        val len = value.substringAfter("/", "24").toIntOrNull() ?: 24
                        c = c.copy(virtualIpv4 = ip, networkLength = len, dhcp = false)
                    }
                    "ipv6" -> c = c.copy(ipv6 = value)
                    "accept_dns" -> c = c.copy(acceptDns = value.toBoolean())
                    "disable_p2p" -> c = c.copy(disableP2p = value.toBoolean())
                    "private_mode" -> c = c.copy(privateMode = value.toBoolean())
                    "multi_thread" -> c = c.copy(multiThread = value.toBoolean())
                    "bind_device" -> c = c.copy(bindDevice = value.toBoolean())
                    "no_tun" -> c = c.copy(noTun = value.toBoolean())
                    "enable_exit_node" -> c = c.copy(enableExitNode = value.toBoolean())
                    "disable_encryption" -> c = c.copy(disableEncryption = value.toBoolean())
                    "latency_first" -> c = c.copy(latencyFirst = value.toBoolean())
                    "p2p_only" -> c = c.copy(p2pOnly = value.toBoolean())
                    "lazy_p2p" -> c = c.copy(lazyP2p = value.toBoolean())
                    "need_p2p" -> c = c.copy(needP2p = value.toBoolean())
                    "proxy_forward_by_system" -> c = c.copy(proxyForwardBySystem = value.toBoolean())
                    "enable_udp_broadcast_relay" -> c = c.copy(enableUdpBroadcastRelay = value.toBoolean())
                    "tld_dns_zone" -> c = c.copy(tldDnsZone = value)
                    "mtu" -> c = c.copy(mtu = value)
                    "dev_name" -> c = c.copy(devName = value)
                    "disable_ipv6" -> c = c.copy(disableIpv6 = value.toBoolean())
                    "relay_network_whitelist" -> c = c.copy(relayNetworkWhitelist = value)
                    "enable_relay_network_whitelist" -> c = c.copy(enableRelayNetworkWhitelist = value.toBoolean())
                    "enabled" -> if (curSection == "secure_mode") c = c.copy(secureMode = value.toBoolean())
                    "local_private_key" -> c = c.copy(localPrivateKey = value, secureMode = true)
                    "local_public_key" -> c = c.copy(localPublicKey = value, secureMode = true)
                    "encryption_algorithm" -> c = c.copy(encryptionAlgorithm = value)
                    "socks5_proxy" -> c = c.copy(enableSocks5 = true, socks5Port = value.split(":").lastOrNull()?.toIntOrNull() ?: c.socks5Port)
                    "uri" -> when (curSection) {
                        "peer" -> c = c.copy(peers = if (c.peers.isBlank()) value else "${c.peers}\n$value")
                    }
                    "url" -> when (curSection) {
                        "listener" -> c = c.copy(listenerUrls = if (c.listenerUrls.isBlank()) value else "${c.listenerUrls}\n$value")
                        "mapped_listener" -> c = c.copy(mappedListeners = if (c.mappedListeners.isBlank()) value else "${c.mappedListeners}\n$value")
                    }
                    "name" -> when (curSection) {
                        "exit_node" -> c = c.copy(exitNodes = if (c.exitNodes.isBlank()) value else "${c.exitNodes}\n$value")
                    }
                    "cidr" -> when (curSection) {
                        "route" -> c = c.copy(routes = if (c.routes.isBlank()) value else "${c.routes}\n$value")
                        "proxy_network" -> c = c.copy(proxyNetworks = if (c.proxyNetworks.isBlank()) value else "${c.proxyNetworks}\n$value")
                    }
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
