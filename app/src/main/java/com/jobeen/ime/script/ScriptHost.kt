// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.jobeen.ime.base.notify.NotificationChannels
import com.jobeen.ime.data.manager.ClipboardManager
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber

/**
 * $http 一次完成（与元书 ue4 Completion 对应）：
 * id＝$http 返回的自增 id；rejected＝true 时 payload 为错误文本，
 * 否则为响应体。
 */
data class HttpCompletion(val id: Long, val rejected: Boolean, val payload: String)

/**
 * 脚本宿主 API（WO52 8.1）：仅 6 个，与元书 n90.d 的 switch 全集一致：
 * $log／$http／$setClipboard／$insertText／$openURL／$userNotification。
 * 其余一切（网络白名单、文件、Java 互操作）都不存在——脚本只能调这 6 个。
 *
 * 安全模型（单子定）：
 * - 联网：$http 无白名单（刻意与元书对齐，不是遗漏）——唯一限制是
 *   URL 须能被 OkHttp HttpUrl 解析（http/https）与超时钳制 0.1–60 秒；
 * - 剪贴板：$setClipboard 写系统剪贴板（带内部标记，见
 *   [AndroidScriptHost]）；读剪贴板只发生在运行方注入
 *   $pasteboardContent 时，受脚本的 allowPasteboard 门控；
 * - 通知：$userNotification 经系统通知渠道发，须 POST_NOTIFICATIONS
 *   权限（未授时返回 false，不抛错）。
 */
interface ScriptHost {
    /**
     * JNI 分发：name 为 "$log" 等 6 个之一；types 见 [ARG_UNDEFINED]…
     * strArgs 为各参数的 ToString（undefined／null 为 ""）；objJson
     * 为对象型参数的 JSON.stringify（非对象为 ""）。
     * 返回值按 String／Long／Boolean／null 转回 JS 值。
     */
    fun dispatch(name: String, types: IntArray, strArgs: Array<String>, objJson: Array<String>): Any? {
        return when (name) {
            "\$log" -> {
                log(strArgs.joinToString(" "))
                null
            }
            "\$http" -> {
                val json = if (types.getOrNull(0) == ARG_OBJECT) objJson.getOrNull(0).orEmpty()
                else strArgs.getOrNull(0).orEmpty()
                http(json)
            }
            "\$setClipboard" -> {
                setClipboard(strArgs.getOrNull(0).orEmpty())
                null
            }
            "\$insertText" -> {
                insertText(strArgs.getOrNull(0).orEmpty())
                null
            }
            "\$openURL" -> openUrl(strArgs.getOrNull(0).orEmpty())
            "\$userNotification" -> userNotification(
                strArgs.getOrNull(0).orEmpty(),
                strArgs.getOrNull(1).orEmpty(),
                strArgs.getOrNull(2).orEmpty(),
            )
            else -> null
        }
    }

    /** $log(...)：记入本次运行的日志列表（调用方给 logSink）。 */
    fun log(text: String)

    /**
     * $http(json)：解析参数并异步发请求，返回自增 id（从 1 起）。
     * 参数非法（JSON 坏／URL 不合法）时同样返回 id，并立即投递一次
     * rejected 完成（与元书 n90 的失败回调口径一致：先拿 id 再回调）。
     */
    fun http(json: String): Long

    /** $setClipboard(txt)：写系统剪贴板（带内部标记，不触发自家监听回录）。 */
    fun setClipboard(text: String)

    /** $insertText(txt)：上屏文本；空串忽略（与元书 n90 一致）。 */
    fun insertText(text: String)

    /** $openURL(url)：URI 有 scheme 才打开，返回是否打开。 */
    fun openUrl(url: String): Boolean

    /** $userNotification(title, shortcutName, content)：发通知，返回是否发出。 */
    fun userNotification(title: String, shortcutName: String, content: String): Boolean

    /** $http 完成投递（OkHttp 线程调入；实现方只入队／转交，不碰 JS）。 */
    fun onHttpCompletion(completion: HttpCompletion)

