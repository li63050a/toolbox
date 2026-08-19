package com.toolbox.app.vpn

import android.content.Context
import com.toolbox.app.log.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/**
 * DNS 本地代理：监听 127.0.0.1:5353（UDP + TCP），
 * hosts 命中直接应答，否则循环尝试上游 PLAIN / DoT / DoH。
 */
object DnsProxy {

    const val HOST = "127.0.0.1"
    const val PORT = 5353

    private const val TAG = "DNS"

    private const val RECV_BUF = 65535
    private const val UPSTREAM_TIMEOUT_MS = 3000
    private const val TCP_BACKLOG = 32

    @Volatile private var running = false
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private val tcpConnections = ConcurrentLinkedQueue<Socket>()

    private val okHttp by lazy {
        OkHttpClient.Builder()
            .socketFactory(ProtectedSocketFactory())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun start(context: Context) {
        VpnConfigStore.init(context)
        if (running) return
        synchronized(this) {
            if (running) return
            running = true
        }
        thread(name = "dns-udp") { udpLoop() }
        thread(name = "dns-tcp") { tcpAcceptLoop() }
        Log.i(TAG, "DNS 代理启动 $HOST:$PORT")
    }

    fun stop() {
        running = false
        runCatching { udpSocket?.close() }
        runCatching { tcpServer?.close() }
        tcpConnections.forEach { runCatching { it.close() } }
        tcpConnections.clear()
        Log.i(TAG, "DNS 代理已停止")
    }

    // ---------------------------------------------------------------- UDP

    private fun udpLoop() {
        runCatching {
            // 无参构造会绑定随机端口，导致后续 bind 抛 already bound
            udpSocket = DatagramSocket(null).also { ds ->
                ds.reuseAddress = true
                SocketProtector.protect(ds) // 双保险：本机回环本不需要，防回环
                ds.bind(InetSocketAddress(HOST, PORT))
                ds.soTimeout = 1000
            }
            val buf = ByteArray(RECV_BUF)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    udpSocket?.receive(packet) ?: break
                } catch (t: SocketTimeoutException) {
                    continue
                } catch (t: Throwable) {
                    if (running) Log.e(TAG, "UDP 接收异常", t)
                    break
                }
                val query = buf.copyOfRange(0, packet.length)
                val resp = handleQuery(query)
                runCatching {
                    udpSocket?.send(DatagramPacket(resp, resp.size, packet.socketAddress))
                }.onFailure { t -> Log.e(TAG, "UDP 响应发送失败", t) }
            }
        }.onFailure { t ->
            Log.e(TAG, "DNS UDP 服务异常退出", t)
        }
        if (running) runCatching { udpSocket?.close() }
    }

    // ---------------------------------------------------------------- TCP

    private fun tcpAcceptLoop() {
        runCatching {
            tcpServer = ServerSocket()
            tcpServer?.reuseAddress = true
            tcpServer?.bind(InetSocketAddress(HOST, PORT), TCP_BACKLOG)
            while (running) {
                val socket = tcpServer?.accept() ?: break
                tcpConnections.add(socket)
                thread(name = "dns-tcp-conn") { handleTcpConnection(socket) }
            }
        }.onFailure { t ->
            if (running) Log.e(TAG, "DNS TCP 服务异常退出", t)
        }
        if (running) runCatching { tcpServer?.close() }
    }

    private fun handleTcpConnection(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            while (running) {
                val len = try {
                    input.readUnsignedShort()
                } catch (t: Throwable) {
                    break // EOF / 超时即关闭连接
                }
                if (len == 0) break
                val query = ByteArray(len)
                input.readFully(query)
                val resp = handleQuery(query)
                output.writeShort(resp.size)
                output.write(resp)
                output.flush()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "TCP 连接处理异常", t)
        } finally {
            runCatching { socket.close() }
            tcpConnections.remove(socket)
        }
    }

    // ---------------------------------------------------------------- 查询处理

    private data class ParsedQuery(val id: Int, val question: ByteArray, val domain: String?)

    private fun parseQuery(query: ByteArray): ParsedQuery? {
        if (query.size < 12) return null
        val id = read16(query, 0)
        val qdcount = read16(query, 4)
        if (qdcount < 1) return null
        // 解析第一条问题段（支持压缩指针）
        var pos = 12
        val parts = mutableListOf<String>()
        var ok = false
        while (pos < query.size) {
            val b = query[pos].toInt() and 0xFF
            if (b == 0) {
                pos++
                ok = true
                break
            }
            if ((b and 0xC0) == 0xC0) {
                pos += 2
                ok = true
                break
            }
            if ((b and 0xC0) != 0 || pos + 1 + b > query.size) return null
            val sb = StringBuilder(b)
            for (i in 1..b) sb.append((query[pos + i].toInt() and 0xFF).toChar())
            parts.add(sb.toString())
            pos += 1 + b
        }
        if (!ok || pos + 4 > query.size) return null
        val question = query.copyOfRange(12, pos + 4)
        val domain = if (parts.isEmpty()) "" else parts.joinToString(".").lowercase()
        return ParsedQuery(id, question, domain)
    }

    private fun handleQuery(query: ByteArray): ByteArray {
        val parsed = parseQuery(query) ?: run {
            Log.w(TAG, "无法解析的 DNS 查询（${query.size} 字节）")
            return noerrorResponse(0, ByteArray(0))
        }
        val config = VpnConfigStore.config.value
        val hostsIp = HostsEngine.resolve(parsed.domain ?: "", config.hostsRules, config.hostsEnabled)
        if (hostsIp != null) {
            Log.i(TAG, "hosts 命中: ${parsed.domain} -> $hostsIp")
            return hostsResponse(parsed, hostsIp)
        }
        for (server in config.dnsServers) {
            try {
                val resp = when (server.type) {
                    DnsType.PLAIN -> plainExchange(server, query)
                    DnsType.DOT -> dotExchange(server, query)
                    DnsType.DOH -> dohExchange(server, query)
                }
                if (resp != null) return resp
            } catch (t: Throwable) {
                Log.w(TAG, "上游 ${server.host}:${server.port} (${server.type}) 失败: ${t.message}")
            }
        }
        Log.e(TAG, "所有 DNS 上游均失败，返回空响应")
        return noerrorResponse(parsed.id, parsed.question)
    }

    // ---------------------------------------------------------------- 上游

    private fun plainExchange(server: DnsUpstream, query: ByteArray): ByteArray? {
        val socket = DatagramSocket()
        SocketProtector.protect(socket)
        try {
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            val address = InetAddress.getByName(server.host)
            socket.send(DatagramPacket(query, query.size, address, server.port))
            val buf = ByteArray(RECV_BUF)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return buf.copyOfRange(0, packet.length)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun dotExchange(server: DnsUpstream, query: ByteArray): ByteArray? {
        val raw = InsecureTls.trustAllContext().socketFactory.createSocket() as SSLSocket
        SocketProtector.protect(raw)
        var socket: SSLSocket = raw
        runCatching {
            socket.connect(InetSocketAddress(server.host, server.port), UPSTREAM_TIMEOUT_MS)
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            // 信任任意证书：DoT 上游可能是内网/自建 DNS，无法校验
            socket.startHandshake()
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            // RFC 7858：2 字节长度前缀
            output.writeShort(query.size)
            output.write(query)
            output.flush()
            val len = input.readUnsignedShort()
            val resp = ByteArray(len)
            input.readFully(resp)
            socket.close()
            return resp
        }.onFailure { t ->
            runCatching { socket.close() }
            throw t
        }
        return null
    }

    private fun dohExchange(server: DnsUpstream, query: ByteArray): ByteArray? {
        val hostPort = if (server.host.contains(':')) "[${server.host}]" else server.host
        val url = "https://$hostPort:${server.port}/dns-query"
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/dns-message")
            .addHeader("Accept", "application/dns-message")
            .post(query.toRequestBody("application/dns-message".toMediaType()))
            .build()
        okHttp.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "DoH 状态码 ${resp.code} @ $url")
                return null
            }
            val body = resp.body?.bytes() ?: return null
            if (body.isEmpty()) return null
            return body
        }
    }

    // ---------------------------------------------------------------- 响应构造

    private fun emptyResponse(id: Int, qdcount: Int, question: ByteArray): ByteArray {
        val resp = ByteArray(12 + question.size)
        write16(resp, 0, id)
        write16(resp, 2, 0x8180) // QR + RD + RA, NOERROR
        write16(resp, 4, qdcount)
        write16(resp, 6, 0)
        write16(resp, 8, 0)
        write16(resp, 10, 0)
        System.arraycopy(question, 0, resp, 12, question.size)
        return resp
    }

    private fun noerrorResponse(id: Int, question: ByteArray): ByteArray =
        emptyResponse(id, if (question.isEmpty()) 0 else 1, question)

    private fun hostsResponse(parsed: ParsedQuery, ip: String): ByteArray {
        // 查询类型取问题段末 2 字节（A=1 / AAAA=28）
        val qtype = if (parsed.question.size >= 4) read16(parsed.question, parsed.question.size - 4) else 1
        val rdata = try {
            InetAddress.getByName(ip).address
        } catch (t: Throwable) {
            Log.e(TAG, "hosts IP 解析失败: $ip", t)
            return emptyResponse(parsed.id, 1, parsed.question)
        }
        val isV6 = ip.contains(':')
        val type = if (isV6) 28 else 1 // AAAA / A
        // 规则 IP 与查询类型不匹配（如 AAAA 查询命中 IPv4 规则）：返回 NOERROR 空答
        if (qtype != type) {
            Log.i(TAG, "hosts 类型不匹配，空答: ${parsed.domain} $ip (qtype=$qtype)")
            return emptyResponse(parsed.id, 1, parsed.question)
        }
        val resp = ByteArray(12 + parsed.question.size + 12 + rdata.size)
        write16(resp, 0, parsed.id)
        write16(resp, 2, 0x8180)
        write16(resp, 4, 1) // qdcount
        write16(resp, 6, 1) // ancount
        System.arraycopy(parsed.question, 0, resp, 12, parsed.question.size)
        var pos = 12 + parsed.question.size
        resp[pos] = 0xC0.toByte(); resp[pos + 1] = 0x0C // 指针指向问题段起始
        pos += 2
        write16(resp, pos, type); pos += 2
        write16(resp, pos, 1); pos += 2 // class IN
        write32(resp, pos, 300); pos += 4 // ttl
        write16(resp, pos, rdata.size); pos += 2
        System.arraycopy(rdata, 0, resp, pos, rdata.size)
        return resp
    }

    private fun read16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun write16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun write32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
    }
}