package com.jobeen.ime.base.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore 的机密字符串存储：密钥不出 Keystore，落盘的只有
 * AES-GCM 密文。密文格式 `enc:v1:<Base64(iv ‖ ciphertext)>`，[isEncrypted]
 * 用前缀区分存量明文，调用方据此做一次性迁移。
 *
 * Keystore 在个别设备上可能损坏/不可用：此时加密返回 null、解密返回空，
 * 调用方不得回退到明文落盘（宁可让功能不可用，也不留明文）。
 */
object SecretStore {
    private const val KEY_ALIAS = "jime_secret_store"
    private const val PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    /** 加密后返回带前缀的密文串；Keystore 不可用时返回 null */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            require(iv.size == IV_BYTES) { "unexpected GCM IV size ${iv.size}" }
            val body = iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(body, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.w(e, "SecretStore encrypt failed")
            null
        }
    }

    /** 解密带前缀的密文；非密文或失败（密钥丢失/损坏）返回 null */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!isEncrypted(stored)) return null
        return try {
            val body = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (body.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, body.copyOfRange(0, IV_BYTES)),
            )
            String(cipher.doFinal(body.copyOfRange(IV_BYTES, body.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "SecretStore decrypt failed")
            null
        }
    }

    private val keyLock = Any()

    /**
     * 查/建密钥必须串行：保存密码（主线程）与同步读密码（IO 线程）可能在
     * 某台设备首次使用 Keystore 的瞬间并发走到建密钥，两把同 alias 密钥
     * 互相覆盖会让先加密的密文永久解不开。
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        synchronized(keyLock) {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        }
    }
}
