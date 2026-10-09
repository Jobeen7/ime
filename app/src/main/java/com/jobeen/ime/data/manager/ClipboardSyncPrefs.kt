package com.jobeen.ime.data.manager

import android.content.Context
import android.util.Base64
import com.jobeen.ime.base.util.SecretStore
import timber.log.Timber
import java.security.SecureRandom
import java.util.UUID

/**
 * 剪贴板局域网同步（与 Windows 电脑端配对）的配置存储。
 *
 * 共享密钥经 [SecretStore]（Android Keystore，AES-GCM）加密后落盘，
 * 与用户词典 WebDAV 密码同一口径：Keystore 不可用时 [setSecret] 不
 * 落盘并返回 false，绝不回退明文存储。同步协议见 [ClipboardCloudSync]：
 * 密钥为 24 字节随机数，Base64URL（无填充）编码后 32 个字符，手机与
 * 电脑端填写同一串即可配对。
 */
object ClipboardSyncPrefs {
    private const val PREFS_NAME = "clipboard_sync"

    private const val KEY_ENABLED = "lan_sync_enabled"
    private const val KEY_HOST = "lan_sync_host"
    private const val KEY_PORT = "lan_sync_port"
    private const val KEY_SECRET = "lan_sync_secret"
    private const val KEY_DEVICE_ID = "lan_sync_device_id"
    private const val KEY_SEEN_IDS = "lan_sync_seen_ids"

    const val DEFAULT_PORT = 47653
    private const val SECRET_BYTES = 24
    private const val MAX_SEEN_IDS = 500

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun host(context: Context): String =
        prefs(context).getString(KEY_HOST, "").orEmpty()

    fun setHost(context: Context, host: String) {
        prefs(context).edit().putString(KEY_HOST, host.trim()).apply()
    }

    fun port(context: Context): Int =
        prefs(context).getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_PORT, port.coerceIn(1, 65535)).apply()
    }

    /** 共享密钥明文（Base64URL 串）；未设置或解密失败返回空串。 */
    fun secret(context: Context): String {
        val stored = prefs(context).getString(KEY_SECRET, "").orEmpty()
        if (stored.isEmpty()) return ""
        if (SecretStore.isEncrypted(stored)) {
            // 密钥丢失/损坏时解密失败：返回空而不是把密文当密钥用
            return SecretStore.decrypt(stored).orEmpty()
        }
        // 本功能不存在明文存量；非密文值视为损坏，不当密钥使用
        Timber.w("Clipboard sync secret is not encrypted; ignoring")
        return ""
    }

    /** 保存共享密钥：加密落盘成功返回 true；Keystore 不可用返回 false 且不落盘。 */
    fun setSecret(context: Context, secret: String): Boolean {
        val value = secret.trim()
        val editor = prefs(context).edit()
        if (value.isEmpty()) {
            editor.remove(KEY_SECRET).apply()
            return true
        }
        val encrypted = SecretStore.encrypt(value) ?: return false
        editor.putString(KEY_SECRET, encrypted).apply()
        return true
    }

    /** 生成新的 24 字节随机共享密钥（Base64URL 无填充，与同步协议解码口径一致）。 */
    fun generateSecret(): String {
        val bytes = ByteArray(SECRET_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    /** 本机设备 ID（协议里区分条目来源，防自发自收回环）；首次读取时生成并落盘。 */
    fun deviceId(context: Context): String {
        val p = prefs(context)
        p.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** 已处理过的同步条目 ID 集合（去重用，上限 [MAX_SEEN_IDS] 条，超出丢最旧）。 */
    fun seenIds(context: Context): Set<String> {
        val stored = prefs(context).getString(KEY_SEEN_IDS, null) ?: return emptySet()
        return stored.split("\n").filter { it.isNotEmpty() }.toSet()
    }

    fun markSeen(context: Context, id: String) {
        if (id.isBlank()) return
        val p = prefs(context)
        val current = p.getString(KEY_SEEN_IDS, null)
            ?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()
        if (id in current) return
        val merged = (current + id).takeLast(MAX_SEEN_IDS)
        p.edit().putString(KEY_SEEN_IDS, merged.joinToString("\n")).apply()
    }
}
