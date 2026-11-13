// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.error

import android.content.Context
import android.database.sqlite.SQLiteException
import com.jobeen.ime.R
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.zip.ZipException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * 深层异常消息 → 用户可读文案的统一映射入口（WO33 第 4(c) 项）。
 *
 * 背景：深层抛出的异常消息（JVM／OkHttp／SQLite／WebDAV 的英文原文，
 * 甚至带服务端正文与 URL）曾原样进 toast／提示框。现统一走这里：
 * 先按异常类型与消息特征归类为 [DeepErrorCode]，再按码取 strings.xml
 * 里的 `deep_error_*` 文案。原始消息只进 Timber／DiagLog，不上屏。
 *
 * 两个入口（语义不同，别混用）：
 * - [userMessage]：手头有 [Throwable] 时用。归类成功取映射文案；
 *   归类失败时保留原消息（应用层自写的中文提示，如 WebDAV 的
 *   “同步目录不存在…”、require 的校验文案本就是给用户看的），
 *   消息为空才回落 [DeepErrorCode.UNKNOWN]。
 * - [userMessageForMessage]：手头只有组合好的字符串时用（如禁碰文件
 *   BackupManager 拼好的 RestoreState.Failed.message、SchemeDirs
 *   存在偏好里的迁移失败文本）。只换“尾巴”：结果前缀（“还原失败，
 *   已恢复到还原前状态”这类）保留，只把带深层特征的尾段换成映射
 *   文案；无特征时原文不动。
 *
 * 取消不映射：调用方须在到这里之前把 [CancellationException] 排除
 * （各调用点已按既有口径重抛／跳过 toast），不要把取消当失败报给用户。
 */
object DeepErrorMapper {

    /** 深层失败的归类码（码 → `deep_error_*` 串，见 [messageResId]）。 */
    enum class DeepErrorCode {
        /** 域名解不出／连不上／连接被拒或重置。 */
        NETWORK,
        /** 读／写／连接超时。 */
        TIMEOUT,
        /** HTTP 5xx：服务端暂时不可用。 */
        SERVER,
        /** ENOSPC：存储空间不足。 */
        NO_SPACE,
        /** 文件不存在、HTTP 404。 */
        NOT_FOUND,
        /** 压缩包／JSON／数据库文件损坏或格式不对。 */
        CORRUPT,
        /** 无访问权限、HTTP 401／403。 */
        PERMISSION,
        /** 其他 IOException（读写失败）。 */
        IO,
        /** 归类不出的其他失败。 */
        UNKNOWN,
    }

    /** 码 → strings.xml 串 ID。 */
    fun messageResId(code: DeepErrorCode): Int = when (code) {
        DeepErrorCode.NETWORK -> R.string.deep_error_network
        DeepErrorCode.TIMEOUT -> R.string.deep_error_timeout
        DeepErrorCode.SERVER -> R.string.deep_error_server
        DeepErrorCode.NO_SPACE -> R.string.deep_error_no_space
        DeepErrorCode.NOT_FOUND -> R.string.deep_error_not_found
        DeepErrorCode.CORRUPT -> R.string.deep_error_corrupt
        DeepErrorCode.PERMISSION -> R.string.deep_error_permission
        DeepErrorCode.IO -> R.string.deep_error_io
        DeepErrorCode.UNKNOWN -> R.string.deep_error_unknown
    }

    /**
     * 按异常归类（纯函数，可单测）：沿 cause 链逐层先看类型、再看
     * 消息特征；任一层命中即返回；全链无特征返回 null（调用方保留
     * 原消息，不硬套通用文案）。
     */
    fun classify(throwable: Throwable?): DeepErrorCode? {
        var current = throwable
        var depth = 0
        while (current != null && depth < 6) {
            if (current is CancellationException) {
                current = current.cause
                depth++
                continue
            }
            classifyByType(current)?.let { return it }
            classifyMessage(current.message)?.let { return it }
            // 通用 IOException（已排除掉上面能认出的具体类型与消息特征）
            if (current is IOException) return DeepErrorCode.IO
            current = current.cause
            depth++
        }
        return null
    }

    /** 只按消息文本归类（纯函数，可单测）：无特征返回 null。 */
    fun classifyMessage(message: String?): DeepErrorCode? {
        val text = message?.lowercase().orEmpty()
        if (text.isBlank()) return null
        httpCode(text)?.let { return it }
        return when {
            text.contains("no space left") || text.contains("enospc") -> DeepErrorCode.NO_SPACE
            text.contains("timed out") || text.contains("timeout") ||
                text.contains("etimedout") -> DeepErrorCode.TIMEOUT
            text.contains("permission denied") || text.contains("eacces") ||
                text.contains("access denied") || text.contains("unauthorized") ||
                text.contains("forbidden") -> DeepErrorCode.PERMISSION
            text.contains("no such file") || text.contains("file not found") ||
                text.contains("enoent") || text.contains("not found") -> DeepErrorCode.NOT_FOUND
            text.contains("malformed") || text.contains("not a zip") ||
                text.contains("bad zip") || text.contains("unexpected token") ||
                text.contains("failed to parse") || text.contains("parse error") ||
                text.contains("invalid json") || text.contains("json syntax") ||
                text.contains("json parse") -> DeepErrorCode.CORRUPT
            text.contains("unable to resolve host") || text.contains("unknownhost") ||
                text.contains("connection refused") || text.contains("econnrefused") ||
                text.contains("network is unreachable") || text.contains("enetunreach") ||
                text.contains("no route to host") || text.contains("ehostunreach") ||
                text.contains("connection reset") || text.contains("broken pipe") ||
                text.contains("ssl") || text.contains("certificate") ||
                text.contains("handshake") -> DeepErrorCode.NETWORK
            else -> null
        }
    }

