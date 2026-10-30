package com.jobeen.ime.skin.model

/**
 * 单键模型 G1 扩展（皮肤 v3 单键模型与渲染分流）：
 *
 * - 分档字号（[KeyTextRole]）：皮肤级 `textSizes` + 逐键
 *   `textSizes` 覆写；档位只分三类，不按布局/按键逐一定制。
 * - 单键几何覆写（[KeyGeometryOverride]）：宽/高/内容中心/
 *   可视内缩；行宽和不变性由装配器保证（见 KeyOverrideApplier）。
 * - 前景多层叠加（[KeyForegroundLayer]）：每键至多 4 层，按
 *   数组顺序渲染，后层压前层。
 * - 动态文字（[DynamicTextSource]）：只定来源范围，值在键盘
 *   构建期快照（实时跟随候选变化属后续项，见报告 open 项）。
 * - 派生自定义公式（[DerivationFormulas]）：冷路径展开的小
 *   表达式语言，只引用 6 基色与三个纯函数。
 *
 * 本文件全部纯函数/纯数据：无 Android 依赖，可直接单测。
 * 所有数值区间与回退口径见各解析函数注释；日志只记元数据，
 * 绝不记输入文本、候选词、剪贴板、词典、凭证。
 */

/** 字号档位类别（皮肤 textSizes / 逐键 textSizes 的键）。 */
enum class KeyTextRole(val jsonName: String) {
    /** 主文字：Text/AltText/ImageText 的主显文本。 */
    MAIN("main"),

    /** 副文字：AltText 的副显文本与 subLabel 附加文字。 */
    SUB("sub"),

    /**
     * 符号：主显文本为单个非字母数字字符的键（如标点键）。
     * 判定在装配期按显示文本做（装配器 [KeyOverrideApplier]），
     * 非布局/键盘类型判定——符号键盘的功能键（如切换键）不算。
     */
    SYMBOL("symbol"),
    ;

    companion object {
        fun byJsonName(name: String): KeyTextRole? = entries.firstOrNull { it.jsonName == name }
    }
}

/** 字号档位有效区间（dp/sp 同值域；渲染层以 DIP 计）。 */
object KeyTextSizeLimits {
    const val MIN = 8f
    const val MAX = 40f

    fun isValid(value: Float): Boolean = value.isFinite() && value in MIN..MAX
}

/**
 * 单键几何覆写（keyMappings 条目 `geometry` 对象；全部字段可选，
 * 缺失 = 不覆写该维；非法单字段只丢该字段，不拖垮整键）。
 *
 * - widthFactor：键宽乘子，0.5..2.0；装配期按行归一化，行宽和不变。
 * - heightFactor：键可视高度乘子，0.5..1.0；行高由网格固定，
 *   只能以对称竖向内缩实现可视收缩，>1 不放大（越界按非法丢弃）。
 * - contentCenterX/Y：主文字内容中心偏置，0..1（0.5 = 居中）。
 * - hInsetDp/vInsetDp：可视区域附加内缩，0..20dp（与皮肤级键间距叠加）。
 *
 * 限制（装配器强制执行，见报告）：跨行键（rowSpan > 1）的宽覆写
 * 不生效（跨行宽度由跨行布局单独约束）；其余维照常生效。
 */
data class KeyGeometryOverride(
    val widthFactor: Float? = null,
    val heightFactor: Float? = null,
    val contentCenterX: Float? = null,
    val contentCenterY: Float? = null,
    val hInsetDp: Float? = null,
    val vInsetDp: Float? = null,
) {
    val isEmpty: Boolean get() =
        widthFactor == null && heightFactor == null && contentCenterX == null &&
            contentCenterY == null && hInsetDp == null && vInsetDp == null

    companion object {
        val WIDTH_RANGE = 0.5f..2.0f
        val HEIGHT_RANGE = 0.5f..1.0f
        val CENTER_RANGE = 0f..1f
        val INSET_RANGE = 0f..20f
    }
}

/** 前景层原始形态（keyMappings 条目 `foregroundLayers` 数组元素）。 */
data class KeyForegroundLayer(
    /** "text" 或 "image"，其余一律非法（整层丢弃）。 */
    val type: String,
    /** text 层文本（≤16 字）；image 层恒为 null。 */
    val text: String? = null,
    /** image 层资产逻辑 id；text 层恒为 null。 */
    val image: String? = null,
    /** 层颜色原始值：令牌路径或字面色（装配期按快照解析，非法则整层丢弃）。 */
    val colorRaw: String? = null,
    /** text 层字号（8..40）；null = SUB 档位（副文字级）。 */
    val size: Float? = null,
    /** 层中心偏置，0..1；null = 居中（0.5）。 */
    val biasX: Float? = null,
    val biasY: Float? = null,
) {
    companion object {
        /** 每键前景层数上限（超限的后层丢弃）。 */
        const val MAX_LAYERS = 4

        /** 前景层文本长度上限（与 label 同口径）。 */
        const val MAX_TEXT_LENGTH = 16
    }
}

