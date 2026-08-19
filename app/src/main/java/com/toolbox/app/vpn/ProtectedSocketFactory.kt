package com.toolbox.app.vpn

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * OkHttp 使用的 SocketFactory（javax.net.SocketFactory）：
 * 每个新建 Socket 先经 SocketProtector.protect 再返回，确保 DoH 出站流量
 * 不通过 VPN tun 回环。
 */
class ProtectedSocketFactory : SocketFactory() {

    override fun createSocket(): Socket {
        val socket = Socket()
        SocketProtector.protect(socket)
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        SocketProtector.protect(socket)
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = Socket()
        SocketProtector.protect(socket)
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val socket = Socket()
        SocketProtector.protect(socket)
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = Socket()
        SocketProtector.protect(socket)
        socket.bind(InetSocketAddress(localHost, localPort))
        socket.connect(InetSocketAddress(host, port))
        return socket
    }
}
