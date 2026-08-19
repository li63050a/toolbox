package com.toolbox.app.vpn.mitm

import android.content.Context
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.InsecureTls
import com.toolbox.app.vpn.SniForwarder
import com.toolbox.app.vpn.SocketProtector
import com.toolbox.app.vpn.VpnConfigStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName
import kotlin.concurrent.thread

/**
 * MITM 终结服务器：
 * 1. 读 App ClientHello 取 SNI
 * 2. 用 CertManager 证书完成与 App 的 TLS 服务端握手（强制 HTTP/1.1，不协商 ALPN）
 * 3. 对外以 trust-all + 伪装 SNI 建立 TLS
 * 4. HTTP/1.1 双向转发（响应注入 Connection: close）
 */
internal class MitmServer(private val context: Context) {

    companion object {
        private const val TAG = "MITM"
        private const val PORT = 8443
        private const val IO_TIMEOUT_MS = 30_000
        private const val MAX_HEAD_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val BODY_BUF = 16 * 1024
        private const val KEY_PASSWORD = "toolbox"
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val connections = ConcurrentLinkedQueue<Socket>()

    fun start() {
        if (running) return
        running = true
        thread(name = "mitm-accept") {
            runCatching {
                serverSocket = ServerSocket().also { ss ->
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress("127.0.0.1", PORT), 32)
                }
                Log.i(TAG, "MITM 监听 127.0.0.1:$PORT")
                while (running) {
                    val socket = serverSocket?.accept() ?: break
                    connections.add(socket)
                    thread(name = "mitm-conn") { handleConnection(socket) }
                }
            }.onFailure { t ->
                if (running) Log.e(TAG, "MITM accept 循环退出: ${t.message}", t)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    // ---------------------------------------------------------------- 连接处理

    private fun handleConnection(plain: Socket) {
        try {
            plain.soTimeout = IO_TIMEOUT_MS
            val clientHello = readClientHello(plain) ?: run {
                Log.w(TAG, "连接首包不是 ClientHello，关闭")
                return
            }
            val hostname = SniForwarder.parseSni(clientHello) ?: run {
                Log.w(TAG, "ClientHello 无 SNI，关闭")
                return
            }
            Log.i(TAG, "MITM 接管 $hostname")

            val (key, cert) = CertManager.getServerCertChain(context, hostname)

            val appSsl = serverSideSsl(plain, key, cert, hostname)
            appSsl.useClientMode = false
            try {
                appSsl.startHandshake()
                Log.i(TAG, "与 App 握手完成: $hostname")
            } catch (t: Throwable) {
                Log.w(TAG, "与 App 握手失败（App 拒绝证书）: ${t.message}")
                return
            }

            val upstream = outboundTls(hostname) ?: run {
                Log.w(TAG, "出站连接失败: $hostname")
                send502(appSsl)
                return
            }
            val fakeSni = VpnConfigStore.config.value.spoof.fakeSni
            Log.i(TAG, "出站 SNI 伪装 $hostname -> $fakeSni")

            relayHttp(appSsl, upstream)
        } catch (t: Throwable) {
            Log.w(TAG, "MITM 连接处理异常: ${t.message}")
        } finally {
            runCatching { plain.close() }
            connections.remove(plain)
        }
    }

    private fun send502(appSsl: SSLSocket) {
        runCatching {
            val out = BufferedOutputStream(appSsl.getOutputStream())
            out.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray())
            out.flush()
        }
    }

    /** 读取 App 侧第一个 TLS 记录（应为首个 ClientHello） */
    private fun readClientHello(socket: Socket): ByteArray? {
        val input = socket.getInputStream()
        val head = ByteArray(5)
        if (!readFully(input, head, 0, 5)) return null
        val type = head[0].toInt() and 0xFF
        if (type != 0x16) return null // 不是握手记录
        val len = ((head[3].toInt() and 0xFF) shl 8) or (head[4].toInt() and 0xFF)
        if (len <= 0 || len > 32768) return null
        val record = ByteArray(5 + len)
        System.arraycopy(head, 0, record, 0, 5)
        if (!readFully(input, record, 5, len)) return null
        return record
    }

    private fun serverSideSsl(plain: Socket, key: java.security.PrivateKey, cert: X509Certificate, hostname: String): SSLSocket {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("srv", key, KEY_PASSWORD.toCharArray(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, KEY_PASSWORD.toCharArray())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, SecureRandom())
        val ssl = ctx.socketFactory.createSocket(plain, hostname, PORT, true) as SSLSocket
        ssl.soTimeout = IO_TIMEOUT_MS
        return ssl
    }

    /**
     * 出站 TLS。
     * 不安全说明：必须 trust-all —— SNI 已伪装为 fakeSni，服务器证书与伪装名
     * 不匹配；此处鉴权职责由用户安装的 Toolbox CA 转移到 App 侧（App 校验我们
     * 的证书），出站无需再校验。也不设置 endpoint identification（无主机名校验）。
     */
    private fun outboundTls(realHost: String): SSLSocket? {
        return runCatching {
            val raw = Socket()
            SocketProtector.protect(raw)
            raw.connect(InetSocketAddress(realHost, 443), IO_TIMEOUT_MS.toInt())
            raw.soTimeout = IO_TIMEOUT_MS
            val ctx = InsecureTls.trustAllContext()
            val ssl = ctx.socketFactory.createSocket(raw, realHost, 443, true) as SSLSocket
            ssl.useClientMode = true
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(VpnConfigStore.config.value.spoof.fakeSni))
            ssl.sslParameters = params
            ssl.startHandshake()
            ssl
        }.getOrElse { t ->
            Log.e(TAG, "出站 TLS 失败 $realHost: ${t.message}", t)
            null
        }
    }

    // ---------------------------------------------------------------- HTTP/1.1 转发

    private data class HeadInfo(val contentLength: Int, val chunked: Boolean)

    private fun relayHttp(appSsl: SSLSocket, upstream: SSLSocket) {
        val appIn = BufferedInputStream(appSsl.getInputStream())
        val appOut = BufferedOutputStream(appSsl.getOutputStream())
        val upIn = BufferedInputStream(upstream.getInputStream())
        val upOut = BufferedOutputStream(upstream.getOutputStream())
        try {
            // 1) 请求：行 + 头 + body
            val requestHead = readHead(appIn) ?: return
            upOut.write(requestHead)
            upOut.flush()
            val reqInfo = analyzeHead(requestHead)
            if (reqInfo.chunked) {
                if (!copyChunked(appIn, upOut)) return
            } else if (reqInfo.contentLength > 0) {
                if (!copyN(appIn, upOut, reqInfo.contentLength)) return
            }
            upOut.flush()

            // 2) 响应：可能含 100-continue 中间响应
            while (true) {
                val respHead = readHead(upIn) ?: return
                val respInfo = analyzeHead(respHead)
                val status = statusCode(respHead)
                writeModifiedResponse(appOut, respHead)
                if (respInfo.chunked) {
                    if (!copyChunked(upIn, appOut)) return
                } else if (respInfo.contentLength > 0) {
                    if (!copyN(upIn, appOut, respInfo.contentLength)) return
                } else if (status != 100) {
                    copyUntilEof(upIn, appOut) // Connection: close 界定
                    break
                }
                appOut.flush()
                if (status != 100) break
            }
        } catch (t: SocketTimeoutException) {
            Log.w(TAG, "HTTP 转发超时")
        } catch (t: Throwable) {
            Log.w(TAG, "HTTP 转发异常: ${t.message}")
        } finally {
            runCatching { upstream.close() }
            runCatching { appSsl.close() }
        }
    }

    private fun analyzeHead(head: ByteArray): HeadInfo {
        val text = String(head, Charsets.ISO_8859_1)
        var contentLength = -1
        var chunked = false
        for (line in text.split("\r\n")) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            if (key == "content-length") {
                contentLength = runCatching { value.toInt() }.getOrDefault(-1)
            } else if (key == "transfer-encoding" && value.contains("chunked", ignoreCase = true)) {
                chunked = true
            }
        }
        return HeadInfo(contentLength, chunked)
    }

