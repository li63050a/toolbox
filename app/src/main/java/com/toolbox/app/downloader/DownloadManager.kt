package com.toolbox.app.downloader

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DlStatus { RUNNING, PAUSED, DONE, FAILED }

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String = "",
    val dirUri: String,
    val threads: Int = 4,
    val total: Long = 0L,
    val downloaded: Long = 0L,
    val status: DlStatus = DlStatus.PAUSED,
    val speed: Long = 0L,
    val error: String? = null
)

/** 多线程分段下载器（Range 请求 + 分块临时文件断点续传） */
object DownloadManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var appContext: Context
    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    private val _dirError = MutableStateFlow<String?>(null)
    val dirError: StateFlow<String?> = _dirError

    fun clearDirError() { _dirError.value = null }

    private val jobs = mutableMapOf<String, Job>()

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    private fun partsDir(id: String): File = File(appContext.cacheDir, "dl_$id")

    /** 新建下载任务并自动开始 */
    fun add(url: String, dirUri: String, threads: Int) {
        val u = url.trim()
        if (u.isEmpty()) return
        val task = DownloadTask(
            url = u,
            dirUri = dirUri,
            threads = threads.coerceIn(1, 16),
            fileName = fileNameFrom(u),
            status = DlStatus.RUNNING
        )
        _tasks.value = listOf(task) + _tasks.value
        scope.launch { startInternal(task.id) }
    }

    fun start(id: String) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        if (t.status == DlStatus.RUNNING) return
        update(id) { it.copy(status = DlStatus.RUNNING, error = null) }
        scope.launch { startInternal(id) }
    }

    fun pause(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { it.copy(status = DlStatus.PAUSED, speed = 0L) }
    }

    fun remove(id: String) {
        jobs.remove(id)?.cancel()
        _tasks.value = _tasks.value.filterNot { it.id == id }
        scope.launch { partsDir(id).deleteRecursively() }
    }

    /** 从 HEAD / Range 探测文件总大小；不支持 Range 时返回 -1 */
    private suspend fun probe(url: String): Pair<Long, Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val head = client.newCall(Request.Builder().url(url).head().build()).execute()
            head.use {
                val len = it.header("Content-Length")?.toLongOrNull() ?: 0L
                val ranges = it.header("Accept-Ranges")?.contains("bytes") == true
                if (ranges) return@runCatching len to true
                if (len > 0 && it.isSuccessful) {
                    // 无 Accept-Ranges 时用 Range 请求确认
                }
            }
            val range = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute()
            range.use { resp ->
                if (resp.code == 206) {
                    val total = resp.header("Content-Range")?.split("/")?.lastOrNull()?.toLongOrNull()
                    if (total != null && total > 0) return@runCatching total to true
                }
                val len = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                len to false
            }
        }.getOrElse { -1L to false }
    }

    private suspend fun startInternal(id: String) {
        if (jobs[id]?.isActive == true) return
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        try {
            val (total, supportsRange) = probe(t.url)
            if (total <= 0) throw IllegalStateException("无法获取文件大小")
            val name = t.fileName.ifBlank { fileNameFrom(t.url) }
            update(id) { it.copy(fileName = name, total = total, status = DlStatus.RUNNING, error = null) }

            val job = scope.launch {
                val threadN = (if (supportsRange) t.threads else 1).coerceIn(1, 16)
                val dir = partsDir(id).apply { mkdirs() }
                val block = if (supportsRange) (total + threadN - 1) / threadN else total
                val files = ArrayList<File>(threadN)
                for (i in 0 until threadN) files.add(File(dir, "p$i.tmp"))
                coroutineScope {
                    // 实时进度 + 速度（节流 250ms / 1s）
                    val progressJob = launch {
                        var lastBytes = 0L
                        var lastTs = System.currentTimeMillis()
                        while (isActive) {
                            val sum = files.sumOf { f -> runCatching { f.length() }.getOrDefault(0L) }
                            val now = System.currentTimeMillis()
                            val speed = if (now > lastTs) ((sum - lastBytes) * 1000 / (now - lastTs)).coerceAtLeast(0L) else 0L
                            update(id) { it.copy(downloaded = sum, speed = speed) }
                            lastBytes = sum
                            lastTs = now
                            delay(250)
                        }
                    }
                    for (i in 0 until threadN) {
                        val part = files[i]
                        launch(Dispatchers.IO) {
                            if (supportsRange) {
                                downloadRange(t.url, part, i.toLong() * block, ((i + 1L) * block - 1).coerceAtMost(total - 1))
                            } else {
                                downloadWhole(t.url, part)
                            }
                        }
                    }
                    progressJob.cancel()
                }
                val merged = mergeToUri(t.url, id, name, t.dirUri, files)
                if (merged != true) return@launch // 目录失败已置 FAILED
                dir.deleteRecursively()
                update(id) { it.copy(downloaded = total, status = DlStatus.DONE, speed = 0L) }
            }
            jobs[id] = job
            job.join()
        } catch (e: CancellationException) {
            update(id) { it.copy(status = DlStatus.PAUSED, speed = 0L) }
        } catch (t: Throwable) {
            update(id) { it.copy(status = DlStatus.FAILED, error = t.message ?: "下载失败", speed = 0L) }
        } finally {
            jobs.remove(id)
        }
    }

    /** Range 分段下载：服务器回 206 按偏移续写，回 200 则从头覆盖 */
    private suspend fun downloadRange(url: String, part: File, start: Long, end: Long) {
        var from = start + part.length()
        if (from > end) return
        while (true) {
            val resp = client.newCall(
                Request.Builder().url(url).header("Range", "bytes=$from-$end").build()
            ).execute()
            resp.use {
                if (it.code == 416) return
                if (it.code !in 200..206) throw IllegalStateException("HTTP ${it.code}")
                val body = it.body ?: throw IllegalStateException("无响应体")
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(if (it.code == 206) from - start else 0L)
                    val buf = ByteArray(64 * 1024)
                    var cursor = if (it.code == 206) from else start
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            cursor += n
                            if (cursor > end) break
                        }
                    }
                    from = cursor
                }
            }
            if (from > end) return
        }
    }

    /** 服务器不支持 Range：单线程全量下载（无续传） */
    private suspend fun downloadWhole(url: String, part: File) {
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        resp.use {
            if (!it.isSuccessful) throw IllegalStateException("HTTP ${it.code}")
            it.body?.byteStream()?.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        }
    }

    /** 合并分块到目标目录（SAF tree URI，用户授权后可写） */
    private suspend fun mergeToUri(url: String, id: String, name: String, dirUriStr: String, files: List<File>): Boolean? =
        withContext(Dispatchers.IO) {
            runCatching {
                val treeUri = Uri.parse(dirUriStr)
                val tree = DocumentFile.fromTreeUri(appContext, treeUri) ?: throw IllegalStateException("无法访问目标文件夹")
                if (!tree.canWrite()) throw IllegalStateException("文件夹不可写")
                if (files.any { it.length() == 0L }) throw IllegalStateException("分块下载不完整")
                var target = tree.findFile(name)
                if (target == null) {
                    target = tree.createFile("application/octet-stream", name) ?: throw IllegalStateException("无法创建目标文件")
                }
                appContext.contentResolver.openOutputStream(target.uri)?.use { out ->
                    files.sortedBy { it.name }.forEach { f ->
                        f.inputStream().use { inp ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                            }
                        }
                    }
                } ?: throw IllegalStateException("无法写入目标文件")
                true
            }.getOrElse { e ->
                _dirError.value = e.message ?: "合并失败"
                update(id) { it.copy(status = DlStatus.FAILED, error = e.message, speed = 0L) }
                null
            }
        }

    private fun fileNameFrom(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val raw = clean.substringAfterLast('/')
        val decoded = URLDecoder.decode(raw, "UTF-8")
        return if (decoded.isBlank() || decoded == clean) "download_${System.currentTimeMillis() / 1000}" else decoded
    }

    private fun uniqueFile(candidate: File): File {
        if (!candidate.exists()) return candidate
        var i = 1
        while (true) {
            val alt = File(candidate.parentFile, "${candidate.nameWithoutExtension} ($i).${candidate.extension}")
            if (!alt.exists()) return alt
            i++
        }
    }
}