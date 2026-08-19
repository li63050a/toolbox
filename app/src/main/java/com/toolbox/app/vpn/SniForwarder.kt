package com.toolbox.app.vpn

import com.toolbox.app.log.Log
import java.io.OutputStream

/**
 * App→远程 方向的 TLS 记录级包装流：
 * 1. SNI 伪装（SniSpoofConfig）：改写 ClientHello 的 SNI 扩展名（长度不变）
 * 2. SNI 分片（SniFragConfig）：TLS 记录按 firstFragment/chunk 分批写出
 * 3. 半记录缓冲等待（最多 2 秒）后原样透传兜底
 */
class SniForwarder(
    private val out: OutputStream,
    private val frag: SniFragConfig,
    private val spoof: SniSpoofConfig
) : OutputStream() {

    private val lock = Object()
    private var pending = ByteArray(0)
    private var firstArrival = 0L

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (pending.isEmpty()) firstArrival = System.currentTimeMillis()
            val joined = ByteArray(pending.size + len)
            System.arraycopy(pending, 0, joined, 0, pending.size)
            System.arraycopy(b, off, joined, pending.size, len)
            pending = joined
            drain(force = false)
        }
    }

    override fun flush() {
        synchronized(lock) { drain(force = true) }
        out.flush()
    }

    override fun close() {
        synchronized(lock) { drain(force = true) }
        out.close()
    }

    private fun drain(force: Boolean) {
        while (pending.size >= 5 && isTlsType(pending[0].toInt() and 0xFF)) {
            val recLen = ((pending[3].toInt() and 0xFF) shl 8) or (pending[4].toInt() and 0xFF)
            if (pending.size < 5 + recLen) break // 半记录：等满
            val rec = ByteArray(5 + recLen)
            System.arraycopy(pending, 0, rec, 0, rec.size)
            pending = pending.copyOfRange(rec.size, pending.size)
            if (pending.isNotEmpty()) firstArrival = System.currentTimeMillis()
            emitRecord(rec)
        }
        if (pending.isEmpty()) return
        val headIsTls = isTlsType(pending[0].toInt() and 0xFF)
        val heldTooLong = System.currentTimeMillis() - firstArrival > MAX_HOLD_MS
        if (!headIsTls || force || heldTooLong) {
            // 非 TLS（或被 hold 超时）→ 原样透传，不阻塞主管线
            out.write(pending, 0, pending.size)
            out.flush()
            pending = ByteArray(0)
        }
    }

    private fun emitRecord(rec: ByteArray) {
        var toSend = rec
        if (spoof.enabled && (rec[0].toInt() and 0xFF) == 0x16 && rec.size >= 6 && (rec[5].toInt() and 0xFF) == 0x01) {
            toSend = maybeRewriteSni(rec)
        }
        if (frag.enabled && isFragType(toSend[0].toInt() and 0xFF)) {
            var pos = 0
            val first = minOf(frag.firstFragment, toSend.size)
            out.write(toSend, 0, first)
            pos = first
            while (pos < toSend.size) {
                if (frag.mode == FragMode.DELAY) {
                    try {
                        Thread.sleep(frag.delayMs.toLong())
                    } catch (t: InterruptedException) {
                        return
                    }
                }
                val n = minOf(frag.chunk, toSend.size - pos)
                out.write(toSend, pos, n)
                pos += n
            }
        } else {
            out.write(toSend, 0, toSend.size)
        }
    }

    // ---------------------------------------------------------------- SNI 改写

    private fun maybeRewriteSni(record: ByteArray): ByteArray {
        val loc = findSniIn(record) ?: return record
        val fake = spoof.fakeSni
        if (fake.length > loc.nameLength) {
            Log.d(TAG, "fakeSni(${fake.length}) 长于原始 SNI(${loc.nameLength})，不改写")
            return record
        }
        val copy = record.copyOf()
        val bytes = fake.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, copy, loc.nameOffset, bytes.size)
        for (i in bytes.size until loc.nameLength) {
            copy[loc.nameOffset + i] = 'a'.code.toByte() // 长度不变，其余补 'a'
        }
        Log.i(TAG, "SNI 改写为 $fake（原始长度 ${loc.nameLength}）")
        return copy
    }

    companion object {
        private const val TAG = "SNI"
        private const val MAX_HOLD_MS = 2000L
        private val TLS_RECORD_TYPES = intArrayOf(0x14, 0x15, 0x16, 0x17) // CCS/Alert/Handshake/AppData
        private val FRAG_TYPES = intArrayOf(0x14, 0x16) // 完整记录与 ChangeCipherSpec 拆分
        private fun isTlsType(b: Int) = TLS_RECORD_TYPES.contains(b)
        private fun isFragType(b: Int) = FRAG_TYPES.contains(b)

        /**
         * 解析完整 ClientHello 记录（0x16 …，第 6 字节 0x01）中的 SNI 主机名。
         * MITM 服务端复用此解析逻辑。
         */
        fun parseSni(clientHello: ByteArray): String? {
            if (clientHello.size < 9) return null
            if ((clientHello[0].toInt() and 0xFF) != 0x16) return null
            if ((clientHello[5].toInt() and 0xFF) != 0x01) return null
            val loc = findSniIn(clientHello) ?: return null
            return String(clientHello, loc.nameOffset, loc.nameLength, Charsets.US_ASCII)
        }

        /** 返回 ClientHello 记录中 SNI 名的位置（绝对偏移与长度） */
        fun findSniIn(record: ByteArray): SniLocation? {
            if (record.size < 9) return null
            val hsLen = ((record[6].toInt() and 0xFF) shl 16) or
                ((record[7].toInt() and 0xFF) shl 8) or (record[8].toInt() and 0xFF)
            val bodyEnd = minOf(record.size, 9 + hsLen)
            var pos = 9 + 2 // ClientHello version
            if (pos + 32 > bodyEnd) return null
            pos += 32 // random
            if (pos + 1 > bodyEnd) return null
            val sidLen = record[pos].toInt() and 0xFF
            pos += 1
            if (pos + sidLen > bodyEnd) return null
            pos += sidLen
            if (pos + 2 > bodyEnd) return null
            val csLen = read16(record, pos)
            pos += 2
            if (pos + csLen > bodyEnd) return null
            pos += csLen
            if (pos + 1 > bodyEnd) return null
            val cmLen = record[pos].toInt() and 0xFF
            pos += 1
            if (pos + cmLen > bodyEnd) return null
            pos += cmLen
            if (pos + 2 > bodyEnd) return null
            val extLen = read16(record, pos)
            pos += 2
            val extEnd = pos + extLen
            while (pos + 4 <= extEnd) {
                val extType = read16(record, pos)
                val extSize = read16(record, pos + 2)
                val dataStart = pos + 4
                if (dataStart + extSize > extEnd) return null
                if (extType == 0x0000) {
                    var p = dataStart
                    if (p + 2 > dataStart + extSize) return null
                    val listLen = read16(record, p)
                    p += 2
                    val listEnd = p + listLen
                    while (p + 3 <= listEnd) {
                        val nameType = record[p].toInt() and 0xFF
                        val nameLen = read16(record, p + 1)
                        p += 3
                        if (nameType == 0 && nameLen > 0 && p + nameLen <= listEnd) {
                            return SniLocation(p, nameLen)
                        }
                        p += nameLen
                    }
                    return null
                }
                pos = dataStart + extSize
            }
            return null
        }

        private fun read16(b: ByteArray, off: Int): Int =
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

        data class SniLocation(val nameOffset: Int, val nameLength: Int)
    }
}