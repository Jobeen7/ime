package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
}
