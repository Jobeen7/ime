package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 按键样式几何（审计 A 项）：对端背景样式的几何面。
 *
 * - 颜色口径与既有皮肤字段不同：对端 8 位十六进制为 RRGGBBAA
 *  （alpha 在末尾），见 [parsePeerColor]；6 位为不透明。
 *   既有皮肤字段仍走 [SkinJson.parseColor]（AARRGGBB），两者
 *   不可混用——本文件所有颜色解析只认对端口径。
 * - 字段级回退：非法字段逐项丢弃并记警告，绝不拒整包。
 *
 * 纯解析，可直接单测；渲染见 input/keyboard/key/KeyDrawable。
 */

/** 对端颜色解析：#RRGGBB（不透明）或 #RRGGBBAA（alpha 在末尾）。 */
fun parsePeerColor(raw: String): Int? {
    val s = raw.trim()
    if (!s.startsWith("#")) return null
    val hex = s.substring(1)
    if (hex.isEmpty() ||
        hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }
    ) {
        return null
    }
    return when (hex.length) {
        6 -> (0xFF000000L or hex.toLong(16)).toInt()
        8 -> {
            val rgb = hex.substring(0, 6).toLong(16)
            val alpha = hex.substring(6, 8).toLong(16)
            ((alpha shl 24) or rgb).toInt()
        }
        else -> null
    }
}

/**
 * 对端 16 进制色 → Jime 16 进制色（转换器用）：`#RRGGBB` 原样；
 * `#RRGGBBAA` → `#AARRGGBB`（alpha 从末尾移到开头）；`#` 可省
 * （对端文档：`#` 可选；输出恒带 `#`）；非法 → null。
 * 口径警告：两解析器绝不可混用——[parsePeerColor] 读 RRGGBBAA，
 * `SkinJson.parseColor` 读 AARRGGBB；同一字面量经两者解析的
 * alpha 字节不同（见 HexColorTest 的对照用例）。
 */
fun convertPeerHexToJimeHex(raw: String): String? {
    val s = raw.trim().removePrefix("#")
    if (s.isEmpty() ||
        s.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }
    ) {
        return null
    }
    return when (s.length) {
        6 -> "#$s"
        8 -> "#" + s.substring(6, 8) + s.substring(0, 6)
        else -> null
    }
}

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
    /** 是否为有效渐变：至少两个颜色（单色等价纯色，由调用方按纯色处理）。 */
    val isMultiColor: Boolean get() = colors.size >= 2
}

/**
 * 尺寸分数解析（审计 A 项）：对端尺寸写法。
 *
 * - 数字 → 本身；
 * - 字符串 "a/b"（a、b 有限数，b 非零，结果为正）→ a/b；
 * - 对象 {"percentage": n} → n；
 * - 其余 → null（调用方按字段回退处理）。
 */
object SizeFraction {

