package com.toolbox.app.ui.files

import android.content.Context
import android.net.Uri
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 本地文件系统实现（配合 MANAGE_EXTERNAL_STORAGE 权限浏览根目录） */
class LocalFileOps(private val context: Context) : FileOps {

    override val isLocal: Boolean get() = true

    override fun rootPath(): String = "/"

    override fun displayName(): String = "本地 /"

    override suspend fun list(path: String): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(path)
            if (!dir.isDirectory) throw IllegalArgumentException("不是目录: $path")
            dir.listFiles()
                ?.map { f ->
                    FileEntry(
                        path = f.absolutePath,
                        name = f.name,
                        isDirectory = f.isDirectory,
                        size = if (f.isFile) f.length() else 0,
                        modified = f.lastModified()
                    )
                }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
        }.onFailure { }
    }

    override suspend fun mkdir(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!File(path).mkdirs()) throw IllegalStateException("创建目录失败")
        }
    }

    override suspend fun delete(path: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(path)
            fun remove(file: File) {
                if (file.isDirectory) file.listFiles()?.forEach { remove(it) }
                if (!file.delete()) throw IllegalStateException("删除失败: ${file.path}")
            }
            remove(f)
        }
    }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val old = File(oldPath)
                val target = File(old.parentFile, newName)
                if (!old.renameTo(target)) throw IllegalStateException("重命名失败")
            }
        }

    override suspend fun download(remotePath: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(localUri)?.use { out ->
                    File(remotePath).inputStream().use { inp ->
                        val buf = ByteArray(64 * 1024)
                        val total = File(remotePath).length()
                        var done = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                } ?: throw IllegalStateException("无法写入目标文件")
            }
        }

    override suspend fun upload(remoteDir: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(localUri)?.use { inp ->
                    File(remoteDir).outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                } ?: throw IllegalStateException("无法读取源文件")
                progress(1f)
            }
        }
}