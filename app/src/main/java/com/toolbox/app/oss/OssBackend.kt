package com.toolbox.app.oss

import android.content.Context
import android.net.Uri
import com.aliyun.oss.OSS
import com.aliyun.oss.OSSClientBuilder
import com.aliyun.oss.model.CopyObjectRequest
import com.aliyun.oss.model.ListObjectsRequest
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.log.Log
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps

/** 阿里云 OSS 后端，路径语义同 S3Backend */
class OssBackend(
    private val context: Context,
    private val config: ConnectionConfig.Oss
) : FileOps {

    private val client: OSS by lazy {
        val base = config.endpoint.trim()
        val url = if (base.startsWith("http://") || base.startsWith("https://")) base else "https://$base"
        OSSClientBuilder().build(url, config.accessKeyId, config.accessKeySecret)
    }

    override fun rootPath() = ""
    override fun displayName() = "OSS · ${config.name.ifEmpty { config.endpoint }}"

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        if (path.isEmpty() || path == "/") {
            client.listBuckets().map { b ->
                FileEntry(path = "${b.name}/", name = b.name, isDirectory = true, size = 0, modified = 0)
            }
        } else {
            val (bucket, prefix) = splitPath(path)
            val req = ListObjectsRequest(bucket, prefix, null, "/", 1000)
            val result = client.listObjects(req)
            val out = ArrayList<FileEntry>()
            result.commonPrefixes.forEach { cp ->
                out.add(
                    FileEntry(
                        path = "$bucket/$cp",
                        name = cp.trimEnd('/').substringAfterLast('/'),
                        isDirectory = true, size = 0, modified = 0
                    )
                )
            }
            result.objectSummaries.forEach { s ->
                val key = s.key
                if (key == prefix) return@forEach
                out.add(
                    FileEntry(
                        path = "$bucket/$key",
                        name = key.substringAfterLast('/'),
                        isDirectory = false,
                        size = s.size,
                        modified = s.lastModified.time.div(1000)
                    )
                )
            }
            out.sortedBy { !it.isDirectory }
        }
    }.onFailure { Log.e("OSS", "OSS list $path 失败", it) }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching {
        val (bucket, prefix) = splitPath(path)
        val dirKey = if (prefix.isEmpty()) "/" else prefix.trimEnd('/') + "/"
        client.putObject(bucket, dirKey, java.io.ByteArrayInputStream(ByteArray(0)))
        Unit
    }.onFailure { Log.e("OSS", "OSS mkdir $path 失败", it) }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val (bucket, key) = splitPath(path)
        if (path.endsWith("/")) {
            var marker: String? = null
            var hasMore = true
            while (hasMore) {
                val req = ListObjectsRequest(bucket, key, marker, "/", 1000)
                val result = client.listObjects(req)
                result.objectSummaries.forEach { s ->
                    runCatching { client.deleteObject(bucket, s.key) }
                }
                hasMore = result.isTruncated
                marker = result.nextMarker
            }
        } else {
            client.deleteObject(bucket, key)
        }
        Unit
    }.onFailure { Log.e("OSS", "OSS delete $path 失败", it) }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        val (bucket, oldKey) = splitPath(oldPath)
        val parent = oldKey.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        client.copyObject(CopyObjectRequest(bucket, oldKey, bucket, "$parent$newName"))
        client.deleteObject(bucket, oldKey)
        Unit
    }.onFailure { Log.e("OSS", "OSS rename $oldPath 失败", it) }

    override suspend fun download(
        remotePath: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val (bucket, key) = splitPath(remotePath)
        val obj = client.getObject(bucket, key)
        val total = obj.objectMetadata.contentLength
        context.contentResolver.openOutputStream(localUri)?.use { out ->
            obj.objectContent.use { input ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            out.flush()
        }
        Unit
    }.onFailure { Log.e("OSS", "OSS download $remotePath 失败", it) }

    override suspend fun upload(
        remoteDir: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val (bucket, prefix) = splitPath(remoteDir)
        val name = localUri.lastPathSegment ?: "upload"
        context.contentResolver.openInputStream(localUri)?.use { input ->
            client.putObject(bucket, "$prefix$name", input)
            progress(1f)
        }
        Unit
    }.onFailure { Log.e("OSS", "OSS upload 到 $remoteDir 失败", it) }

    override fun close() {
        runCatching { client.shutdown() }
    }

    private fun splitPath(path: String): Pair<String, String> {
        val p = path.trimStart('/')
        val bucket = p.substringBefore('/')
        val key = p.substringAfter('/', "")
        return bucket to key
    }
}