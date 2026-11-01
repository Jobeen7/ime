// SPDX-License-Identifier: Apache-2.0
package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 候选单元格样式（对端 candidateStyle 全字段，Jime 侧模型）：
 *
 * - 字号：textFontSize / indexFontSize / commentFontSize（dp）；
 * - 字重：textFontWeight / indexFontWeight / commentFontWeight
 *   （对端枚举名，见 [CandidateFontWeight]）；
 * - insets：单元格内边距；backgroundInsets：单元格背景内边距；
 * - cornerRadius：单一样式圆角（对端现行皮肤写法；文档别名
 *   backgroundCornerRadius 同义，解析期 cornerRadius 优先）。
 *
 * 全部字段皆可空：缺失 = 该皮肤未指定，不代表任何通用值。
 * 字号是按皮肤取值（per-skin 参数），不存在跨皮肤的通用值：
 * 现行皮肤 20/10/10、参考皮肤 18/16 系列、文档示例 18/18/18
 * 与 16/16/16 皆为各自皮肤的值，只作测试向量，不作默认值。
 * 纯解析，可直接单测。
 */
data class CandidateCellStyleSpec(
    val textSizeDp: Float? = null,
    val textWeight: CandidateFontWeight? = null,
    val indexSizeDp: Float? = null,
    val indexWeight: CandidateFontWeight? = null,
    val commentSizeDp: Float? = null,
    val commentWeight: CandidateFontWeight? = null,
    val insets: AreaInsets = AreaInsets(),
    val backgroundInsets: AreaInsets = AreaInsets(),
    /** 单一样式圆角（dp）；null = 未指定。 */
    val cornerRadiusDp: Float? = null,
)

/** 对端字重枚举 → 数值字重（100–900，Android 字重口径）。 */
enum class CandidateFontWeight(val peerName: String, val weight: Int) {
    ULTRA_LIGHT("ultraLight", 100),
    THIN("thin", 200),
    LIGHT("light", 300),
    REGULAR("regular", 400),
    MEDIUM("medium", 500),
    SEMIBOLD("semibold", 600),
    BOLD("bold", 700),
    HEAVY("heavy", 800),
    BLACK("black", 900);

    companion object {
        fun fromPeerName(name: String?): CandidateFontWeight? =
            entries.firstOrNull { it.peerName == name }
    }
}

/** 解析候选单元格样式节点：缺失返回 null，非对象记 issue 并返回 null。 */
fun parseCandidateCellStyle(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): CandidateCellStyleSpec? {
    if (node == null) return null
    val obj = node as? JsonObject
    if (obj == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "candidate_cell_style_not_object")
        return null
    }
    fun weight(name: String): CandidateFontWeight? {
        val raw = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (raw == null) return null
        return CandidateFontWeight.fromPeerName(raw) ?: run {
            issues += SkinIssue("$path.$name", SkinIssue.Kind.FIELD_FALLBACK, "font_weight_invalid")
            null
        }
    }
    // 圆角：现行皮肤名 cornerRadius 优先，文档别名 backgroundCornerRadius 兜底。
    val cornerRadius = parseDpField(obj["cornerRadius"], "$path.cornerRadius", issues, 0f..32f)
        ?: parseDpField(
            obj["backgroundCornerRadius"], "$path.backgroundCornerRadius", issues, 0f..32f,
        )
    return CandidateCellStyleSpec(
        textSizeDp = parseDpField(obj["textFontSize"], "$path.textFontSize", issues, 8f..32f),
        textWeight = weight("textFontWeight"),
        indexSizeDp = parseDpField(obj["indexFontSize"], "$path.indexFontSize", issues, 8f..32f),
        indexWeight = weight("indexFontWeight"),
        commentSizeDp = parseDpField(obj["commentFontSize"], "$path.commentFontSize", issues, 8f..32f),
        commentWeight = weight("commentFontWeight"),
        insets = parseAreaInsets(obj["insets"], "$path.insets", issues),
        backgroundInsets = parseAreaInsets(obj["backgroundInsets"], "$path.backgroundInsets", issues),
        cornerRadiusDp = cornerRadius,
    )
}

/**
 * 候选字号解析口径（App > 皮肤 > Jime 默认）：
 *
 * - App 设置优先：用户在 App 侧显式设定字号时胜过皮肤值
 *   （对端文档同口径；Jime 现无 App 侧候选字号设置，App 侧
 *   一律传 null，规则本身仍以本函数钉住，供日后设置项复用）；
 * - 皮肤值其次：皮肤写了即用，不论其数值归属哪套参考值；
 * - 缺失时回落 Jime 自有默认值（下述常量），绝不套用任何
 *   单一参考皮肤的值作通用回落。
 */
object CandidateSizeResolution {
    /** Jime 自有默认（2026-11-01 纠正前既有值：序号与注释同取 16）。 */
    const val JIME_DEFAULT_TEXT_DP = 18f
    const val JIME_DEFAULT_INDEX_DP = 16f
    const val JIME_DEFAULT_COMMENT_DP = 16f

    /** 单项解析：App > 皮肤 > Jime 默认。 */
    fun resolve(appDp: Float?, skinDp: Float?, jimeDefaultDp: Float): Float =
        appDp ?: skinDp ?: jimeDefaultDp

    /** 三项一次解析（App 侧三项各自独立优先）。 */
    fun resolveAll(
        appTextDp: Float? = null,
        appIndexDp: Float? = null,
        appCommentDp: Float? = null,
        skin: CandidateCellStyleSpec? = null,
        legacyIndexDp: Float? = null,
        legacyCommentDp: Float? = null,
    ): ResolvedCandidateSizes = ResolvedCandidateSizes(
        textDp = resolve(appTextDp, skin?.textSizeDp, JIME_DEFAULT_TEXT_DP),
        indexDp = resolve(appIndexDp, skin?.indexSizeDp ?: legacyIndexDp, JIME_DEFAULT_INDEX_DP),
        commentDp = resolve(
            appCommentDp, skin?.commentSizeDp ?: legacyCommentDp, JIME_DEFAULT_COMMENT_DP,
        ),
        textWeight = skin?.textWeight,
        indexWeight = skin?.indexWeight,
        commentWeight = skin?.commentWeight,
        backgroundInsets = skin?.backgroundInsets ?: AreaInsets(),
        cornerRadiusDp = skin?.cornerRadiusDp,
    )
}

/** 解析后的候选字号全集（渲染消费点读此结构，不再各自回落）。 */
data class ResolvedCandidateSizes(
    val textDp: Float,
    val indexDp: Float,
    val commentDp: Float,
    val textWeight: CandidateFontWeight? = null,
    val indexWeight: CandidateFontWeight? = null,
    val commentWeight: CandidateFontWeight? = null,
    val backgroundInsets: AreaInsets = AreaInsets(),
    val cornerRadiusDp: Float? = null,
)
