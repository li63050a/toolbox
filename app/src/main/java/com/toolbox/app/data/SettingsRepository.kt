package com.toolbox.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toolbox.app.ui.theme.AccentPreset
import com.toolbox.app.ui.theme.BgPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val raw: String) { SYSTEM("system"), LIGHT("light"), DARK("dark") }

enum class AppLanguage(val raw: String, val tag: String?) {
    SYSTEM("system", null), ZH("zh", "zh"), EN("en", "en")
}

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bgPreset: BgPreset = BgPreset.DEFAULT,
    val accentPreset: AccentPreset = AccentPreset.INDIGO,
    val language: AppLanguage = AppLanguage.ZH
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BG_PRESET = stringPreferencesKey("bg_preset")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val LANGUAGE = stringPreferencesKey("language")
    }

    val settings: Flow<UiSettings> = context.settingsStore.data.map { prefs ->
        UiSettings(
            themeMode = ThemeMode.entries.firstOrNull { it.raw == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM,
            bgPreset = BgPreset.entries.firstOrNull { it.raw == prefs[Keys.BG_PRESET] } ?: BgPreset.DEFAULT,
            accentPreset = AccentPreset.entries.firstOrNull { it.raw == prefs[Keys.ACCENT_PRESET] } ?: AccentPreset.INDIGO,
            language = AppLanguage.entries.firstOrNull { it.raw == prefs[Keys.LANGUAGE] } ?: AppLanguage.ZH,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[Keys.THEME_MODE] = mode.raw }
    }

    suspend fun setBgPreset(preset: BgPreset) {
        context.settingsStore.edit { it[Keys.BG_PRESET] = preset.raw }
    }

    suspend fun setAccentPreset(preset: AccentPreset) {
        context.settingsStore.edit { it[Keys.ACCENT_PRESET] = preset.raw }
    }

    /** 语言切换：先同步写 SharedPreferences（供 Activity.attachBaseContext 读取），再写 DataStore */
    suspend fun setLanguage(language: AppLanguage) {
        applyLanguageSync(language)
        context.settingsStore.edit { it[Keys.LANGUAGE] = language.raw }
    }

    /** 同步写入语言，Activity re-create 时 attachBaseContext 会立即读到 */
    fun applyLanguageSync(language: AppLanguage) {
        context.getSharedPreferences("settings_locale", Context.MODE_PRIVATE)
            .edit()
            .putString("tag", language.tag)
            .commit()
    }
}