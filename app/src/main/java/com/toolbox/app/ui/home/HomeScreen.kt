package com.toolbox.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    var drawerOpen by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
        drawerContent = {
            ModalDrawerSheet {
                // 顶部标题
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Menu, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("工具箱", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
                Divider()

                // 功能分类 - 网络工具
                Text(
                    "网络工具",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
                DrawerItem(Icons.Filled.Terminal, "SSH终端", "ssh") { drawerOpen = false; onOpen("ssh") }
                DrawerItem(Icons.Filled.Dns, "VPN代理", "vpn") { drawerOpen = false; onOpen("vpn") }
                DrawerItem(Icons.Filled.Shield, "Shizuku", "shizuku") { drawerOpen = false; onOpen("shizuku") }
                DrawerItem(Icons.Filled.Devices, "ADB管理器", "adb") { drawerOpen = false; onOpen("adb") }

                // 功能分类 - 文件工具
                Text(
                    "文件工具",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
                DrawerItem(Icons.Filled.FolderOpen, "文件管理器", "files") { drawerOpen = false; onOpen("files") }
                DrawerItem(Icons.Filled.Download, "下载器", "download") { drawerOpen = false; onOpen("download") }

                // 功能分类 - 其他工具
                Text(
                    "其他工具",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
                DrawerItem(Icons.Filled.GraphicEq, "分贝仪", "decibel") { drawerOpen = false; onOpen("decibel") }
                DrawerItem(Icons.Filled.Article, "日志查看", "log") { drawerOpen = false; onOpen("log") }

                Spacer(Modifier.weight(1f))
                Divider()

                // 底部菜单
                DrawerItem(Icons.Filled.Settings, "设置", "settings") { drawerOpen = false; onOpen("settings") }
                DrawerItem(Icons.Filled.Person, "开发者信息", "about") { drawerOpen = false; onOpen("about") }
                Spacer(Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("") },
                    navigationIcon = {
                        IconButton(onClick = { drawerOpen = true }) {
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
private fun DrawerItem(icon: ImageVector, label: String, route: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
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