package com.toolbox.app.ui.mediatool

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbox.app.mediatool.FFmpegWrapper
import com.toolbox.app.mediatool.FrequencyWatermark
import com.toolbox.app.mediatool.VideoDownloader
import com.toolbox.app.mediatool.WatermarkType
import kotlinx.coroutines.launch

@OptIn(::class)
@Composable
fun MediaToolScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedTab by remember { mutableStateOf(0) }
    var snackbarHostState = remember { SnackbarHostState() }
    
    // 下载相关状态
    var downloadUrl by remember { mutableStateOf("") }
    var downloadQuality by remember { mutableStateOf("best") }
    var downloadFormat by remember { mutableStateOf("mp4") }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    
    // 转换相关状态
    var convertInput by remember { mutableStateOf("") }
    var convertOutput by remember { mutableStateOf("") }
    var convertFormat by remember { mutableStateOf("mp4") }
    var isConverting by remember { mutableStateOf(false) }
    
    // 合并相关状态
    var videoPath by remember { mutableStateOf("") }
    var audioPath by remember { mutableStateOf("") }
    var mergeOutput by remember { mutableStateOf("") }
    var isMerging by remember { mutableStateOf(false) }
    
    // 水印相关状态
    var watermarkInput by remember { mutableStateOf("") }
    var watermarkText by remember { mutableStateOf("水印文字") }
    var watermarkType by remember { mutableStateOf(WatermarkType.VISUAL) }
    var isWatermarking by remember { mutableStateOf(false) }
    var detectedWatermark by remember { mutableStateOf<String?>(null) }
    
    val ffmpeg = remember { FFmpegWrapper(context) }
    val downloader = remember { VideoDownloader(context) }
    val watermark = remember { FrequencyWatermark(context) }
    
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.path?.let { path ->
            when (selectedTab) {
                1 -> convertInput = path
                2 -> {
                    if (videoPath.isEmpty()) videoPath = path
                    else audioPath = path
                }
                3 -> watermarkInput = path
            }
        }
    }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("媒体工具", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf("下载", "转换", "合并", "水印").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            when (selectedTab) {
                0 -> DownloadPanel(
                    url = downloadUrl,
                    onUrlChange = { downloadUrl = it },
                    quality = downloadQuality,
                    onQualityChange = { downloadQuality = it },
                    format = downloadFormat,
                    onFormatChange = { downloadFormat = it },
                    isDownloading = isDownloading,
                    progress = downloadProgress,
                    onDownload = {
                        isDownloading = true
                        scope.launch {
                            val result = downloader.download(downloadUrl, downloadQuality, downloadFormat)
                            result.onSuccess {
                                snackbarHostState.showSnackbar("下载成功: ${it.fileName}")
                            }
                            result.onFailure {
                                snackbarHostState.showSnackbar(it.message ?: "下载失败")
                            }
                            isDownloading = false
                            downloadProgress = 0f
                        }
                    }
                )
                1 -> ConvertPanel(
                    input = convertInput,
                    onInputChange = { convertInput = it },
                    output = convertOutput,
                    onOutputChange = { convertOutput = it },
                    format = convertFormat,
                    onFormatChange = { convertFormat = it },
                    isConverting = isConverting,
                    onSelectInput = { filePicker.launch("*/*") },
                    onConvert = {
                        isConverting = true
                        scope.launch {
                            val result = ffmpeg.convertVideo(convertInput, convertOutput, convertFormat)
                            result.onSuccess { snackbarHostState.showSnackbar("转换成功") }
                            result.onFailure { snackbarHostState.showSnackbar(it.message ?: "转换失败") }
                            isConverting = false
                        }
                    }
                )
                2 -> MergePanel(
                    video = videoPath,
                    onVideoSelect = { filePicker.launch("video/*") },
                    audio = audioPath,
                    onAudioSelect = { filePicker.launch("audio/*") },
                    output = mergeOutput,
                    onOutputChange = { mergeOutput = it },
                    isMerging = isMerging,
                    onMerge = {
                        isMerging = true
                        scope.launch {
                            val result = ffmpeg.mergeVideoAudio(videoPath, audioPath, mergeOutput)
                            result.onSuccess { snackbarHostState.showSnackbar("合并成功") }
                            result.onFailure { snackbarHostState.showSnackbar(it.message ?: "合并失败") }
                            isMerging = false
                        }
                    }
                )
                3 -> WatermarkPanel(
                    input = watermarkInput,
                    onInputSelect = { filePicker.launch("*/*") },
                    text = watermarkText,
                    onTextChange = { watermarkText = it },
                    type = watermarkType,
                    onTypeChange = { watermarkType = it },
                    isWatermarking = isWatermarking,
                    onApplyWatermark = {
                        isWatermarking = true
                        scope.launch {
                            val outputPath = "${watermarkInput.removeSuffix(watermarkInput.substringAfterLast('.'))}_watermarked.mp4"
                            val result = if (watermarkType == WatermarkType.VISUAL) {
                                watermark.embedVisualWatermark(watermarkInput, outputPath, watermarkText)
                            } else {
                                watermark.embedAudioWatermark(watermarkInput, outputPath, watermarkText)
                            }
                            result.onSuccess { snackbarHostState.showSnackbar("水印添加成功") }
                            result.onFailure { snackbarHostState.showSnackbar(it.message ?: "水印添加失败") }
                            isWatermarking = false
                        }
                    },
                    detectedWatermark = detectedWatermark,
                    onDetectWatermark = {
                        scope.launch {
                            val result = watermark.detectAudioWatermark(watermarkInput)
                            result.onSuccess { 
                                detectedWatermark = it.detectedWatermark
                                snackbarHostState.showSnackbar(if (it.success) "检测到水印" else "未检测到水印")
                            }
                            result.onFailure { snackbarHostState.showSnackbar(it.message ?: "检测失败") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DownloadPanel(
    url: String,
    onUrlChange: (String) -> Unit,
    quality: String,
    onQualityChange: (String) -> Unit,
    format: String,
    onFormatChange: (String) -> Unit,
    isDownloading: Boolean,
    progress: Float,
    onDownload: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            label = { Text("视频URL") },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://www.bilibili.com/video/...") }
        )
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(120.dp)) {
                Text("清晰度", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(expanded = false, onExpandedChange = {}) {
                    OutlinedTextField(
                        value = quality,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        readOnly = true
                    )
                    ExposedDropdownMenu(expanded = false, onDismissRequest = {})
                }
            }
            Column(Modifier.width(100.dp)) {
                Text("格式", style = MaterialTheme.typography.labelMedium)
                ExposedDropdownMenuBox(expanded = false, onExpandedChange = {}) {
                    OutlinedTextField(
                        value = format,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        readOnly = true
                    )
                    ExposedDropdownMenu(expanded = false, onDismissRequest = {})
                }
            }
        }
        
        if (isDownloading) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        }
        
        Button(
            onClick = onDownload,
            enabled = url.isNotEmpty() && !isDownloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始下载")
        }
    }
}

@Composable
private fun ConvertPanel(
    input: String,
    onInputChange: (String) -> Unit,
    output: String,
    onOutputChange: (String) -> Unit,
    format: String,
    onFormatChange: (String) -> Unit,
    isConverting: Boolean,
    onSelectInput: () -> Unit,
    onConvert: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("输入文件", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (input.isNotEmpty()) {
                    Text(input, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = onSelectInput, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择文件")
                }
            }
        }
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("输出格式", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp4", "mkv", "avi", "webm", "mp3", "wav", "jpg", "png").forEach { fmt ->
                        FilterChip(
                            selected = format == fmt,
                            onClick = { onFormatChange(fmt) },
                            label = { Text(fmt) }
                        )
                    }
                }
            }
        }
        
        OutlinedTextField(
            value = output,
            onValueChange = onOutputChange,
            label = { Text("输出路径") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Button(
            onClick = onConvert,
            enabled = input.isNotEmpty() && !isConverting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Transform, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始转换")
        }
    }
}