    companion object {
        const val ARG_UNDEFINED = 0
        const val ARG_NULL = 1
        const val ARG_STRING = 2
        const val ARG_PRIMITIVE = 3
        const val ARG_OBJECT = 4

        /** 空实现（元书默认 host y43 的等价物：全部调用返回 null／false）。 */
        val NOOP: ScriptHost = object : ScriptHost {
            override fun log(text: String) = Unit
            override fun http(json: String): Long = 0L
            override fun setClipboard(text: String) = Unit
            override fun insertText(text: String) = Unit
            override fun openUrl(url: String): Boolean = false
            override fun userNotification(title: String, shortcutName: String, content: String): Boolean = false
            override fun onHttpCompletion(completion: HttpCompletion) = Unit
        }
    }
}

/** $http 参数非法（JSON 坏／缺 url／URL 解析失败）。 */
class HttpParamException(message: String) : IllegalArgumentException(message)

/** $http 解析后的待发请求（纯数据，可主机单测）。 */
data class PreparedHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val mediaType: String?,
    /** 超时秒（已钳制 0.1–60）。 */
    val timeoutSeconds: Double,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreparedHttpRequest) return false
        return url == other.url && method == other.method && headers == other.headers &&
            (body == null && other.body == null || body != null && other.body != null && body.contentEquals(other.body)) &&
            mediaType == other.mediaType && timeoutSeconds == other.timeoutSeconds
    }

    override fun hashCode(): Int {
        var result = url.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        result = 31 * result + (mediaType?.hashCode() ?: 0)
        result = 31 * result + timeoutSeconds.hashCode()
        return result
    }
}

/**
 * $http 参数解析（纯函数，可主机单测；口径逐项照元书 xe4.b）：
 * - 入参为 JSON 对象字符串：{url, method?, header?, body?, timeout?}；
 * - url 必填，须能被 OkHttp HttpUrl 解析（http/https），否则抛
 *   [HttpParamException]（"Invalid HTTP URL"）；
 * - method 缺省 GET（大写化）；header 为 string→string 对象；
 * - body 为对象时：Content-Type 为 application/x-www-form-urlencoded
 *   则编 form（k=v&…），否则取其 JSON 文本；body 为字符串时原样用；
 * - GET／HEAD 不带 body；POST／PUT／PATCH／PROPPATCH／REPORT 无 body
 *   时带空 body；其余方法无 body 时不带 body；
 * - timeout 缺省 15 秒，数字或数字串，钳制 0.1–60 秒（与元书
 *   cp0.y(d, 0.1, 60.0) 一致）；
 * - 无白名单：任何能被 HttpUrl 解析的 http/https 地址都放行（刻意
 *   与元书对齐，见 [ScriptHost] 安全模型）。
 */
object HttpRequestParser {
    /** 字符串 primitive 取值；非字符串／非 primitive 返回 null。 */
    private fun JsonElement.stringOrNull(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private val BODY_METHODS_WITH_EMPTY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

    fun parse(json: String): PreparedHttpRequest {
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            throw HttpParamException("http parameters is nil")
        }
        val obj = element as? JsonObject ?: throw HttpParamException("http parameters is nil")
        val url = obj["url"]?.stringOrNull()
            ?: throw HttpParamException("Invalid HTTP URL")
        if (url.toHttpUrlOrNull() == null) throw HttpParamException("Invalid HTTP URL")
        val method = obj["method"]?.stringOrNull()?.uppercase().orEmpty()
            .ifEmpty { "GET" }
        val headers = mutableMapOf<String, String>()
        var mediaType: String? = null
        (obj["header"] as? JsonObject)?.forEach { (key, value) ->
            val v = value.stringOrNull() ?: return@forEach
            headers[key] = v
            if (key.equals("Content-Type", ignoreCase = true)) mediaType = v
        }
        val isForm = mediaType?.startsWith("application/x-www-form-urlencoded", ignoreCase = true) == true
        val bodyText: String? = when (val body = obj["body"]) {
            null -> null
            is JsonObject -> if (isForm) {
                body.entries.joinToString("&") { (k, v) ->
                    "${urlEncode(k)}=${urlEncode(v.stringOrNull().orEmpty())}"
                }
            } else {
                body.toString()
            }
            else -> body.stringOrNull()
        }
        val bodyBytes: ByteArray? = when {
            method == "GET" || method == "HEAD" -> null
            bodyText != null -> bodyText.toByteArray(Charsets.UTF_8)
            method in BODY_METHODS_WITH_EMPTY -> ByteArray(0)
            else -> null
        }
        val timeout = parseTimeout(
            obj["timeout"]?.let {
                runCatching {
                    it.jsonPrimitive.doubleOrNull ?: it.stringOrNull()?.toDoubleOrNull()
                }.getOrNull()
            },
        )
        return PreparedHttpRequest(
            url = url,
            method = method,
            headers = headers,
            body = bodyBytes,
            mediaType = mediaType,
            timeoutSeconds = timeout,
        )
    }

