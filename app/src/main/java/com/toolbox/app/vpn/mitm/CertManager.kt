package com.toolbox.app.vpn.mitm

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.toolbox.app.log.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.atomic.AtomicLong

/**
 * MITM 证书管理：自签 CA（持久化 ca.p12）+ 按主机名签发服务器证书（LRU 缓存 64）。
 */
object CertManager {

    private const val TAG = "MITM"

    private const val CA_ALIAS = "ca"
    private const val STORE_NAME = "ca.p12"
    private const val EXPORT_NAME = "toolbox_ca.der"
    private const val STORE_PASSWORD = "toolbox-ca-password"
    private const val CA_VALIDITY_YEARS = 10L
    private const val SERVER_VALIDITY_DAYS = 50L

    private val serialCounter = AtomicLong(1)

    private val cache = object : LinkedHashMap<String, Pair<PrivateKey, X509Certificate>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<PrivateKey, X509Certificate>>): Boolean =
            size > 64
    }

    private fun ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ---------------------------------------------------------------- CA

    private class CaBundle(val certificate: X509Certificate, val privateKey: PrivateKey)

    private fun caFile(context: Context): File = File(context.filesDir, STORE_NAME)

    private fun loadOrCreateCa(context: Context): CaBundle {
        ensureProvider()
        val file = caFile(context)
        runCatching {
            if (file.exists()) {
                val ks = KeyStore.getInstance("PKCS12")
                file.inputStream().use { ks.load(it, STORE_PASSWORD.toCharArray()) }
                val cert = ks.getCertificate(CA_ALIAS) as? X509Certificate ?: throw IllegalStateException("CA 缺失")
                val key = ks.getKey(CA_ALIAS, STORE_PASSWORD.toCharArray()) as? PrivateKey
                    ?: throw IllegalStateException("CA 私钥缺失")
                return CaBundle(cert, key)
            }
        }.onFailure { t ->
            Log.w(TAG, "加载旧 CA 失败，重新生成: ${t.message}")
            runCatching { file.delete() }
        }
        val generated = generateCa()
        persistCa(file, generated)
        Log.i(TAG, "已生成新 CA：${generated.certificate.subjectDN}")
        return generated
    }

    private fun generateCa(): CaBundle {
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val name = X500Name("CN=Toolbox CA")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.ONE, Date(now), Date(now + CA_VALIDITY_YEARS * 365L * 24L * 3600L * 1000L),
            name, keyPair.public
        )
        val extUtils = JcaX509ExtensionUtils()
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier, false,
            extUtils.createSubjectKeyIdentifier(keyPair.public)
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(keyPair.private)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
        return CaBundle(cert, keyPair.private)
    }

    private fun persistCa(file: File, bundle: CaBundle) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(CA_ALIAS, bundle.privateKey, STORE_PASSWORD.toCharArray(), arrayOf(bundle.certificate))
        file.outputStream().use { ks.store(it, STORE_PASSWORD.toCharArray()) }
    }

    /** 供 UI 显示指纹 / 导出用 */
    fun caCertificate(context: Context): X509Certificate? =
        runCatching { loadOrCreateCa(context).certificate }.getOrNull()

    // ---------------------------------------------------------------- 服务器证书

    @Synchronized
    fun getServerCertChain(context: Context, hostname: String): Pair<PrivateKey, X509Certificate> {
        cache[hostname]?.let { return it }
        val ca = loadOrCreateCa(context)
        ensureProvider()
        val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        kpg.initialize(2048)
        val keyPair = kpg.generateKeyPair()
        val serial = BigInteger.valueOf(serialCounter.getAndIncrement())
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=$hostname")
        val builder = JcaX509v3CertificateBuilder(
            X500Name(ca.certificate.subjectDN.name), serial,
            Date(now), Date(now + SERVER_VALIDITY_DAYS * 24L * 3600L * 1000L),
            subject, keyPair.public
        )
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.extendedKeyUsage, false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
            GeneralNames(GeneralName(GeneralName.dNSName, hostname))
        )
        builder.addExtension(
            org.bouncycastle.asn1.x509.Extension.authorityKeyIdentifier, false,
            JcaX509ExtensionUtils().createAuthorityKeyIdentifier(ca.certificate)
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(ca.privateKey)
        val cert = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
        val pair = keyPair.private to cert
        cache[hostname] = pair
        Log.i(TAG, "已签发证书: $hostname (序列号 $serial)")
        return pair
    }

    // ---------------------------------------------------------------- 导出

    fun exportCa(context: Context): Uri? {
        return runCatching {
            val cert = caCertificate(context) ?: return null
            val file = File(context.cacheDir, EXPORT_NAME)
            file.writeBytes(cert.encoded)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure { t ->
            Log.e(TAG, "导出 CA 失败", t)
        }.getOrNull()
    }
}