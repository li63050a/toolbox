package com.toolbox.app.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.content.pm.ServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.toolbox.app.MainActivity
import com.toolbox.app.log.Log
import com.toolbox.app.vpn.mitm.MitmProxy
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * NetGuard 式全流量本地代理 VPN 服务。
 * 前台服务 + tun 隧道 + TcpIp 中继 + DnsProxy 本地 DNS。
 */
class ToolboxVpnService : VpnService() {

    companion object {
        const val CHANNEL_ID = "vpn"
        const val NOTIFICATION_ID = 1
        private const val TAG = "VPN"
    }

    private var relayThread: Thread? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mitmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mitmJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (relayThread == null || !relayThread!!.isAlive) {
            startForegroundCompat(buildNotification("工具箱VPN", "VPN 运行中"))
            // 注入保护接点：本进程所有对外 socket 绕过 tun
            SocketProtector.protectFn = { socket ->
                runCatching { protect(socket) }
                    .onFailure { t -> Log.w(TAG, "protect(socket) 失败: ${t.message}") }
            }
            SocketProtector.datagramProtectFn = { ds ->
                runCatching { protect(ds) }
                    .onFailure { t -> Log.w(TAG, "protect(datagram) 失败: ${t.message}") }
            }
            DnsProxy.start(this)
            syncMitmProxy()
            relayThread = thread(name = "vpn-relay") { relayLoop() }
        }
        return START_NOT_STICKY
    }

    /** 跟随配置启停 MITM（含运行中热切换），配置为 StateFlow 避免异步加载竞态 */
    private fun syncMitmProxy() {
        mitmJob?.cancel()
        mitmJob = mitmScope.launch {
            VpnConfigStore.config.collect { cfg ->
                if (cfg.mitmEnabled) MitmProxy.start(this@ToolboxVpnService)
                else MitmProxy.stop()
            }
        }
    }

    private fun relayLoop() {
        try {
            val fd = establishTun()
            if (fd == null) {
                Log.e(TAG, "establish() 返回 null（授权可能已撤销）")
                VpnController.onError("VPN 授权失效")
                return
            }
            tunFd = fd
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            Log.i(TAG, "tun 已建立，进入中继主循环")
            TcpIp.run(input, output)
        } catch (t: Throwable) {
            Log.e(TAG, "隧道异常终止", t)
            VpnController.onError(t.message ?: "隧道异常终止")
        } finally {
            postNotification("工具箱VPN", "VPN 已断开")
            stopForegroundCompat()
            stopSelf()
            tunFd = null
        }
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val config = VpnConfigStore.config.value
        val builder = Builder()
            .setSession("工具箱VPN")
            .setConfigureIntent(configureIntent())
            .addAddress("10.8.0.2", 32)
            .addAddress("fd00::2", 128)
            // DNS 指向本地回环，由 DnsProxy 监听 127.0.0.1:5353 处理
            .addDnsServer(java.net.InetAddress.getByName("127.0.0.1"))
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setMtu(1500)
            .setBlocking(true)
        for (pkg in config.blockedApps) {
            runCatching { builder.addDisallowedApplication(pkg) }
                .onFailure { t -> Log.w(TAG, "屏蔽应用失败: $pkg ${t.message}") }
        }
        return builder.establish()
    }

    // ------------------------------------------------------------ 通知

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun configureIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildNotification(title: String, text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(configureIntent())
            .setOngoing(true)
            .build()

    @SuppressLint("NewApi")
    private fun startForegroundCompat(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground 失败（前台类型/权限）: ${t.message}", t)
            (getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun postNotification(title: String, text: String) {
        createChannelIfNeeded()
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text))
        }
        Log.i(TAG, "通知: $text")
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID) ?: createChannel()
        }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        VpnConfigStore.init(this)
        VpnController.onServiceStarted()
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN 服务销毁")
        mitmJob?.cancel()
        MitmProxy.stop()
        TcpIp.closeTunOut()        // 关闭输出流 → unblock relayLoop 的 tunIn.read
        DnsProxy.stop()
        SocketProtector.protectFn = null
        SocketProtector.datagramProtectFn = null
        relayThread?.join(3000)    // 等待 relayLoop 的 finally 块执行完毕
        VpnController.onServiceStopped()
        stopForegroundCompat()     // 最后在 onDestroy 里兜底清理通知
        super.onDestroy()
    }
}