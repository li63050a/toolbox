package com.toolbox.app.ui.splash

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.toolbox.app.R
import com.toolbox.app.data.SplashType
import com.toolbox.app.data.UiSettings
import com.toolbox.app.data.SettingsRepository
import kotlinx.coroutines.launch
import android.widget.VideoView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplashSettingsScreen(
    settings: UiSettings,
    repo: SettingsRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var splashType by remember { mutableStateOf(settings.splashType) }
    var splashColor by remember { mutableStateOf(settings.splashColor) }
    var splashDuration by remember { mutableStateOf(settings.splashDuration.toString()) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                repo.setSplashImage(it.toString())
            }
        }
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            scope.launch {
                repo.setSplashVideo(it.toString())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.splash_settings_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.file_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 开屏类型选择
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.splash_type), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SplashType.entries.forEach { type ->
                            FilterChip(
                                selected = splashType == type,
                                onClick = {
                                    splashType = type
                                    scope.launch { repo.setSplashType(type) }
                                },
                                label = { Text(type.label) }
                            )
                        }
                    }
                }
            }

            // 颜色选择
            if (splashType == SplashType.COLOR) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.splash_color), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(48.dp)
                                    .background(Color(android.graphics.Color.parseColor(splashColor)), RoundedCornerShape(8.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            OutlinedTextField(
                                value = splashColor,
                                onValueChange = { splashColor = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                    }
                }
            }

            // 图片选择
            if (splashType == SplashType.IMAGE) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.splash_image), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        settings.splashImagePath?.let { path ->
                            AsyncImage(
                                model = path,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick = { imagePicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Image, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.select_image))
                        }
                    }
                }
            }

            // 视频选择
            if (splashType == SplashType.VIDEO) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(stringResource(R.string.splash_video), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { videoPicker.launch("video/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.VideoLibrary, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.select_video))
                        }
                    }
                }
            }

            // 持续时间
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.splash_duration), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = splashDuration,
                        onValueChange = { splashDuration = it },
                        label = { Text(stringResource(R.string.duration_ms)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1000", "2000", "3000", "5000").forEach { ms ->
                            OutlinedButton(onClick = {
                                splashDuration = ms
                                scope.launch { repo.setSplashDuration(ms.toInt()) }
                            }) { Text("$ms ms") }
                        }
                    }
                }
            }

            // 预览
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.preview), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    SplashPreview(
                        type = splashType,
                        color = splashColor,
                        imagePath = settings.splashImagePath,
                        videoPath = settings.splashVideoPath
                    )
                }
            }
        }
    }
}

@Composable
private fun SplashPreview(
    type: SplashType,
    color: String,
    imagePath: String?,
    videoPath: String?
) {
    when (type) {
        SplashType.COLOR -> {
            Box(
                modifier = Modifier.fillMaxWidth().height(100.dp)
                    .background(Color(android.graphics.Color.parseColor(color))),
                contentAlignment = Alignment.Center
            ) {
                Text("纯色预览", color = Color.White)
            }
        }
        SplashType.IMAGE -> {
            if (imagePath != null) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未选择图片", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        SplashType.VIDEO -> {
            if (videoPath != null) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.parse(videoPath))
                            setOnErrorListener { _, _, _ -> false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("未选择视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}