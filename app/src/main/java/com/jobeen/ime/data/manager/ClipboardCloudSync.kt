package com.jobeen.ime.data.manager

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** End-to-end encrypted clipboard sync over the local Wi-Fi network to the Windows tray host. */
object ClipboardCloudSync {
    private const val POLL_MS = 2_000L
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS).build()
    private val syncMutex = Mutex()
    @Volatile private var syncJob: Job? = null

    fun start(context: Context, scope: CoroutineScope) {
        if (syncJob?.isActive == true || !ClipboardSyncPrefs.isEnabled(context) ||
            ClipboardSyncPrefs.secret(context).isBlank() || ClipboardSyncPrefs.host(context).isBlank()) return
        syncJob = scope.launch(Dispatchers.IO) {
            while (isActive && ClipboardSyncPrefs.isEnabled(context)) {
                var failed = false
                try { syncMutex.withLock { syncOnce(context.applicationContext) } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { failed = true; Timber.w(e, "LAN clipboard sync failed") }
                delay(if (failed) 15_000L else POLL_MS)
            }
        }
    }

    fun stop() { syncJob?.cancel(); syncJob = null }

    fun publishLocalCopy(context: Context, text: String) {
        val app = context.applicationContext
        if (!ClipboardSyncPrefs.isEnabled(app) || text.isBlank() || ClipboardSyncPrefs.host(app).isBlank()) return
        com.jobeen.ime.base.util.appScope.launch(Dispatchers.IO) {
            runCatching { post(app, encrypt(app, text.take(ClipboardManager.MAX_TEXT_LENGTH))) }
                .onFailure { Timber.w(it, "LAN clipboard upload failed") }
        }
    }

    private fun baseUrl(context: Context): String {
        val host = ClipboardSyncPrefs.host(context).trim()
        require(host.isNotBlank()) { "请填写 Windows 电脑的局域网 IP 地址" }
        val parts = host.split('.')
        val octets = parts.mapNotNull { it.toIntOrNull() }
        require(octets.size == 4 && octets.all { it in 0..255 } &&
            (octets[0] == 10 || octets[0] == 192 && octets[1] == 168 ||
                octets[0] == 172 && octets[1] in 16..31 ||
                octets[0] == 169 && octets[1] == 254 ||
                octets[0] == 100 && octets[1] in 64..127)) { "请输入有效的局域网 IPv4 地址" }
        val port = ClipboardSyncPrefs.port(context)
        require(port in 1..65535) { "端口无效" }
        return "http://$host:$port"
    }

    private suspend fun syncOnce(context: Context) {
        if (!ClipboardSyncPrefs.isEnabled(context) || ClipboardSyncPrefs.secret(context).isBlank()) return
        val request = Request.Builder().url("${baseUrl(context)}/items").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Windows 同步端 HTTP ${response.code}")
            val body = response.body ?: return
            val bytes = ByteArrayOutputStream()
            body.byteStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(bytes.size() + count <= 2_000_000) { "同步响应过大" }
                    bytes.write(buffer, 0, count)
                }
            }
            val items = JSONArray(bytes.toString(Charsets.UTF_8.name()))
            val own = ClipboardSyncPrefs.deviceId(context)
            val known = ClipboardSyncPrefs.seenIds(context)
            for (i in 0 until minOf(items.length(), 300)) {
                val json = items.optJSONObject(i) ?: continue
                val id = json.optString("id")
                if (id.isBlank() || id in known) continue
                try {
                    val clip = decrypt(context, json)
                    ClipboardSyncPrefs.markSeen(context, clip.id)
                    if (clip.deviceId != own && clip.text.isNotBlank())
                        ClipboardManager.addCloudEntry(context, clip.text, clip.timestamp)
                } catch (e: Exception) {
                    Timber.w(e, "Ignoring invalid LAN clipboard item")
                    ClipboardSyncPrefs.markSeen(context, id)
                }
            }
        }
    }

    private fun post(context: Context, envelope: JSONObject) {
        val request = Request.Builder().url("${baseUrl(context)}/items")
            .post(envelope.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Windows 同步端 HTTP ${response.code}")
        }
        ClipboardSyncPrefs.markSeen(context, envelope.getString("id"))
    }

    private data class PlainClip(val id: String, val deviceId: String, val timestamp: Long, val text: String)

    private fun encrypt(context: Context, text: String): JSONObject {
        val secret = Base64.decode(ClipboardSyncPrefs.secret(context), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(secret.size == 24) { "共享密钥格式无效" }
        val id = UUID.randomUUID().toString(); val device = ClipboardSyncPrefs.deviceId(context)
        val timestamp = System.currentTimeMillis(); val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val aesKey = deriveKey(secret, "JimeClipboardSync/AES/v1")
        val encrypted = try { Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv)); doFinal(text.toByteArray(Charsets.UTF_8))
        } } finally { aesKey.fill(0) }
        val iv64 = b64(iv); val data64 = b64(encrypted)
        val canonical = "1\n$id\n$device\n$timestamp\n$iv64\n$data64"
        val macKey = deriveKey(secret, "JimeClipboardSync/HMAC/v1")
        val mac = try { Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(macKey, "HmacSHA256")); doFinal(canonical.toByteArray(Charsets.UTF_8)) } }
        finally { macKey.fill(0); secret.fill(0) }
        return JSONObject().put("version", 1).put("id", id).put("deviceId", device).put("timestamp", timestamp)
            .put("iv", iv64).put("data", data64).put("mac", b64(mac))
    }

    private fun decrypt(context: Context, json: JSONObject): PlainClip {
        require(json.getInt("version") == 1) { "不支持的同步记录版本" }
        val id = json.getString("id")
        val device = json.getString("deviceId")
        val timestamp = json.getLong("timestamp")
        val now = System.currentTimeMillis()
        require(timestamp >= now - 7L * 24 * 60 * 60 * 1000 && timestamp <= now + 5L * 60 * 1000) { "剪贴板记录时间无效" }
        val iv64 = json.getString("iv"); val data64 = json.getString("data"); val mac64 = json.getString("mac")
        require(runCatching { UUID.fromString(id) }.isSuccess && runCatching { UUID.fromString(device) }.isSuccess)
        val secret = Base64.decode(ClipboardSyncPrefs.secret(context), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(secret.size == 24)
        val macKey = deriveKey(secret, "JimeClipboardSync/HMAC/v1")
        val expected = try { Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(macKey, "HmacSHA256")); doFinal("1\n$id\n$device\n$timestamp\n$iv64\n$data64".toByteArray(Charsets.UTF_8))
        } } finally { macKey.fill(0) }
        val supplied = Base64.decode(mac64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(MessageDigest.isEqual(expected, supplied)) { "剪贴板记录认证失败" }
        val iv = Base64.decode(iv64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        require(iv.size == 16)
        val aesKey = deriveKey(secret, "JimeClipboardSync/AES/v1")
        val plain = try { Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
            doFinal(Base64.decode(data64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } } finally { aesKey.fill(0); secret.fill(0) }
        val text = String(plain, Charsets.UTF_8)
        require(text.length <= ClipboardManager.MAX_TEXT_LENGTH)
        return PlainClip(id, device, timestamp, text)
    }

    private fun deriveKey(secret: ByteArray, purpose: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(secret + purpose.toByteArray(Charsets.UTF_8))
    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
