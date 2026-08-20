package com.toolbox.app.mediatool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FFmpeg 封装 - 处理视频/音频转换、合并、水印
 */
class FFmpegWrapper(private val context: android.content.Context) {
    
    companion object {
        private const val TAG = "FFmpegWrapper"
    }
    
    suspend fun convertVideo(input: String, output: String, format: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val ext = ".$format"
            val outputFile = File(output)
            
            val args = arrayOf(
                "ffmpeg",
                "-y",
                "-i", input,
                "-c:v", "libx264",
                "-c:a", "aac",
                "-pix_fmt", "yuv420p",
                outputFile.absolutePath
            )
            
            execute(*args)
            Unit
        }
    }
    
    suspend fun extractAudio(videoPath: String, audioPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val args = arrayOf(
                "ffmpeg",
                "-y",
                "-i", videoPath,
                "-vn",
                "-acodec", "copy",
                audioPath
            )
            execute(*args)
            Unit
        }
    }
    
    suspend fun mergeVideoAudio(videoPath: String, audioPath: String, outputPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val args = arrayOf(
                "ffmpeg",
                "-y",
                "-i", videoPath,
                "-i", audioPath,
                "-c:v", "copy",
                "-c:a", "aac",
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-shortest",
                outputPath
            )
            execute(*args)
            Unit
        }
    }
    
    suspend fun addVisualWatermark(inputPath: String, outputPath: String, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val args = arrayOf(
                "ffmpeg",
                "-y",
                "-i", inputPath,
                "-vf", "drawtext=text='$text':fontcolor=white:fontsize=24:bottom=50",
                "-c:a", "copy",
                outputPath
            )
            execute(*args)
            Unit
        }
    }
    
    suspend fun getMediaInfo(filePath: String): Result<MediaFileInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val args = arrayOf(
                "ffprobe",
                "-v", "quiet",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                filePath
            )
            
            val output = execute(*args).getOrNull() ?: return@runCatching throw Exception("无法获取媒体信息")
            
            // 简化解析
            val file = File(filePath)
            MediaFileInfo(
                path = filePath,
                name = file.name,
                size = file.length(),
                duration = 0L,
                mimeType = "",
                width = 0,
                height = 0,
                bitrate = 0
            )
        }
    }
    
    fun isAvailable(): Boolean {
        return try {
            execute("ffmpeg", "-version").isSuccess
        } catch (e: Exception) {
            false
        }
    }
    
    private fun execute(vararg args: String): Result<String> {
        return runCatching {
            val process = Runtime.getRuntime().exec(args)
            val output = StringBuilder()
            
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            
            process.waitFor()
            output.toString()
        }
    }
}

data class MediaFileInfo(
    val path: String,
    val name: String,
    val size: Long,
    val duration: Long = 0L,
    val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0
)