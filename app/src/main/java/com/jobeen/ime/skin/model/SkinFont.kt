package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 皮肤内嵌字体（审计 C 项）：
 *
 * 对端 `fontFace` 的 Jime 映射：顶层 `fontFaces` 数组，每项
 * `{name, url}`——name 为皮肤内引用名（fontFamily / roleFonts /
 * fontRanges 的 family 取值），url 为清单资产逻辑 id（文件位
 * 于包内 fonts/ 目录，扩展名 ttf / otf）。快照只记名 → 资产
 * id 的映射与资产 sha，字体字节由运行期按内容寻址取（与单
 * 键图片同款口径），缺失／损坏时逐项回落，绝不拒包。
 *
 * 纯函数：资产类别与字节数经参数注入，可直接单测。
 */
data class SkinFontFace(
    /** 皮肤内引用名（声明原文，匹配时大小写不敏感）。 */
    val name: String,
    /** 清单资产逻辑 id（fontFace.url）。 */
    val assetId: String,
)

/** 皮肤内嵌字体解析结果。 */
data class ParsedSkinFontFaces(
    val faces: List<SkinFontFace>,
    val issues: List<SkinIssue>,
)

/**
 * 按码位区间指派字体（审计 C 项）：顶层 `fontRanges` 数组，
 * 每项 `{location, length, family}`——location/length 以
 * UTF-16 码元计（与对端 NSRange 及 Java String 下标同口径，
 * 增补字符占 2 码元）；family 为系统字族名或 fontFaces 声明名。
 * 区间重叠时声明在先者胜出（[SkinFontRanges.spansFor] 同口径）。
 */
data class SkinFontRange(
    val location: Int,
    val length: Int,
    /** 规范化后的 family：系统名小写，或内嵌字体声明原文。 */
    val family: String,
)

/** 按码位区间指派字体的解析结果。 */
data class ParsedSkinFontRanges(
    val ranges: List<SkinFontRange>,
    val issues: List<SkinIssue>,
)

/**
 * 文本分段结果（纯数据，供渲染层按段取字族）：
 * [family] 为 null 的段未被任何区间覆盖，走基础字体。
 */
data class FontSpan(
    /** UTF-16 码元下标（起，含）。 */
    val start: Int,
    /** UTF-16 码元下标（止，不含）。 */
    val end: Int,
    val family: String?,
)

/**
 * 区间分段（纯函数，可 JVM 单测）：按 UTF-16 码元下标逐位
 * 取首个覆盖区间的 family，相邻同 family 合并为一段；超
 * 出文本的区间部分自然不命中，不另行裁剪。
 */
object SkinFontRanges {

    fun spansFor(text: String, ranges: List<SkinFontRange>): List<FontSpan> {
        if (text.isEmpty()) return emptyList()
        val spans = mutableListOf<FontSpan>()
        var runStart = 0
        var runFamily: String? = familyAt(0, ranges)
        for (index in 1 until text.length) {
            val family = familyAt(index, ranges)
            if (family != runFamily) {
                spans += FontSpan(runStart, index, runFamily)
                runStart = index
                runFamily = family
            }
        }
        spans += FontSpan(runStart, text.length, runFamily)
        return spans
    }

    private fun familyAt(index: Int, ranges: List<SkinFontRange>): String? {
        for (range in ranges) {
            if (index >= range.location && index < range.location + range.length) {
                return range.family
            }
        }
        return null
    }
}

object SkinFontParser {

    /**
     * 皮肤内嵌字体单件上限（与全局字体导入同值
     * GlobalFonts.MAX_IMPORT_BYTES；此处不跨包引用常量，
     * 避免 model → theme 的新依赖边）。
     */
    const val MAX_SKIN_FONT_BYTES = 32L * 1024 * 1024