    private fun statusCode(head: ByteArray): Int {
        val nl = indexOf(head, '\n')
        if (nl < 12) return 0
        val line = String(head, 0, nl - 1, Charsets.ISO_8859_1)
        val parts = line.split(' ')
        return runCatching { parts[1].toInt() }.getOrDefault(0)
    }

    /** 转发响应头，注入 "Connection: close"（已有则替换） */
    private fun writeModifiedResponse(appOut: OutputStream, head: ByteArray) {
        val firstNl = indexOf(head, '\n')
        if (firstNl < 0) {
            appOut.write(head)
            appOut.write("\r\n".toByteArray())
            return
        }
        appOut.write(head, 0, firstNl + 1) // 状态行
        var pos = firstNl + 1
        var injected = false
        while (pos < head.size) {
            val nl = indexOfAt(head, '\n', pos)
            val end = if (nl < 0) head.size else nl + 1
            val lineLen = end - pos
            if (lineLen <= 2) {
                val b0 = if (lineLen >= 1) head[pos].toInt() else -1
                val b1 = if (lineLen >= 2) head[pos + 1].toInt() else -1
                if ((b0 == '\n'.code) || (b0 == '\r'.code && b1 == '\n'.code)) break // 头结束空行
            }
            val line = String(head, pos, lineLen, Charsets.ISO_8859_1)
            val idx = line.indexOf(':')
            val key = if (idx > 0) line.substring(0, idx).trim().lowercase() else ""
            when (key) {
                "connection" -> {
                    appOut.write("Connection: close\r\n".toByteArray())
                    injected = true
                }
                "proxy-connection", "keep-alive" -> {} // 跳过，由 Connection: close 决定
                else -> appOut.write(line.toByteArray(Charsets.ISO_8859_1))
            }
            pos = end
        }
        if (!injected) appOut.write("Connection: close\r\n".toByteArray())
        appOut.write("\r\n".toByteArray())
    }

