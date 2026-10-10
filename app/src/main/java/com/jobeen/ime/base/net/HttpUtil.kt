package com.jobeen.ime.base.net

import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.base.util.appContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 通用 HTTP 工具，基于 OkHttp 封装。
 *
 * 约定服务端统一返回以下信封：
 * ```json
 * { "code": 0, "msg": "success", "data": { ... } }
 * ```
 * - [get]/[post] 为泛型方法，成功时直接返回 `data` 反序列化后的对象实例；
 * - 当 `code != 0` 时自动 Toast 提示 `msg` 并抛出 [ApiException]，调用方无需额外处理业务错误码。
 */
object HttpUtil {
    // 这些客户端只访问写死的 HTTPS 地址：禁止 https↔http 重定向，
    // 避免全局放开明文后被降级（明文仅供 WebDAV 在用户显式开启后使用）
    private val client = OkHttpClient.Builder()
        .followSslRedirects(false)
        .connectTimeout(ApiConfig.CONNECT_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(ApiConfig.READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    class ApiException(val code: Int, message: String) : Exception(message)

    internal suspend inline fun <reified T> get(path: String): T {
        val raw = rawRequest(Request.Builder().url(ApiConfig.BASE_URL + appendVersion(path)).build())
        return unwrap(raw)
    }

    internal suspend inline fun <reified T> post(path: String, body: String): T {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(ApiConfig.BASE_URL + path)
            .post(body.toRequestBody(mediaType))
            .build()
        val raw = rawRequest(request)
        return unwrap(raw)
    }

    private inline fun <reified T> unwrap(raw: String): T {
        val root = JSONObject(raw)
        val code = root.optInt("code", -1)
        val msg = root.optString("msg", "")
        if (code != 0) {
            Timber.w("API returned error: code=%d msg=%s", code, msg)
            // 服务端文案不直接原样上屏：压缩空白并截断（完整原文已进日志），
            // 防止异常服务端用长文案/伪造提示误导用户
            showToast(
                sanitizeServerMessage(msg).ifEmpty { "请求失败：业务错误 $code" },
            )
            throw ApiException(code, msg)
        }
        val dataStr = root.opt("data")?.toString().orEmpty()
        return json.decodeFromString<T>(dataStr)
    }

    internal fun showToast(msg: String) {
        ToastUtil.showToast(msg)
    }

    private fun appendVersion(path: String): String {
        val separator = if (path.contains('?')) '&' else '?'
        return "$path${separator}version=${appVersion()}"
    }

    private fun appVersion(): String {
        return runCatching {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
                ?: "0"
        }.getOrDefault("0")
    }

    /** 响应体上限：本通道只传小 JSON 信封，8MB 已是数量级冗余 */
    private const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024

    /**
     * 异步执行请求：协程取消时同步取消底层 OkHttp Call（旧实现是阻塞
     * execute()，取消只丢弃结果、请求仍在后台跑完）。响应体带上限
     * 流式读取，超限即失败，不整包无界入内存。
     */
    private suspend fun rawRequest(request: Request, silent: Boolean = false): String =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val raw = response.use {
                            if (!it.isSuccessful) {
                                Timber.w("HTTP %d for %s", it.code, request.url)
                                if (!silent) showToast("请求失败：HTTP ${it.code}")
                                throw ApiException(it.code, "HTTP ${it.code}")
                            }
                            readBoundedBody(it)
                        }
                        if (cont.isActive) cont.resume(raw)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            })
        }

    private fun readBoundedBody(response: Response): String {
        val body = response.body ?: return ""
        if (body.contentLength() > MAX_RESPONSE_BYTES) {
            throw ApiException(-1, "响应体过大")
        }
        val out = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_RESPONSE_BYTES) throw ApiException(-1, "响应体过大")
                out.write(buf, 0, n)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * 绝对 URL 的原始 GET，不经过服务端信封解析（用于 GitHub API 等第三方接口）。
     * 失败时静默抛异常，由调用方处理 UI 提示。
     */
    internal suspend fun getRawText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        return rawRequest(request, silent = true)
    }
}

/**
 * 服务端业务错误文案上屏前的收口：折叠所有空白（含换行）为单个空格
 * 并截断到 60 字。服务端可控文案原样弹 Toast 可被用来伪造系统提示，
 * 收口后仍保留正常错误信息的可读性（完整原文由调用处写日志）。
 */
internal fun sanitizeServerMessage(raw: String): String =
    raw.replace(Regex("\\s+"), " ").trim().take(60)
