package com.toolbox.app.ui.files

import android.content.Context
import android.net.Uri
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

class ShizukuFileOps(private val context: Context) : FileOps {

    override val isLocal: Boolean get() = true
    override val supportsChmod: Boolean get() = true

    override fun rootPath(): String = "/"

    override fun displayName(): String = "Shizuku /"

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        if (!isRunning()) return Result.failure(Exception("Shizuku 未运行"))
        if (Shizuku.checkSelfPermission() != 0) return Result.failure(Exception("Shizuku 权限未授权"))
        
        val cmd = "ls -la \"$path\" 2>/dev/null | grep -v '^total'"
        val output = executeCommand(cmd) ?: return@runCatching emptyList()
        
        val entries = output.split("\n").filter { it.isNotEmpty() }
            .drop(1)
            .mapNotNull { line ->
                try {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 9) null
                    else {
                        val perms = parts[0]
                        val isDir = perms.startsWith("d")
                        val name = parts[8]
                        val size = try { parts[4].toLong() } catch (e: Exception) { 0L }
                        
                        FileEntry(
                            path = if (isDir && !name.endsWith("/")) "$path$name/" else "$path$name",
                            name = name,
                            isDirectory = isDir,
                            size = size,
                            modified = 0L
                        )
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .filter { it.name != "." && it.name != ".." }
            .sortedBy { !it.isDirectory }
        
        Result.success(entries)
    }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) return Result.failure(Exception("Shizuku 未授权"))
        executeCommand("mkdir -p \"$path\"")
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) return Result.failure(Exception("Shizuku 未授权"))
        val cmd = if (path.endsWith("/")) "rm -rf \"$path\"" else "rm -f \"$path\""
        executeCommand(cmd)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) return Result.failure(Exception("Shizuku 未授权"))
        val parent = oldPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else it }
        executeCommand("mv \"$oldPath\" \"$parent/$newName\"")
    }

    override suspend fun download(remotePath: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> = runCatching {
        TODO("Shizuku 下载功能待实现")
    }

    override suspend fun upload(remoteDir: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> = runCatching {
        TODO("Shizuku 上传功能待实现")
    }

    override suspend fun chmod(path: String, mode: Int): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) return Result.failure(Exception("Shizuku 未授权"))
        val modeStr = String.format("%04o", mode)
        executeCommand("chmod $modeStr \"$path\"")
    }

    fun isRunning(): Boolean = Shizuku.pingBinder()
    
    fun requestPermission() {
        Shizuku.requestPermission(0)
    }

    private fun executeCommand(cmd: String): String? {
        try {
            val binder = Shizuku.getBinder() ?: return null
            val service = android.os.ServiceManager.getService("shizuku")
            if (service == null) return null
            
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), emptyArray<String>(), null) as java.lang.Process
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errReader.readLine().also { line = it } != null) {
                output.append("ERR: ").append(line).append("\n")
            }
            
            process.waitFor()
            process.destroy()
            
            return output.toString().trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}