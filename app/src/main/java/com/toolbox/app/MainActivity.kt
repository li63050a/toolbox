package com.toolbox.app

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.toolbox.app.data.SettingsRepository
import com.toolbox.app.data.UiSettings
import com.toolbox.app.ui.App
import com.toolbox.app.ui.splash.SplashScreen
import com.toolbox.app.ui.theme.ToolboxTheme
import java.util.Locale

object LocaleState {
    var appliedTag: String? = null
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val tag = newBase.getSharedPreferences("settings_locale", MODE_PRIVATE).getString("tag", null)
        LocaleState.appliedTag = tag
        val base = if (!tag.isNullOrBlank()) {
            newBase.createConfigurationContext(
                Configuration(newBase.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(tag))
                }
            )
        } else {
            newBase
        }
        super.attachBaseContext(base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val repo = SettingsRepository(applicationContext)
        setContent {
            val settings by repo.settings.collectAsState(initial = UiSettings())
            ToolboxTheme(
                themeMode = settings.themeMode,
                bgPreset = settings.bgPreset,
                accentPreset = settings.accentPreset,
            ) {
                SplashHost(repo = repo, settings = settings)
            }
        }
    }
}

@Composable
private fun SplashHost(repo: SettingsRepository, settings: UiSettings) {
    var showMain by remember { mutableStateOf(false) }
    if (showMain) {
        App(repo = repo)
    } else {
        SplashScreen(
            settings = settings,
            repo = repo,
            onFinished = { showMain = true }
        )
    }
}