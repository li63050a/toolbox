package com.toolbox.app.vpn

import kotlinx.serialization.Serializable

@Serializable
enum class DnsType { PLAIN, DOT, DOH }

@Serializable
data class DnsUpstream(val type: DnsType, val host: String, val port: Int)

@Serializable
data class HostsRule(
    val domain: String,
    val ip: String,
    val enabled: Boolean = true
)

@Serializable
enum class FragMode { SPLIT, DELAY }

@Serializable
data class SniFragConfig(
    val enabled: Boolean = true,
    val mode: FragMode = FragMode.SPLIT,
    val firstFragment: Int = 2,
    val chunk: Int = 32,
    val delayMs: Int = 10
)

@Serializable
data class SniSpoofConfig(
    val enabled: Boolean = true,
    val fakeSni: String = "www.apple.com",
    val mitmFallback: Boolean = true
)

@Serializable
data class VpnConfig(
    val dnsServers: List<DnsUpstream> = listOf(DnsUpstream(DnsType.PLAIN, "223.5.5.5", 53)),
    val hostsEnabled: Boolean = true,
    val hostsRules: List<HostsRule> = emptyList(),
    val frag: SniFragConfig = SniFragConfig(),
    val spoof: SniSpoofConfig = SniSpoofConfig(),
    val mitmEnabled: Boolean = false,
    val blockedApps: Set<String> = emptySet()
)
