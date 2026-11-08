package com.jobeen.ime.input.keyboard.key

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 注解显示范围（E3 G44，对端 AnnotationInsets 的 Jime 版）。
 *
 * 对端机制（v3.3.9，KeyAppearance.kt / Key.kt / KeyView.kt）：
 * 每键 4 个内边距字段——symbolInsetX/Y（上方符号／顶部小字）、
 * hintInsetX/Y（下方注解／副文字），null = 继承，合法范围 0~48dp；
 * 显示区 = 键背景 bounds 按内边距内缩（缺省 4dp 横向／2dp 纵向，
 * 内边距钳制在半宽／半高减 0.5 以内）；文字再按圆角间隙收窄——
 * 墨迹越贴近键顶／键底，圆角帽处的可用宽度越小。
 *
 * Jime 的等价物是副文字（altText，下方注解）与顶部小字（topText，
 * 上方符号）；主文字不是注解，不走这套内边距。内边距目前取对端
 * 缺省值（未接皮肤逐键配置）；parse/resolve 已就位，待皮肤模型
 * 加字段后接线。本模块只用 Kotlin 标准库，不碰 Android 框架，
 * 可在主机单测里直接跑。
 */
object AnnotationDisplayRange {

    /** 单个内边距合法上限（dp），与对端的解析口径一致。 */
    const val MAX_INSET_DP = 48f

    const val DEFAULT_SYMBOL_INSET_X_DP = 4f
    const val DEFAULT_SYMBOL_INSET_Y_DP = 2f
    const val DEFAULT_HINT_INSET_X_DP = 4f
    const val DEFAULT_HINT_INSET_Y_DP = 2f

    /**
     * 逐键注解内边距（dp）。null = 继承缺省；显式 0 会覆盖非零缺省
     * （与对端的 merge 口径一致：空白继承、0 是有效值）。
     */
    data class Insets(
        val symbolInsetXDp: Float? = null,
        val symbolInsetYDp: Float? = null,
        val hintInsetXDp: Float? = null,
        val hintInsetYDp: Float? = null,
    )

    /** 解析后的内边距（dp），无 null。 */
    data class ResolvedInsets(
        val symbolInsetXDp: Float,
        val symbolInsetYDp: Float,
        val hintInsetXDp: Float,
        val hintInsetYDp: Float,
    )

    /** 显示区（px，键坐标系，原点在键左上角）。 */
    data class DisplayRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        fun width(): Float = right - left
        fun height(): Float = bottom - top
    }

    val DEFAULT = ResolvedInsets(
        symbolInsetXDp = DEFAULT_SYMBOL_INSET_X_DP,
        symbolInsetYDp = DEFAULT_SYMBOL_INSET_Y_DP,
        hintInsetXDp = DEFAULT_HINT_INSET_X_DP,
        hintInsetYDp = DEFAULT_HINT_INSET_Y_DP,
    )

    /**
     * 解析内边距表（键名同对端：symbolInsetX/Y、hintInsetX/Y）。
     * 空白 = 继承（null）；非数字、非有限、<0、>48 一律抛
     * IllegalArgumentException（与对端的 AnnotationInsetsTest 口径一致）。
     */
    fun parse(values: Map<String, String?>): Insets {
        fun parseField(name: String): Float? {
            val raw = values[name] ?: return null
            if (raw.isBlank()) return null
            val value = raw.toFloatOrNull()
                ?: throw IllegalArgumentException("invalid annotation inset for $name: $raw")
            if (!value.isFinite() || value < 0f || value > MAX_INSET_DP) {
                throw IllegalArgumentException("annotation inset out of range for $name: $raw")
            }
            return value
        }
        return Insets(
            symbolInsetXDp = parseField("symbolInsetX"),
            symbolInsetYDp = parseField("symbolInsetY"),
            hintInsetXDp = parseField("hintInsetX"),
            hintInsetYDp = parseField("hintInsetY"),
        )
    }

    /** 缺省补齐：未给的字段取 [DEFAULT]，显式值（含 0）优先。 */
    fun resolve(insets: Insets?): ResolvedInsets = ResolvedInsets(
        symbolInsetXDp = insets?.symbolInsetXDp ?: DEFAULT.symbolInsetXDp,
        symbolInsetYDp = insets?.symbolInsetYDp ?: DEFAULT.symbolInsetYDp,
        hintInsetXDp = insets?.hintInsetXDp ?: DEFAULT.hintInsetXDp,
        hintInsetYDp = insets?.hintInsetYDp ?: DEFAULT.hintInsetYDp,
    )

    /**
     * 注解显示区：键 bounds 按内边距内缩。isTop = true 取 symbol
     * （上方）内边距，否则取 hint（下方）内边距；每向内边距钳制
     * 在半宽／半高减 0.5px 以内（与对端的 annotationArea 一致），
     * 保证显示区永不为空。
     */
    fun displayRect(
        keyWidthPx: Float,
        keyHeightPx: Float,
        isTop: Boolean,
        insets: ResolvedInsets,
        density: Float,
    ): DisplayRect {
        val xDp = if (isTop) insets.symbolInsetXDp else insets.hintInsetXDp
        val yDp = if (isTop) insets.symbolInsetYDp else insets.hintInsetYDp
        val xPx = (xDp * density).coerceAtMost((keyWidthPx / 2f - 0.5f).coerceAtLeast(0f))
        val yPx = (yDp * density).coerceAtMost((keyHeightPx / 2f - 0.5f).coerceAtLeast(0f))
        return DisplayRect(xPx, yPx, keyWidthPx - xPx, keyHeightPx - yPx)
    }

    /**
     * 圆角间隙（px）：墨迹所在高度上，圆角帽吃掉的横向宽度。
     * 与对端的 roundedClearance 同式——半径钳制在半宽／半高以内；
     * 墨迹离键顶／键底越近（distance 越小），间隙越大；墨迹在
     * 中部（distance >= radius）时间隙为 0。
     */
    fun roundedClearance(
        inkTopPx: Float,
        inkBottomPx: Float,
        radiusPx: Float,
        boundsWidthPx: Float,
        boundsHeightPx: Float,
    ): Float {
        val radius = radiusPx.coerceIn(0f, min(boundsWidthPx, boundsHeightPx) / 2f)
        if (radius <= 0f) return 0f
        val distance = min(inkTopPx, boundsHeightPx - inkBottomPx).coerceIn(0f, radius)
        return radius - sqrt((radius * radius - (radius - distance) * (radius - distance)).coerceAtLeast(0f))
    }

    /**
     * 给定墨迹高度的可用宽度（px）：显示区再按圆角间隙左右收窄，
     * 永不为负。调用方把注解文字排进这个宽度，超宽以省略号截断。
     */
    fun availableWidth(
        area: DisplayRect,
        inkTopPx: Float,
        inkBottomPx: Float,
        radiusPx: Float,
        boundsWidthPx: Float,
        boundsHeightPx: Float,
    ): Float {
        val clearance = roundedClearance(inkTopPx, inkBottomPx, radiusPx, boundsWidthPx, boundsHeightPx)
        val left = maxOf(area.left, clearance)
        val right = minOf(area.right, boundsWidthPx - clearance)
        return (right - left).coerceAtLeast(0f)
    }
}