    /** 超时钳制：缺省 15 秒，钳制 0.1–60 秒。 */
    fun parseTimeout(raw: Double?): Double {
        val value = raw ?: 15.0
        return value.coerceIn(0.1, 60.0)
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

/** $http 执行器：发一次异步请求，完成时调回调（OkHttp 线程）。 */
fun interface HttpExecutor {
    fun execute(id: Long, request: PreparedHttpRequest, onComplete: (HttpCompletion) -> Unit)
}

/** 默认 $http 执行器：OkHttp 异步发请求（响应体上限 1 MiB，超限按失败投递）。 */
class OkHttpHttpExecutor(
    private val client: OkHttpClient,
) : HttpExecutor {
    override fun execute(id: Long, request: PreparedHttpRequest, onComplete: (HttpCompletion) -> Unit) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach { (k, v) -> builder.header(k, v) }
        val body = request.body?.toRequestBody(request.mediaType?.toMediaTypeOrNull())
        builder.method(request.method, body)
        val timeoutMs = (request.timeoutSeconds * 1000).toLong()
        val callClient = client.newBuilder()
            .callTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        callClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onComplete(HttpCompletion(id, true, e.message ?: "request failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    // 有界读：超过 1 MiB 即按失败投递，不把整包读进内存。
                    val text = runCatching {
                        val source = it.body?.source() ?: return@runCatching ""
                        val buffer = okio.Buffer()
                        var total = 0L
                        while (true) {
                            val read = source.read(buffer, 8192L)
                            if (read == -1L) break
                            total += read
                            if (total > MAX_RESPONSE_BYTES) return@runCatching null
                        }
                        buffer.readUtf8()
                    }.getOrNull()
                    if (text == null) {
                        onComplete(HttpCompletion(id, true, "response too large (over 1 MiB)"))
                    } else {
                        onComplete(HttpCompletion(id, false, text))
                    }
                }
            }
        })
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 1024L * 1024L
    }
}

/**
 * Android 宿主实现（WO52 8.1）：6 个宿主 API 的真实落点。
 *
 * 可注入点（主机单测用 fake，不碰 Android 框架）：
 * - [onInsertText]：默认只记入 [insertedTexts]（管理页试跑没有可
 *   插文本的编辑框，记下来给用户看就是诚实行为；键盘内命令面板接
 *   入时由调用方传入真正的上屏提交）；
 * - [clipboardWriter]：默认写系统剪贴板＋[ClipboardManager] 自写
 *   标记（复用 5.2 的自家回写抑制，不另立机制）；
 * - [urlOpener]：默认 ACTION_VIEW；
 * - [notifier]：默认经 [NotificationChannels] 的 script 渠道发系统通知；
 * - [logSink]：$log 行的去向（运行方传入自己的日志列表）；
 * - [onHttpCompletion]：$http 完成投递（运行方传入 settle 接线）。
 */
