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
        if (!isRunning()) throw Exception("Shizuku 未运行")
        if (Shizuku.checkSelfPermission() != 0) throw Exception("Shizuku 权限未授权")
        
        val cmd = "ls -la \"$path\" 2>/dev/null | grep -v '^total'"
        val entries = executeCommand(cmd, path) ?: emptyList()
        
        entries.filter { it.name != "." && it.name != ".." }
            .sortedBy { !it.isDirectory }
    }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) throw Exception("Shizuku 未授权")
        executeCommand("mkdir -p \"$path\"", path)
    }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) throw Exception("Shizuku 未授权")
        val cmd = if (path.endsWith("/")) "rm -rf \"$path\"" else "rm -f \"$path\""
        executeCommand(cmd, path)
    }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) throw Exception("Shizuku 未授权")
        val parent = oldPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "/" else it }
        executeCommand("mv \"$oldPath\" \"$parent/$newName\"", oldPath)
    }

    override suspend fun download(remotePath: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> = runCatching {
        TODO("Shizuku 下载功能待实现")
    }

    override suspend fun upload(remoteDir: String, localUri: Uri, progress: (Float) -> Unit): Result<Unit> = runCatching {
        TODO("Shizuku 上传功能待实现")
    }

    override suspend fun chmod(path: String, mode: Int): Result<Unit> = runCatching {
        if (!isRunning() || Shizuku.checkSelfPermission() != 0) throw Exception("Shizuku 未授权")
        val modeStr = String.format("%04o", mode)
        executeCommand("chmod $modeStr \"$path\"", path)
    }

    fun isRunning(): Boolean = Shizuku.pingBinder()
    
    fun requestPermission() {
        Shizuku.requestPermission(0)
    }

    private fun executeCommand(cmd: String, currentPath: String): List<FileEntry>? {
        try {
            val binder = Shizuku.getBinder() ?: return null
            if (binder == null) return null
            
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            val method = clazz.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            method.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), emptyArray<String>(), null) as java.lang.Process
            
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val errorOutput = StringBuilder()
            var line: String?
            while (errReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }
            
            val entries = mutableListOf<FileEntry>()
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty() || line.startsWith("total")) continue
                try {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 9) continue
                    val perms = parts[0]
                    val isDir = perms.startsWith("d")
                    val name = parts[8]
                    val size = try { parts[4].toLong() } catch (e: Exception) { 0L }
                    
                    entries.add(
                        FileEntry(
                            path = if (isDir && !name.endsWith("/")) "$currentPath$name/" else "$currentPath$name",
                            name = name,
                            isDirectory = isDir,
                            size = size,
                            modified = 0L
                        )
                    )
                } catch (e: Exception) {
                    // skip malformed lines
                }
            }
            
            process.waitFor()
            process.destroy()
            
            return entries
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}