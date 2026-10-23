package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import kotlinx.serialization.json.JsonObject

/**
 * v3 皮肤强类型模型（定稿三章 + 第十章 32 叶子 + 第九章校验口径）。
 *
 * 原始 JSON 树是唯一存储真相（[ValidatedSkin.tree]）；本模型只是
 * 校验后的只读视图：已知字段给出有效值与 presence，未知/未支持
 * 节点不进模型、留在树里随存储与导出原样往返。
 */

/** 配色叶子字段注册表（定稿十章）：路径与 ColorScheme 取值口径一一对应。 */
enum class StyleField(val path: String) {
    KEY_BACKGROUND("keyBackground"),
    KEY_PRESSED("keyPressed"),
    KEY_BORDER_STROKE("keyBorderStroke"),
    SPECIAL_KEY_BACKGROUND("specialKeyBackground"),
    SPECIAL_KEY_PRESSED("specialKeyPressed"),
    SPECIAL_KEY_BORDER_STROKE("specialKeyBorderStroke"),
    ACCENT_KEY_BACKGROUND("accentKeyBackground"),
    ACCENT_KEY_PRESSED("accentKeyPressed"),
    ACCENT_KEY_BORDER_STROKE("accentKeyBorderStroke"),
    KEY_TEXT("keyText"),
    SPECIAL_KEY_TEXT("specialKeyText"),
    ACCENT_KEY_TEXT("accentKeyText"),
    ALT_TEXT("altText"),
    BACKGROUND("background"),
    TOAST_BACKGROUND("toastBackground"),
    TOAST_TEXT("toastText"),
    PANEL_BACKGROUND("panel.background"),
    PANEL_TOOLBAR_TEXT("panel.toolbarText"),
    PANEL_TOOLBAR_ACTIVED("panel.toolbarActived"),
    PANEL_TOOLBAR_ICON("panel.toolbarIcon"),
    PANEL_TOOLBAR_PRESSED("panel.toolbarPressed"),
    PANEL_CANDIDATE_BACKGROUND("panel.candidateBackground"),
    PANEL_CANDIDATE_TEXT("panel.candidateText"),
    PANEL_CANDIDATE_INDEX("panel.candidateIndex"),
    PANEL_CANDIDATE_DIVIDER("panel.candidateDivider"),
    PINNER_BACKGROUND("pinner.background"),
    PINNER_TEXT_COLOR("pinner.textColor"),
    PINNER_SECONDARY_TEXT_COLOR("pinner.secondaryTextColor"),
    ;

    /** 本字段在给定 ColorScheme 中的当前值（回填链末级 = 内置默认的取值来源）。 */
    fun valueIn(scheme: KeyboardColors.ColorScheme): Int = when (this) {
        KEY_BACKGROUND -> scheme.keyBackground
        KEY_PRESSED -> scheme.keyPressed
        KEY_BORDER_STROKE -> scheme.keyBorderStroke
        SPECIAL_KEY_BACKGROUND -> scheme.specialKeyBackground
        SPECIAL_KEY_PRESSED -> scheme.specialKeyPressed
        SPECIAL_KEY_BORDER_STROKE -> scheme.specialKeyBorderStroke
        ACCENT_KEY_BACKGROUND -> scheme.accentKeyBackground
        ACCENT_KEY_PRESSED -> scheme.accentKeyPressed
        ACCENT_KEY_BORDER_STROKE -> scheme.accentKeyBorderStroke
        KEY_TEXT -> scheme.keyText
        SPECIAL_KEY_TEXT -> scheme.specialKeyText
        ACCENT_KEY_TEXT -> scheme.accentKeyText
        ALT_TEXT -> scheme.altText
        BACKGROUND -> scheme.background
        TOAST_BACKGROUND -> scheme.toastBackground
        TOAST_TEXT -> scheme.toastText
        PANEL_BACKGROUND -> scheme.panel.background
        PANEL_TOOLBAR_TEXT -> scheme.panel.toolbarText
        PANEL_TOOLBAR_ACTIVED -> scheme.panel.toolbarActived
        PANEL_TOOLBAR_ICON -> scheme.panel.toolbarIcon
        PANEL_TOOLBAR_PRESSED -> scheme.panel.toolbarPressed
        PANEL_CANDIDATE_BACKGROUND -> scheme.panel.candidateBackground
        PANEL_CANDIDATE_TEXT -> scheme.panel.candidateText
        PANEL_CANDIDATE_INDEX -> scheme.panel.candidateIndex
        PANEL_CANDIDATE_DIVIDER -> scheme.panel.candidateDivider
        PINNER_BACKGROUND -> scheme.pinner.background
        PINNER_TEXT_COLOR -> scheme.pinner.textColor
        PINNER_SECONDARY_TEXT_COLOR -> scheme.pinner.secondaryTextColor
    }
}

