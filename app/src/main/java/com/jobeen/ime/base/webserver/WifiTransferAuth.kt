package com.jobeen.ime.base.webserver

import android.content.Context
import android.content.SharedPreferences
import com.jobeen.ime.base.util.DigestUtil
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wi-Fi 传输鉴权（WO52 4.4）。
 *
 * 对端Wi-Fi 传输无鉴权是缺陷，不照搬。本实现：
 * - 密码由用户在设置页自设，SHA-256 存偏好（明文不落盘）；
 *   未设密码时服务拒绝启动。
 * - /api/login 校验密码后签发 JWT（HMAC-SHA256，key＝密码哈希，
 *   exp 24 小时）；/api/renew 须校验原 JWT 有效才续期；
 *   除 login/renew 外全部接口校验 Authorization: Bearer。
 *
 * 全部纯 JVM 逻辑（Base64url 手写，不碰 android.util.Base64，
 * 以便主机单测）；HMAC／SHA-256 走标准 JCA。
 */
object WifiTransferAuth {
    const val PREFS_NAME = "web_server"
    const val KEY_PASSWORD_HASH = "password_sha256"

    /**
     * C-L-09：吊销水位（毫秒）——服务停服时记下当时时间；签发早于
     * 该水位的 token 一律作废（重开后须重新登录）。水位为 0 表示
     * 从未停服，不影响老 token（无 iat 的旧 token 按 iat=0 计）。
     */
    const val KEY_REVOKED_BEFORE_MS = "revoked_before_ms"

    /** JWT 有效期：24 小时（单子定值）。 */
    const val TOKEN_TTL_MS = 24L * 3600L * 1000L

    const val HEADER_AUTHORIZATION = "Authorization"
    const val BEARER_PREFIX = "Bearer "

    private const val HMAC_ALGORITHM = "HmacSHA256"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasPassword(prefs: SharedPreferences): Boolean =
        !prefs.getString(KEY_PASSWORD_HASH, null).isNullOrEmpty()

    /** 读吊销水位（未记过为 0）。 */
    fun revokedBeforeMs(prefs: SharedPreferences): Long =
        prefs.getLong(KEY_REVOKED_BEFORE_MS, 0L)

    /** 停服时调用：把吊销水位记到现在，之前签发的 token 全部作废。 */
    fun revokeTokensIssuedBefore(prefs: SharedPreferences, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_REVOKED_BEFORE_MS, nowMs).apply()
    }

    fun setPassword(prefs: SharedPreferences, password: String) {
        prefs.edit().putString(KEY_PASSWORD_HASH, hashPassword(password)).apply()
    }

    fun hashPassword(password: String): String =
        DigestUtil.sha256Hex(password.toByteArray(Charsets.UTF_8))

    /** 签发 JWT：header.payload.signature，payload 只含 sub／iat／exp（秒）。 */
    fun issueToken(passwordHash: String, nowMs: Long = System.currentTimeMillis()): String {
        val header = Base64Url.encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8))
        val iatSeconds = nowMs / 1000L
        val expSeconds = (nowMs + TOKEN_TTL_MS) / 1000L
        val payload = Base64Url.encode(
            """{"sub":"jime","iat":$iatSeconds,"exp":$expSeconds}""".toByteArray(Charsets.UTF_8),
        )
        val signature = sign(passwordHash, "$header.$payload")
        return "$header.$payload.$signature"
    }

    /**
     * 校验 JWT：结构＋签名＋未过期＋签发不早于吊销水位，四者全过才有效。
     * 无 iat 的旧 token 按 iat=0 计（水位为 0 时照旧有效）。
     */
    fun verifyToken(
        passwordHash: String,
        token: String,
        nowMs: Long = System.currentTimeMillis(),
        revokedBeforeMs: Long = 0L,
    ): Boolean {
        val parts = token.split(".")
        if (parts.size != 3) return false
        val (header, payload, signature) = parts
        if (!constantTimeEquals(signature, sign(passwordHash, "$header.$payload"))) return false
        val payloadJson = runCatching {
            String(Base64Url.decode(payload), Charsets.UTF_8)
        }.getOrNull() ?: return false
        val exp = EXP_REGEX.find(payloadJson)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return false
        if (nowMs / 1000L >= exp) return false
        val iat = IAT_REGEX.find(payloadJson)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        return iat * 1000L >= revokedBeforeMs
    }

    /** 从 Authorization 请求头取 Bearer token；缺失或非 Bearer 返回 null。 */
    fun bearerToken(headers: Map<String, String>): String? {
        val value = headers.entries.firstOrNull {
            it.key.equals(HEADER_AUTHORIZATION, ignoreCase = true)
        }?.value ?: return null
        if (!value.startsWith(BEARER_PREFIX)) return null
        return value.removePrefix(BEARER_PREFIX).takeIf { it.isNotEmpty() }
    }

    private fun sign(passwordHash: String, data: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(passwordHash.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        return Base64Url.encode(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private val EXP_REGEX = Regex(""""exp"\s*:\s*(\d+)""")
    private val IAT_REGEX = Regex(""""iat"\s*:\s*(\d+)""")

    /** Base64url（RFC 4648 §5，无填充），手写以便 JVM 单测。 */
    internal object Base64Url {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        fun encode(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var i = 0
            while (i < bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
                out.append(ALPHABET[b0 shr 2])
                out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
                if (i + 1 < bytes.size) out.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                if (i + 2 < bytes.size) out.append(ALPHABET[b2 and 0x3F])
                i += 3
            }
            return out.toString()
        }

        fun decode(text: String): ByteArray {
            val clean = text.trim()
            val out = ArrayList<Byte>(clean.length * 3 / 4 + 3)
            var acc = 0
            var bits = 0
            for (c in clean) {
                val v = ALPHABET.indexOf(c)
                if (v < 0) throw IllegalArgumentException("bad base64url char: $c")
                acc = (acc shl 6) or v
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.add(((acc shr bits) and 0xFF).toByte())
                }
            }
            return out.toByteArray()
        }
    }
}
