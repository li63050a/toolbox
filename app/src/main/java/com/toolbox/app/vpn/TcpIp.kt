package com.toolbox.app.vpn

import com.toolbox.app.log.Log
import com.toolbox.app.vpn.mitm.MitmProxy
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Random
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * TCP/IP 中继核心（NetGuard 式）：
 * 读 tun 帧 → 解析 IPv4/IPv6 → TCP/UDP 分派 → 本地 socket 直连目标。
 * TCP 每 5 元组一个会话（本地握手 + 双向转发），UDP 每 5 元组一个 DatagramSocket。
 */
object TcpIp {

    const val TUN_MTU = 1500

    private const val TAG = "RP"
    private const val READ_BUF = 65535
    private const val TCP_CONNECT_TIMEOUT_MS = 8000
    private const val TCP_IDLE_MS = 5 * 60_000L
    private const val UDP_IDLE_MS = 60_000L
    private const val QUEUE_CAPACITY = 1024
    private const val WINDOW = 65535
    private const val TTL = 64
    private const val MITM_HOST = "127.0.0.1"
    private const val MITM_PORT = 8443
    private const val DNS_PORT = 53
    private const val TUN_DNS_PORT = 5353

    private const val FLAG_FIN = 0x01
    private const val FLAG_SYN = 0x02
    private const val FLAG_RST = 0x04
    private const val FLAG_PSH = 0x08
    private const val FLAG_ACK = 0x10

    @Volatile private var running = false
    @Volatile private var tunOut: OutputStream? = null
    private val writeLock = Object()
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()
    private val txAccum = AtomicLong(0)
    private val rxAccum = AtomicLong(0)

    /**
     * 阻塞式主循环，异常抛出由调用方（VpnService）处理。返回即隧道结束。
     */
    fun run(tunIn: InputStream, tunOut: OutputStream) {
        this.tunOut = tunOut
        running = true
        txAccum.set(0)
        rxAccum.set(0)
        thread(name = "rp-counters", isDaemon = true) { counterLoop() }
        thread(name = "rp-cleaner", isDaemon = true) { cleanerLoop() }
        val buf = ByteArray(READ_BUF)
        try {
            while (running) {
                val n = tunIn.read(buf)
                if (n <= 0) {
                    Log.w(TAG, "tun 读到 EOF")
                    break
                }
                txAccum.addAndGet(n.toLong())
                var off = 0
                // 一帧可能含多个 IP 包
                while (off + 20 <= n) {
                    val consumed = handlePacket(buf, off, n - off)
                    if (consumed <= 0) break
                    off += consumed
                }
            }
        } catch (t: Throwable) {
            if (running) Log.e(TAG, "tun 读取异常", t)
        } finally {
            shutdown()
        }
    }

    fun shutdown() {
        running = false
        sessions.values.forEach { it.closeSession("VPN 停止") }
        sessions.clear()
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()
        txAccum.set(0)
        rxAccum.set(0)
    }

    // ---------------------------------------------------------------- 计数

    private fun counterLoop() {
        while (running) {
            try {
                Thread.sleep(1000)
            } catch (t: InterruptedException) {
                break
            }
            val t = txAccum.getAndSet(0)
            val r = rxAccum.getAndSet(0)
            if (t != 0L || r != 0L) VpnController.addTraffic(t, r)
        }
    }

