package com.toolbox.app.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.toolbox.app.data.ConnectionConfig
import com.toolbox.app.log.Log

class SshEngine(private val config: ConnectionConfig.Ssh) {

    fun connect(): Result<Session> = runCatching {
        val jsch = JSch()
        if (config.auth == com.toolbox.app.data.SshAuth.KEY) {
            jsch.addIdentity("toolbox", config.privateKey.trim().toByteArray(), null, config.passphrase.toByteArray())
        }
        val session = jsch.getSession(config.user, config.host, config.port)
        if (config.auth == com.toolbox.app.data.SshAuth.PASSWORD) {
            session.setPassword(config.password)
        }
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        if (config.useCompression) {
            session.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            session.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        }
        session.setTimeout(10000)
        session.connect(10000)
        Log.i("SSH", "连接成功 ${config.user}@${config.host}:${config.port}")
        session
    }.onFailure {
        Log.e("SSH", "连接失败 ${config.host}:${config.port}", it)
    }

    fun test(): Result<String> = runCatching {
        val session = connect().getOrElse { return Result.failure(it) }
        try {
            val channel = session.openChannel("exec")
            (channel as com.jcraft.jsch.ChannelExec).setCommand("echo ok")
            channel.setInputStream(null)
            val out = java.io.ByteArrayOutputStream()
            val pipe = object : java.io.OutputStream() {
                override fun write(b: Int) { out.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            }
            channel.setOutputStream(pipe)
            channel.connect(10000)
            var text = ""
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && text.isBlank()) {
                Thread.sleep(100)
                text = out.toString("UTF-8").trim()
            }
            channel.disconnect()
            if (text == "ok") "连接成功，服务器响应正常" else "连接成功（服务器无回应）"
        } finally {
            session.disconnect()
        }
    }.onFailure {
        Log.e("SSH", "测试失败", it)
    }

    companion object {
        fun openShell(session: Session): Result<com.jcraft.jsch.ChannelShell> = runCatching {
            val channel = session.openChannel("shell") as com.jcraft.jsch.ChannelShell
            channel.setPty(true)
            channel.setPtyType("xterm-256color")
            channel.connect(10000)
            channel
        }.onFailure { Log.e("SSH", "打开 shell 失败", it) }
    }
}