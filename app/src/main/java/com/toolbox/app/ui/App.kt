package com.toolbox.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.toolbox.app.data.SettingsRepository
import com.toolbox.app.ui.ftp.FtpHomeScreen
import com.toolbox.app.ui.home.HomeScreen
import com.toolbox.app.ui.log.LogScreen
import com.toolbox.app.ui.oss.OssHomeScreen
import com.toolbox.app.ui.settings.SettingsScreen
import com.toolbox.app.ui.ssh.SshHomeScreen
import com.toolbox.app.ui.vpn.VpnScreen

object Routes {
    const val HOME = "home"
    const val SSH = "ssh"
    const val FTP = "ftp"
    const val OSS = "oss"
    const val VPN = "vpn"
    const val LOG = "log"
    const val SETTINGS = "settings"
}

@Composable
fun App(repo: SettingsRepository) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(onOpen = { navController.navigate(it) }) }
        composable(Routes.SSH) { SshHomeScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.FTP) { FtpHomeScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.OSS) { OssHomeScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.VPN) { VpnScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.LOG) { LogScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(repo = repo, onBack = { navController.popBackStack() }) }
    }
}