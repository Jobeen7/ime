package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.notify.ConditionStyle
import com.jobeen.ime.skin.notify.SkinNotification
import com.jobeen.ime.skin.symbol.SymbolBarSpec
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

/**
 * 键盘级几何字段（2026-10-27 新增分流）：只出现在顶层 `geometry`
 * 对象，与 appearance 几何是两套字段、同一消费点。
 *
 * - keyRadiusDp / keyHGapDp / keyVGapDp 分别对应圆角、键横/纵间距，
 *   区间与 appearance 几何一致（0..40 / 0..20 / 0..20）；
 * - heightPercent 为键盘高度百分比，区间取 App 现有竖屏滑条
 *   20..50 与横屏滑条 30..70 的并集 20..70，竖横屏同值生效。
 * 优先级：geometry 有效值 > appearance 有效值 > App 设置（见
 * [SkinSnapshotBuilder] 与 [KeyboardColors.resolve]，冲突时记
 * INFO issue，不静默覆盖）。
 */
enum class KeyboardGeometryField(val jsonName: String) {
    KEY_RADIUS("keyRadiusDp"),
    KEY_H_GAP("keyHGapDp"),
    KEY_V_GAP("keyVGapDp"),
    HEIGHT_PERCENT("heightPercent"),
}

/** 键盘级几何校验结果：只含文件显式给出的有效值；缺失/坏值不在此表，消费点回退下一级。 */
data class KeyboardGeometrySpec(
    val presence: Set<KeyboardGeometryField> = emptySet(),
    val values: Map<KeyboardGeometryField, Float> = emptyMap(),
)

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
enum class AssetKind { IMAGE, AUDIO, VIDEO, OTHER }

/** appearance.background 校验结果（资产引用已按清单解析）。 */
data class BackgroundSpec(
    /** 资产逻辑 id（皮肤文件内的引用名）。 */
    val assetId: String,
    val blurRadius: Float,
    val overlayAlpha: Float,
    /** 背景图调节（参考项）：null = 未写/全项无效 = 不调节。 */
    val imageAdjust: ImageAdjust? = null,
    /**
     * 主题视频背景资产逻辑 id（2026-10-29 外观轮）：与静态图同段，
     * 视频优先于静态图；null = 无视频。校验期只认 VIDEO 类资产，
     * 错类/缺失时本字段丢弃、静态段仍有效（字段级回退）。
     */
    val videoAssetId: String? = null,
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
    /**
     * 横屏专属布局绑定（2026-10-27 第一组）：skin.json 的
     * layoutIdLandscape，形态校验与 layoutId 同口径；引用解析
     * 在快照工厂层（横屏绑定缺失/非法时回退竖屏布局，永不空白）。
     * null = 未绑定（横屏沿用竖屏布局的横屏权重变体）。
     */
    val layoutIdLandscape: String? = null,
    val appearance: AppearanceSpec,
    val styles: StylesSpec,
    /**
     * 深色板（2026-10-27 第一组，一包双色板）：stylesDark /
     * derivationDark 合成后的完整深色令牌表（缺失叶子已按
     * 浅色板逐字段回填，见 SkinValidator）；null = 皮肤未
     * 声明深色板（旧单色板皮肤，快照恒取浅色板）。
     */
    val darkStyles: StylesSpec? = null,
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
    /** 单键样式表（2026-10-27 并入项）：样式名 → 角色 → 取值字符串（令牌路径或字面色）。 */
    val keyStyles: Map<String, Map<KeyStyleRole, String>> = emptyMap(),
    /** 状态驱动通知（重点候选 8-1）：顶层 notifications 校验结果；未定义即空表。 */
    val notifications: List<SkinNotification> = emptyList(),
    /** 条件样式（重点候选 8-1）：顶层 conditionStyles 校验结果；未定义即空表。 */
    val conditionStyles: List<ConditionStyle> = emptyList(),
    /** 符号栏（重点候选 8-6）：顶层 symbolBar 校验结果；null = 无自定义栏。 */
    val symbolBar: SymbolBarSpec? = null,
    /** 拟态双阴影（2026-10-29 item-1）：顶层 keyShadow 校验结果；缺省关闭。 */
    val keyShadow: KeyShadowSpec = KeyShadowSpec(),
    /** 按键动效（2026-10-29 item-1）：顶层 keyAnimation 校验结果；缺省关闭。 */
    val keyAnimation: KeyAnimationSpec = KeyAnimationSpec(),
    /**
     * 空格专属动效（2026-10-29 item-1）：顶层 spaceAnimation；
     * null = 空格键跟随 keyAnimation（不单独指定）。
     */
    val spaceAnimation: KeyAnimationSpec? = null,
    /** 键盘级几何（2026-10-27 新增分流）：顶层 geometry 有效值。 */
    val keyboardGeometry: KeyboardGeometrySpec = KeyboardGeometrySpec(),
    /**
     * 字族（2026-10-27 新增分流）：顶层 fontFamily 规范化后的系统
     * 字族名；null = 未指定或非法值回退（用默认字体）。
     */
    val fontFamily: String? = null,
    /**
     * 分角色字体（2026-10-29 item-1）：顶层 roleFonts 校验结果，
     * 角色 → 规范化系统字族名（仅有效项）；空 = 无逐角色指定。
     * 分层：皮肤逐角色 > 皮肤 fontFamily > 全局逐角色 > 系统默认。
     */
    val roleFonts: Map<com.jobeen.ime.data.keyboard.theme.FontRole, String> = emptyMap(),
    /**
     * 气泡溢出检测（2026-10-28 截图 6 项）：顶层 bubbleOverflowCheck；
     * true = 短按提示气泡与长按弹出气泡超出键盘高度时自动调位，
     * false = 旧行为（只贴边夹取，不翻转）。默认开。
     */
    val bubbleOverflowCheck: Boolean = true,
    /**
     * 滑动阈值（2026-10-28 截图 6 项）：顶层 swipeThresholdDp；
     * null = 未指定（走逐键覆写/全局设置/默认 36dp）。
     */
    val swipeThresholdDp: Float? = null,
    /**
     * 候选字背景圆角（2026-10-28 截图 6 项）：顶层 candidateRadiusDp，
     * dp；默认 6（与旧写死值一致），区间 0..32。
     */
    val candidateRadiusDp: Float = 6f,
    /**
     * 预测态关闭键独立样式（2026-10-28 截图 6 项）：顶层
     * predictionCloseBg（ARGB Int）与 predictionCloseRadiusDp（dp）；
     * null = 复用候选字背景（颜色/圆角各跟各的默认值）。
     * 只作用于预测态右缘关闭键，组字态展开键不受影响。
     */
    val predictionCloseBg: Int? = null,
    val predictionCloseRadiusDp: Float? = null,
    /** 工具栏构成（2026-10-27 工具栏进皮肤）：顶层 toolbar 节点校验结果；未定义时 present=false。 */
    val toolbar: SkinToolbarSpec = SkinToolbarSpec(),
    /** G1 皮肤级分档字号：角色 → 字号（缺失角色回落出厂字号）。 */
    val textSizes: Map<KeyTextRole, Float> = emptyMap(),
)
