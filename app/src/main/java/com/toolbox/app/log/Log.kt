package com.toolbox.app.log

import android.content.Context
import android.util.Log as AndroidLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

data class LogEntry(
    val level: Int,
    val time: Long,
    val tag: String,
    val message: String,
    val throwable: String?
) {
    val timeText: String
        get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(time))

    val levelText: String
        get() = when (level) {
            Log.LEVEL_DEBUG -> "D"
            Log.LEVEL_INFO -> "I"
            Log.LEVEL_WARN -> "W"
            else -> "E"
        }
}

/**
 * 全局日志：Logcat + 内存环形缓冲 + 文件落盘（自动轮转）
 */
object Log {
    const val LEVEL_DEBUG = 0
    const val LEVEL_INFO = 1
    const val LEVEL_WARN = 2
    const val LEVEL_ERROR = 3

    private const val MAX_RAM_ENTRIES = 800
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val MAX_FILES = 3

    private val queue = ConcurrentLinkedQueue<LogEntry>()
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    private var logDir: File? = null
    private var currentFile: File? = null

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        currentFile = logDir?.let { File(it, logFileName()) }
    }

    fun logDirFile(): File? = logDir

    fun d(tag: String, message: String) = log(LEVEL_DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(LEVEL_INFO, tag, message, null)

    fun w(tag: String, message: String) = log(LEVEL_WARN, tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LEVEL_ERROR, tag, message, throwable)

    private fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
        val stack = throwable?.stackTraceToString()
        when (level) {
            LEVEL_DEBUG -> AndroidLog.d(tag, message)
            LEVEL_INFO -> AndroidLog.i(tag, message)
            LEVEL_WARN -> AndroidLog.w(tag, message)
            else -> AndroidLog.e(tag, message, throwable)
        }
        val entry = LogEntry(level, System.currentTimeMillis(), tag, message, stack)
        queue.add(entry)
        while (queue.size > MAX_RAM_ENTRIES) queue.poll()
        synchronized(this) {
            _entries.value = queue.toList()
        }
        writeFile(entry)
    }

    private fun writeFile(entry: LogEntry) {
        runCatching {
            val file = currentFile ?: return
            if (file.length() > MAX_FILE_BYTES) rotate()
            val sb = StringBuilder()
            sb.append(entry.timeText).append(' ').append(entry.levelText).append('/')
                .append(entry.tag).append(": ").append(entry.message).append('\n')
            entry.throwable?.let { sb.append(it).append('\n') }
            file.appendText(sb.toString())
        }
    }

    private fun rotate() {
        val dir = logDir ?: return
        val files = dir.listFiles { f -> f.name.endsWith(".log") }
            ?.sortedBy { it.name } ?: emptyList()
        while (files.size >= MAX_FILES) {
            files.firstOrNull()?.delete()
        }
        currentFile = File(dir, logFileName())
    }

    private fun logFileName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    fun clear() {
        queue.clear()
        synchronized(this) { _entries.value = emptyList() }
        runCatching {
            currentFile?.delete()
            currentFile = logDir?.let { File(it, logFileName()) }
        }
    }
}

/**
 * 崩溃捕获：落盘到 logs/crash_*.log
 */
class CrashHandler : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            val dir = Log.logDirFile() ?: return
            val file = File(dir, "crash_${System.currentTimeMillis()}.log")
            file.writeText(
                "时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n" +
                    "线程: ${thread.name}\n${throwable.stackTraceToString()}"
            )
        }
        Log.e("Crash", "未捕获异常: ${throwable.message}", throwable)
        defaultHandler?.uncaughtException(thread, throwable)
    }
}