    fun parse(
        tree: JsonObject,
        assetKind: (String) -> AssetKind?,
        assetBytes: (String) -> Long? = { null },
    ): ParsedSkinFontFaces {
        val node = tree["fontFaces"] ?: return ParsedSkinFontFaces(emptyList(), emptyList())
        val array = node as? JsonArray
        if (array == null) {
            return ParsedSkinFontFaces(
                emptyList(),
                listOf(
                    SkinIssue(
                        "fontFaces", SkinIssue.Kind.FIELD_FALLBACK,
                        "font_faces_not_array",
                    ),
                ),
            )
        }
        val faces = mutableListOf<SkinFontFace>()
        val issues = mutableListOf<SkinIssue>()
        val seenNames = mutableSetOf<String>()
        for ((index, item) in array.withIndex()) {
            val obj = item as? JsonObject
            if (obj == null) {
                issues += SkinIssue(
                    "fontFaces[$index]", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_face_not_object",
                )
                continue
            }
            val name = (obj["name"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull?.trim()
            if (name.isNullOrEmpty()) {
                issues += SkinIssue(
                    "fontFaces[$index].name", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_face_name_empty",
                )
                continue
            }
            val url = (obj["url"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull?.trim()
            if (url.isNullOrEmpty()) {
                issues += SkinIssue(
                    "fontFaces[$index].url", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_face_url_empty",
                )
                continue
            }
            if (!seenNames.add(name.lowercase())) {
                issues += SkinIssue(
                    "fontFaces[$index].name", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_face_duplicate_name",
                )
                continue
            }
            // 清单已知且类别非字体 → 丢弃；清单未知（assetKind
            // 为 null）时暂收，快照构建期以清单在册为准复核。
            val kind = assetKind(url)
            if (kind != null && kind != AssetKind.FONT) {
                issues += SkinIssue(
                    "fontFaces[$index].url", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_face_not_font",
                )
                continue
            }
            // 字节数已知时按全局导入同口径卡空文件与上限。
            val bytes = assetBytes(url)
            if (bytes != null && (bytes <= 0L || bytes > MAX_SKIN_FONT_BYTES)) {
                issues += SkinIssue(
                    "fontFaces[$index].url", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_face_size_invalid",
                )
                continue
            }
            faces += SkinFontFace(name, url)
        }
        return ParsedSkinFontFaces(faces, issues)
    }

    /**
     * 按码位区间指派字体解析（审计 C 项）：
     * [resolveFamily] 将 family 原值规范化（系统名小写／内嵌
     * 声明原文），返回 null = 未知族名（该项丢弃）。单项非法
     * 只剔该项，绝不拒包；location + length 上限为 Unicode
     * 码位空间（0x110000），溢出即丢弃。
     */
    fun parseRanges(
        tree: JsonObject,
        resolveFamily: (String) -> String?,
    ): ParsedSkinFontRanges {
        val node = tree["fontRanges"] ?: return ParsedSkinFontRanges(emptyList(), emptyList())
        val array = node as? JsonArray
        if (array == null) {
            return ParsedSkinFontRanges(
                emptyList(),
                listOf(
                    SkinIssue(
                        "fontRanges", SkinIssue.Kind.FIELD_FALLBACK,
                        "font_ranges_not_array",
                    ),
                ),
            )
        }
        val ranges = mutableListOf<SkinFontRange>()
        val issues = mutableListOf<SkinIssue>()
        for ((index, item) in array.withIndex()) {
            val obj = item as? JsonObject
            if (obj == null) {
                issues += SkinIssue(
                    "fontRanges[$index]", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_range_not_object",
                )
                continue
            }
            val location = (obj["location"] as? JsonPrimitive)?.intOrNull
            val length = (obj["length"] as? JsonPrimitive)?.intOrNull
            val rawFamily = (obj["family"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.contentOrNull?.trim()
            when {
                location == null || location < 0 -> issues += SkinIssue(
                    "fontRanges[$index].location", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_range_location_invalid",
                )
                length == null || length < 1 -> issues += SkinIssue(
                    "fontRanges[$index].length", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_range_length_invalid",
                )
                location.toLong() + length.toLong() > 0x110000L -> issues += SkinIssue(
                    "fontRanges[$index]", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_range_overflow",
                )
                rawFamily.isNullOrEmpty() -> issues += SkinIssue(
                    "fontRanges[$index].family", SkinIssue.Kind.FIELD_FALLBACK,
                    "font_range_family_empty",
                )
                else -> {
                    val family = resolveFamily(rawFamily)
                    if (family == null) {
                        issues += SkinIssue(
                            "fontRanges[$index].family", SkinIssue.Kind.FIELD_FALLBACK,
                            "font_range_unknown_family",
                        )
                    } else {
                        ranges += SkinFontRange(location, length, family)
                    }
                }
            }
        }
        return ParsedSkinFontRanges(ranges, issues)
    }
}