/**
 * 前景层解析结果（装配期产物：颜色已按快照方案解析为 ARGB，
 * image 层只保留已接纳资产 id；渲染层只读本类型，不再解析）。
 */
data class ResolvedForegroundLayer(
    val type: String,
    val text: String? = null,
    val imageAssetId: String? = null,
    val color: Int? = null,
    val size: Float? = null,
    val biasX: Float = 0.5f,
    val biasY: Float = 0.5f,
)

/**
 * 动态文字来源（keyMappings 条目 `dynamicText` 字符串）：
 * 构建期快照解析 + 运行时跟随（键盘层在候选/组字变化时刷新，
 * 见 KeyOverrideApplier.resolveDynamicDisplayText 与窗口推送）。
 *
 * - candidateFirst：首选候选词（候选条首项；无候选时回退静态 label）。
 * - composing：正在输入的拼音/组字串（组字区当前串；无组字时回退静态 label）。
 * - schemeName：当前方案名（方案切换时跟随）。
 * - returnKeyType：回车键类型（GO/SEND/SEARCH/NEXT/PREVIOUS/DONE/ENTER）。
 * - candidateComment：首候选注释（无注释时回退静态 label）。
 * - optionLabel$<开关名> / optionLabelAbbreviated$<开关名>：Rime
 *   开关的当前态标签／首字缩写（重点候选 8-8；解析见
 *   OptionLabelResolver；值由供给链按开关配置＋选项值算出，
 *   无值时回退静态 label）。
 *
 * 值绝不进日志：装配/渲染只记来源名与键 id，动态值本身只存在
 * 于本次构建的 KeyDef 文本中。
 */
sealed interface DynamicTextSource {
    val jsonName: String

    data object CANDIDATE_FIRST : DynamicTextSource {
        override val jsonName: String = "candidateFirst"
    }

    data object COMPOSING : DynamicTextSource {
        override val jsonName: String = "composing"
    }

    data object SCHEME_NAME : DynamicTextSource {
        override val jsonName: String = "schemeName"
    }

    data object RETURN_KEY_TYPE : DynamicTextSource {
        override val jsonName: String = "returnKeyType"
    }

    data object CANDIDATE_COMMENT : DynamicTextSource {
        override val jsonName: String = "candidateComment"
    }

    /** 选项标签键（参数化来源）：开关名＋是否缩写。 */
    data class OptionLabel(
        val switchName: String,
        val abbreviated: Boolean,
    ) : DynamicTextSource {
        override val jsonName: String
            get() = (if (abbreviated) OPTION_LABEL_ABBREV_PREFIX else OPTION_LABEL_PREFIX) +
                switchName
    }

    companion object {
        const val OPTION_LABEL_PREFIX = "optionLabel$"
        const val OPTION_LABEL_ABBREV_PREFIX = "optionLabelAbbreviated$"

        fun byJsonName(name: String): DynamicTextSource? {
            when (name) {
                CANDIDATE_FIRST.jsonName -> return CANDIDATE_FIRST
                COMPOSING.jsonName -> return COMPOSING
                SCHEME_NAME.jsonName -> return SCHEME_NAME
                RETURN_KEY_TYPE.jsonName -> return RETURN_KEY_TYPE
                CANDIDATE_COMMENT.jsonName -> return CANDIDATE_COMMENT
            }
            if (name.startsWith(OPTION_LABEL_ABBREV_PREFIX)) {
                val switchName = name.removePrefix(OPTION_LABEL_ABBREV_PREFIX)
                if (switchName.isNotBlank()) return OptionLabel(switchName, true)
                return null
            }
            if (name.startsWith(OPTION_LABEL_PREFIX)) {
                val switchName = name.removePrefix(OPTION_LABEL_PREFIX)
                if (switchName.isNotBlank()) return OptionLabel(switchName, false)
                return null
            }
            return null
        }
    }
}

