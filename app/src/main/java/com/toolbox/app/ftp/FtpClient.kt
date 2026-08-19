package com.toolbox.app.ftp

import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.data.FtpSecurity
import com.toolbox.app.log.Log
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.util.TrustManagerUtils

class FtpClient(private val config: ConnectionConfig.Ftp) {

    fun connect(): Result<FTPClient> = runCatching {
        val client: FTPClient = if (config.security == FtpSecurity.FTP) {
            FTPClient()
        } else {
            val ftps = FTPSClient(config.security == FtpSecurity.FTPS_IMPLICIT)
            ftps.setTrustManager(TrustManagerUtils.getAcceptAllTrustManager())
            ftps
        }
        client.setConnectTimeout(10000)
        client.setDefaultTimeout(30000)
        client.setDataTimeout(30000)
        client.setBufferSize(64 * 1024)
        client.connect(config.host, config.port)
        if (client is FTPSClient && config.security == FtpSecurity.FTPS_EXPLICIT) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        val ok = client.login(config.user, config.password)
        if (!ok) {
            val msg = "登录失败: ${client.replyString?.trim()}"
            runCatching { client.disconnect() }
            throw Exception(msg)
        }
        if (config.passive) client.enterLocalPassiveMode() else client.enterLocalActiveMode()
        Log.i("FTP", "连接成功 ${config.user}@${config.host}:${config.port}")
        client
    }.onFailure {
        Log.e("FTP", "连接失败 ${config.host}:${config.port}", it)
    }

    fun test(): Result<String> = runCatching {
        val client = connect().getOrElse { return Result.failure(it) }
        try {
            val pwd = client.printWorkingDirectory().trim()
            "连接成功，当前目录: $pwd"
        } finally {
            runCatching { client.logout() }
            runCatching { client.disconnect() }
        }
    }.onFailure { Log.e("FTP", "测试失败", it) }
}