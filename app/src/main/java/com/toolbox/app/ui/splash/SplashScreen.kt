package com.toolbox.app.ui.splash

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.toolbox.app.data.SplashType
import com.toolbox.app.data.UiSettings
import com.toolbox.app.data.SettingsRepository
import kotlinx.coroutines.delay
import android.widget.VideoView

@Composable
fun SplashScreen(
    settings: UiSettings,
    repo: SettingsRepository,
    onFinished: () -> Unit
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(settings.splashDuration.toLong())
        visible = false
        onFinished()
    }

    if (visible) {
        when (settings.splashType) {
            SplashType.COLOR -> {
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(Color(android.graphics.Color.parseColor(settings.splashColor))),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                }
            }
            SplashType.IMAGE -> {
                if (settings.splashImagePath != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = Uri.parse(settings.splashImagePath),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(Color(0xFF1A73E8)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
            SplashType.VIDEO -> {
                if (settings.splashVideoPath != null) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoURI(Uri.parse(settings.splashVideoPath))
                                setOnPreparedListener { mp ->
                                    mp.isLooping = false
                                    mp.start()
                                }
                                setOnCompletionListener {
                                    visible = false
                                    onFinished()
                                }
                                start()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(Color(0xFF1A73E8)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}