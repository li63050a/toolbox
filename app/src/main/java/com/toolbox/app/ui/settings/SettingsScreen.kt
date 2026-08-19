package com.toolbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.data.AppLanguage
import com.toolbox.app.data.SettingsRepository
import com.toolbox.app.data.ThemeMode
import com.toolbox.app.data.UiSettings
import com.toolbox.app.ui.theme.AccentPreset
import com.toolbox.app.ui.theme.BgPreset
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {
    val settings = repo.settings

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { repo.setThemeMode(mode) }
    fun setBgPreset(preset: BgPreset) = viewModelScope.launch { repo.setBgPreset(preset) }
    fun setAccentPreset(preset: AccentPreset) = viewModelScope.launch { repo.setAccentPreset(preset) }
    fun setLanguage(language: AppLanguage) = viewModelScope.launch { repo.setLanguage(language) }
}

private data class RadioOption<T>(val label: String, val value: T)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    onBack: () -> Unit
) {
    val vm: SettingsViewModel = viewModel { SettingsViewModel(repo) }
    val settings by vm.settings.collectAsState(initial = UiSettings())
    val activity = LocalContext.current as? android.app.Activity
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun applyLanguage(language: AppLanguage) {
        repo.applyLanguageSync(language) // 先同步落盘，recreate 后 attachBaseContext 立即生效
        vm.setLanguage(language)
        activity?.recreate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            SectionTitle(stringResource(R.string.appearance))

            SettingsCard {
                OptionGroup(
                    title = stringResource(R.string.theme_mode),
                    options = listOf(
                        RadioOption(stringResource(R.string.theme_system), ThemeMode.SYSTEM),
                        RadioOption(stringResource(R.string.theme_light), ThemeMode.LIGHT),
                        RadioOption(stringResource(R.string.theme_dark), ThemeMode.DARK),
                    ),
                    selected = settings.themeMode,
                    onSelect = vm::setThemeMode,
                )
                Divider()
                LabeledText(stringResource(R.string.bg_color))
                ColorPalette(
                    items = BgPreset.entries.map { it to (if (dark) it.dark else it.light) },
                    selected = settings.bgPreset,
                    label = { it.labelRes },
                    onSelect = vm::setBgPreset,
                )
                LabeledText(stringResource(R.string.accent_color))
                ColorPalette(
                    items = AccentPreset.entries.map { it to (if (dark) it.dark else it.light) },
                    selected = settings.accentPreset,
                    label = { it.labelRes },
                    onSelect = vm::setAccentPreset,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.language))

            SettingsCard {
                OptionGroup(
                    title = stringResource(R.string.language),
                    options = listOf(
                        RadioOption(stringResource(R.string.lang_system), AppLanguage.SYSTEM),
                        RadioOption(stringResource(R.string.lang_zh), AppLanguage.ZH),
                        RadioOption(stringResource(R.string.lang_en), AppLanguage.EN),
                    ),
                    selected = settings.language,
                    onSelect = ::applyLanguage,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle(stringResource(R.string.about))

            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.version), style = MaterialTheme.typography.bodyMedium)
                    Text("1.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(4.dp)) {
            content()
        }
    }
}

@Composable
private fun Divider() {
    androidx.compose.material3.HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun LabeledText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<RadioOption<T>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        options.forEach { opt ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(opt.value) }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = opt.value == selected, onClick = { onSelect(opt.value) })
                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ColorPalette(
    items: List<Pair<T, Color>>,
    selected: T,
    label: (T) -> Int,
    onSelect: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (value, color) ->
            val isSel = value == selected
            Column(
                Modifier
                    .clip(CircleShape)
                    .clickable { onSelect(value) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(if (isSel) 44.dp else 38.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSel) 3.dp else 1.dp,
                            color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                )
                Text(
                    stringResource(label(value)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}