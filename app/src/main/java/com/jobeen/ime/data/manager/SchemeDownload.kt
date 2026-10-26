// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import java.net.URI

/**
 * 第三期远程下载：纯逻辑，不依赖 Android 框架，可 JVM 单测；OkHttp
 * 执行与界面在 SchemaSettingsScreen。
 *
 * 约束来源（定稿文档修订版，2026-10-24）：
 * - 远程下载只留手动 URL，绝不做预设目录 / 一键下载；
 * - 手动 URL 包无更新提醒；
 * - 仅限 HTTPS，非 HTTPS 直接拒绝；
 * - 下载完成后不另起管线，走第二期同一条校验 / 安装管线；
 * - 下载条目只保存方案名 + URL；Header 每次下载时输入、不持久化
 *   （避免把可能含令牌的请求头存进偏好）；
 * - 下载失败（网络错误、非 zip、超二期体积上限）按二期错误口径明示，
 *   暂存清理干净；非 zip 由二期 extractZip 以 NOT_ZIP 口径报出。
 */
object SchemeDownload {

    /** 下载条目偏好文件名（与方案列表、包登记偏好分开，避免键冲突）。 */
    const val PREFS_NAME = "scheme_download"

    /** 下载条目键：整表一行一记录的编码字符串（见 encodeDownloadEntries）。 */
    const val KEY_DOWNLOAD_ENTRIES = "download_entries"

    /** URL 校验结果。 */
    sealed interface UrlValidation {
        /** 合法：trim 后的原始串（OkHttp 直接用，scheme 大小写不敏感）。 */
        data class Valid(val url: String) : UrlValidation

        data class Invalid(val reason: UrlInvalidReason) : UrlValidation
    }

    enum class UrlInvalidReason {
        EMPTY,
        MALFORMED,
        NOT_HTTPS,
        NO_HOST,
    }

    /**
     * 仅限 HTTPS：空串、解析失败、scheme 不是 https、host 缺失一律拒绝。
     * 不做白名单、不做预设 URL 匹配——手动 URL 的全部含义就是用户自填。
     */
    fun validateDownloadUrl(raw: String): UrlValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return UrlValidation.Invalid(UrlInvalidReason.EMPTY)
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            return UrlValidation.Invalid(UrlInvalidReason.MALFORMED)
        }
        if (uri.scheme?.lowercase() != "https") {
            return UrlValidation.Invalid(UrlInvalidReason.NOT_HTTPS)
        }
        if (uri.host.isNullOrBlank()) {
            return UrlValidation.Invalid(UrlInvalidReason.NO_HOST)
        }
        return UrlValidation.Valid(trimmed)
    }

    /** 请求头名合法字符：RFC 7230 token（字母数字与 !#$%&'*+-.^_`|~）。 */
    private fun isHeaderNameValid(name: String): Boolean {
        if (name.isEmpty()) return false
        return name.all { c ->
            c.isLetterOrDigit() || c in "!#\$%&'*+-.^_`|~"
        }
    }

    /** 请求头值合法：不含 CR / LF 与 0x7F 以下控制字符（Tab 除外）。 */
    private fun isHeaderValueValid(value: String): Boolean =
        value.all { c -> c == '\t' || c.code >= 0x20 && c.code != 0x7F }

    fun validateHeader(name: String, value: String): Boolean =
        isHeaderNameValid(name.trim()) && isHeaderValueValid(value)

    /** 表单 Header 行（名 / 值）组装结果：空行视为未填、直接丢弃。 */
    sealed interface HeadersResult {
        data class Ok(val headers: List<Pair<String, String>>) : HeadersResult
        data class InvalidRow(val index: Int) : HeadersResult
    }

    /**
     * 组装 Header 行：名空且值空的行丢弃；名空但值非空、或名 / 值含
     * 非法字符的行整单拒绝（返回行号，界面据此标错，不静默丢弃）。
     */
    fun buildHeaders(rows: List<Pair<String, String>>): HeadersResult {
        val headers = mutableListOf<Pair<String, String>>()
        for ((index, row) in rows.withIndex()) {
            val name = row.first.trim()
            val value = row.second
            if (name.isEmpty() && value.isBlank()) continue
            if (!validateHeader(name, value)) return HeadersResult.InvalidRow(index)
            headers.add(name to value)
        }
        return HeadersResult.Ok(headers)
    }

    /** 下载条目：只保存方案名 + URL，Header 永不进这条记录。 */
    data class DownloadEntry(
        val name: String,
        val url: String,
    )

    /** 条目名净化（Tab / 换行进编码会断行，统一成下划线）。 */
    fun sanitizeEntryName(raw: String): String =
        raw.replace('\t', '_').replace('\n', '_').replace('\r', '_').trim()

    /**
     * 下载条目编码：一行一记录，名与 URL 以 Tab 分隔。
     * 解码容错：字段不足、名或 URL 为空的行直接丢弃；URL 合法性在
     * 下载时校验，不在解码时丢条目（不静默删用户已存的条目）。
     */
    fun encodeDownloadEntries(entries: List<DownloadEntry>): String =
        entries.joinToString("\n") { entry ->
            sanitizeEntryName(entry.name) + "\t" + entry.url.trim()
        }

    fun decodeDownloadEntries(raw: String?): List<DownloadEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val parts = line.split('\t', limit = 2)
            if (parts.size < 2) return@mapNotNull null
            val name = parts[0].trim()
            val url = parts[1].trim()
            if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
            DownloadEntry(name, url)
        }
    }

    /** 同名条目替换（新增保存时按名 upsert，不留两条同名）。 */
    fun upsertEntry(entries: List<DownloadEntry>, entry: DownloadEntry): List<DownloadEntry> =
        entries.filter { it.name != entry.name } + entry

    /** 下载阶段失败口径（校验 / 安装阶段失败仍走二期 ImportErrorKind）。 */
    enum class DownloadErrorKind {
        NETWORK,
        HTTP_STATUS,
        REDIRECT_DOWNGRADE,
        TOO_LARGE,
        INCOMPLETE,
    }

    /**
     * HTTP 状态码映射：3xx 到达此处只可能是降级跳转（https→http 被
     * 客户端禁跟随后原样返回）或其他无法跟随的跳转，一律按拒绝跳转
     * 处理，不把 3xx 当成普通 HTTP 错误含混过去。
     */
    fun errorKindForHttp(code: Int): DownloadErrorKind =
        if (code in 300..399) DownloadErrorKind.REDIRECT_DOWNGRADE
        else DownloadErrorKind.HTTP_STATUS
}
