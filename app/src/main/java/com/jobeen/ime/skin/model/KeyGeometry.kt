package com.jobeen.ime.skin.model

/**
 * 按键样式几何（审计 A 项）：对端背景样式的几何面。
 *
 * - 颜色口径与既有皮肤字段不同：对端 8 位十六进制为 RRGGBBAA
 *  （alpha 在末尾）；6 位为不透明。
 *   既有皮肤字段仍走 [SkinJson.parseColor]（AARRGGBB），两者
 *   不可混用——本文件所有颜色解析只认对端口径。
 * - 字段级回退：非法字段逐项丢弃并记警告，绝不拒整包。
 *
 * 纯解析，可直接单测；渲染见 input/keyboard/key/KeyDrawable。
 */

/** 渐变类型：axial=线性，radial=径向，conic=锥形（扫掠）。 */
enum class KeyGradientType {
    LINEAR,
    RADIAL,
    CONIC,
}

/** 渐变端点（0..1 相对坐标：x 横向，y 纵向）。 */
data class KeyGradientPoint(
    val x: Float,
    val y: Float,
)

/** 单支渐变：颜色数组 + 可选位置数组 + 类型 + 可选起止点。 */
data class KeyGradient(
    val colors: List<Int>,
    /** 与 colors 等长的位置数组；null = 均匀分布。 */
    val locations: List<Float>? = null,
    val type: KeyGradientType = KeyGradientType.LINEAR,
    val startPoint: KeyGradientPoint? = null,
    val endPoint: KeyGradientPoint? = null,
) {
}

/** 单支阴影层：颜色（对端口径）× 不透明度 × 模糊半径 × 偏移。 */
data class KeyShadowLayer(
    val color: Int,
    val opacity: Float,
    val radiusDp: Float,
    val offsetXDp: Float,
    val offsetYDp: Float,
) {
    /**
     * 生效阴影色：颜色自带 alpha 再乘不透明度（对端 shadowOpacity
     * 与颜色 alpha 相乘口径）；结果 alpha 钳制 0..255。
     */
    fun effectiveColor(): Int {
        val alpha = (color ushr 24) and 0xFF
        val effective = (alpha * opacity).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (effective shl 24)
    }
}

/** 逐样式阴影：常态层与按压层各一支；任一缺失时渲染层回落另一支。 */
data class KeyPerStyleShadow(
    val normal: KeyShadowLayer?,
    val pressed: KeyShadowLayer?,
)

/** 圆角曲线：rounded=圆弧（既有行为），continuous=连续曲线。 */
enum class KeyCornerCurve {
    ROUNDED,
    CONTINUOUS,
}

/** 可视区域水平对齐（对端 bounds alignment 横向）。 */
enum class KeyBoundsHorizontal {
    LEFT,
    CENTER,
    RIGHT,
}

/** 可视区域纵向对齐（对端 bounds alignment 纵向）。 */
enum class KeyBoundsVertical {
    TOP,
    CENTER,
    BOTTOM,
}

/**
 * 可视区域（对端 bounds）：键面在单元格内的可见宽／高占比与九向对齐。
 * widthFraction／heightFraction 为 null = 该向不限（只取对齐）；
 * 对齐缺省均为居中。
 */
data class KeyVisibleBounds(
    val widthFraction: Float?,
    val horizontal: KeyBoundsHorizontal = KeyBoundsHorizontal.CENTER,
    val vertical: KeyBoundsVertical = KeyBoundsVertical.CENTER,
    val heightFraction: Float? = null,
)

/**
 * 样式几何（一个 keyStyles 表项的非颜色面）：渐变、阴影、描边、
 * 圆角、下边缘、可视区域与动效名引用。颜色字段均为对端口径
 * 解析后的 Android ARGB；动效名在装配期经动效表解析。
 */
data class KeyStyleGeometry(
    val gradientNormal: KeyGradient? = null,
    val gradientHighlight: KeyGradient? = null,
    val shadow: KeyPerStyleShadow? = null,
    val borderWidthDp: Float? = null,
    val cornerRadiusDp: Float? = null,
    val cornerCurve: KeyCornerCurve? = null,
    val normalBorderColor: Int? = null,
    val highlightBorderColor: Int? = null,
    val normalLowerEdgeColor: Int? = null,
    val highlightLowerEdgeColor: Int? = null,
    /**
     * WO38 3D 底边厚度（dp，对端 KeyEffects.edgeDepth 口径 0..8）：
     * null = 未指定（渲染沿用既有 1dp，旧皮肤行为不变）。
     * 常态／按压两态共用同一厚度（对端亦为单值）。
     */
    val lowerEdgeHeightDp: Float? = null,
    /**
     * WO38 内阴影（对端 KeyEffects.innerShadow 口径）：逐样式内阴影，
     * 常态层与按压层各一支；null = 无内阴影。渲染见 KeyEffectPainter：
     * 沿键形边缘画一圈模糊描边、裁切到键形内部（描边宽 = 模糊半径 ×2）。
     */
    val innerShadow: KeyPerStyleShadow? = null,
    val bounds: KeyVisibleBounds? = null,
    val animationNames: List<String> = emptyList(),
    /** 逐样式字号（S1）：主文字／副文字／图标字形，dp；null = 回落键覆写／全局档位。 */
    val fontSizeDp: Float? = null,
    val altFontSizeDp: Float? = null,
    val iconSizeDp: Float? = null,
    /** 逐样式内边距（S2）：横／纵，dp；null = 回落布局键几何／全局边距。 */
    val hInsetDp: Float? = null,
    val vInsetDp: Float? = null,
    /** L4 四边内缩：单边优先于对称值（缺边回落 hInsetDp／vInsetDp）。 */
    val leftInsetDp: Float? = null,
    val rightInsetDp: Float? = null,
    val topInsetDp: Float? = null,
    val bottomInsetDp: Float? = null,
) {
    val isEmpty: Boolean get() =
        gradientNormal == null && gradientHighlight == null && shadow == null &&
            borderWidthDp == null && cornerRadiusDp == null && cornerCurve == null &&
            normalBorderColor == null && highlightBorderColor == null &&
            normalLowerEdgeColor == null && highlightLowerEdgeColor == null &&
            lowerEdgeHeightDp == null && innerShadow == null &&
            bounds == null && animationNames.isEmpty() &&
            fontSizeDp == null && altFontSizeDp == null && iconSizeDp == null &&
            hInsetDp == null && vInsetDp == null &&
            leftInsetDp == null && rightInsetDp == null &&
            topInsetDp == null && bottomInsetDp == null
}
