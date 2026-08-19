package com.toolbox.app.ui.decibel

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.toolbox.app.R
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val SAMPLE_RATE = 44100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecibelScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    var running by remember { mutableStateOf(false) }
    var db by remember { mutableFloatStateOf(0f) }
    var minDb by remember { mutableFloatStateOf(0f) }
    var maxDb by remember { mutableFloatStateOf(0f) }
    var sumDb by remember { mutableFloatStateOf(0f) }
    var count by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(30f) }

    LaunchedEffect(running, granted) {
        if (!running || !granted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val rec = remember_AudioRecord() ?: return@withContext
            try {
                rec.startRecording()
                val buf = ShortArray(SAMPLE_RATE / 10)
                var lastDb = 0f
                while (isActive && running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) {
                            val v = buf[i].toDouble()
                            sum += v * v
                        }
                        val rms = sqrt(sum / n)
                        lastDb = (20.0 * log10(rms / 32768.0) + offset).coerceIn(0.0, 120.0).toFloat()
                        db = lastDb
                        if (count == 0) { minDb = lastDb; maxDb = lastDb }
                        if (lastDb < minDb) minDb = lastDb
                        if (lastDb > maxDb) maxDb = lastDb
                        sumDb += lastDb
                        count++
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.decibel_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.decibel_back)) }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "${db.roundToInt()}",
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold,
                        color = dbColor(db)
                    )
                    Text(
                        "dB",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    MeteBar(db, Modifier.fillMaxWidth().padding(top = 16.dp).height(14.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Stat("${stringResource(R.string.decibel_min)} ${minDb.roundToInt()}")
                        Stat("${stringResource(R.string.decibel_avg)} ${if (count == 0) 0 else (sumDb / count).roundToInt()}")
                        Stat("${stringResource(R.string.decibel_max)} ${maxDb.roundToInt()}")
                    }
                }
            }

            Text(
                stringResource(R.string.decibel_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            if (!granted) {
                Button(onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Icon(Icons.Filled.Mic, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.decibel_need_permission))
                }
            } else {
                Button(
                    onClick = {
                        if (running) { running = false } else {
                            minDb = 0f; maxDb = 0f; sumDb = 0f; count = 0
                            running = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text(
                        if (running) stringResource(R.string.decibel_stop) else stringResource(R.string.decibel_start),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun remember_AudioRecord(): AudioRecord? = runCatching {
    val minBuf = AudioRecord.getMinBufferSize(
        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    )
    if (minBuf <= 0) return@runCatching null
    AudioRecord(
        MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
    )
}.getOrNull()

private fun dbColor(db: Float): Color = when {
    db < 40 -> Color(0xFF4CAF50)
    db < 70 -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

@Composable
private fun Stat(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun MeteBar(db: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(7.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth((db / 120f).coerceIn(0.06f, 1f))
                .height(14.dp)
                .background(dbColor(db), RoundedCornerShape(7.dp))
        )
    }
}