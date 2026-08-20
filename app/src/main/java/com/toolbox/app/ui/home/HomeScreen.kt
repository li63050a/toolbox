package com.toolbox.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.toolbox.app.R
import com.toolbox.app.ui.Routes
import kotlinx.coroutines.launch

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
    Feature(Routes.MEDIA_TOOL, R.string.home_media_tool_title, R.string.home_media_tool_desc, Icons.Filled.VideoLibrary),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current

    val isTablet = configuration.smallestScreenWidthDp >= 600
    val drawerModifier: Modifier = if (isTablet) {
        val w = configuration.screenWidthDp * 2f / 5f
        Modifier.width(w.dp)
    } else {
        Modifier.fillMaxSize()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = drawerModifier) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Apps, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text("工具箱", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Divider()

                LazyColumn {
                    item { Text("网络工具", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
                    item { NavItem(Icons.Filled.Terminal, "SSH终端", Routes.SSH) { scope.launch { drawerState.close() }; onOpen(Routes.SSH) } }
                    item { NavItem(Icons.Filled.Dns, "VPN代理", Routes.VPN) { scope.launch { drawerState.close() }; onOpen(Routes.VPN) } }
                    item { NavItem(Icons.Filled.Shield, "Shizuku", Routes.SHIZUKU) { scope.launch { drawerState.close() }; onOpen(Routes.SHIZUKU) } }
                    item { NavItem(Icons.Filled.Devices, "ADB管理器", Routes.ADB) { scope.launch { drawerState.close() }; onOpen(Routes.ADB) } }
                    
                    item { Text("文件工具", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
                    item { NavItem(Icons.Filled.FolderOpen, "文件管理器", Routes.FILES) { scope.launch { drawerState.close() }; onOpen(Routes.FILES) } }
                    item { NavItem(Icons.Filled.Download, "下载器", Routes.DOWNLOAD) { scope.launch { drawerState.close() }; onOpen(Routes.DOWNLOAD) } }
                    item { NavItem(Icons.Filled.VideoLibrary, "媒体工具", Routes.MEDIA_TOOL) { scope.launch { drawerState.close() }; onOpen(Routes.MEDIA_TOOL) } }
                    
                    item { Text("其他工具", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
                    item { NavItem(Icons.Filled.GraphicEq, "分贝仪", Routes.DECIBEL) { scope.launch { drawerState.close() }; onOpen(Routes.DECIBEL) } }
                    item { NavItem(Icons.Filled.Article, "日志查看", Routes.LOG) { scope.launch { drawerState.close() }; onOpen(Routes.LOG) } }
                    
                    item { Spacer(Modifier.height(8.dp)) }
                    item { Divider() }
                    item { Text("系统", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(16.dp, 8.dp)) }
                    item { NavItem(Icons.Filled.Settings, "设置", Routes.SETTINGS) { scope.launch { drawerState.close() }; onOpen(Routes.SETTINGS) } }
                    item { NavItem(Icons.Filled.Person, "开发者信息", Routes.ABOUT) { scope.launch { drawerState.close() }; onOpen(Routes.ABOUT) } }
                    item { Spacer(Modifier.height(32.dp)) }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, "菜单")
                        }
                    }
                )
            }
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(features) { feature ->
                    FeatureCard(feature) { onOpen(feature.route) }
                }
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, route: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FeatureCard(feature: Feature, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(feature.icon, null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(stringResource(feature.titleRes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(feature.descRes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}