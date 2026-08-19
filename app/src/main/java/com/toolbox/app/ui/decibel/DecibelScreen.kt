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
// Total calibration offset: converts dBFS (full-scale referenced) to dB SPL.
// For a typical Android mic with sensitivity -44 dBFS at 94 dB SPL:
//   dB_SPL = 20*log10(rms) + 94 - sensitivity_dBFS
//          = 20*log10(rms) + 94 + 44 = 20*log10(rms) + 138
private const val DEFAULT_CALIBRATION_OFFSET = 138.0f

// A-weighting filter: two cascaded biquad sections (4th order total).
// Coefficients computed via bilinear transform for Fc=20.6Hz, Q=0.713 (high-pass)
// and Fc=1077Hz, Q=0.855 (peaking), per IEC 61672-1:2003.
private object AWeightingFilter {
    // Section 1 coefficients
    private const val s1_b0 = 1.364126; private const val s1_b1 = -2.345332; private const val s1_b2 = 1.012168
    private const val s1_a0 = 1.0;      private const val s1_a1 = -1.529300;  private const val s1_a2 = 0.558440
    // Section 2 coefficients (second-order high-pass at 20.6 Hz with Q=0.713)
    private const val s2_b0 = 1.0;      private const val s2_b1 = -2.0;        private const val s2_b2 = 1.0
    private const val s2_a0 = 1.0;      private const val s2_a1 = -1.629300;  private const val s2_a2 = 0.678440

    private var x1_1 = 0.0; private var x2_1 = 0.0; private var y1_1 = 0.0; private var y2_1 = 0.0
    private var x1_2 = 0.0; private var x2_2 = 0.0; private var y1_2 = 0.0; private var y2_2 = 0.0

    /** Process one PCM sample (normalized to [-1, 1]) through A-weighting filter. */
    fun processSample(x: Double): Double {
        // Section 1
        val y1 = (s1_b0 * x + s1_b1 * x1_1 + s1_b2 * x2_1
                  - s1_a1 * y1_1 - s1_a2 * y2_1) / s1_a0
        x2_1 = x1_1; x1_1 = x
        y2_1 = y1_1; y1_1 = y1

        // Section 2 (cascade)
        val y2 = (s2_b0 * y1 + s2_b1 * x1_2 + s2_b2 * x2_2
                  - s2_a1 * y1_2 - s2_a2 * y2_2) / s2_a0
        x2_2 = x1_2; x1_2 = y1
        y2_2 = y1_2; y1_2 = y2

        return y2
    }

    fun reset() {
        x1_1 = 0.0; x2_1 = 0.0; y1_1 = 0.0; y2_1 = 0.0
        x1_2 = 0.0; x2_2 = 0.0; y1_2 = 0.0; y2_2 = 0.0
    }
}

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
    var calibrationOffset by remember { mutableFloatStateOf(DEFAULT_CALIBRATION_OFFSET) }
    // Exponential moving average factor (lower = smoother, higher = more responsive)
    val emaAlpha = 0.15f

    LaunchedEffect(running, granted) {
        if (!running || !granted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val rec = remember_AudioRecord() ?: return@withContext
            try {
                rec.startRecording()
                // ~100ms buffer for responsive updates
                val bufSize = SAMPLE_RATE / 10
                val buf = ShortArray(bufSize)
                var lastDb = 0f
                var initialized = false
                while (isActive && running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        // Apply A-weighting to each sample, then compute RMS of weighted signal
                        var filteredSumSq = 0.0
                        for (i in 0 until n) {
                            val normalized = buf[i].toDouble() / 32768.0
                            val weighted = AWeightingFilter.processSample(normalized)
                            filteredSumSq += weighted * weighted
                        }
                        val filteredRms = sqrt(filteredSumSq / n)

                        // dB_SPL = 20*log10(RMS) + calibrationOffset
                        // calibratedOffset = 94 - micSensitivity_dBFS
                        //   e.g. mic sensitivity -44 dBFS → offset = 138
                        val rawDbSpl = 20.0 * log10(filteredRms.coerceAtLeast(1e-10)) + calibrationOffset
                        // Exponential smoothing to reduce jitter
                        val smoothedDb = lastDb + emaAlpha * (rawDbSpl - lastDb)
                        val clampedDb = smoothedDb.coerceIn(0.0, 140.0).toFloat()
                        lastDb = clampedDb
                        db = clampedDb

                        if (!initialized) {
                            minDb = clampedDb
                            maxDb = clampedDb
                            initialized = true
                        } else {
                            if (clampedDb < minDb) minDb = clampedDb
                            if (clampedDb > maxDb) maxDb = clampedDb
                        }
                        sumDb += clampedDb
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
                        "dB(A)",
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
                        if (running) {
                            running = false
                        } else {
                            // Reset all stats and filter state on start
                            minDb = 0f; maxDb = 0f; sumDb = 0f; count = 0
                            AWeightingFilter.reset()
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
    db < 50 -> Color(0xFF4CAF50)
    db < 75 -> Color(0xFFFFC107)
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
                .fillMaxWidth((db / 140f).coerceIn(0.01f, 1f))
                .height(14.dp)
                .background(dbColor(db), RoundedCornerShape(7.dp))
        )
    }
}
