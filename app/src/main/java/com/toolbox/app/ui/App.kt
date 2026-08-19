package com.toolbox.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.toolbox.app.data.SettingsRepository
import com.toolbox.app.ui.decibel.DecibelScreen
import com.toolbox.app.ui.downloader.DownloaderScreen
import com.toolbox.app.ui.files.FilesScreen
import com.toolbox.app.ui.ftp.FtpHomeScreen
import com.toolbox.app.ui.home.HomeScreen
import com.toolbox.app.ui.log.LogScreen
import com.toolbox.app.ui.settings.SettingsScreen
import com.toolbox.app.ui.shizuku.ShizukuScreen
import com.toolbox.app.ui.ssh.SshHomeScreen
import com.toolbox.app.ui.vpn.VpnScreen

object Routes {
    const val HOME = "home"
    const val FILES = "files"
    const val DECIBEL = "decibel"
    const val DOWNLOAD = "download"
    const val SSH = "ssh"
    const val VPN = "vpn"
    const val SHIZUKU = "shizuku"
    const val LOG = "log"
    const val SETTINGS = "settings"
}

@Composable
fun App(repo: SettingsRepository) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(onOpen = { navController.navigate(it) }) }
        composable(Routes.FILES) { FilesScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.DECIBEL) { DecibelScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.DOWNLOAD) { DownloaderScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SSH) { SshHomeScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.VPN) { VpnScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SHIZUKU) { ShizukuScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.LOG) { LogScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsScreen(repo = repo, onBack = { navController.popBackStack() }) }
    }
}