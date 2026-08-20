package com.toolbox.app.mediatool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 视频下载器
 */
class VideoDownloader(private val context: Context) {
    
    companion object {
        private const val YT_DLP_BIN = "yt-dlp"
    }
    
    suspend fun download(
        url: String,
        quality: String = "best",
        format: String = "mp4",
        outputDir: String = context.getExternalFilesDir(null)?.absolutePath ?: "/storage/emulated/0/Download"
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val outputFile = File(outputDir, "video_${System.currentTimeMillis()}.$format")
            
            val args = arrayOf(
                YT_DLP_BIN,
                "-f", getFormatSelector(quality, format),
                "-o", outputFile.absolutePath,
                "--no-overwrites",
                url
            )
            
            execute(*args)
            
            if (outputFile.exists()) {
                Result.success(DownloadResult(
                    success = true,
                    filePath = outputFile.absolutePath,
                    fileName = outputFile.name,
                    fileSize = outputFile.length()
                ))
            } else {
                Result.failure(Exception("下载失败"))
            }
        }.getOrElse { e ->
            Result.failure(Exception("下载失败: ${e.message}"))
        }
    }
    
    suspend fun downloadAudio(
        url: String,
        quality: String = "best",
        outputDir: String = context.getExternalFilesDir(null)?.absolutePath ?: "/storage/emulated/0/Download"
    ): Result<DownloadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val outputFile = File(outputDir, "audio_${System.currentTimeMillis()}.mp3")
            
            val args = arrayOf(
                YT_DLP_BIN,
                "-x",
                "--audio-format", "mp3",
                "--audio-quality", "0",
                "-o", outputFile.absolutePath,
                url
            )
            
            execute(*args)
            
            if (outputFile.exists()) {
                Result.success(DownloadResult(
                    success = true,
                    filePath = outputFile.absolutePath,
                    fileName = outputFile.name,
                    fileSize = outputFile.length()
                ))
            } else {
                Result.failure(Exception("音频下载失败"))
            }
        }.getOrElse { e ->
            Result.failure(Exception("音频下载失败: ${e.message}"))
        }
    }
    
    suspend fun getVideoInfo(url: String): Result<VideoInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val args = arrayOf(YT_DLP_BIN, "--dump-json", url)
            execute(*args)
            
            // 简化版 - 返回默认值
            Result.success(VideoInfo(
                title = "未知标题",
                description = "",
                duration = 0,
                uploader = "未知",
                thumbnail = "",
                viewCount = 0,
                uploadDate = "",
                formats = emptyList()
            ))
        }.getOrElse { e ->
            Result.failure(Exception("获取视频信息失败: ${e.message}"))
        }
    }
    
    private fun getFormatSelector(quality: String, format: String): String {
        return when {
            quality == "audio" -> "bestaudio"
            format == "mp3" -> "bestaudio[ext=m4a]/bestaudio/best"
            else -> "bestvideo[ext=$format]+bestaudio[ext=m4a]/best[ext=$format]/best"
        }
    }
    
    private fun execute(vararg args: String): String {
        val process = Runtime.getRuntime().exec(args)
        val output = StringBuilder()
        
        process.inputStream.bufferedReader().use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
        }
        
        process.waitFor()
        return output.toString()
    }
    
    fun isAvailable(): Boolean {
        return try {
            execute(YT_DLP_BIN, "--version").isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}

data class DownloadResult(
    val success: Boolean,
    val filePath: String,
    val fileName: String,
    val fileSize: Long
)

data class VideoInfo(
    val title: String,
    val description: String,
    val duration: Long,
    val uploader: String,
    val thumbnail: String,
    val viewCount: Long,
    val uploadDate: String,
    val formats: List<VideoFormat>
)

data class VideoFormat(
    val formatId: String,
    val quality: String,
    val ext: String,
    val resolution: String,
    val filesize: Long,
    val vcodec: String,
    val acodec: String
)