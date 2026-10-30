package com.jobeen.ime.skin.qr

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 皮肤二维码载荷约定（2026-10-27 新增分流，编辑器侧二维码导出
 * 按同一约定并行施工；App 侧导入与分享均实现，分享见 SkinQrShare
 * ——库页现有的“配色二维码”是旧主题短码，不是 v3 皮肤分享）。
 *
 * 两种载荷（扫码文本经 trim 后按前缀分流；其它一律拒绝）：
 *
 * App 侧分享（2026-10-28 新增）：库页卡片“分享二维码”按同一约定
 * 出码（见 SkinQrShare 与 QrShareDialog），内联与 URL 型均须经本
 * 解析复核；导入侧不改。
 *
 * 1. 内联型（无资产皮肤，编辑器资产为空时导出用）：
 *    `JIME-SKIN:1:<base64url(UTF-8 skin.json)>`
 *    - base64url：标准 base64 的 URL 安全字母表（`-` `_`），无
 *      padding（`=`），无换行；解码后 skin.json ≤ 16KB（二维码
 *      本身实际容量约 3KB，16KB 只是安全上限，不是容量承诺）。
 *    - 内联包不含资产与布局：skin.json 内若引用资产/布局，
 *      导入校验按既有口径逐项回退（RESOURCE_FALLBACK）或快照
 *      期按未设处理，绝不静默补资产；含资产的皮肤必须走 URL 型。
 *    - manifest 皮肤 id：取 skin.json 的 meta.id（有则必须与
 *      清单一致，导入管线已有 meta 一致性门）；无 meta.id 时
 *      由导入侧生成 `qr-<uuid8>`，不改写 skin.json 原文。
 *
 * 2. URL 型（含资产/布局的完整 .jime 包，文件须由用户自行托管）：
 *    `JIME-SKIN-URL:1:<https-url>[#sha256=<64 位小写 hex>]`
 *    - 只接受 https；http、带 userinfo（`@`）、空白/控制字符、
 *      长度 > 2048 一律拒绝；fragment 只能是 sha256 一种形式，
 *      其它 fragment 一律拒绝（防歧义解析）。
 *    - sha256 fragment 可选：给出时下载后必验， mismatch 拒绝；
 *      未给出时仍受 .jime 内 manifest 逐文件 sha256 校验保护
 *      （导入管线已有），但不做整包级预验。
 *    - 下载上限 20MB、超时 15s/30s（见 SkinQrImporter）；下载
 *      产物原样走既有 JimeImporter.importZip 全量校验，绝不
 *      绕过校验；非法包拒绝 + 日志。
 *
 * 旧主题短码（`IMEKBTHEME:`，旧主题设置页的扫码格式）：
 * 库页扫码在调 SkinQrImporter 之前先按前缀分流到共享旧主题
 * 导入（ThemeImport），故正常扫码路径不会走到这里；本入口
 * 仍将其识别为 legacy_theme_qr 并拒绝（直接调解析器的兜底，
 * 不会被误当成皮肤导入）。
 *
 * 日志隐私：本文件只做解析与打包，不记日志；调用方（导入器
 * 与 UI）只记 reason/kind/skinId/字节数等元数据，绝不记扫码
 * 原文、URL、skin.json 内容。
 *
 * 纯 Kotlin（手写 base64url，不依赖 android.util.Base64——
 * minSdk 24 无 java.util.Base64，且单测须在 JVM 跑通）。
 */
object SkinQrPayload {

    const val INLINE_PREFIX = "JIME-SKIN:1:"
    const val URL_PREFIX = "JIME-SKIN-URL:1:"

    /** 旧主题短码前缀（ThemeCode.PREFIX）：仅用于识别拒绝（兜底），不解析。 */
    const val LEGACY_PREFIX = "IMEKBTHEME:"

    /** 内联解码后 skin.json 字节上限（安全上限，非容量承诺）。 */
    const val MAX_INLINE_BYTES = 16 * 1024

    /** URL 型载荷全文长度上限。 */
    const val MAX_URL_LENGTH = 2048

    /** URL 型下载字节上限（导入器执行）。 */
    const val MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024

    sealed interface Parsed {
        /** 内联 skin.json 原文；metaId 为树内 meta.id（无则 null）。 */
        data class Inline(val skinJson: String, val metaId: String?) : Parsed

        /** 下载 URL；sha256 为 fragment 给出的整包期望值（无则 null）。 */
        data class Url(val url: String, val sha256: String?) : Parsed
    }

    sealed interface ParseResult {
        data class Ok(val parsed: Parsed) : ParseResult
        data class Rejected(val reason: String) : ParseResult
    }

    fun parse(rawText: String?): ParseResult {
        val raw = rawText?.trim().orEmpty()
        if (raw.isEmpty()) return ParseResult.Rejected("qr_empty")
        if (raw.startsWith(LEGACY_PREFIX)) return ParseResult.Rejected("legacy_theme_qr")
        return when {
            raw.startsWith(INLINE_PREFIX) -> parseInline(raw.removePrefix(INLINE_PREFIX))
            raw.startsWith(URL_PREFIX) -> parseUrl(raw.removePrefix(URL_PREFIX))
            else -> ParseResult.Rejected("qr_unknown_prefix")
        }
    }

