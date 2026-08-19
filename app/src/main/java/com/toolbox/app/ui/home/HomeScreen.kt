package com.toolbox.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.ui.Routes

private data class Feature(
    val route: String,
    val titleRes: Int,
    val descRes: Int,
    val icon: ImageVector
)

private val features = listOf(
    Feature(Routes.FILES, R.string.home_files_title, R.string.home_files_desc, Icons.Filled.FolderOpen),
    Feature(Routes.SSH, R.string.home_ssh_title, R.string.home_ssh_desc, Icons.Filled.Terminal),
    Feature(Routes.SHIZUKU, R.string.home_shizuku_title, R.string.home_shizuku_desc, Icons.Filled.Shield),
    Feature(Routes.VPN, R.string.home_vpn_title, R.string.home_vpn_desc, Icons.Filled.Dns),
    Feature(Routes.LOG, R.string.home_log_title, R.string.home_log_desc, Icons.Filled.Article),
    Feature(Routes.DECIBEL, R.string.home_decibel_title, R.string.home_decibel_desc, Icons.Filled.GraphicEq),
    Feature(Routes.DOWNLOAD, R.string.home_dl_title, R.string.home_dl_desc, Icons.Filled.Download),
    Feature(Routes.ADB, R.string.home_adb_title, R.string.home_adb_desc, Icons.Filled.Devices),
    Feature(Routes.SETTINGS, R.string.settings, R.string.settings_desc, Icons.Filled.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    stringResource(R.string.app_name),
                    fontWeight = FontWeight.Bold
                )
            })
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(features) { feature ->
                FeatureCard(feature) { onOpen(feature.route) }
            }
        }
    }
}

@Composable
private fun FeatureCard(feature: Feature, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    feature.icon,
                    null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(stringResource(feature.titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(feature.descRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}