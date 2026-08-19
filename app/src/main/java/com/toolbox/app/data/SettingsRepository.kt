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

enum class SplashType(val raw: String, val label: String) {
    COLOR("color", "纯色背景"),
    IMAGE("image", "图片"),
    VIDEO("video", "视频")
}

data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bgPreset: BgPreset = BgPreset.DEFAULT,
    val accentPreset: AccentPreset = AccentPreset.INDIGO,
    val language: AppLanguage = AppLanguage.ZH,
    val splashType: SplashType = SplashType.COLOR,
    val splashColor: String = "#1A73E8",
    val splashImagePath: String? = null,
    val splashVideoPath: String? = null,
    val splashDuration: Int = 2000
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BG_PRESET = stringPreferencesKey("bg_preset")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val LANGUAGE = stringPreferencesKey("language")
        val SPLASH_TYPE = stringPreferencesKey("splash_type")
        val SPLASH_COLOR = stringPreferencesKey("splash_color")
        val SPLASH_IMAGE = stringPreferencesKey("splash_image")
        val SPLASH_VIDEO = stringPreferencesKey("splash_video")
        val SPLASH_DURATION = stringPreferencesKey("splash_duration")
    }

    val settings: Flow<UiSettings> = context.settingsStore.data.map { prefs ->
        UiSettings(
            themeMode = ThemeMode.entries.firstOrNull { it.raw == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM,
            bgPreset = BgPreset.entries.firstOrNull { it.raw == prefs[Keys.BG_PRESET] } ?: BgPreset.DEFAULT,
            accentPreset = AccentPreset.entries.firstOrNull { it.raw == prefs[Keys.ACCENT_PRESET] } ?: AccentPreset.INDIGO,
            language = AppLanguage.entries.firstOrNull { it.raw == prefs[Keys.LANGUAGE] } ?: AppLanguage.ZH,
            splashType = SplashType.entries.firstOrNull { it.raw == prefs[Keys.SPLASH_TYPE] } ?: SplashType.COLOR,
            splashColor = prefs[Keys.SPLASH_COLOR] ?: "#1A73E8",
            splashImagePath = prefs[Keys.SPLASH_IMAGE],
            splashVideoPath = prefs[Keys.SPLASH_VIDEO],
            splashDuration = prefs[Keys.SPLASH_DURATION]?.toIntOrNull() ?: 2000
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

    suspend fun setLanguage(language: AppLanguage) {
        applyLanguageSync(language)
        context.settingsStore.edit { it[Keys.LANGUAGE] = language.raw }
    }

    suspend fun setSplashType(type: SplashType) {
        context.settingsStore.edit { it[Keys.SPLASH_TYPE] = type.raw }
    }

    suspend fun setSplashColor(color: String) {
        context.settingsStore.edit { it[Keys.SPLASH_COLOR] = color }
    }

    suspend fun setSplashImage(path: String?) {
        context.settingsStore.edit { it[Keys.SPLASH_IMAGE] = path ?: "" }
    }

    suspend fun setSplashVideo(path: String?) {
        context.settingsStore.edit { it[Keys.SPLASH_VIDEO] = path ?: "" }
    }

    suspend fun setSplashDuration(duration: Int) {
        context.settingsStore.edit { it[Keys.SPLASH_DURATION] = duration.toString() }
    }

    fun applyLanguageSync(language: AppLanguage) {
        context.getSharedPreferences("settings_locale", Context.MODE_PRIVATE)
            .edit()
            .putString("tag", language.tag)
            .commit()
    }
}