package com.toolbox.app.vpn

/**
 * hosts 规则引擎：小写归一、去尾点；先试全名，再依次退到最后一段
 * （a.b.com → a.b.com → b.com → com），第一条 enabled 且匹配的规则生效。
 */
object HostsEngine {

    fun resolve(fqdn: String, rules: List<HostsRule>, enabled: Boolean): String? {
        if (!enabled) return null
        val name = fqdn.trim().lowercase().trimEnd('.')
        if (name.isEmpty()) return null
        val parts = name.split('.')
        for (i in parts.indices) {
            val candidate = parts.drop(i).joinToString(".")
            val rule = rules.firstOrNull { it.enabled && it.domain.lowercase() == candidate }
            if (rule != null) return rule.ip
        }
        return null
    }
}