    private fun cleanerLoop() {
        while (running) {
            try {
                Thread.sleep(30_000)
            } catch (t: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            sessions.values.forEach { s ->
                if (now - s.lastActivity.get() > TCP_IDLE_MS) s.closeSession("空闲超时")
            }
            // UDP 会话由 SO_TIMEOUT 自行退出
        }
    }

    // ---------------------------------------------------------------- 帧解析

    private fun handlePacket(buf: ByteArray, off: Int, remaining: Int): Int {
        val version = (buf[off].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> handleIpv4(buf, off, remaining)
            6 -> handleIpv6(buf, off, remaining)
            else -> 0
        }
    }

    private fun handleIpv4(buf: ByteArray, off: Int, remaining: Int): Int {
        val ihl = (buf[off].toInt() and 0x0F) * 4
        if (ihl < 20 || remaining < ihl) return 0
        val total = read16(buf, off + 2)
        if (total < ihl || total > remaining) return 0
        val proto = buf[off + 9].toInt() and 0xFF
        val srcIp = buf.copyOfRange(off + 12, off + 16)
        val dstIp = buf.copyOfRange(off + 16, off + 20)
        when (proto) {
            6 -> handleTcp(buf, off + ihl, total - ihl, srcIp, dstIp)
            17 -> handleUdp(buf, off + ihl, total - ihl, srcIp, dstIp)
        }
        return total
    }

    private fun handleIpv6(buf: ByteArray, off: Int, remaining: Int): Int {
        if (remaining < 40) return 0
        val payloadLen = read16(buf, off + 4)
        val total = 40 + payloadLen
        if (total > remaining) return 0
        val next = buf[off + 6].toInt() and 0xFF
        if (next != 6 && next != 17) return total // 不支持扩展头，丢弃
        val srcIp = buf.copyOfRange(off + 8, off + 24)
        val dstIp = buf.copyOfRange(off + 24, off + 40)
        if (next == 6) handleTcp(buf, off + 40, payloadLen, srcIp, dstIp)
        else handleUdp(buf, off + 40, payloadLen, srcIp, dstIp)
        return total
    }

    // ---------------------------------------------------------------- TCP

    /** @param segLen TCP 段长度（含 TCP 头） */
    private fun handleTcp(buf: ByteArray, segOff: Int, segLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (segLen < 20) return
        val srcPort = read16(buf, segOff)
        val dstPort = read16(buf, segOff + 2)
        val seq = read32(buf, segOff + 4)
        val dataOffset = ((buf[segOff + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || dataOffset > segLen) return
        val flags = buf[segOff + 13].toInt() and 0xFF
        val payloadLen = segLen - dataOffset
        val key = "${ipString(srcIp)}:$srcPort->${ipString(dstIp)}:$dstPort"

        if ((flags and FLAG_RST) != 0) {
            sessions[key]?.closeSession("对端 RST")
            return
        }

        if ((flags and FLAG_SYN) != 0 && (flags and FLAG_ACK) == 0) {
            val s = sessions.computeIfAbsent(key) { TcpSession(key, seq, srcIp, srcPort, dstIp, dstPort) }
            s.touch()
            s.sendSynAck()
            s.startWorker()
            return
        }

        val s = sessions[key]
        if (s == null) {
            if (payloadLen > 0) {
                // 未知连接数据段：回 RST 让对端快速复位
                writeTcpSegment(
                    dstIp, srcIp, dstPort, srcPort, 0L, seq + payloadLen,
                    FLAG_RST or FLAG_ACK, null, 0, 0
                )
            }
            return
        }

        if ((flags and FLAG_FIN) != 0) {
            s.touch()
            s.onClientFin()
            return
        }

        if (payloadLen > 0 && (flags and FLAG_SYN) == 0) {
            s.touch()
            s.enqueue(buf.copyOfRange(segOff + dataOffset, segOff + segLen))
        }
    }

    private fun sendSynAck(s: TcpSession) {
        synchronized(s.lock) {
            if (s.closed) return
            writeTcpSegment(
                s.dstIp, s.srcIp, s.dstPort, s.srcPort,
                s.serverSeq, s.clientSynSeq + 1, FLAG_SYN or FLAG_ACK, null, 0, 0
            )
            s.serverSeq += 1
        }
    }

    private fun sendAckFor(s: TcpSession) {
        synchronized(s.lock) {
            if (s.closed) return
            writeTcpSegment(
                s.dstIp, s.srcIp, s.dstPort, s.srcPort,
                s.serverSeq, s.clientSeq, FLAG_ACK, null, 0, 0
            )
        }
    }

    private fun sendClientFin(s: TcpSession) {
        synchronized(s.lock) {
            if (s.closed) return
            writeTcpSegment(
                s.dstIp, s.srcIp, s.dstPort, s.srcPort,
                s.serverSeq, s.clientSeq, FLAG_FIN or FLAG_ACK, null, 0, 0
            )
        }
    }

    private fun sendRst(s: TcpSession) {
        synchronized(s.lock) {
            if (s.closed) return
            writeTcpSegment(
                s.dstIp, s.srcIp, s.dstPort, s.srcPort,
                s.serverSeq, s.clientSeq, FLAG_RST or FLAG_ACK, null, 0, 0
            )
        }
    }

    private fun shutdownOutput(s: TcpSession) {
        runCatching { s.socket?.shutdownOutput() }.onFailure { t ->
            if (s.connected) Log.w(TAG, "半关闭输出失败 ${s.key}: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- TCP worker

    private class TcpSession(
        val key: String,
        val clientSynSeq: Long,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int
    ) {
        val lock = Object()
        val pending = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
        var serverSeq: Long = randomSeq()
        var clientSeq: Long = clientSynSeq + 1 // 期望的下一序号
        val lastActivity = AtomicLong(System.currentTimeMillis())
        @Volatile var closed = false
        @Volatile var clientFinished = false
        @Volatile var serverFinished = false
        @Volatile var connected = false
        @Volatile var mitmActive = false
        @Volatile var switching = false
        @Volatile var firstPayloadSeen = false
        var fallbackAttempted = false
        @Volatile var socket: Socket? = null
        @Volatile var outStream: OutputStream? = null
        @Volatile var worker: Thread? = null
        @Volatile var readThread: Thread? = null

        fun touch() = lastActivity.set(System.currentTimeMillis())

        fun startWorker() {
            synchronized(lock) {
                if (closed || (worker != null && worker!!.isAlive)) return
            }
            val t = thread(name = "rp-tcp-worker", isDaemon = true) { workerLoop(this) }
            worker = t
        }

        fun enqueue(data: ByteArray) {
            synchronized(lock) {
                if (closed) return
                clientSeq += data.size
                if (!pending.offer(data)) {
                    Log.e(TAG, "会话 $key 发送队列满，强制关闭")
                    closeSession("队列溢出")
                    return
                }
            }
            sendAckFor(this)
        }

        fun onClientFin() {
            synchronized(lock) {
                if (closed) return
                clientFinished = true
            }
            sendAckFor(this)
            shutdownOutput(this)
            if (serverFinished) closeSession("双向 FIN")
        }

        fun sendSynAck() = sendSynAck(this)

        fun closeSession(reason: String) {
            synchronized(lock) {
                if (closed) return
                closed = true
            }
            Log.i(TAG, "会话关闭 ${key}: $reason")
            runCatching { socket?.close() }
            worker?.interrupt()
            sessions.remove(key, this)
        }
    }

    private fun workerLoop(s: TcpSession) {
        try {
            connectInitial(s)
            s.connected = true
            startReadThread(s)
            while (!s.closed) {
                val data = s.pending.poll(500, TimeUnit.MILLISECONDS) ?: continue
                if (s.clientFinished) continue // 客户端已 FIN，丢弃后到数据
                s.touch()
                handleClientPayload(s, data)
            }
        } catch (t: Throwable) {
            if (!s.closed) {
                Log.e(TAG, "会话 ${s.key} worker 异常: ${t.message}", t)
                sendRst(s)
                s.closeSession("worker 异常")
            }
        }
    }

    private fun connectInitial(s: TcpSession) {
        val cfg = VpnConfigStore.config.value
        val targetHost: String
        val targetPort: Int
        val isMitm: Boolean
        val isDns: Boolean
        when {
            s.dstPort == DNS_PORT -> { targetHost = "127.0.0.1"; targetPort = TUN_DNS_PORT; isMitm = false; isDns = true }
            MitmProxy.isMitmDesired(s.dstPort, cfg) -> { targetHost = MITM_HOST; targetPort = MITM_PORT; isMitm = true; isDns = false }
            else -> { targetHost = ipString(s.dstIp); targetPort = s.dstPort; isMitm = false; isDns = false }
        }
        Log.i(TAG, "TCP 会话 ${s.key} -> $targetHost:$targetPort${if (isMitm) " (MITM)" else ""}")
        val sock = Socket()
        SocketProtector.protect(sock)
        sock.connect(InetSocketAddress(targetHost, targetPort), TCP_CONNECT_TIMEOUT_MS)
        runCatching { sock.tcpNoDelay = true }
        s.socket = sock
        val rawOut = BufferedOutputStream(sock.getOutputStream())
        s.outStream = if (isMitm || isDns) rawOut else SniForwarder(rawOut, cfg.frag, cfg.spoof)
        s.mitmActive = isMitm
    }

    private fun handleClientPayload(s: TcpSession, data: ByteArray) {
        if (!s.firstPayloadSeen) {
            s.firstPayloadSeen = true
            val cfg = VpnConfigStore.config.value
            // 模式 A 失败自动升级：首个入站字节为 TLS Alert → 重连到本地 MITM，仅一次
            if (!s.fallbackAttempted && !s.mitmActive && s.dstPort == 443 &&
                (data[0].toInt() and 0xFF) == 0x15 &&
                cfg.spoof.mitmFallback && !cfg.mitmEnabled
            ) {
                Log.i(TAG, "入站 TLS Alert(0x15)，MITM 回落重连 ${s.key}")
                s.fallbackAttempted = true
                reconnectToMitm(s)
            }
        }
        val out = s.outStream
        if (out == null) {
            s.closeSession("无输出流")
            return
        }
        out.write(data)
    }

    private fun reconnectToMitm(s: TcpSession) {
        s.switching = true
        runCatching { s.socket?.close() } // 旧读线程将静默退出
        val sock = Socket()
        SocketProtector.protect(sock)
        sock.connect(InetSocketAddress(MITM_HOST, MITM_PORT), TCP_CONNECT_TIMEOUT_MS)
        runCatching { sock.tcpNoDelay = true }
        s.socket = sock
        s.outStream = BufferedOutputStream(sock.getOutputStream())
        s.mitmActive = true
        s.switching = false
        startReadThread(s)
        Log.i(TAG, "MITM 回落完成 ${s.key}")
    }

    private fun startReadThread(s: TcpSession) {
        val sock = s.socket ?: return
        val t = thread(name = "rp-tcp-in", isDaemon = true) {
            val buf = ByteArray(READ_BUF)
            while (!s.closed) {
                try {
                    val n = sock.getInputStream().read(buf)
                    if (n < 0) {
                        onServerEof(s)
                        return@thread
                    }
                    if (n > 0) writeServerData(s, buf, 0, n)
                } catch (t: Throwable) {
                    if (!s.closed && !s.switching) {
                        Log.w(TAG, "会话 ${s.key} 读线程异常: ${t.message}")
                        s.closeSession("读线程异常")
                    }
                    return@thread
                }
            }
        }
        s.readThread = t
    }

    private fun writeServerData(s: TcpSession, buf: ByteArray, off: Int, len: Int) {
        synchronized(s.lock) {
            if (s.closed) return
            s.touch()
            val seq = s.serverSeq
            val ack = s.clientSeq
            s.serverSeq += len
            writeTcpSegment(
                s.dstIp, s.srcIp, s.dstPort, s.srcPort,
                seq, ack, FLAG_ACK or FLAG_PSH, buf, off, len
            )
        }
    }

    private fun onServerEof(s: TcpSession) {
        synchronized(s.lock) {
            if (s.closed || s.serverFinished) return
            s.serverFinished = true
            s.touch()
            sendClientFin(s)
        }
        // 等待对端 FIN（有界），然后收尾
        var waited = 0L
        while (waited < 8000 && !s.closed && !s.clientFinished) {
            try {
                Thread.sleep(50)
            } catch (t: InterruptedException) {
                return
            }
            waited += 50
        }
        s.closeSession(if (s.clientFinished) "双向 FIN" else "对端 FIN 超时")
    }

    // ---------------------------------------------------------------- UDP

    private class UdpSession(
        val key: String,
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int
    ) {
        val ds = DatagramSocket()
        val lastActivity = AtomicLong(System.currentTimeMillis())
        @Volatile var closed = false

        fun start() {
            SocketProtector.protect(ds)
            val target = if (dstPort == DNS_PORT) {
                // DNS 特例：转发到本地 DNS 代理
                InetSocketAddress("127.0.0.1", TUN_DNS_PORT)
            } else {
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            }
            runCatching { ds.connect(target) } // connect 后只接收该地址的回应
            ds.soTimeout = UDP_IDLE_MS.toInt() // 60s 无收包由超时退出清理
            thread(name = "rp-udp-in", isDaemon = true) { readLoop() }
        }

        fun send(data: ByteArray) {
            lastActivity.set(System.currentTimeMillis())
            runCatching {
                ds.send(DatagramPacket(data, data.size))
            }.onFailure { t ->
                Log.e(TAG, "UDP 发送失败 $key: ${t.message}", t)
                close()
            }
        }

        fun readLoop() {
            val buf = ByteArray(READ_BUF)
            while (!closed) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    ds.receive(packet)
                } catch (t: SocketTimeoutException) {
                    close() // SO_TIMEOUT 60s：清理空闲会话
                    return
                } catch (t: SocketException) {
                    if (!closed) Log.w(TAG, "UDP 接收异常 $key: ${t.message}")
                    return
                } catch (t: Throwable) {
                    Log.e(TAG, "UDP 接收失败 $key", t)
                    return
                }
                lastActivity.set(System.currentTimeMillis())
                val data = buf.copyOfRange(0, packet.length)
                writeUdpSegment(
                    dstIp, srcIp, dstPort, srcPort, data, 0, data.size
                )
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { ds.close() }
            udpSessions.remove(key, this)
        }
    }

    private fun handleUdp(buf: ByteArray, segOff: Int, segLen: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (segLen < 8) return
        val srcPort = read16(buf, segOff)
        val dstPort = read16(buf, segOff + 2)
        val payloadLen = segLen - 8
        val key = "${ipString(srcIp)}:$srcPort->${ipString(dstIp)}:$dstPort"
        var s = udpSessions[key]
        if (s == null) {
            s = UdpSession(key, srcIp, srcPort, dstIp, dstPort)
            udpSessions[key] = s
            s.start()
        }
        s.send(buf.copyOfRange(segOff + 8, segOff + segLen))
    }

    // ---------------------------------------------------------------- 封包写出

    private fun writeTun(frame: ByteArray, len: Int) {
        synchronized(writeLock) {
            val out = tunOut ?: return
            try {
                out.write(frame, 0, len)
                out.flush()
                rxAccum.addAndGet(len.toLong())
            } catch (t: Throwable) {
                Log.e(TAG, "tun 写入失败", t)
                running = false
            }
        }
    }

    private fun writeTcpSegment(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray?, payloadOff: Int, payloadLen: Int
    ) {
        val isV6 = srcIp.size == 16
        val hdrLen = if (isV6) 40 else 20
        val tcpLen = 20 + payloadLen
        val total = hdrLen + tcpLen
        val frame = ByteArray(total)
        if (isV6) {
            frame[0] = 0x60.toByte()
            write16(frame, 4, tcpLen)
            frame[6] = 6
            frame[7] = TTL.toByte()
            System.arraycopy(srcIp, 0, frame, 8, 16)
            System.arraycopy(dstIp, 0, frame, 24, 16)
        } else {
            frame[0] = 0x45.toByte()
            write16(frame, 2, total)
            write16(frame, 4, 0)
            write16(frame, 6, 0x4000) // DF
            frame[8] = TTL.toByte()
            frame[9] = 6
            System.arraycopy(srcIp, 0, frame, 12, 4)
            System.arraycopy(dstIp, 0, frame, 16, 4)
        }
        val tcpOff = hdrLen
        write16(frame, tcpOff, srcPort)
        write16(frame, tcpOff + 2, dstPort)
        write32(frame, tcpOff + 4, seq)
        write32(frame, tcpOff + 8, ack)
        frame[tcpOff + 12] = 0x50.toByte() // 无选项
        frame[tcpOff + 13] = flags.toByte()
        write16(frame, tcpOff + 14, WINDOW)
        write16(frame, tcpOff + 18, 0)
        if (payloadLen > 0 && payload != null) {
            System.arraycopy(payload, payloadOff, frame, tcpOff + 20, payloadLen)
        }
        // TCP 校验和：伪头 + 段（含 IPv6）
        var sum = 0L
        var i = 0
        while (i < srcIp.size) { sum += (read16OrZero(srcIp, i)); i += 2 }
        i = 0
        while (i < dstIp.size) { sum += read16OrZero(dstIp, i); i += 2 }
        if (isV6) {
            sum += (tcpLen ushr 16) and 0xFFFF
            sum += tcpLen and 0xFFFF
        } else {
            sum += tcpLen
        }
        sum += 6 // protocol TCP
        sum += sumRange(frame, tcpOff, tcpLen)
        write16(frame, tcpOff + 16, foldChecksum(sum))
        if (!isV6) {
            write16(frame, 10, foldChecksum(sumRange(frame, 0, hdrLen)))
        }
        writeTun(frame, total)
    }

    private fun writeUdpSegment(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray, payloadOff: Int, payloadLen: Int
    ) {
        val isV6 = srcIp.size == 16
        val hdrLen = if (isV6) 40 else 20
        val udpLen = 8 + payloadLen
        val total = hdrLen + udpLen
        val frame = ByteArray(total)
        if (isV6) {
            frame[0] = 0x60.toByte()
            write16(frame, 4, udpLen)
            frame[6] = 17
            frame[7] = TTL.toByte()
            System.arraycopy(srcIp, 0, frame, 8, 16)
            System.arraycopy(dstIp, 0, frame, 24, 16)
        } else {
            frame[0] = 0x45.toByte()
            write16(frame, 2, total)
            write16(frame, 4, 0)
            write16(frame, 6, 0x4000)
            frame[8] = TTL.toByte()
            frame[9] = 17
            System.arraycopy(srcIp, 0, frame, 12, 4)
            System.arraycopy(dstIp, 0, frame, 16, 4)
        }
        val udpOff = hdrLen
        write16(frame, udpOff, srcPort)
        write16(frame, udpOff + 2, dstPort)
        write16(frame, udpOff + 4, udpLen)
        write16(frame, udpOff + 6, 0) // 校验和占位
        if (payloadLen > 0) {
            System.arraycopy(payload, payloadOff, frame, udpOff + 8, payloadLen)
        }
        // UDP 校验和（IPv4 可选但计算，IPv6 必须）
        var sum = 0L
        var i = 0
        while (i < srcIp.size) { sum += read16OrZero(srcIp, i); i += 2 }
        i = 0
        while (i < dstIp.size) { sum += read16OrZero(dstIp, i); i += 2 }
        if (isV6) {
            sum += (udpLen ushr 16) and 0xFFFF
            sum += udpLen and 0xFFFF
        } else {
            sum += udpLen
        }
        sum += 17 // protocol UDP
        sum += sumRange(frame, udpOff, udpLen)
        write16(frame, udpOff + 6, foldChecksum(sum))
        if (!isV6) {
            write16(frame, 10, foldChecksum(sumRange(frame, 0, hdrLen)))
        }
        writeTun(frame, total)
    }

    private fun sumRange(buf: ByteArray, off: Int, len: Int): Long {
        var sum = 0L
        var i = off
        var remaining = len
        while (remaining > 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
            remaining -= 2
        }
        if (remaining == 1) sum += (buf[i].toInt() and 0xFF) shl 8
        return sum
    }

    private fun foldChecksum(sum: Long): Int {
        var s = sum
        while (s > 0xFFFF) s = (s and 0xFFFF) + (s ushr 16)
        return ((s.inv()) and 0xFFFFL).toInt()
    }

    private fun read16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun read32(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }

    private fun write16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v shr 8).toByte()
        buf[off + 1] = v.toByte()
    }

    private fun write32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = (v shr 24).toByte()
        buf[off + 1] = (v shr 16).toByte()
        buf[off + 2] = (v shr 8).toByte()
        buf[off + 3] = v.toByte()
    }

    private fun read16OrZero(buf: ByteArray, off: Int): Int {
        if (off + 1 >= buf.size) return (buf[off].toInt() and 0xFF) shl 8
        return ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
    }

    private fun randomSeq(): Long = (Random().nextInt().toLong() and 0xFFFF_FFFFL).let { if (it == 0L) 1L else it }

    private fun ipString(ip: ByteArray): String =
        InetAddress.getByAddress(ip).hostAddress ?: "unknown"
}