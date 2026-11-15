package com.jobeen.ime.base.webserver

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import com.jobeen.ime.base.util.DigestUtil
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/**
 * Wi-Fi 传输证书管理（WO54）：设备端自签名证书。
 *
 * - 证书由 AndroidKeyStore 生成 RSA 2048 自签名证书（10 年，
 *   CN=Jime Wi-Fi Transfer），私钥不出 KeyStore；不引 Bouncy
 *   Castle、不加新依赖。
 * - 信任模型：浏览器首次访问必报自签名警告（局域网 IP 拿不到公信
 *   证书）；传输页显示证书 SHA-256 指纹供用户核对（见 W54-4）。
 * - 生成／加载失败直接抛给调用方（Service 侧接住停服，见
 *   WebServerService.onCreate），不在这里吞掉、不回落 http。
 */
object WifiTransferCertManager {

    const val CERT_ALIAS = "jime_wifi_transfer"

    /** 上次向用户展示过的证书指纹（存于 "web_server" 偏好，见 W54-5）。 */
    const val KEY_CERT_FINGERPRINT_SHOWN = "cert_fingerprint_shown"

    /** 证书有效期：10 年（自生成日起）。 */
    const val CERT_VALIDITY_MS = 10L * 365L * 24L * 3600L * 1000L

    /** 换发阈值：过期前 30 天即视为需换发，避免用到最后一刻。 */
    const val ROTATION_THRESHOLD_MS = 30L * 24L * 3600L * 1000L

    /**
     * 备好证书并返回：已有条目且未到换发阈值直接返回；否则删旧重建。
     * 生成后重载 KeyStore 再取证书（生成后同一实例看不到新条目）。
     */
    fun ensureCertificate(): X509Certificate {
        val keyStore = openKeyStore()
        val existing = keyStore.getCertificate(CERT_ALIAS) as? X509Certificate
        if (existing != null && !needsRotation(existing.notAfter.time, System.currentTimeMillis())) {
            return existing
        }
        runCatching { keyStore.deleteEntry(CERT_ALIAS) }
        val now = Date()
        val spec = KeyGenParameterSpec.Builder(CERT_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(2048)
            .setCertificateSubject(X500Principal("CN=Jime Wi-Fi Transfer"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
            .setCertificateNotBefore(now)
            .setCertificateNotAfter(Date(now.time + CERT_VALIDITY_MS))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // 显式写清：不绑锁屏，服务须在锁屏后台也能启动；
            // 默认值即 false，显式写是为防止后人误加。
            .setUserAuthenticationRequired(false)
            // 不设 setIsStrongBoxBacked：不强制 StrongBox，
            // 无 StrongBox 的设备会生成失败；留系统自选。
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        generator.initialize(spec)
        generator.generateKeyPair()
        val reloaded = openKeyStore()
        return reloaded.getCertificate(CERT_ALIAS) as? X509Certificate
            ?: error("AndroidKeyStore did not return the generated certificate")
    }

    /** 服务端 SSLContext：KeyManager 取本证书；trustManagers 传 null——服务端不校验客户端，不做双向认证。 */
    fun sslContext(): SSLContext {
        val keyStore = openKeyStore()
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
    }

    /**
     * 证书 SHA-256 指纹：冒号分隔大写十六进制（AA:BB:…），与
     * Chrome/Firefox 证书查看器显示格式一致，逐字可比对。
     * 不复用 DigestUtil.sha256Hex：那是小写无冒号，口径不同；
     * 这里另写格式化（3 行），口径见上。
     */
    fun fingerprint(cert: X509Certificate): String {
        val digest = DigestUtil.newSha256Digest()
        val hash = digest.digest(cert.encoded)
        return hash.joinToString(":") { "%02X".format(it) }
    }

    /** 删 KeyStore 条目（手动重新生成用，见 W54-5；下次启动时重建）。 */
    fun deleteCertificate() {
        val keyStore = openKeyStore()
        keyStore.deleteEntry(CERT_ALIAS)
    }

    /**
     * 换发检测（见 W54-5）：无存值 → 存当前、返 false（首次展示）；
     * 有存值且不同 → 存当前、返 true（证书已更换）；相同 → false。
     */
    fun checkRotation(prefs: SharedPreferences, fingerprint: String): Boolean {
        val shown = prefs.getString(KEY_CERT_FINGERPRINT_SHOWN, null)
        if (shown == fingerprint) return false
        prefs.edit { putString(KEY_CERT_FINGERPRINT_SHOWN, fingerprint) }
        return shown != null
    }

    /** 是否需换发（纯函数）：距过期 ≤30 天（含已过期）即 true。 */
    fun needsRotation(notAfterMs: Long, nowMs: Long): Boolean =
        notAfterMs - nowMs <= ROTATION_THRESHOLD_MS

    private fun openKeyStore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
