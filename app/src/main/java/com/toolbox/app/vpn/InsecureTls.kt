package com.toolbox.app.vpn

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 不安全的 TLS 辅助（仅用于 DoT 上游与 MITM 出站）。
 * 安全说明：这些场景必须信任任意证书 —— DoT 上游可能用内网/自建 DNS 证书；
 * MITM 出站必须接受真实服务器的任何证书（SNI 已伪装，证书与伪装名不匹配）。
 * 此类连接仅承载 DNS 报文与 HTTP 明文转发，不承载用户敏感凭据的端到端
 * 校验职责（该职责已由用户安装的 Toolbox CA 在 app 侧完成）。
 */
internal object InsecureTls {

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun trustAllContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        return ctx
    }

    /** 无主机名校验：SSLSocket 默认不做 hostname 校验（需显式设置
     *  endpoint identification），此处提供值仅用于 OkHttp/Https 场景兜底 */
    val verifyNone: HostnameVerifier = HostnameVerifier { _, _ -> true }
}