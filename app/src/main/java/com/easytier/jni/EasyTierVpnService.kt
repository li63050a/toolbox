package com.easytier.jni

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.toolbox.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * EasyTier VPN 服务：建立 TUN 接口并将 fd 传递给 native 层。
 */
class EasyTierVpnService : VpnService() {

    companion object {
        private const val TAG = "EasyTierVpn"
        const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "easytier_vpn"
        const val ACTION_STOP = "com.easytier.jni.action.EASYTIER_STOP"
        const val EXTRA_INSTANCE_NAME = "instance_name"
        const val EXTRA_IPV4_ADDRESS = "ipv4_address"
        private const val PREFS_NAME = "easytier_vpn_state"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private val lock = Any()
    private val setupExecutor = Executors.newSingleThreadExecutor { Thread(it, "easytier-setup") }
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Log.d(TAG, "EasyTierVpnService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("EasyTier", "正在建立连接…"))
        val params = parseIntent(intent)
        if (params == null) {
            Log.e(TAG, "缺少 VPN 参数，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }
        launchSetup(params)
        return START_STICKY
    }

    private fun parseIntent(intent: Intent?): VpnServiceParams? {
        if (intent != null) {
            val ipv4 = intent.getStringExtra(EXTRA_IPV4_ADDRESS)
            val inst = intent.getStringExtra(EXTRA_INSTANCE_NAME)
            if (ipv4 != null && inst != null) {
                return VpnServiceParams(
                    instanceName = inst,
                    ipv4Address = ipv4,
                    proxyCidrs = intent.getStringArrayListExtra("proxy_cidrs") ?: emptyList(),
                    dnsServers = intent.getStringArrayListExtra("dns_servers") ?: emptyList()
                )
            }
        }
        return restoreFromPrefs()
    }

    private data class VpnServiceParams(
        val instanceName: String,
        val ipv4Address: String,
        val proxyCidrs: List<String>,
        val dnsServers: List<String>
    )

    private fun saveParams(p: VpnServiceParams) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("instance_name", p.instanceName)
            putString("ipv4_address", p.ipv4Address)
            putString("proxy_cidrs", p.proxyCidrs.joinToString(","))
            putString("dns_servers", p.dnsServers.joinToString(","))
            apply()
        }
    }

    private fun restoreFromPrefs(): VpnServiceParams? {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val inst = sp.getString("instance_name", null) ?: return null
        val ipv4 = sp.getString("ipv4_address", null) ?: return null
        return VpnServiceParams(
            instanceName = inst,
            ipv4Address = ipv4,
            proxyCidrs = (sp.getString("proxy_cidrs", "") ?: "").split(",").filter { it.isNotEmpty() },
            dnsServers = (sp.getString("dns_servers", "") ?: "").split(",").filter { it.isNotEmpty() }
        )
    }

    private fun launchSetup(params: VpnServiceParams) {
        setupExecutor.execute {
            try {
                setupInterface(params)
            } catch (t: Throwable) {
                Log.e(TAG, "VPN 建立失败", t)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun setupInterface(params: VpnServiceParams) {
        val builder = Builder()
            .setSession("EasyTier")
            .setConfigureIntent(configureIntent())
        val ipParts = params.ipv4Address.split("/")
        val ipAddr = ipParts[0]
        val netLen = ipParts.getOrNull(1)?.toIntOrNull() ?: 24
        builder.addAddress(ipAddr, netLen)
        builder.addDisallowedApplication(packageName)

        val dnsList = if (params.dnsServers.isEmpty()) listOf("223.5.5.5", "114.114.114.114") else params.dnsServers
        dnsList.forEach { try { builder.addDnsServer(it) } catch (_: Exception) {} }

        if (params.proxyCidrs.isEmpty()) {
            builder.addRoute("0.0.0.0", 0)
        } else {
            params.proxyCidrs.forEach { cidr ->
                val parts = cidr.split("/")
                try { builder.addRoute(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 0) } catch (_: Exception) {}
            }
        }

        builder.setMtu(1400).setBlocking(true)
        val newFd = builder.establish()
        if (newFd == null) {
            Log.e(TAG, "establish() 返回 null，授权可能已被撤销")
            synchronized(lock) {
                if (vpnInterface == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return
        }

        synchronized(lock) {
            vpnInterface?.close()
            vpnInterface = newFd
        }
        Log.i(TAG, "TUN 接口已建立")

        // 复制 fd 并 detach，让 native 层持有；原 PFD 仍由 VpnService 管理
        runCatching {
            val dupFd = newFd.dup()?.detachFd() ?: -1
            EasyTierJNI.setTunFd(params.instanceName, dupFd)
        }.onFailure { Log.e(TAG, "setTunFd 失败", it) }

        saveParams(params)
        updateNotification("EasyTier", "已连接 $ipAddr/$netLen")
        startMonitor(params.instanceName)
    }

    private fun startMonitor(instanceName: String) {
        monitorJob?.cancel()
        monitorJob = monitorScope.launch {
            while (isActive) {
                delay(5000)
                if (!isActive) break
                withContext(Dispatchers.IO) {
                    runCatching {
                        val info = EasyTierJNI.collectNetworkInfos(10)
                        if (!info.isNullOrBlank()) {
                            val json = JSONObject(info)
                            val mapObj = json.optJSONObject("map")
                            val inst = mapObj?.optJSONObject(instanceName)
                            val running = inst?.optBoolean("running", false) == true
                            val myNode = inst?.optJSONObject("my_node_info")
                            val ipv4Json = myNode?.optJSONObject("virtual_ipv4")
                            val addr = (ipv4Json?.optJSONObject("address")?.optInt("addr", 0)) ?: 0
                            val netLen = ipv4Json?.optInt("network_length", 24) ?: 24
                            val ipStr = if (addr != 0) {
                                val a = addr.toInt()
                                "${(a ushr 24) and 0xFF}.${(a ushr 16) and 0xFF}.${(a ushr 8) and 0xFF}.${a and 0xFF}"
                            } else ""
                            if (running && ipStr.isNotBlank()) {
                                updateNotification("EasyTier", "已连接 $ipStr/$netLen")
                            } else if (!running) {
                                Log.w(TAG, "实例未运行: ${inst?.optString("error_msg", "")}")
                            }
                        }
                    }
                }
            }
        }
        Log.i(TAG, "监控已启动: $instanceName")
    }

    fun stopVpn() {
        monitorJob?.cancel()
        synchronized(lock) {
            vpnInterface?.close()
            vpnInterface = null
        }
        runCatching { EasyTierJNI.stopAllInstances() }
            .onFailure { Log.w(TAG, "stopAllInstances 失败", it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "VPN 已停止")
    }

    private fun configureIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(configureIntent())
            .setOngoing(true)
            .build()

    private fun updateNotification(title: String, text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(title, text))
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "EasyTier VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        synchronized(lock) { vpnInterface?.close(); vpnInterface = null }
        runCatching { EasyTierJNI.stopAllInstances() }
        super.onDestroy()
    }
}
