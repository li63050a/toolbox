package com.toolbox.app

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.toolbox.app.data.SettingsRepository
import com.toolbox.app.data.UiSettings
import com.toolbox.app.ui.App
import com.toolbox.app.ui.theme.ToolboxTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val repo = SettingsRepository(applicationContext)
        setContent {
            val settings by repo.settings.collectAsState(initial = UiSettings())
            val base = LocalContext.current.applicationContext
            val localized = remember(settings.language) {
                base.createConfigurationContext(
                    Configuration(base.resources.configuration).apply {
                        settings.language.tag?.let { setLocale(Locale.forLanguageTag(it)) }
                    }
                )
            }
            CompositionLocalProvider(LocalContext provides localized) {
                ToolboxTheme(
                    themeMode = settings.themeMode,
                    bgPreset = settings.bgPreset,
                    accentPreset = settings.accentPreset,
                ) {
                    App(repo = repo)
                }
            }
        }
    }
}