@Composable
private fun MergePanel(
    video: String,
    onVideoSelect: () -> Unit,
    audio: String,
    onAudioSelect: () -> Unit,
    output: String,
    onOutputChange: (String) -> Unit,
    isMerging: Boolean,
    onMerge: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("视频文件", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (video.isNotEmpty()) Text(video, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = onVideoSelect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.VideoLibrary, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择视频")
                }
            }
        }
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("音频文件", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (audio.isNotEmpty()) Text(audio, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = onAudioSelect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.AudioFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择音频")
                }
            }
        }
        
        OutlinedTextField(
            value = output,
            onValueChange = onOutputChange,
            label = { Text("输出路径") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Button(
            onClick = onMerge,
            enabled = video.isNotEmpty() && audio.isNotEmpty() && !isMerging,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.MergeType, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("合并")
        }
    }
}

@Composable
private fun WatermarkPanel(
    input: String,
    onInputSelect: () -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    type: com.toolbox.app.mediatool.WatermarkType,
    onTypeChange: (com.toolbox.app.mediatool.WatermarkType) -> Unit,
    isWatermarking: Boolean,
    onApplyWatermark: () -> Unit,
    detectedWatermark: String?,
    onDetectWatermark: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("输入文件", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                if (input.isNotEmpty()) Text(input, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = onInputSelect, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择文件")
                }
            }
        }
        
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("水印文字") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = type == com.toolbox.app.mediatool.WatermarkType.VISUAL,
                onClick = { onTypeChange(com.toolbox.app.mediatool.WatermarkType.VISUAL) },
                label = { Text("视觉水印") }
            )
            FilterChip(
                selected = type == com.toolbox.app.mediatool.WatermarkType.FREQUENCY,
                onClick = { onTypeChange(com.toolbox.app.mediatool.WatermarkType.FREQUENCY) },
                label = { Text("频率水印") }
            )
        }
        
        Button(
            onClick = onApplyWatermark,
            enabled = input.isNotEmpty() && !isWatermarking,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.WaterDamage, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("添加水印")
        }
        
        Divider()
        
        Text("检测水印", style = MaterialTheme.typography.titleSmall)
        
        Button(
            onClick = onDetectWatermark,
            enabled = input.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Search, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("检测水印")
        }
        
        if (detectedWatermark != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("检测到水印: $detectedWatermark", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}