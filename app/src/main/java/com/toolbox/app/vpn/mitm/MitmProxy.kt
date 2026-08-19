package com.toolbox.app.vpn.mitm

import android.content.Context
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.VpnConfig

/**
 * MITM 入口：本地 TLS 终结服务（127.0.0.1:8443），
 * 由 TcpIp 在 dstPort==443 且 mitmEnabled 时把目标改写到这里。
 */
object MitmProxy {

    const val PORT = 8443

    private const val TAG = "MITM"

    @Volatile private var server: MitmServer? = null

    fun start(context: Context) {
        synchronized(this) {
            if (server != null) return
            val s = MitmServer(context)
            server = s
            s.start()
        }
    }

    fun stop() {
        synchronized(this) {
            server?.stop()
            server = null
        }
        Log.i(TAG, "MITM 已停止")
    }

    fun isMitmDesired(dstPort: Int, config: VpnConfig): Boolean =
        dstPort == 443 && config.mitmEnabled
}