class AndroidScriptHost(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val httpExecutor: HttpExecutor = OkHttpHttpExecutor(httpClient),
    private val onInsertText: (String) -> Unit = {},
    private val clipboardWriter: (String) -> Unit = { text ->
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Jime", text))
        ClipboardManager.markSelfWritten(text)
    },
    private val urlOpener: (String) -> Boolean = { url ->
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.applicationContext.startActivity(intent)
            true
        }.getOrDefault(false)
    },
    private val notifier: (String, String, String) -> Boolean = { title, shortcutName, content ->
        postScriptNotification(context.applicationContext, title, shortcutName, content)
    },
    private val logSink: (String) -> Unit = {},
    private val onHttpCompletionListener: (HttpCompletion) -> Unit = {},
) : ScriptHost {
    private val appContext: Context = context.applicationContext
    private val nextHttpId = AtomicLong(1L)
    private var logCount = 0

    /** 本次 host 接到的 $insertText 文本（管理页试跑的可见结果）。 */
    val insertedTexts: MutableList<String> = mutableListOf()

    override fun log(text: String) {
        if (text.length > MAX_LOG_LINE_CHARS) return
        if (logCount >= MAX_LOG_LINES) return
        logCount++
        logSink(text)
    }

    override fun http(json: String): Long {
        val id = nextHttpId.getAndIncrement()
        val prepared = try {
            HttpRequestParser.parse(json)
        } catch (e: HttpParamException) {
            onHttpCompletion(HttpCompletion(id, true, e.message ?: "http parameters is nil"))
            return id
        }
        httpExecutor.execute(id, prepared, ::onHttpCompletion)
        return id
    }

    override fun setClipboard(text: String) {
        runCatching { clipboardWriter(text) }
            .onFailure { Timber.w(it, "script \$setClipboard failed") }
    }

    override fun insertText(text: String) {
        if (text.isEmpty()) return
        insertedTexts.add(text)
        runCatching { onInsertText(text) }
            .onFailure { Timber.w(it, "script \$insertText failed") }
    }

    override fun openUrl(url: String): Boolean {
        // java.net.URI（纯 JVM）：android.net.Uri.parse 在主机单测里是桩，
        // 抛 Stub!；URI 解析失败或无 scheme 都返回 false。
        val scheme = try {
            java.net.URI(url).scheme
        } catch (e: Exception) {
            null
        } ?: return false
        if (scheme.isEmpty()) return false
        return runCatching { urlOpener(url) }.getOrDefault(false)
    }

    override fun userNotification(title: String, shortcutName: String, content: String): Boolean {
        if (title.isBlank() || content.isBlank()) return false
        return runCatching { notifier(title, shortcutName, content) }.getOrDefault(false)
    }

    override fun onHttpCompletion(completion: HttpCompletion) {
        onHttpCompletionListener(completion)
    }

    companion object {
        const val MAX_LOG_LINE_CHARS = 1000
        const val MAX_LOG_LINES = 50

        /** 脚本通知 id（每次调用自增，与 13.1 的固定完成通知 id 区分）。 */
        private val nextNotificationId = AtomicInteger(1020)

        /**
         * 脚本通知实发：script 渠道；未授 POST_NOTIFICATIONS 时静默
         * 返回 false（不抛错、不拦脚本）。shortcutName 为空时不显示副标题。
         */
        fun postScriptNotification(
            context: Context,
            title: String,
            shortcutName: String,
            content: String,
        ): Boolean {
            if (!NotificationChannels.isPostNotificationsGranted(context)) return false
            NotificationChannels.ensureChannels(context)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as? NotificationManager ?: return false
            val text = if (shortcutName.isBlank()) content else "$shortcutName：$content"
            val notification = NotificationChannels.completionNotification(
                context,
                NotificationChannels.SCRIPT,
                title,
                text,
            )
            manager.notify(nextNotificationId.getAndIncrement(), notification)
            return true
        }
    }
}
