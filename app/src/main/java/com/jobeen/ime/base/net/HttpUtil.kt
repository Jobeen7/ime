package com.jobeen.ime.base.net

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.base.util.appContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

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
    private val client = OkHttpClient.Builder()
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
            showToast(msg)
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

    private suspend fun rawRequest(request: Request, silent: Boolean = false): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("HTTP %d for %s", response.code, request.url)
                    if (!silent) showToast("请求失败：HTTP ${response.code}")
                    throw ApiException(response.code, "HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
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
