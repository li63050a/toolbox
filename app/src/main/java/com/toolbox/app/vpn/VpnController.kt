package com.toolbox.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.toolbox.app.log.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VpnStatus { OFF, STARTING, ON, ERROR }

object VpnController {

    private const val TAG = "VPN"

    private val _status = MutableStateFlow(VpnStatus.OFF)
    val status: StateFlow<VpnStatus> = _status

    private val _txBytes = MutableStateFlow(0L)
    val txBytes: StateFlow<Long> = _txBytes

    private val _rxBytes = MutableStateFlow(0L)
    val rxBytes: StateFlow<Long> = _rxBytes

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /**
     * 已通过 prepare 授权后直接启动前台 VPN 服务。
     * UI 层负责 VpnService.prepare 授权流程（授权回调后自动重试）。
     */
    fun start(context: Context) {
        val app = context.applicationContext
        VpnConfigStore.init(app)
        if (VpnService.prepare(app) != null) return // 未授权，由 UI 层处理
        _status.value = VpnStatus.STARTING
        _lastError.value = null
        try {
            val intent = Intent(app, ToolboxVpnService::class.java)
            app.startForegroundService(intent)
            Log.i(TAG, "已请求启动 VPN 服务")
        } catch (t: Throwable) {
            Log.e(TAG, "启动 VPN 服务失败", t)
            _status.value = VpnStatus.ERROR
            _lastError.value = t.message ?: "启动 VPN 服务失败"
        }
    }

    fun stop(context: Context) {
        try {
            context.applicationContext.stopService(Intent(context, ToolboxVpnService::class.java))
            Log.i(TAG, "已请求停止 VPN 服务")
        } catch (t: Throwable) {
            Log.e(TAG, "停止 VPN 服务失败", t)
        }
    }

    // ------------------------------------------------------------ 内部（服务/中继调用）

    internal fun onServiceStarted() {
        _status.value = VpnStatus.ON
        _lastError.value = null
        _txBytes.value = 0L
        _rxBytes.value = 0L
    }

    internal fun onServiceStopped() {
        _status.value = VpnStatus.OFF
    }

    internal fun onError(message: String) {
        _status.value = VpnStatus.ERROR
        _lastError.value = message
    }

    internal fun addTraffic(tx: Long, rx: Long) {
        if (tx != 0L) _txBytes.value += tx
        if (rx != 0L) _rxBytes.value += rx
    }
}