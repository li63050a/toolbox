package com.toolbox.app.ftp

import android.content.Context
import android.net.Uri
import com.toolbox.app.log.Log
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile

class FtpFileOps(
    private val context: Context,
    private val client: FTPClient,
    private val displayName: String
) : FileOps {

    override fun rootPath() = "/"
    override fun displayName() = this.displayName

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        val dir = normalizeDir(path)
        val files = client.listFiles(dir)
        files.mapNotNull { f ->
            if (f.name == "." || f.name == "..") null
            else {
                val isDir = f.isDirectory
                FileEntry(
                    path = if (isDir) "$dir${f.name}/" else "$dir${f.name}",
                    name = f.name,
                    isDirectory = isDir,
                    size = f.size,
                    modified = f.timestamp?.timeInMillis?.div(1000) ?: 0L
                )
            }
        }.sortedBy { !it.isDirectory }
    }.onFailure { Log.e("FTP", "list $path 失败", it) }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching {
        val ok = client.makeDirectory(normalizeDir(path))
        if (!ok) throw Exception("服务器拒绝: ${client.replyString?.trim()}")
    }.onFailure { Log.e("FTP", "mkdir $path 失败", it) }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        if (path.endsWith("/")) deleteDirRecursive(path) else {
            val ok = client.deleteFile(path)
            if (!ok) throw Exception("删除失败: ${client.replyString?.trim()}")
        }
    }.onFailure { Log.e("FTP", "delete $path 失败", it) }

    private fun deleteDirRecursive(path: String) {
        val dir = normalizeDir(path)
        val children = client.listFiles(dir)
        for (f in children) {
            if (f.name == "." || f.name == "..") continue
            val child = "$dir${f.name}"
            if (f.isDirectory) deleteDirRecursive(child)
            else client.deleteFile(child)
        }
        val ok = client.removeDirectory(dir)
        if (!ok) throw Exception("删除目录失败: ${client.replyString?.trim()}")
    }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        val parent = oldPath.substringBeforeLast('/', "/").let { if (it.isEmpty()) "/" else "$it/" }
        val ok = client.rename(oldPath, "$parent$newName")
        if (!ok) throw Exception("重命名失败: ${client.replyString?.trim()}")
    }.onFailure { Log.e("FTP", "rename $oldPath 失败", it) }

    override suspend fun download(
        remotePath: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val size = runCatching {
            client.listFiles(remotePath)?.firstOrNull()?.size ?: -1L
        }.getOrDefault(-1L)
        val input = client.retrieveFileStream(remotePath)
            ?: throw Exception("进入数据连接失败: ${client.replyString?.trim()}")
        try {
            context.contentResolver.openOutputStream(localUri)?.use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (size > 0) progress((total.toFloat() / size).coerceIn(0f, 1f))
                }
                out.flush()
            }
        } finally {
            input.close()
        }
        if (!client.completePendingCommand()) {
            throw Exception("传输未完成: ${client.replyString?.trim()}")
        }
    }.onFailure { Log.e("FTP", "download $remotePath 失败", it) }

    override suspend fun upload(
        remoteDir: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val dest = "${normalizeDir(remoteDir)}${localUri.lastPathSegment ?: "upload"}"
        val size = runCatching {
            context.contentResolver.openAssetFileDescriptor(localUri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
        val output = client.storeFileStream(dest)
            ?: throw Exception("进入数据连接失败: ${client.replyString?.trim()}")
        try {
            context.contentResolver.openInputStream(localUri)?.use { input ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    total += n
                    if (size > 0) progress((total.toFloat() / size).coerceIn(0f, 1f))
                }
            }
        } finally {
            output.close()
        }
        if (!client.completePendingCommand()) {
            throw Exception("传输未完成: ${client.replyString?.trim()}")
        }
        Log.i("FTP", "上传完成 $dest")
    }.onFailure { Log.e("FTP", "upload 到 $remoteDir 失败", it) }

    override fun close() {
        runCatching { client.logout() }
        runCatching { client.disconnect() }
    }

    private fun normalizeDir(path: String): String {
        var p = path
        if (!p.startsWith("/")) p = "/$p"
        if (!p.endsWith("/")) p += "/"
        return p
    }
}