    // ---------------------------------------------------------------- 底层 IO

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, off + read, len - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    /** 读请求/响应头到 \r\n\r\n（或 \n\n），上限 MAX_HEAD_BYTES */
    private fun readHead(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(1024)
        var last = IntArray(3) { -1 }
        while (out.size() < MAX_HEAD_BYTES) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) return null
                return out.toByteArray() // EOF 截断
            }
            // 检测末尾 \r\n\r\n
            val crlf = last[2] == '\r'.code && last[1] == '\n'.code && last[0] == '\r'.code && b == '\n'.code
            last[2] = last[1]
            last[1] = last[0]
            last[0] = b
            out.write(b)
            if (crlf) return out.toByteArray()
        }
        Log.w(TAG, "头部超过上限，关闭连接")
        return null
    }

    /** 读一行（到 \n 含），上限 MAX_LINE_BYTES */
    private fun readLine(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(64)
        while (out.size() < MAX_LINE_BYTES) {
            val b = input.read()
            if (b < 0) {
                if (out.size() == 0) return null
                return out.toByteArray()
            }
            out.write(b)
            if (b == '\n'.code) return out.toByteArray()
        }
        return null
    }

    /** 转发 len 字节，成功返回 true */
    private fun copyN(input: InputStream, output: OutputStream, len: Int): Boolean {
        val buf = ByteArray(BODY_BUF)
        var remaining = len
        while (remaining > 0) {
            val n = input.read(buf, 0, minOf(BODY_BUF, remaining))
            if (n < 0) return false
            output.write(buf, 0, n)
            remaining -= n
        }
        return true
    }

    /** chunked 转发（按 chunk 边界拆读，原样转发） */
    private fun copyChunked(input: InputStream, output: OutputStream): Boolean {
        while (true) {
            val line = readLine(input) ?: return false
            output.write(line)
            val sizeText = String(line, Charsets.ISO_8859_1).trim()
            val size = runCatching { sizeText.substringBefore(';').trim().toLong(16) }.getOrDefault(0L)
            if (size == 0L) {
                while (true) {
                    val trailer = readLine(input) ?: return false
                    output.write(trailer)
                    val t = String(trailer, Charsets.ISO_8859_1)
                    if (t == "\r\n" || t == "\n") return true
                }
            }
            if (size > MAX_HEAD_BYTES.toLong() * 16) {
                Log.w(TAG, "chunk 过大，关闭")
                return false
            }
            if (!copyN(input, output, size.toInt())) return false
            val crlf = readLine(input) ?: return false
            output.write(crlf)
        }
    }

    /** 无长度响应：读到 EOF */
    private fun copyUntilEof(input: InputStream, output: OutputStream) {
        val buf = ByteArray(BODY_BUF)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            output.write(buf, 0, n)
        }
    }

    private fun indexOf(head: ByteArray, c: Char): Int {
        for (i in head.indices) if (head[i].toInt() == c.code) return i
        return -1
    }

    private fun indexOfAt(head: ByteArray, c: Char, from: Int): Int {
        for (i in from until head.size) if (head[i].toInt() == c.code) return i
        return -1
    }
}