    /**
     * Throwable 入口：归类成功取映射文案；归类失败时对原消息做
     * [sanitizeTail]（组合消息只换尾巴）；原消息为空才回落 UNKNOWN。
     */
    fun userMessage(context: Context, throwable: Throwable?): String {
        if (throwable == null) return context.getString(messageResId(DeepErrorCode.UNKNOWN))
        classify(throwable)?.let { return context.getString(messageResId(it)) }
        val raw = throwable.message?.trim().orEmpty()
        if (raw.isEmpty()) return context.getString(messageResId(DeepErrorCode.UNKNOWN))
        return userMessageForMessage(context, raw)
    }

    /**
     * 字符串入口：归类成功时只换尾巴（见 [sanitizeTail]）；无特征
     * 时原文不动；空串回落 UNKNOWN。
     */
    fun userMessageForMessage(context: Context, message: String?): String {
        val raw = message?.trim().orEmpty()
        if (raw.isEmpty()) return context.getString(messageResId(DeepErrorCode.UNKNOWN))
        val code = classifyMessage(raw) ?: return raw
        return sanitizeTail(raw, context.getString(messageResId(code)))
    }

    /**
     * 组合消息只换尾巴（纯函数，可单测）：
     * - 末尾 “（…）” 里是深层原文时，只换括号内（如“还原准备失败：
     *   无法保存还原前快照（No space left…）”→ 括号内换成映射文案）；
     * - 否则取最后一个 “：”／“:” 后的尾段，有特征就只换尾段
     *   （如“migration plan failed: Unable to resolve host…”→
     *   前缀保留、尾段换成映射文案；“列出服务器文件失败：HTTP 500
     *   （…服务端正文…）”→ 尾段整体换掉，正文与 URL 不上屏）；
     * - 无分隔符（整条就是深层原文）时整条换成映射文案。
     */
    fun sanitizeTail(raw: String, mapped: String): String {
        if (raw.endsWith("）") && raw.contains("（")) {
            val open = raw.lastIndexOf("（")
            val inner = raw.substring(open + 1, raw.length - 1)
            if (classifyMessage(inner) != null) {
                return raw.substring(0, open + 1) + mapped + "）"
            }
        }
        val sep = maxOf(raw.lastIndexOf("："), raw.lastIndexOf(':'))
        if (sep >= 0) {
            val tail = raw.substring(sep + 1)
            if (tail.isNotBlank() && classifyMessage(tail) != null) {
                return raw.substring(0, sep + 1) + mapped
            }
            return raw
        }
        return mapped
    }

    /** 消息里的 “HTTP NNN” → 码（401／403 权限、404 不存在、408／504 超时、5xx 服务端）。 */
    private fun httpCode(text: String): DeepErrorCode? {
        val match = HTTP_CODE_REGEX.find(text) ?: return null
        val code = match.groupValues[1].toIntOrNull() ?: return null
        return when (code) {
            401, 403 -> DeepErrorCode.PERMISSION
            404 -> DeepErrorCode.NOT_FOUND
            408, 504 -> DeepErrorCode.TIMEOUT
            in 500..599 -> DeepErrorCode.SERVER
            else -> null
        }
    }

    private val HTTP_CODE_REGEX = Regex("""http\s+(\d{3})""")

    /** 按异常类型归类（未命中返回 null，继续看消息特征）。 */
    private fun classifyByType(t: Throwable): DeepErrorCode? = when (t) {
        // SocketTimeoutException 是 SocketException 的子类，必须先判。
        is SocketTimeoutException -> DeepErrorCode.TIMEOUT
        is UnknownHostException, is ConnectException, is NoRouteToHostException,
        is SocketException -> DeepErrorCode.NETWORK
        is FileNotFoundException, is NoSuchFileException -> DeepErrorCode.NOT_FOUND
        is ZipException -> DeepErrorCode.CORRUPT
        is SerializationException -> DeepErrorCode.CORRUPT
        is AccessDeniedException -> DeepErrorCode.PERMISSION
        is SecurityException -> DeepErrorCode.PERMISSION
        is SQLiteException ->
            if (t.message?.contains("malformed", ignoreCase = true) == true) {
                DeepErrorCode.CORRUPT
            } else {
                DeepErrorCode.IO
            }
        else -> if (isJsonParseException(t)) DeepErrorCode.CORRUPT else null
    }

    /** gson／org.json 的解析异常（主工程未引 gson，按类名认，不新增依赖）。 */
    private fun isJsonParseException(t: Throwable): Boolean =
        t.javaClass.simpleName == "JSONException" ||
            t.javaClass.simpleName == "JsonSyntaxException" ||
            t.javaClass.simpleName == "JsonParseException"
}
