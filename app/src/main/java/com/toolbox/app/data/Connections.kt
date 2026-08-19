package com.toolbox.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class SshAuth { PASSWORD, KEY }

@Serializable
enum class FtpSecurity { FTP, FTPS_EXPLICIT, FTPS_IMPLICIT }

@Serializable
sealed class ConnectionConfig {
    abstract val id: String
    abstract val name: String

    @Serializable
    data class Ssh(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "",
        val host: String = "",
        val port: Int = 22,
        val user: String = "",
        val auth: SshAuth = SshAuth.PASSWORD,
        val password: String = "",
        val privateKey: String = "",
        val passphrase: String = "",
        val useCompression: Boolean = true
    ) : ConnectionConfig()

    @Serializable
    data class Ftp(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "",
        val host: String = "",
        val port: Int = 21,
        val user: String = "",
        val password: String = "",
        val security: FtpSecurity = FtpSecurity.FTP,
        val passive: Boolean = true
    ) : ConnectionConfig()

    @Serializable
    data class S3(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "",
        val endpoint: String = "",
        val region: String = "",
        val accessKeyId: String = "",
        val secretKey: String = "",
        val bucket: String = "",
        val pathStyle: Boolean = true,
        val https: Boolean = true
    ) : ConnectionConfig()

    @Serializable
    data class Oss(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "",
        val endpoint: String = "",
        val accessKeyId: String = "",
        val accessKeySecret: String = "",
        val bucket: String = ""
    ) : ConnectionConfig()

    @Serializable
    data class Cos(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String = "",
        val region: String = "",
        val secretId: String = "",
        val secretKey: String = "",
        val bucket: String = ""
    ) : ConnectionConfig()
}

val ConnectionConfig.typeName: String
    get() = when (this) {
        is ConnectionConfig.Ssh -> "SSH/SFTP"
        is ConnectionConfig.Ftp -> "FTP/FTPS"
        is ConnectionConfig.S3 -> "S3"
        is ConnectionConfig.Oss -> "OSS"
        is ConnectionConfig.Cos -> "COS"
    }