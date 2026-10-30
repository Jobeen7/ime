package com.jobeen.ime.base.speech.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import timber.log.Timber

/**
 * 插件密钥存储：AndroidKeystore AES-256-GCM，每插件每字段独立别名。
 *
 * 纪律：密钥值只在宿主网络垫片内解密并即时用于组装请求头，
 * 绝不进脚本、日志、诊断导出；日志只记别名与操作。
 */
interface SecretStore {
    fun put(pluginId: String, field: String, value: String)
    fun get(pluginId: String, field: String): String?
    fun clear(pluginId: String, field: String)
    fun deleteForPlugin(pluginId: String)
    fun has(pluginId: String, field: String): Boolean
}

class KeystoreSecretStore(
    private val prefs: PrefsStore,
) : SecretStore {

    override fun put(pluginId: String, field: String, value: String) {
        val alias = aliasFor(pluginId, field)
        try {
            val key = getOrCreateKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val stored = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs.putString(secretKey(alias), stored)
        } catch (e: Exception) {
            // 只记别名与操作，绝不记值。
            Timber.w("cloudVoice secret put failed alias=%s op=put err=%s", alias, e.javaClass.simpleName)
            throw e
        }
    }

    override fun get(pluginId: String, field: String): String? {
        val alias = aliasFor(pluginId, field)
        val stored = prefs.getString(secretKey(alias), null) ?: return null
        return try {
            val parts = stored.split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = getKey(alias) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥失效（如锁屏凭证变更导致 Keystore 失效）是可恢复态：
            // 返回空缺，让调用方走“密钥未填”提示，不抛错、不记值。
            Timber.w("cloudVoice secret get failed alias=%s op=get err=%s", alias, e.javaClass.simpleName)
            null
        }
    }

    override fun clear(pluginId: String, field: String) {
        val alias = aliasFor(pluginId, field)
        prefs.remove(secretKey(alias))
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(alias)
        }
    }

    override fun deleteForPlugin(pluginId: String) {
        // 密钥偏好键形如 secret_cloudvoice_<pluginId>_<field>：按前缀一次清干净。
        val prefix = SECRET_PREF_PREFIX + "cloudvoice_${pluginId}_"
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            for (key in prefs.keys()) {
                if (!key.startsWith(prefix)) continue
                prefs.remove(key)
                val alias = key.removePrefix(SECRET_PREF_PREFIX)
                runCatching { keyStore.deleteEntry(alias) }
            }
        } catch (e: Exception) {
            Timber.w("cloudVoice secret delete failed plugin=%s err=%s", pluginId, e.javaClass.simpleName)
        }
    }

    override fun has(pluginId: String, field: String): Boolean =
        prefs.getString(secretKey(aliasFor(pluginId, field)), null) != null

    private fun aliasFor(pluginId: String, field: String): String =
        "cloudvoice_${pluginId}_$field"

    private fun secretKey(alias: String): String = SECRET_PREF_PREFIX + alias

    private fun getOrCreateKey(alias: String): SecretKey {
        return getKey(alias) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            generator.init(spec)
            generator.generateKey()
        }
    }

    private fun getKey(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        return keyStore.getKey(alias, null) as? SecretKey
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SECRET_PREF_PREFIX = "secret_"
    }
}
