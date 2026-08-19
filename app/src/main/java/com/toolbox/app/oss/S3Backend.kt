package com.toolbox.app.oss

import android.content.Context
import android.net.Uri
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.log.Log
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps
import io.minio.CopyObjectArgs
import io.minio.GetObjectArgs
import io.minio.ListObjectsArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs

/** S3 兼容后端（AWS/MinIO/R2 等），路径语义：""=桶列表，其余 "bucket/prefix/…" */
class S3Backend(
    private val context: Context,
    private val config: ConnectionConfig.S3
) : FileOps {

    private val client: MinioClient by lazy {
        val base = config.endpoint.trim()
        val url = if (base.startsWith("http://") || base.startsWith("https://")) base
        else "${if (config.https) "https" else "http"}://$base"
        MinioClient.builder()
            .endpoint(url)
            .credentials(config.accessKeyId, config.secretKey)
            .apply {
                if (config.region.isNotBlank()) region(config.region)
            }
            .build()
    }

    override fun rootPath() = ""
    override fun displayName() = "S3 · ${config.name.ifEmpty { config.endpoint }}"

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        if (path.isEmpty() || path == "/") {
            client.listBuckets().map { b ->
                FileEntry(path = "${b.name()}/", name = b.name(), isDirectory = true, size = 0, modified = 0)
            }
        } else {
            val (bucket, prefix) = splitPath(path)
            val items = client.listObjects(
                ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .delimiter("/")
                    .recursive(false)
                    .build()
            )
            val out = ArrayList<FileEntry>()
            for (item: io.minio.Result<io.minio.messages.Item> in items) {
                val it = item.get()
                if (it.isDir) {
                    if (it.objectName() == prefix) continue
                    out.add(
                        FileEntry(
                            path = "$bucket/${it.objectName()}",
                            name = it.objectName().trimEnd('/').substringAfterLast('/'),
                            isDirectory = true, size = 0,
                            modified = it.lastModified()?.toInstant()?.toEpochMilli()?.div(1000) ?: 0
                        )
                    )
                } else {
                    out.add(
                        FileEntry(
                            path = "$bucket/${it.objectName()}",
                            name = it.objectName().substringAfterLast('/'),
                            isDirectory = false,
                            size = it.size(),
                            modified = it.lastModified()?.toInstant()?.toEpochMilli()?.div(1000) ?: 0
                        )
                    )
                }
            }
            out.sortedBy { !it.isDirectory }
        }
    }.onFailure { Log.e("OSS", "S3 list $path 失败", it) }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching {
        val (bucket, prefix) = splitPath(path)
        val dirKey = if (prefix.isEmpty()) "/" else prefix.trimEnd('/') + "/"
        client.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(dirKey)
                .stream(java.io.ByteArrayInputStream(ByteArray(0)), 0, -1)
                .contentType("application/octet-stream")
                .build()
        )
        Unit
    }.onFailure { Log.e("OSS", "S3 mkdir $path 失败", it) }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val (bucket, key) = splitPath(path)
        if (path.endsWith("/")) {
            val all = client.listObjects(
                ListObjectsArgs.builder().bucket(bucket).prefix(key).recursive(true).build()
            )
            for (item in all) {
                runCatching { client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(item.get().objectName()).build()) }
            }
        } else {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(key).build())
        }
    }.onFailure { Log.e("OSS", "S3 delete $path 失败", it) }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        val (bucket, oldKey) = splitPath(oldPath)
        val parent = oldKey.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        client.copyObject(
            CopyObjectArgs.builder()
                .bucket(bucket)
                .`object`("$parent$newName")
                .source(io.minio.CopySource.builder().bucket(bucket).`object`(oldKey).build())
                .build()
        )
        client.removeObject(RemoveObjectArgs.builder().bucket(bucket).`object`(oldKey).build())
    }.onFailure { Log.e("OSS", "S3 rename $oldPath 失败", it) }

    override suspend fun download(
        remotePath: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val (bucket, key) = splitPath(remotePath)
        context.contentResolver.openOutputStream(localUri)?.use { out ->
            client.getObject(GetObjectArgs.builder().bucket(bucket).`object`(key).build()).use { input ->
                val total = input.available().toLong().takeIf { it > 0 } ?: -1L
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                }
                out.flush()
            }
        }
        Unit
    }.onFailure { Log.e("OSS", "S3 download $remotePath 失败", it) }

    override suspend fun upload(
        remoteDir: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val (bucket, prefix) = splitPath(remoteDir)
        val name = localUri.lastPathSegment ?: "upload"
        context.contentResolver.openInputStream(localUri)?.use { input ->
            val size = runCatching {
                context.contentResolver.openAssetFileDescriptor(localUri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            val counting = object : java.io.InputStream() {
                private var done = 0L
                override fun read(): Int {
                    val b = input.read()
                    if (b >= 0 && size > 0) {
                        done++
                        progress((done.toFloat() / size).coerceIn(0f, 1f))
                    }
                    return b
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = input.read(b, off, len)
                    if (n > 0 && size > 0) {
                        done += n
                        progress((done.toFloat() / size).coerceIn(0f, 1f))
                    }
                    return n
                }
            }
            client.putObject(
                PutObjectArgs.builder()
                    .bucket(bucket)
                    .`object`("$prefix$name")
                    .stream(counting, size, -1)
                    .contentType("application/octet-stream")
                    .build()
            )
        }
        Unit
    }.onFailure { Log.e("OSS", "S3 upload 到 $remoteDir 失败", it) }

    override fun close() { /* MinIO 客户端复用 okhttp，无需关闭 */ }

    private fun splitPath(path: String): Pair<String, String> {
        val p = path.trimStart('/')
        val bucket = p.substringBefore('/')
        val key = p.substringAfter('/', "")
        return bucket to key
    }
}