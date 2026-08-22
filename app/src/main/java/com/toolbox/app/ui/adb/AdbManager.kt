package com.toolbox.app.ui.adb

import android.content.Context
import com.toolbox.app.log.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "AdbManager"

data class AdbDevice(val serial: String, val state: String) {
    val isConnected: Boolean get() = state == "device"
    val isWireless: Boolean get() = serial.contains(":")
}

data class AdbFile(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long
)

class AdbManager(private val context: Context) {

    private val log = Log
    private val connectionId = AtomicInteger(0)
    private var _selectedDevice: String? = null
    val selectedDevice: String? get() = _selectedDevice

    fun setSelectedDevice(serial: String?) {
        _selectedDevice = serial
    }

    fun refreshDevices(): List<AdbDevice> {
        return try {
            val (cmd, data) = sendToHostDaemon("host:devices")
            if (cmd != "OKAY") return emptyList()
            parseDeviceList(data)
        } catch (e: Exception) {
            log.e(TAG, "refreshDevices failed", e)
            emptyList()
        }
    }

    fun connectDevice(host: String, port: Int = 5555): Result<Unit> {
        return try {
            val (cmd, data) = sendToHostDaemon("host:connect:$host:$port")
            if (cmd == "OKAY") Result.success(Unit)
            else Result.failure(IOException("host:connect failed: $cmd $data"))
        } catch (e: Exception) {
            log.e(TAG, "connectDevice failed", e)
            Result.failure(e)
        }
    }

    fun disconnectDevice(serial: String): Result<Unit> {
        if (!serial.contains(":")) return Result.success(Unit)
        return try {
            val (cmd, _) = sendToHostDaemon("host:disconnect:$serial")
            if (cmd == "OKAY") Result.success(Unit)
            else Result.failure(IOException("disconnect failed: $cmd"))
        } catch (e: Exception) {
            log.e(TAG, "disconnectDevice failed", e)
            Result.failure(e)
        }
    }

    fun shellCommand(cmd: String): Result<String> {
        return try {
            val (replyCmd, conn) = openDeviceConnection("shell:$cmd")
            if (replyCmd != "OKAY") return Result.failure(IOException("shell: $replyCmd"))
            val sb = StringBuilder()
            var readMore = true
            while (readMore) {
                val (c, d) = conn.readFrame()
                when (c) {
                    "CRPT" -> continue
                    "OKAY" -> continue
                    else -> {
                        sb.append(d)
                        if (d.isEmpty()) readMore = false
                    }
                }
            }
            conn.close()
            Result.success(sb.toString().trimEnd())
        } catch (e: Exception) {
            log.e(TAG, "shellCommand failed: $cmd", e)
            Result.failure(e)
        }
    }

    fun listFiles(path: String): Result<List<AdbFile>> {
        return try {
            val (replyCmd, conn) = openDeviceConnection("sync:")
            if (replyCmd != "OKAY") return Result.failure(IOException("sync $replyCmd"))

            conn.sendString("LIST")
            conn.sendString(path)

            val files = mutableListOf<AdbFile>()
            var done = false
            while (!done) {
                val (c, d) = conn.readFrame()
                when (c) {
                    "DENT" -> {
                        val nullIdx = d.indexOf('\u0000')
                        val line = if (nullIdx >= 0) d.take(nullIdx) else d
                        val parts = line.split(" ", limit = 4)
                        if (parts.size >= 4) {
                            val mode = parts[0].toLongOrNull() ?: 0L
                            val size = parts[1].toLongOrNull() ?: 0L
                            val name = parts[3]
                            if (name.isNotEmpty() && name != "." && name != "..") {
                                files.add(AdbFile(name, (mode and 0x10000L) != 0L, size, parts[2].toLongOrNull() ?: 0L))
                            }
                        }
                    }
                    "DONE" -> done = true
                    "FAIL" -> {
                        conn.close()
                        return Result.failure(IOException("sync FAIL: $d"))
                    }
                    else -> {}
                }
            }
            conn.close()
            Result.success(files)
        } catch (e: Exception) {
            log.e(TAG, "listFiles failed: $path", e)
            Result.failure(e)
        }
    }

    fun installApk(localPath: String): Result<String> {
        return try {
            val result = shellCommand("pm install -r \"$localPath\"")
            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun uninstallApp(pkg: String): Result<String> {
        return try {
            shellCommand("pm uninstall $pkg")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAppList(): Result<String> = shellCommand("pm list packages -3")
    fun getBatteryInfo(): Result<String> = shellCommand("dumpsys battery")
    fun getDeviceInfo(): Result<String> = shellCommand("getprop")
    fun listProcesses(): Result<String> = shellCommand("ps")
    fun listPackages(full: Boolean = false): Result<String> = shellCommand(if (full) "pm list packages" else "pm list packages -3")

    private fun sendToHostDaemon(command: String): Pair<String, String> {
        val sock = Socket("127.0.0.1", 5037).apply { soTimeout = 15_000 }
        return try {
            val conn = AdbConnection("host-${connectionId.incrementAndGet()}", sock)
            conn.sendString(command)
            conn.readFrame()
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun openDeviceConnection(target: String): Pair<String, AdbConnection> {
        val sock = Socket("127.0.0.1", 5037).apply { soTimeout = 30_000 }
        val conn = AdbConnection("dev-${connectionId.incrementAndGet()}", sock)
        try {
            val serial = _selectedDevice ?: return Pair("FAIL", conn)
            conn.sendString("host:transport:$serial")
            val transportReply = conn.readFrame()
            if (transportReply.first != "OKAY") {
                conn.close()
                return Pair(transportReply.first, conn)
            }
            conn.sendString(target)
            val reply = conn.readFrame()
            return Pair(reply.first, conn)
        } catch (e: Exception) {
            conn.close()
            throw e
        }
    }

    private fun parseDeviceList(data: String): List<AdbDevice> {
        return data.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("List") }
            .mapNotNull { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size >= 2) AdbDevice(parts[0], parts[1]) else null
            }.toList()
    }
}

class AdbConnection(internal val id: String, internal val socket: Socket) {
    private val input: InputStream = socket.inputStream
    private val output: OutputStream = socket.outputStream
    internal val closed: Boolean get() = socket.isClosed

    fun sendString(msg: String) {
        val command = msg.take(4)
        val payload = msg.drop(4).toByteArray(Charsets.UTF_8)
        sendFrame(command, payload)
    }

    private fun sendFrame(command: String, payload: ByteArray) {
        val len = payload.size
        val hexLen = "%04x".format(len)
        if (hexLen.length != 4) throw IOException("Payload too large: $len")
        output.write(hexLen.toByteArray(Charsets.UTF_8))
        output.write(command.toByteArray(Charsets.UTF_8))
        output.write(payload)
        output.flush()
    }

    fun readFrame(): Pair<String, String> {
        val lenBytes = readFully(4)
        val hexLen = lenBytes.decodeToString()
        val len = hexLen.toLongOrNull(16) ?: throw IOException("Bad frame length: $hexLen")
        if (len > 10 * 1024 * 1024) throw IOException("Frame too large: $len")
        val cmdBytes = readFully(4)
        val cmd = cmdBytes.decodeToString()
        val payload = if (len > 0) readFully(len.toInt()) else ByteArray(0)
        return Pair(cmd, payload.decodeToString())
    }

    private fun readFully(n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val r = input.read(buf, offset, n - offset)
            if (r < 0) throw IOException("Stream ended at $offset/$n")
            offset += r
        }
        return buf
    }

    fun close() {
        runCatching { socket.close() }
    }
}
