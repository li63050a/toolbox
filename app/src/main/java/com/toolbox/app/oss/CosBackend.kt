package com.toolbox.app.oss

import android.content.Context
import android.net.Uri
import com.tencent.cos.xml.CosXmlService
import com.tencent.cos.xml.CosXmlServiceConfig
import com.tencent.cos.xml.exception.CosXmlClientException
import com.tencent.cos.xml.exception.CosXmlServiceException
import com.tencent.cos.xml.listener.CosXmlProgressListener
import com.tencent.cos.xml.listener.CosXmlResultListener
import com.tencent.cos.xml.model.CosXmlRequest
import com.tencent.cos.xml.model.CosXmlResult
import com.tencent.cos.xml.model.bucket.GetBucketRequest
import com.tencent.cos.xml.model.bucket.GetBucketResult
import com.tencent.cos.xml.model.`object`.CopyObjectRequest
import com.tencent.cos.xml.model.`object`.DeleteObjectRequest
import com.tencent.cos.xml.model.`object`.GetObjectRequest
import com.tencent.cos.xml.model.`object`.PutObjectRequest
import com.tencent.cos.xml.model.service.GetServiceRequest
import com.tencent.qcloud.core.auth.BasicLifecycleCredentialProvider
import com.tencent.qcloud.core.auth.BasicQCloudCredentials
import com.tencent.qcloud.core.auth.QCloudLifecycleCredentials
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.log.Log
import com.toolbox.app.ui.filebrowser.FileEntry
import com.toolbox.app.ui.filebrowser.FileOps
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 腾讯云 COS 后端（官方 SDK 异步接口 + CountDownLatch 包装），路径语义同 S3Backend */
class CosBackend(
    private val context: Context,
    private val config: ConnectionConfig.Cos
) : FileOps {

    private val service: CosXmlService by lazy {
        val serviceConfig = CosXmlServiceConfig(
            CosXmlServiceConfig.Builder()
                .setAppidAndRegion("", config.region)
                .setDebuggable(false)
        )
        val provider = object : BasicLifecycleCredentialProvider() {
            override fun fetchNewCredentials(): QCloudLifecycleCredentials =
                BasicQCloudCredentials(config.secretId, config.secretKey, "")
        }
        CosXmlService(context.applicationContext, serviceConfig, provider)
    }

    override fun rootPath() = ""
    override fun displayName() = "COS · ${config.name.ifEmpty { config.region }}"

    override suspend fun list(path: String): Result<List<FileEntry>> = runCatching {
        if (path.isEmpty() || path == "/") {
            val result = service.getService(GetServiceRequest())
            result.listAllMyBuckets.buckets.map { b ->
                FileEntry(path = "${b.name}/", name = b.name, isDirectory = true, size = 0, modified = 0)
            }
        } else {
            val (bucket, prefix) = splitPath(path)
            val req = GetBucketRequest(bucket).apply {
                setPrefix(prefix)
                setDelimiter('/')
            }
            val out = ArrayList<FileEntry>()
            await(
                "getBucket",
                block = { l -> service.getBucketAsync(req, l) },
                parse = { r ->
                    val lb = (r as? GetBucketResult)?.listBucket
                    lb?.commonPrefixesList?.forEach { raw ->
                        val cp = raw as String
                        out.add(
                            FileEntry(
                                path = "$bucket/$cp",
                                name = cp.trimEnd('/').substringAfterLast('/'),
                                isDirectory = true, size = 0, modified = 0
                            )
                        )
                    }
                    lb?.contentsList?.forEach { raw ->
                        val c = raw as com.tencent.cos.xml.model.tag.ListBucket.Contents
                        val key = c.key
                        if (key == prefix) return@forEach
                        out.add(
                            FileEntry(
                                path = "$bucket/$key",
                                name = key.substringAfterLast('/'),
                                isDirectory = false,
                                size = c.size,
                                modified = runCatching {
                                    java.text.SimpleDateFormat(
                                        "EEE, dd MMM yyyy HH:mm:ss z",
                                        java.util.Locale.US
                                    ).parse(c.lastModified)?.time?.div(1000) ?: 0L
                                }.getOrDefault(0L)
                            )
                        )
                    }
                    Unit
                }
            )
            out.sortedBy { !it.isDirectory }
        }
    }.onFailure { Log.e("OSS", "COS list $path 失败", it) }

    override suspend fun mkdir(path: String): Result<Unit> = runCatching {
        val (bucket, prefix) = splitPath(path)
        val dirKey = if (prefix.isEmpty()) "/" else prefix.trimEnd('/') + "/"
        val temp = File(context.cacheDir, "cos_mkdir_${System.currentTimeMillis()}.tmp")
        temp.writeBytes(ByteArray(0))
        try {
            await(
                "putObject",
                block = { l -> service.putObjectAsync(PutObjectRequest(bucket, dirKey, temp.absolutePath), l) },
                parse = { Unit }
            )
        } finally {
            temp.delete()
        }
    }.onFailure { Log.e("OSS", "COS mkdir $path 失败", it) }

    override suspend fun delete(path: String): Result<Unit> = runCatching {
        val (bucket, key) = splitPath(path)
        if (path.endsWith("/")) {
            var marker: String? = null
            do {
                val req = GetBucketRequest(bucket).apply {
                    setPrefix(key)
                    setMaxKeys(1000)
                    marker?.let { setMarker(it) }
                }
                val result = await(
                    "getBucket",
                    block = { l -> service.getBucketAsync(req, l) },
                    parse = { r -> (r as? GetBucketResult)?.listBucket }
                )
                result?.contentsList?.forEach { c ->
                    runCatching { service.deleteObject(DeleteObjectRequest(bucket, c.key)) }
                }
                marker = result?.nextMarker
            } while (marker != null)
        } else {
            service.deleteObject(DeleteObjectRequest(bucket, key))
        }
        Unit
    }.onFailure { Log.e("OSS", "COS delete $path 失败", it) }

    override suspend fun rename(oldPath: String, newName: String): Result<Unit> = runCatching {
        val (bucket, oldKey) = splitPath(oldPath)
        val parent = oldKey.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val src = CopyObjectRequest.CopySourceStruct(bucket, oldKey, config.region, "")
        src.region = config.region
        service.copyObject(CopyObjectRequest(bucket, "$parent$newName", src))
        service.deleteObject(DeleteObjectRequest(bucket, oldKey))
        Unit
    }.onFailure { Log.e("OSS", "COS rename $oldPath 失败", it) }

    override suspend fun download(
        remotePath: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val (bucket, key) = splitPath(remotePath)
        val temp = File(context.cacheDir, "cos_dl_${System.currentTimeMillis()}.tmp")
        try {
            val req = GetObjectRequest(bucket, key, temp.absolutePath)
            req.setProgressListener(object : CosXmlProgressListener {
                override fun onProgress(complete: Long, target: Long) {
                    if (target > 0) progress((complete.toFloat() / target).coerceIn(0f, 1f))
                }
            })
            await(
                "getObject",
                block = { l -> service.getObjectAsync(req, l) },
                parse = { Unit }
            )
            context.contentResolver.openOutputStream(localUri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
                out.flush()
            }
            Unit
        } finally {
            temp.delete()
        }
    }.onFailure { Log.e("OSS", "COS download $remotePath 失败", it) }

    override suspend fun upload(
        remoteDir: String,
        localUri: Uri,
        progress: (Float) -> Unit
    ): Result<Unit> = runCatching {
        val (bucket, prefix) = splitPath(remoteDir)
        val name = localUri.lastPathSegment ?: "upload"
        val temp = File(context.cacheDir, "cos_up_${System.currentTimeMillis()}.tmp")
        try {
            context.contentResolver.openInputStream(localUri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            }
            val req = PutObjectRequest(bucket, "$prefix$name", temp.absolutePath)
            req.setProgressListener(object : CosXmlProgressListener {
                override fun onProgress(complete: Long, target: Long) {
                    if (target > 0) progress((complete.toFloat() / target).coerceIn(0f, 1f))
                }
            })
            await(
                "putObject",
                block = { l -> service.putObjectAsync(req, l) },
                parse = { Unit }
            )
        } finally {
            temp.delete()
        }
    }.onFailure { Log.e("OSS", "COS upload 到 $remoteDir 失败", it) }

    override fun close() { /* COS 无显式释放 */ }

    private fun <T> await(
        op: String,
        block: (CosXmlResultListener) -> Unit,
        parse: (CosXmlResult?) -> T
    ): T {
        val latch = CountDownLatch(1)
        var result: CosXmlResult? = null
        var err: Throwable? = null
        block(object : CosXmlResultListener {
            override fun onSuccess(request: CosXmlRequest?, result2: CosXmlResult?) {
                result = result2
                latch.countDown()
            }

            override fun onFail(
                request: CosXmlRequest?,
                exception: CosXmlClientException?,
                serviceException: CosXmlServiceException?
            ) {
                err = exception ?: serviceException
                latch.countDown()
            }
        })
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw java.util.concurrent.TimeoutException("COS $op 超时")
        }
        err?.let { throw it }
        return parse(result)
    }

    private fun splitPath(path: String): Pair<String, String> {
        val p = path.trimStart('/')
        val bucket = p.substringBefore('/')
        val key = p.substringAfter('/', "")
        return bucket to key
    }
}