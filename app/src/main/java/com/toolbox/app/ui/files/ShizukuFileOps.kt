package com.toolbox.app.ui.files

import android.content.Context
import android.net.Uri
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class ShizukuFileOps(private val context: Context) : FileOps {

    override val isLocal: Boolean get() = true
    override val supportsChmod: Boolean get() = true

    override fun rootPath(): String = "/"
    override fun displayName(): String = "Shizuku /"

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        checkPermission()
        val cmd = "ls -la \"$path\" 2>/dev/null | grep -v '^total'"
        executeCommand(cmd, path) ?: emptyList()
    }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching { checkPermission(); executeCommand("mkdir -p \"$path\"", path) }
    override suspend fun delete(path: String): Result<Unit> = runCatching { checkPermission(); executeCommand(if (path.endsWith("/")) "rm -rf \"$path\"" else "rm -f \"$path\"", path) }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        checkPermission()
        val parent = oldPath.substringBeforeLast('/', "")
        val dest = if (parent.isEmpty()) "/$newName" else "$parent/$newName"
        executeCommand("mv \"$oldPath\" \"$dest\"", oldPath)
    }

    override suspend fun download(remotePath: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> = runCatching {
        checkPermission()
        val cmd = "cat \"${remotePath.replace("\"", "\\\"")}\""
        val proc = runShizukuProcess(arrayOf("sh", "-c", cmd)) ?: throw Exception("无法启动进程")
        try {
            val out = context.contentResolver.openOutputStream(localUri)?.buffered()
                ?: throw Exception("无法打开输出流: $localUri")
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var read = proc.inputStream.read(buf)
            while (read > 0) {
                out.write(buf, 0, read)
                total += read
                read = proc.inputStream.read(buf)
            }
            out.close()
            val err = readErrorStream(proc)
            proc.waitFor()
            if (proc.exitValue() != 0 || err.isNotEmpty()) throw Exception("下载失败: $err")
            Result.success(Unit)
        } finally { proc.destroy() }
    }

    override suspend fun upload(remoteDir: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> = runCatching {
        checkPermission()
        val localIn = context.contentResolver.openInputStream(localUri)
            ?: throw Exception("无法打开源文件: $localUri")
        val localBytes = localIn.readBytes()
        localIn.close()

        // base64 编码传输避免 shell 特殊字符问题
        val encoded = android.util.Base64.encodeToString(localBytes, android.util.Base64.NO_WRAP)
        val cmd = """printf '%s' "$encoded" | base64 -d > "${remoteDir.trimEnd('/')}/$(basename ${localUri.path ?: "file"})""""
        executeCommand(cmd, remoteDir)
    }

    override suspend fun chmod(path: String, mode: Int): Result<Unit> = runCatching {
        checkPermission()
        executeCommand(String.format("chmod %04o \"%s\"", mode, path), path)
    }

    fun isRunning(): Boolean = Shizuku.pingBinder()

    fun requestPermission() {
        Shizuku.requestPermission(0)
    }

    private fun checkPermission() {
        if (!isRunning()) throw Exception("Shizuku 未运行")
        if (Shizuku.checkSelfPermission() != 0) throw Exception("Shizuku 权限未授权")
    }

    private fun executeCommand(cmd: String, currentPath: String): List<FileEntry>? {
        try {
            val proc = runShizukuProcess(arrayOf("sh", "-c", cmd)) ?: return null
            try {
                val lines = readAllLines(proc)
                return if (cmd.startsWith("ls ")) parseListOutput(lines) else emptyList()
            } finally {
                proc.waitFor()
                proc.destroy()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun readAllLines(proc: java.lang.Process): List<String> {
        return try {
            BufferedReader(InputStreamReader(proc.inputStream)).readLines()
        } catch (_: Exception) { emptyList() }
    }

    private fun parseListOutput(lines: List<String>): List<FileEntry> = lines
        .filter { it.isNotEmpty() && !it.startsWith("total") }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 9)
            if (parts.size < 9) return@mapNotNull null
            try {
                val perms = parts[0]
                val isDir = perms.startsWith("d")
                val name = parts[8]
                val size = try { parts[4].toLong() } catch (_: Exception) { 0L }
                FileEntry(path = if (isDir && !name.endsWith("/")) "$name/" else name, name = name, isDirectory = isDir, size = size, modified = 0L)
            } catch (_: Exception) { null }
        }

    private fun runShizukuProcess(args: Array<String>): java.lang.Process? {
        try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            return method.invoke(null, args, emptyArray<String>(), null) as java.lang.Process
        } catch (e: Exception) {
            return null
        }
    }

    private fun readErrorStream(proc: java.lang.Process): String {
        return try {
            BufferedReader(InputStreamReader(proc.errorStream)).readText().trim()
        } catch (_: Exception) { "" }
    }
}