/**
 * 派生自定义公式（皮肤 `derivationFormulas`：令牌路径 → 公式串）：
 *
 * 语法（冷路径展开，非法公式整条回退固定映射）：
 * ```
 * expr := name | func '(' expr ',' number ')'
 *        | func '(' expr ',' expr ',' number ')'   // 仅 mix
 * name := face | pressed | bed | ink | accent | hint
 * func := darken | lighten | mix
 * ```
 * - darken(c, f)：RGB 各通道乘 (1-f)，f ∈ [0, 1]（f=0 不变）。
 * - lighten(c, f)：RGB 各通道向 255 靠拢 f，f ∈ [0, 1]。
 * - mix(a, b, t)：两色线性混合，t ∈ [0, 1]（t=0 取 a）。
 * - 嵌套允许（如 mix(darken(face, 0.1), accent, 0.2)）；alpha
 *   通道：darken/lighten 保持源 alpha，mix 按 t 混合 alpha。
 * - 空白可有；数字须为普通十进制（不接受 NaN/Inf/科学计数法
 *   之外的花样——解析失败即非法）；未知名/函数/元数/越界一律 null。
 *
 * 优先级（校验器执行）：显式 styles > 公式结果 > 固定派生映射；
 * 公式只改写派生给出的令牌，不改写显式 styles。
 */
object DerivationFormulas {

    /** 6 基色名（与 ColorDerivation.BaseColors.FIELD_NAMES 一致）。 */
    val BASE_NAMES = setOf("face", "pressed", "bed", "ink", "accent", "hint")

    /** 求值：成功返回 ARGB，非法返回 null（调用方回退固定映射并记 issue）。 */
    fun eval(expr: String, base: Map<String, Int>): Int? {
        val parser = Parser(expr, base)
        val value = parser.parseExpr() ?: return null
        if (!parser.atEnd()) return null
        return value
    }

    private class Parser(
        private val input: String,
        private val base: Map<String, Int>,
    ) {
        private var pos = 0

        fun atEnd(): Boolean {
            skipWs()
            return pos >= input.length
        }

        fun parseExpr(): Int? {
            skipWs()
            val name = parseName() ?: return null
            skipWs()
            if (pos < input.length && input[pos] == '(') {
                return parseCall(name)
            }
            if (name in BASE_NAMES) return base[name]
            return null
        }

        private fun parseCall(func: String): Int? {
            pos++ // '('
            val first = parseExpr() ?: return null
            skipWs()
            if (pos >= input.length || input[pos] != ',') return null
            pos++
            when (func) {
                "darken", "lighten" -> {
                    val factor = parseNumber() ?: return null
                    if (factor !in 0f..1f) return null
                    skipWs()
                    if (pos >= input.length || input[pos] != ')') return null
                    pos++
                    return if (func == "darken") darken(first, factor) else lighten(first, factor)
                }
                "mix" -> {
                    val second = parseExpr() ?: return null
                    skipWs()
                    if (pos >= input.length || input[pos] != ',') return null
                    pos++
                    val t = parseNumber() ?: return null
                    if (t !in 0f..1f) return null
                    skipWs()
                    if (pos >= input.length || input[pos] != ')') return null
                    pos++
                    return mix(first, second, t)
                }
                else -> return null
            }
        }

        private fun parseName(): String? {
            skipWs()
            val start = pos
            while (pos < input.length && (input[pos].isLetter())) pos++
            if (start == pos) return null
            return input.substring(start, pos)
        }

        private fun parseNumber(): Float? {
            skipWs()
            val start = pos
            var seenDigit = false
            var seenDot = false
            if (pos < input.length && (input[pos] == '-' || input[pos] == '+')) pos++
            while (pos < input.length) {
                val c = input[pos]
                when {
                    c.isDigit() -> {
                        seenDigit = true
                        pos++
                    }
                    c == '.' && !seenDot -> {
                        seenDot = true
                        pos++
                    }
                    else -> break
                }
            }
            if (!seenDigit) return null
            return input.substring(start, pos).toFloatOrNull()
                ?.takeIf { it.isFinite() }
        }

        private fun skipWs() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun darken(argb: Int, f: Float): Int {
            val factor = 1f - f
            fun ch(shift: Int): Int =
                (((argb ushr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
            return ((argb ushr 24) and 0xFF shl 24) or (ch(16) shl 16) or
                (ch(8) shl 8) or ch(0)
        }

        private fun lighten(argb: Int, f: Float): Int {
            fun ch(shift: Int): Int {
                val c = (argb ushr shift) and 0xFF
                return (c + (255 - c) * f).toInt().coerceIn(0, 255)
            }
            return ((argb ushr 24) and 0xFF shl 24) or (ch(16) shl 16) or
                (ch(8) shl 8) or ch(0)
        }

        private fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(shift: Int): Int {
                val ca = (a ushr shift) and 0xFF
                val cb = (b ushr shift) and 0xFF
                return (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
