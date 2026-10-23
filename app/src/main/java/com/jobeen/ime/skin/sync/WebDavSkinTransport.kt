package com.jobeen.ime.skin.sync

import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * WebDAV 传输实现（详设 §4.1）：与词典同步同一服务器/账号配置
 * （UserDictPrefs）与 OkHttp 口径（30s 连接 / 60s 读写、禁止
 * https→http 跳转、HTTPS 强制与明文放行开关同 checkConfigured
 * 规则）；独立于词典同步的实现与锁，远端根 `jime_skins/v1/`。
 * 错误信息含状态码与正文摘要（≤400 字），不含凭据。
 */
class WebDavSkinTransport : SkinSyncTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .build()

    private val octetStream = "application/octet-stream".toMediaType()

    override suspend fun get(path: String): ByteArray? = withContext(Dispatchers.IO) {
        checkConfigured()
        val request = Request.Builder()
            .url(urlFor(path))
            .header("Authorization", authHeader())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            when {
                response.code == 404 -> null
                response.code == 401 || response.code == 403 ->
                    throw SkinSyncTransport.AuthException(
                        "下载 $path 失败：HTTP ${response.code}（${errorDetail(response)}）",
                    )
                !response.isSuccessful ->
                    throw SkinSyncTransport.HttpException(
                        response.code,
                        "下载 $path 失败：HTTP ${response.code}（${errorDetail(response)}）",
                    )
                else -> response.body?.bytes()
                    ?: throw IOException("下载 $path 失败：响应体为空")
            }
        }
    }

    override suspend fun put(path: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            checkConfigured()
            ensureParentDirs(path)
            val request = Request.Builder()
                .url(urlFor(path))
                .header("Authorization", authHeader())
                .put(bytes.toRequestBody(octetStream))
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 ->
                        throw SkinSyncTransport.AuthException(
                            "上传 $path 失败：HTTP ${response.code}（${errorDetail(response)}）",
                        )
                    !response.isSuccessful ->
                        throw SkinSyncTransport.HttpException(
                            response.code,
                            "上传 $path 失败：HTTP ${response.code}（${errorDetail(response)}）",
                        )
                }
            }
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            checkConfigured()
            val request = Request.Builder()
                .url(urlFor(path))
                .header("Authorization", authHeader())
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Unit // 已不存在，视为删除完成
                    response.code == 401 || response.code == 403 ->
                        throw SkinSyncTransport.AuthException(
                            "删除 $path 失败：HTTP ${response.code}（${errorDetail(response)}）",
                        )
                    !response.isSuccessful ->
                        throw SkinSyncTransport.HttpException(
                            response.code,
                            "删除 $path 失败：HTTP ${response.code}（${errorDetail(response)}）",
                        )
                }
            }
        }
    }

    /** PUT 前逐级 MKCOL 确保父目录（405 = 已存在，同 ensureSyncDir 先例）。 */
    private fun ensureParentDirs(path: String) {
        val segments = (REMOTE_ROOT + "/" + path).split("/").dropLast(1)
        var current = UserDictPrefs.syncDirUrl()
        for (segment in segments) {
            current += "/" + encodeSegment(segment)
            val request = Request.Builder()
                .url(current)
                .header("Authorization", authHeader())
                .method("MKCOL", "".toRequestBody(null))
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.code == 401 || response.code == 403) {
                        throw SkinSyncTransport.AuthException(
                            "创建同步目录失败：HTTP ${response.code}（${errorDetail(response)}）",
                        )
                    }
                }
            }.onFailure {
                if (it is SkinSyncTransport.AuthException) throw it
                // 其余 MKCOL 失败不在此抛：随后的 PUT 会报出真实原因
            }
        }
    }

    private fun urlFor(path: String): String {
        val segments = (REMOTE_ROOT + "/" + path).split("/")
        return UserDictPrefs.syncDirUrl() + "/" +
            segments.joinToString("/") { encodeSegment(it) }
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

    private fun authHeader(): String =
        Credentials.basic(UserDictPrefs.username, UserDictPrefs.password, Charsets.UTF_8)

    /** 同 WebDavSync.checkConfigured 口径（服务器形态 + HTTPS 强制 + 账号非空）。 */
    private fun checkConfigured() {
        val server = UserDictPrefs.normalizedServer()
        require(server.startsWith("http://") || server.startsWith("https://")) {
            "服务器地址须以 http:// 或 https:// 开头"
        }
        require(!server.startsWith("http://") || UserDictPrefs.allowHttp) {
            "服务器使用明文 HTTP，账号密码与皮肤数据会被明文传输。" +
                "请改用 HTTPS，或在用户词典页的「高级」中开启「允许明文 HTTP」并确认风险后再同步"
        }
        require(UserDictPrefs.username.isNotBlank()) { "请填写用户名" }
        require(UserDictPrefs.password.isNotEmpty()) { "请填写密码" }
    }

    private fun errorDetail(response: Response): String {
        val body = runCatching { response.body?.string()?.trim().orEmpty() }.getOrDefault("")
        if (body.isEmpty()) return ""
        return body.replace(Regex("\\s+"), " ").take(400)
    }

    private companion object {
        const val REMOTE_ROOT = "jime_skins/v1"
    }
}