    private fun parseInline(body: String): ParseResult {
        if (body.isEmpty()) return ParseResult.Rejected("qr_inline_empty")
        val bytes = Base64Url.decode(body) ?: return ParseResult.Rejected("qr_base64_invalid")
        if (bytes.size > MAX_INLINE_BYTES) return ParseResult.Rejected("qr_inline_too_large")
        val text = runCatching { bytes.decodeToString() }.getOrNull()
            ?: return ParseResult.Rejected("qr_inline_not_utf8")
        val tree = SkinJson.parseObject(text) ?: return ParseResult.Rejected("qr_skin_unparseable")
        val metaId = (tree["meta"] as? JsonObject)?.get("id")
            ?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.contentOrNull
        return ParseResult.Ok(Parsed.Inline(text, metaId))
    }

    private fun parseUrl(body: String): ParseResult {
        if (body.isEmpty()) return ParseResult.Rejected("qr_url_empty")
        if (body.length > MAX_URL_LENGTH) return ParseResult.Rejected("qr_url_too_long")
        if (body.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) {
            return ParseResult.Rejected("qr_url_invalid_chars")
        }
        val hashIndex = body.indexOf('#')
        if (hashIndex >= 0 && body.indexOf('#', hashIndex + 1) >= 0) {
            return ParseResult.Rejected("qr_url_fragment_invalid")
        }
        val url = if (hashIndex >= 0) body.substring(0, hashIndex) else body
        val fragment = if (hashIndex >= 0) body.substring(hashIndex + 1) else null
        var sha256: String? = null
        if (fragment != null) {
            val match = Regex("^sha256=([0-9a-f]{64})$").matchEntire(fragment)
                ?: return ParseResult.Rejected("qr_url_fragment_invalid")
            sha256 = match.groupValues[1]
        }
        if (!url.startsWith("https://")) return ParseResult.Rejected("qr_url_not_https")
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?')
        if (authority.isEmpty() || '@' in authority) {
            return ParseResult.Rejected("qr_url_userinfo_rejected")
        }
        return ParseResult.Ok(Parsed.Url(url, sha256))
    }

    /** 内联载荷编码（编辑器/分享侧用；App 导入不依赖本函数）。 */
    fun encodeInline(skinJson: String): String =
        INLINE_PREFIX + Base64Url.encode(skinJson.encodeToByteArray())

    /**
     * 由内联 skin.json 构建可直接喂给 JimeImporter.importZip 的
     * .jime 包字节：manifest.json + skin.json，清单 sha256 与
     * 内容一致（导入管线会复核）。manifest 皮肤 id 取 metaId，
     * 无 meta.id 时调用方须先生成（见 [newQrSkinId]）。
     */
    fun buildPackageZip(skinJson: String, manifestSkinId: String): ByteArray {
        val skinBytes = skinJson.encodeToByteArray()
        val manifest = PackageManifest(
            formatVersion = PackageManifest().formatVersion,
            contents = listOf(
                PackageManifest.Content(
                    type = PackageManifest.TYPE_SKIN,
                    id = manifestSkinId,
                    path = "skin.json",
                    sha256 = DigestUtil.sha256Hex(skinBytes),
                ),
            ),
        )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(PackageManifest.toJson(manifest).encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("skin.json"))
            zip.write(skinBytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** 无 meta.id 的内联皮肤的清单 id（不改写 skin.json 原文）。 */
    fun newQrSkinId(): String = "qr-" + UUID.randomUUID().toString().take(8)

    /** 手写 base64url（无 padding）：严格解码，非法字符/长度一律 null。 */
    object Base64Url {
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        private val DECODE = IntArray(256) { -1 }.apply {
            ALPHABET.forEachIndexed { index, c -> this[c.code] = index }
        }

        fun encode(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size * 4 + 2) / 3)
            var i = 0
            while (i + 3 <= bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                out.append(ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
                out.append(ALPHABET[b2 and 0x3F])
                i += 3
            }
            val remaining = bytes.size - i
            if (remaining == 1) {
                val b0 = bytes[i].toInt() and 0xFF
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[(b0 and 0x03) shl 4])
            } else if (remaining == 2) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                out.append(ALPHABET[b0 ushr 2])
                out.append(ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
                out.append(ALPHABET[(b1 and 0x0F) shl 2])
            }
            return out.toString()
        }

        fun decode(text: String): ByteArray? {
            if (text.isEmpty() || text.length % 4 == 1) return null
            if (text.any { it.code >= 256 || DECODE[it.code] < 0 }) return null
            val out = ByteArrayOutputStream((text.length * 3) / 4 + 3)
            var i = 0
            while (i + 4 <= text.length) {
                val v0 = DECODE[text[i].code]
                val v1 = DECODE[text[i + 1].code]
                val v2 = DECODE[text[i + 2].code]
                val v3 = DECODE[text[i + 3].code]
                out.write((v0 shl 2) or (v1 ushr 4))
                out.write(((v1 and 0x0F) shl 4) or (v2 ushr 2))
                out.write(((v2 and 0x03) shl 6) or v3)
                i += 4
            }
            val remaining = text.length - i
            if (remaining == 2) {
                val v0 = DECODE[text[i].code]
                val v1 = DECODE[text[i + 1].code]
                if (v1 and 0x0F != 0) return null // 尾 bits 非零 = 非规范编码
                out.write((v0 shl 2) or (v1 ushr 4))
            } else if (remaining == 3) {
                val v0 = DECODE[text[i].code]
                val v1 = DECODE[text[i + 1].code]
                val v2 = DECODE[text[i + 2].code]
                if (v2 and 0x03 != 0) return null
                out.write((v0 shl 2) or (v1 ushr 4))
                out.write(((v1 and 0x0F) shl 4) or (v2 ushr 2))
            }
            return out.toByteArray()
        }
    }
}