    fun parse(node: JsonElement?): Double? {
        return when (node) {
            null -> null
            is JsonPrimitive -> {
                if (!node.isString) {
                    node.content.toDoubleOrNull()?.takeIf { it.isFinite() }
                } else {
                    parseString(node.content)
                }
            }
            is JsonObject -> {
                val percentage = node["percentage"] as? JsonPrimitive
                if (percentage != null && !percentage.isString) {
                    percentage.content.toDoubleOrNull()?.takeIf { it.isFinite() }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun parseFloat(node: JsonElement?): Float? = parse(node)?.toFloat()

    private fun parseString(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if ('/' in s) {
            val parts = s.split("/")
            if (parts.size != 2) return null
            val a = parts[0].trim().toDoubleOrNull()
            val b = parts[1].trim().toDoubleOrNull()
            if (a == null || b == null || !a.isFinite() || !b.isFinite() || b == 0.0) {
                return null
            }
            val result = a / b
            return result.takeIf { it.isFinite() && it > 0.0 }
        }
        // 非分数字符串不算数（沿用布局权重严格读取口径：字符串数值无效）。
        return null
    }
}

object KeyGradientParser {

    /**
     * 从几何对象解析常态/按压两支渐变。
     * 颜色字段：normalColor / highlightColor（字符串单色或颜色数组）；
     * 位置与类型两支共用：colorLocation / colorGradientType /
     * colorStartPoint / colorEndPoint。单色不构成渐变（返回 null，
     * 调用方按纯色角色处理）；数组含非法项时丢弃该项并记警告，
     * 剩余不足两色时整支丢弃。
     */
    fun parseFaces(
        geometry: JsonObject,
    ): Triple<KeyGradient?, KeyGradient?, List<String>> {
        val warnings = mutableListOf<String>()
        val (normalColors, normalWarnings) = parseColorList(geometry["normalColor"], "normalColor")
        val (highlightColors, highlightWarnings) =
            parseColorList(geometry["highlightColor"], "highlightColor")
        warnings += normalWarnings
        warnings += highlightWarnings
        val locations = parseLocations(geometry["colorLocation"], warnings)
        val type = parseType(geometry["colorGradientType"], warnings)
        val start = parsePoint(geometry["colorStartPoint"], "colorStartPoint", warnings)
        val end = parsePoint(geometry["colorEndPoint"], "colorEndPoint", warnings)
        fun build(colors: List<Int>?): KeyGradient? {
            if (colors == null || colors.size < 2) return null
            val loc = locations?.takeIf { it.size == colors.size }
            if (locations != null && loc == null) {
                warnings += "gradient_location_mismatch"
            }
            return KeyGradient(colors, loc, type, start, end)
        }
        return Triple(build(normalColors), build(highlightColors), warnings)
    }

    private fun parseColorList(
        node: JsonElement?,
        field: String,
    ): Pair<List<Int>?, List<String>> {
        if (node == null) return null to emptyList()
        val warnings = mutableListOf<String>()
        when (node) {
            is JsonPrimitive -> {
                if (!node.isString) return null to listOf("gradient_${field}_invalid")
                val color = parsePeerColor(node.content)
                    ?: return null to listOf("gradient_${field}_invalid")
                return listOf(color) to warnings
            }
            is JsonArray -> {
                val colors = mutableListOf<Int>()
                for (item in node) {
                    val raw = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val color = raw?.let { parsePeerColor(it) }
                    if (color == null) {
                        warnings += "gradient_${field}_invalid"
                    } else {
                        colors += color
                    }
                }
                return colors to warnings
            }
            else -> return null to listOf("gradient_${field}_invalid")
        }
    }

    private fun parseLocations(
        node: JsonElement?,
        warnings: MutableList<String>,
    ): List<Float>? {
        if (node == null) return null
        val array = node as? JsonArray
            ?: run {
                warnings += "gradient_location_invalid"
                return null
            }
        val values = mutableListOf<Float>()
        for (item in array) {
            val primitive = item as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toFloatOrNull()
            } else {
                null
            }
            if (value == null || !value.isFinite()) {
                warnings += "gradient_location_invalid"
                return null
            }
            values += value
        }
        return values
    }

    private fun parseType(
        node: JsonElement?,
        warnings: MutableList<String>,
    ): KeyGradientType {
        if (node == null) return KeyGradientType.LINEAR
        val raw = (node as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
        return when (raw) {
            "axial", "linear" -> KeyGradientType.LINEAR
            "radial" -> KeyGradientType.RADIAL
            "conic", "sweep" -> KeyGradientType.CONIC
            else -> {
                warnings += "gradient_type_invalid"
                KeyGradientType.LINEAR
            }
        }
    }

    private fun parsePoint(
        node: JsonElement?,
        field: String,
        warnings: MutableList<String>,
    ): KeyGradientPoint? {
        if (node == null) return null
        val obj = node as? JsonObject
            ?: run {
                warnings += "gradient_${field}_invalid"
                return null
            }
        fun component(name: String): Float? {
            val primitive = obj[name] as? JsonPrimitive
            if (primitive != null && !primitive.isString) {
                return primitive.content.toFloatOrNull()?.takeIf { it.isFinite() }
            }
            return null
        }
        val x = component("x")
        val y = component("y")
        if (x == null || y == null) {
            warnings += "gradient_${field}_invalid"
            return null
        }
        var clamped = false
        val cx = x.coerceIn(0f, 1f).also { if (it != x) clamped = true }
        val cy = y.coerceIn(0f, 1f).also { if (it != y) clamped = true }
        if (clamped) warnings += "gradient_${field}_clamped"
        return KeyGradientPoint(cx, cy)
    }
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
            bounds == null && animationNames.isEmpty() &&
            fontSizeDp == null && altFontSizeDp == null && iconSizeDp == null &&
            hInsetDp == null && vInsetDp == null &&
            leftInsetDp == null && rightInsetDp == null &&
            topInsetDp == null && bottomInsetDp == null
}

object KeyStyleGeometryParser {

    /**
     * 解析一个样式表项的几何面：优先读 `geometry` 子对象（Jime
     * 写法）；无子对象时直接读表项本身的对端同名字段（对端
     * 背景样式平铺写法）。`animations` 数组只在表项层读。
     * 返回（几何， 警告原因码）；几何全空时返回 null 几何（不占位）。
     */
    fun parseEntry(
        entry: JsonObject,
    ): Pair<KeyStyleGeometry?, List<String>> {
        val warnings = mutableListOf<String>()
        val geometryNode = entry["geometry"]
        val geometry: JsonObject = when {
            geometryNode == null -> entry
            geometryNode is JsonObject -> geometryNode
            else -> {
                warnings += "style_geometry_invalid"
                entry
            }
        }
        val (gradientNormal, gradientHighlight, gradientWarnings) =
            KeyGradientParser.parseFaces(geometry)
        warnings += gradientWarnings
        val (shadow, shadowWarnings) = KeyPerStyleShadowParser.parse(geometry)
        warnings += shadowWarnings
        val borderWidthDp = parseDp(geometry, "borderSize", 0f, 8f, warnings)
        val cornerRadiusDp = parseDp(geometry, "cornerRadius", 0f, 40f, warnings)
        // S1／S2：逐样式字号与内边距（与文字档位同值域 8..40，
        // 图标字形放宽至 8..48，内边距与键边距同值域 0..20）。
        val fontSizeDp = parseDp(geometry, "fontSize", 8f, 40f, warnings)
        val altFontSizeDp = parseDp(geometry, "altFontSize", 8f, 40f, warnings)
        val iconSizeDp = parseDp(geometry, "iconFontSize", 8f, 48f, warnings)
        val hInsetDp = parseDp(geometry, "hInsetDp", 0f, 20f, warnings)
        val vInsetDp = parseDp(geometry, "vInsetDp", 0f, 20f, warnings)
        // L4 四边内缩（对端 `insets` 对象口径，读 `geometry` 节点内；
        // 非对象时记警告、整段丢弃，对称值不受影响）。
        val insetsNode = geometry["insets"]
        val insetsObj = insetsNode as? JsonObject
        if (insetsNode != null && insetsObj == null) {
            warnings += "style_geometry_insets_invalid"
        }
        fun insetSide(name: String): Float? =
            insetsObj?.let { parseDp(it, name, 0f, 20f, warnings) }
        val leftInsetDp = insetSide("left")
        val rightInsetDp = insetSide("right")
        val topInsetDp = insetSide("top")
        val bottomInsetDp = insetSide("bottom")
        val cornerCurve = parseCornerCurve(geometry["cornerCurve"], warnings)
        fun peerColor(field: String): Int? {
            val raw = (geometry[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return null
            return parsePeerColor(raw) ?: run {
                warnings += "style_geometry_${field}_invalid"
                null
            }
        }
        val bounds = parseBounds(geometry["bounds"], warnings)
        val animationNames = parseAnimationNames(entry["animations"], warnings)
        val result = KeyStyleGeometry(
            gradientNormal = gradientNormal,
            gradientHighlight = gradientHighlight,
            shadow = shadow,
            borderWidthDp = borderWidthDp,
            cornerRadiusDp = cornerRadiusDp,
            cornerCurve = cornerCurve,
            normalBorderColor = peerColor("normalBorderColor"),
            highlightBorderColor = peerColor("highlightBorderColor"),
            normalLowerEdgeColor = peerColor("normalLowerEdgeColor"),
            highlightLowerEdgeColor = peerColor("highlightLowerEdgeColor"),
            bounds = bounds,
            animationNames = animationNames,
            fontSizeDp = fontSizeDp,
            altFontSizeDp = altFontSizeDp,
            iconSizeDp = iconSizeDp,
            hInsetDp = hInsetDp,
            vInsetDp = vInsetDp,
            leftInsetDp = leftInsetDp,
            rightInsetDp = rightInsetDp,
            topInsetDp = topInsetDp,
            bottomInsetDp = bottomInsetDp,
        )
        return result.takeIf { !it.isEmpty } to warnings
    }

    private fun parseDp(
        geometry: JsonObject,
        field: String,
        min: Float,
        max: Float,
        warnings: MutableList<String>,
    ): Float? {
        val node = geometry[field] ?: return null
        val primitive = node as? JsonPrimitive
        val value = if (primitive != null && !primitive.isString) {
            primitive.content.toFloatOrNull()
        } else {
            null
        }
        if (value == null || !value.isFinite() || value < min || value > max) {
            warnings += "style_geometry_${field}_invalid"
            return null
        }
        return value
    }

    private fun parseCornerCurve(
        node: JsonElement?,
        warnings: MutableList<String>,
    ): KeyCornerCurve? {
        if (node == null) return null
        val raw = (node as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase()
        return when (raw) {
            "continuous" -> KeyCornerCurve.CONTINUOUS
            "rounded" -> KeyCornerCurve.ROUNDED
            else -> {
                warnings += "style_geometry_corner_curve_invalid"
                null
            }
        }
    }

    private fun parseBounds(
        node: JsonElement?,
        warnings: MutableList<String>,
    ): KeyVisibleBounds? {
        if (node == null) return null
        val obj = node as? JsonObject
            ?: run {
                warnings += "style_geometry_bounds_invalid"
                return null
            }
        var horizontal = KeyBoundsHorizontal.CENTER
        var vertical = KeyBoundsVertical.CENTER
        val rawAlignment = (obj["alignment"] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.lowercase()
        if (rawAlignment != null) {
            var recognized = false
            for (token in rawAlignment.split('-', '_', ' ')) {
                when (token) {
                    "left" -> {
                        horizontal = KeyBoundsHorizontal.LEFT
                        recognized = true
                    }
                    "right" -> {
                        horizontal = KeyBoundsHorizontal.RIGHT
                        recognized = true
                    }
                    "top" -> {
                        vertical = KeyBoundsVertical.TOP
                        recognized = true
                    }
                    "bottom" -> {
                        vertical = KeyBoundsVertical.BOTTOM
                        recognized = true
                    }
                    "center", "centre" -> recognized = true
                    "" -> Unit
                    else -> warnings += "style_geometry_bounds_alignment_invalid"
                }
            }
            if (!recognized && rawAlignment.isNotBlank()) {
                warnings += "style_geometry_bounds_alignment_invalid"
            }
        }
        val width = SizeFraction.parseFloat(obj["width"])?.let { value ->
            if (value <= 0f || value > 1f) {
                warnings += "style_geometry_bounds_width_invalid"
                null
            } else {
                value
            }
        }
        // S7：高度占比与宽度同口径（0,1]，非法只警告、不丢整项。
        val height = SizeFraction.parseFloat(obj["height"])?.let { value ->
            if (value <= 0f || value > 1f) {
                warnings += "style_geometry_bounds_height_invalid"
                null
            } else {
                value
            }
        }
        return KeyVisibleBounds(width, horizontal, vertical, height)
    }

    private fun parseAnimationNames(
        node: JsonElement?,
        warnings: MutableList<String>,
    ): List<String> {
        if (node == null) return emptyList()
        val array = node as? JsonArray
            ?: run {
                warnings += "style_geometry_animations_invalid"
                return emptyList()
            }
        val names = mutableListOf<String>()
        for (item in array) {
            val name = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name.isNullOrBlank()) {
                warnings += "style_geometry_animations_invalid"
            } else {
                names += name
            }
        }
        return names
    }
}

object KeyPerStyleShadowParser {

    /**
     * 解析逐样式阴影：normalShadowColor / highlightShadowColor（对端口径）、
     * shadowOpacity（两支共用，缺省 1.0）、highlightShadowOpacity（按压支覆盖）、
     * shadowRadius（dp，缺省 3）、shadowOffset {x, y}（dp，缺省 0,3；
     * A7：对端缺省，非 0,0）。
     * 两支颜色皆无 → 无阴影（null）；单支有色时另一支为 null（渲染层回落）。
     */
    fun parse(
        geometry: JsonObject,
    ): Pair<KeyPerStyleShadow?, List<String>> {
        val warnings = mutableListOf<String>()
        fun color(field: String): Int? {
            val raw = (geometry[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: return null
            return parsePeerColor(raw) ?: run {
                warnings += "style_geometry_${field}_invalid"
                null
            }
        }
        fun opacity(field: String, default: Float): Float {
            val node = geometry[field] ?: return default
            val primitive = node as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toFloatOrNull()
            } else {
                null
            }
            if (value == null || !value.isFinite() || value < 0f || value > 1f) {
                warnings += "style_geometry_${field}_invalid"
                return default
            }
            return value
        }
        fun dpField(field: String, default: Float, min: Float, max: Float): Float {
            val node = geometry[field] ?: return default
            val primitive = node as? JsonPrimitive
            val value = if (primitive != null && !primitive.isString) {
                primitive.content.toFloatOrNull()
            } else {
                null
            }
            if (value == null || !value.isFinite() || value < min || value > max) {
                warnings += "style_geometry_${field}_invalid"
                return default
            }
            return value
        }
        fun offset(): Pair<Float, Float> {
            val obj = geometry["shadowOffset"] as? JsonObject ?: return 0f to 3f
            fun axis(name: String, default: Float): Float {
                val primitive = obj[name] as? JsonPrimitive
                val value = if (primitive != null && !primitive.isString) {
                    primitive.content.toFloatOrNull()
                } else {
                    null
                }
                if (value == null || !value.isFinite() || value < -64f || value > 64f) {
                    warnings += "style_geometry_shadow_offset_invalid"
                    return default
                }
                return value
            }
            return axis("x", 0f) to axis("y", 3f)
        }
        val normalColor = color("normalShadowColor")
        val highlightColor = color("highlightShadowColor")
        if (normalColor == null && highlightColor == null) return null to warnings
        val opacity = opacity("shadowOpacity", 1f)
        val highlightOpacity = opacity("highlightShadowOpacity", opacity)
        val radius = dpField("shadowRadius", 3f, 0f, 64f)
        val (offsetX, offsetY) = offset()
        fun layer(color: Int?, layerOpacity: Float): KeyShadowLayer? =
            color?.let {
                KeyShadowLayer(it, layerOpacity, radius, offsetX, offsetY)
            }
        return KeyPerStyleShadow(
            normal = layer(normalColor, opacity),
            pressed = layer(highlightColor, highlightOpacity),
        ) to warnings
    }
}
