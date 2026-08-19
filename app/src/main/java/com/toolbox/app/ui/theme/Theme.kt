package com.toolbox.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.toolbox.app.data.ThemeMode

/** 背景色预设，raw 用于持久化 */
enum class BgPreset(val raw: String, val light: Color, val dark: Color, val labelRes: Int) {
    DEFAULT("default", Color(0xFFFBF8FF), Color(0xFF121212), com.toolbox.app.R.string.bg_default),
    GRAY("gray", Color(0xFFF3F3F2), Color(0xFF1C1C1E), com.toolbox.app.R.string.bg_gray),
    CREAM("cream", Color(0xFFF8F3E9), Color(0xFF1F1A12), com.toolbox.app.R.string.bg_cream),
    SKY("sky", Color(0xFFEFF4FA), Color(0xFF101A2A), com.toolbox.app.R.string.bg_sky),
    GREEN("green", Color(0xFFEDF5F0), Color(0xFF0E1F18), com.toolbox.app.R.string.bg_green),
    ROSE("rose", Color(0xFFFAF0F2), Color(0xFF231419), com.toolbox.app.R.string.bg_rose),
}

/** 主色预设 */
enum class AccentPreset(val raw: String, val light: Color, val dark: Color, val labelRes: Int) {
    INDIGO("indigo", Color(0xFF4A5BD6), Color(0xFFAEB8FF), com.toolbox.app.R.string.accent_indigo),
    BLUE("blue", Color(0xFF1E6FD9), Color(0xFF9FC7FF), com.toolbox.app.R.string.accent_blue),
    TEAL("teal", Color(0xFF0F8771), Color(0xFF8AD9C6), com.toolbox.app.R.string.accent_teal),
    RED("red", Color(0xFFD33A3A), Color(0xFFFFA6A6), com.toolbox.app.R.string.accent_red),
    AMBER("amber", Color(0xFFB87900), Color(0xFFFFD78F), com.toolbox.app.R.string.accent_amber),
    PURPLE("purple", Color(0xFF8B3FD8), Color(0xFFD6AFFF), com.toolbox.app.R.string.accent_purple),
}

private fun mix(base: Color, target: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = base.red + (target.red - base.red) * f,
        green = base.green + (target.green - base.green) * f,
        blue = base.blue + (target.blue - base.blue) * f,
        alpha = base.alpha,
    )
}

private fun presetScheme(bg: BgPreset, accent: AccentPreset, dark: Boolean): ColorScheme {
    val background = if (dark) bg.dark else bg.light
    val primary = if (dark) accent.dark else accent.light
    val surfaceVariant = if (dark) mix(background, Color.White, 0.06f) else mix(background, Color.Black, 0.05f)
    val outline = if (dark) mix(background, Color.White, 0.18f) else mix(background, Color.Black, 0.22f)
    val surfaceContainer = if (dark) mix(background, Color.White, 0.04f) else mix(background, Color.Black, 0.03f)
    val surface = if (dark) mix(background, Color.White, 0.02f) else mix(background, Color.Black, 0.01f)
    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF181818),
            primaryContainer = mix(primary, Color.White, 0.15f),
            onPrimaryContainer = Color(0xFFFFFFFF),
            secondary = mix(primary, Color.White, 0.35f),
            background = background,
            onBackground = Color(0xFFE6E6E6),
            surface = surface,
            onSurface = Color(0xFFE6E6E6),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = Color(0xFFB8B8B8),
            outline = outline,
            error = Color(0xFFFF8A8A),
            outlineVariant = surfaceVariant,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color(0xFFFFFFFF),
            primaryContainer = mix(primary, Color.White, 0.82f),
            onPrimaryContainer = mix(primary, Color.Black, 0.35f),
            secondary = mix(primary, Color.Black, 0.12f),
            background = background,
            onBackground = Color(0xFF1B1B1B),
            surface = surface,
            onSurface = Color(0xFF1B1B1B),
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = Color(0xFF555555),
            outline = outline,
            outlineVariant = surfaceVariant,
            error = Color(0xFFB3261E),
        )
    }
}

@Composable
fun ToolboxTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    bgPreset: BgPreset = BgPreset.DEFAULT,
    accentPreset: AccentPreset = AccentPreset.INDIGO,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = presetScheme(bgPreset, accentPreset, dark),
        content = content
    )
}