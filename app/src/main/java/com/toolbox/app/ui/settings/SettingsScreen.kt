package com.toolbox.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.toolbox.app.R
import com.toolbox.app.data.AppLanguage
import com.toolbox.app.data.SettingsRepository
import com.toolbox.app.data.ThemeMode
import com.toolbox.app.data.UiSettings
import com.toolbox.app.ui.theme.AccentPreset
import com.toolbox.app.ui.theme.BgPreset
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
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知"
        }.getOrElse { "未知" }
    }
    val versionCode = remember(context) {
        runCatching {
            val vc = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
            if (vc > 0) vc.toString() else "1"
        }.getOrElse { "1" }
    }
    val activity = LocalContext.current as? android.app.Activity
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun applyLanguage(language: AppLanguage) {
        repo.applyLanguageSync(language)
        vm.setLanguage(language)
        activity?.recreate()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold) },
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
                Spacer(Modifier.height(8.dp))
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.version), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$versionName ($versionCode)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle(stringResource(R.string.developer))

            DeveloperCard()

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DeveloperCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column {
            DeveloperRow(
                icon = Icons.Filled.Person,
                label = stringResource(R.string.developer_name),
                subtitle = "B站",
                value = "小帅5656"
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            DeveloperRow(
                icon = Icons.Filled.Email,
                label = stringResource(R.string.developer_email),
                value = "li63050@qq.com"
            )
            Divider(modifier = Modifier.padding(horizontal = 16.dp))
            DeveloperRow(
                icon = Icons.Filled.Code,
                label = stringResource(R.string.developer_github),
                value = "github.com/li63050a/toolbox"
            )
        }
    }
}

@Composable
private fun DeveloperRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, subtitle: String = "", value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
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
                Box(
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