/** 几何字段全集（定稿三.4）：只出现在 appearance，单位 dp 浮点。 */
enum class GeometryField(val jsonName: String) {
    CORNER_RADIUS("cornerRadius"),
    KEY_H_MARGIN("keyHMargin"),
    KEY_V_MARGIN("keyVMargin"),
    BOTTOM_INSET("bottomInsetDp"),
}

/** 校验问题（逐项进导入报告，无静默项；详设 §3.1）。 */
data class SkinIssue(
    val path: String,
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        /** 已知字段取值损坏，已按优先级链回填下一级（定稿九.2）。 */
        FIELD_FALLBACK,

        /** 已知但当前批次未支持的段落：保留原文、不应用（定稿三.2）。 */
        RETAINED_INACTIVE,

        /** 资源引用无效（缺失/类型错），该引用已回退（定稿七.3/九.4）。 */
        RESOURCE_FALLBACK,

        /** 其他需明示事项（不改变生效结果）。 */
        INFO,
    }
}

/** 几何回填来源：全局偏好现值（定稿九.2 优先级链的第二级）。 */
data class GeometryFallbacks(
    val cornerRadius: Float,
    val keyHMargin: Float,
    val keyVMargin: Float,
    val bottomInsetDp: Float,
) {
    fun valueOf(field: GeometryField): Float = when (field) {
        GeometryField.CORNER_RADIUS -> cornerRadius
        GeometryField.KEY_H_MARGIN -> keyHMargin
        GeometryField.KEY_V_MARGIN -> keyVMargin
        GeometryField.BOTTOM_INSET -> bottomInsetDp
    }
}

/** 资产类别（引用校验用，定稿七.3：类型错按字段级回退）。 */
enum class AssetKind { IMAGE, AUDIO, OTHER }

/** appearance.background 校验结果（资产引用已按清单解析）。 */
data class BackgroundSpec(
    /** 资产逻辑 id（皮肤文件内的引用名）。 */
    val assetId: String,
    val blurRadius: Float,
    val overlayAlpha: Float,
)

/** appearance 校验结果：几何 presence 与有效值（坏值已回填、presence 记 false）。 */
data class AppearanceSpec(
    /** 文件显式指定且有效的几何字段集合（定稿三.4.1 presence 位图）。 */
    val presence: Set<GeometryField>,
    /** 逐字段有效值：presence 内为文件值，其余为回填链结果（偏好现值）。 */
    val effectiveGeometry: Map<GeometryField, Float>,
    val background: BackgroundSpec?,
)

/** styles 校验结果：仅含文件显式给出的有效颜色；缺失/坏值由快照构建回落默认方案。 */
data class StylesSpec(
    val colors: Map<StyleField, Int>,
    val surfaceStyle: KeyboardColors.SurfaceStyle?,
)

/** 校验通过的皮肤文档（只读视图；原始树在 [tree]）。 */
data class ValidatedSkin(
    val tree: JsonObject,
    val formatVersion: Int,
    val metaId: String?,
    val metaName: String,
    val metaAuthor: String,
    val metaVersion: String,
    val layoutId: String?,
    val appearance: AppearanceSpec,
    val styles: StylesSpec,
    val keyMappingsPresent: Boolean,
    val keyMappingsCount: Int,
    /** keyMappings 强类型解析结果（B2 起应用）：校验通过的逐键覆写。 */
    val keyOverrides: Map<String, KeyOverride>,
    /** keyMappings 逐项剔除记录（导入报告与诊断日志共用）。 */
    val keyRejections: List<KeyBindingRejection>,
    /** sequence 占位绑定数（保留不生效，报告逐项明示）。 */
    val sequenceBindingCount: Int,
    /** audio 映射 slot → 资产 id（B2 起经快照接纳判定后应用）。 */
    val audioRefs: Map<String, String>,
)
