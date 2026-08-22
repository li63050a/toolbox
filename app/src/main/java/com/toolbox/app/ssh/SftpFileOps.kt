package com.toolbox.app.ssh

import android.net.Uri
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.toolbox.app.log.Log
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps
import java.io.InputStream
import java.io.OutputStream
import java.util.Vector

class SftpFileOps(
    private val context: android.content.Context,
    private val session: Session,
    private val name: String
) : FileOps {

    override fun rootPath() = "/"
    override fun displayName() = "SFTP · $name"

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val channel = channel()
        try {
            @Suppress("UNCHECKED_CAST")
            val raw = channel.ls(normalizeDir(path)) as Vector<ChannelSftp.LsEntry>
            raw.filter { !it.filename.startsWith(".") || it.filename == "." || it.filename == ".." }
                .mapNotNull { entry ->
                    if (entry.filename == "." || entry.filename == "..") null
                    else {
                        val attrs = entry.attrs
                        val isDir = attrs.isDir
                        val full = if (isDir) "${normalizeDir(path)}${entry.filename}/" else "${normalizeDir(path)}${entry.filename}"
                        FileEntry(
                            path = full,
                            name = entry.filename,
                            isDirectory = isDir,
                            size = attrs.size,
                            modified = attrs.mTime.toLong()
                        )
                    }
                }.sortedBy { !it.isDirectory }
        } finally {
            channel.disconnect()
        }
    }.onFailure { Log.e("SFTP", "list $path 失败", it) }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching {
        val channel = channel()
        try {
            channel.mkdir(normalizeDir(path))
        } finally {
            channel.disconnect()
        }
    }.onFailure { Log.e("SFTP", "mkdir $path 失败", it) }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        deleteRecursive(path)
    }.onFailure { Log.e("SFTP", "delete $path 失败", it) }

    private fun deleteRecursive(path: String) {
        val channel = channel()
        try {
            deleteRecursiveInner(channel, path)
        } finally {
            channel.disconnect()
        }
    }

    private fun deleteRecursiveInner(channel: ChannelSftp, path: String) {
        val attrs = try { channel.stat(path) } catch (_: Exception) { return }
        if (attrs.isDir) {
            @Suppress("UNCHECKED_CAST")
            val entries = try { channel.ls(normalizeDir(path)) as Vector<ChannelSftp.LsEntry> } catch (_: Exception) { return }
            for (e in entries) {
                if (e.filename == "." || e.filename == "..") continue
                val child = "${normalizeDir(path)}${e.filename}"
                if (e.attrs.isDir) {
                    deleteRecursiveInner(channel, child)
                    try { channel.rmdir(child) } catch (_: Exception) {}
                } else {
                    try { channel.rm(child) } catch (_: Exception) {}
                }
            }
            try { channel.rmdir(path) } catch (_: Exception) {}
        } else {
            try { channel.rm(path) } catch (_: Exception) {}
        }
    }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        val channel = channel()
        try {
            val parent = oldPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else it }
            channel.rename(oldPath, "$parent/$newName")
        } finally {
            channel.disconnect()
        }
    }.onFailure { Log.e("SFTP", "rename $oldPath 失败", it) }

    override val supportsChmod: Boolean get() = true

    override suspend fun chmod(path: String, mode: Int): Result<Unit> = runCatching {
        val target = path.trimEnd('/').ifEmpty { "/" }
        val channel = channel()
        try {
            channel.chmod(mode, target)
        } finally {
            channel.disconnect()
        }
    }.onFailure { Log.e("SFTP", "chmod $path 失败", it) }

    override suspend fun download(
        remotePath: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val total = channelStat(remotePath).size
        val channel = channel()
        try {
            context.contentResolver.openOutputStream(localUri)?.use { out ->
                channel.get(remotePath, object : OutputStream() {
                    private val buf = ByteArray(64 * 1024)
                    private var written = 0L
                    override fun write(b: Int) {
                        buf[0] = b.toByte()
                        out.write(buf, 0, 1)
                        written++
                        if (total > 0) progress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        out.write(b, off, len)
                        written += len
                        if (total > 0) progress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                })
                out.flush()
            }
            Unit
        } finally {
            channel.disconnect()
        }
    }.onFailure { Log.e("SFTP", "download $remotePath 失败", it) }

    override suspend fun upload(
        remoteDir: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val channel = channel()
        try {
            val dest = normalizeDir(remoteDir) + (localUri.lastPathSegment ?: "upload")
            context.contentResolver.openInputStream(localUri)?.use { input ->
                val size = localUriSize(localUri)
                val monitor = object : com.jcraft.jsch.SftpProgressMonitor {
                    private var sent = 0L
                    override fun init(op: Int, src: String, dst: String, max: Long) {}
                    override fun count(count: Long): Boolean {
                        sent += count
                        if (size > 0) progress((sent.toFloat() / size).coerceIn(0f, 1f))
                        return true
                    }
                    override fun end() {}
                }
                channel.put(input, dest, monitor)
            }
            Unit
        } finally {
            channel.disconnect()
        }
    }.onFailure { Log.e("SFTP", "upload 到 $remoteDir 失败", it) }

    override fun close() {
        runCatching { session.disconnect() }
    }

    private fun channel(): ChannelSftp {
        val ch = session.openChannel("sftp") as ChannelSftp
        ch.connect(10000)
        return ch
    }

    private fun channelStat(path: String): SftpATTRS {
        val ch = channel()
        try {
            return ch.stat(path)
        } finally {
            ch.disconnect()
        }
    }

    private fun localUriSize(uri: Uri): Long = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    }.getOrDefault(-1L)

    private fun normalizeDir(path: String): String {
        var p = path.replace("//", "/")
        if (!p.startsWith("/")) p = "/$p"
        if (!p.endsWith("/")) p += "/"
        return p
    }
}