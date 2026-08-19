package com.toolbox.app.vpn

import java.net.DatagramSocket
import java.net.Socket

/**
 * Socket 保护接点：VpnService 激活时注入 protectFn，把本进程对外 socket
 * 标记为绕过 VPN tun，防止回环进 VPN。
 */
object SocketProtector {

    @Volatile
    var protectFn: ((Socket) -> Unit)? = null

    // VpnService.protect(DatagramSocket) 与 protect(Socket) 是两个重载，
    // protectFn 只承载 Socket 版本，DatagramSocket 版本走独立接点
    @Volatile
    var datagramProtectFn: ((DatagramSocket) -> Unit)? = null

    fun protect(socket: Socket) {
        protectFn?.invoke(socket)
    }

    fun protect(datagram: DatagramSocket) {
        datagramProtectFn?.invoke(datagram)
    }
}
