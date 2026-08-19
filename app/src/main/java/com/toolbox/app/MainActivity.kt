package com.toolbox.app

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.toolbox.app.data.SettingsRepository
import com.toolbox.app.data.UiSettings
import com.toolbox.app.ui.App
import com.toolbox.app.ui.theme.ToolboxTheme
import java.util.Locale

/** 记录当前已应用到 Activity 的语言 tag（attachBaseContext 时更新） */
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
                    setLocale(Locale.forLanguageTag(tag))
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
                App(repo = repo)
            }
        }
    }
}