package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 区域样式解析（审计 B / I 项：工具栏、预编辑、候选区）。
 *
 * 本文件只做纯解析：输入 JSON 节点，输出强类型规格 + issue，
 * 不读快照、不碰 Android，可直接单测。消费点（窗口、渲染器、
 * 预编辑宿主）各取所需字段，缺失一律回落旧写死值。
 *
 * 长度写法（对端 `preeditHeight` / `toolbarHeight` 同款）：
 * 数字 = dp；字符串 `"Nvh"` = 视口高度百分比（N 为十进制数）。
 * 字符串数字（如 `"48"`）不算数值（与九.1 严格口径一致），
 * 按非法记 issue 并丢弃。
 */

/** 四边内边距（dp）：节点缺失的边为 0（对端 insets 只列非零边）。 */
data class AreaInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isZero: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

/**
 * dp 或视口高度百分比二选一的长度。
 * `dp != null` = 数字写法；`vhPercent != null` = `"Nvh"` 写法；
 * 两者皆 null 不出现（解析失败返回 null，不以空规格占位）。
 */
data class VhLengthSpec(
    val dp: Float?,
    val vhPercent: Float?,
) {
    /**
     * 解析为 dp：vh 写法按视口高度换算，dp 写法直取，
     * 规格缺失（null 接收方）时调用方传 fallback。
     */
    fun resolveDp(screenHeightDp: Float, fallbackDp: Float): Float =
        if (vhPercent != null) {
            (screenHeightDp * vhPercent / 100f).coerceAtLeast(0f)
        } else {
            dp ?: fallbackDp
        }
}

private val VH_PATTERN = Regex("""^\s*(\d+(?:\.\d+)?)\s*vh\s*$""", RegexOption.IGNORE_CASE)

/**
 * 解析 dp / `"Nvh"` 长度节点。
 *
 * @param dpRange 数字写法的合法区间；vh 写法的合法区间固定 0..50。
 * @return null = 节点缺失（调用方回落）或非法（已记 issue，调用方回落）。
 */
fun parseVhLength(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
    dpRange: ClosedFloatingPointRange<Float>,
): VhLengthSpec? {
    if (node == null) return null
    val primitive = node as? JsonPrimitive
    if (primitive == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "length_not_scalar")
        return null
    }
    if (primitive.isString) {
        val raw = primitive.contentOrNull ?: ""
        val match = VH_PATTERN.matchEntire(raw)
        val percent = match?.groupValues?.get(1)?.toFloatOrNull()
        if (percent != null && percent.isFinite() && percent in 0f..50f) {
            return VhLengthSpec(dp = null, vhPercent = percent)
        }
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "length_vh_invalid")
        return null
    }
    val value = primitive.doubleOrNull?.toFloat()
    if (value != null && value.isFinite() && value in dpRange) {
        return VhLengthSpec(dp = value, vhPercent = null)
    }
    issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "length_dp_invalid")
    return null
}

/** 解析 insets 对象：每边为数字（dp，0..64），非数字边记 issue 并按 0 计。 */
fun parseAreaInsets(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): AreaInsets {
    val obj = node as? JsonObject ?: run {
        if (node != null) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "insets_not_object")
        }
        return AreaInsets()
    }
    fun side(name: String): Float {
        val raw = (obj[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()
        if (obj.containsKey(name) && (raw == null || !raw.isFinite() || raw !in 0f..64f)) {
            issues += SkinIssue("$path.$name", SkinIssue.Kind.FIELD_FALLBACK, "inset_invalid")
            return 0f
        }
        return raw ?: 0f
    }
    return AreaInsets(
        left = side("left"),
        top = side("top"),
        right = side("right"),
        bottom = side("bottom"),
    )
}

/**
 * 工具栏样式（审计 B 项）：顶层 `toolbarStyle` 节点。
 * - insets：条内边距（dp），左右叠加到 App 横向内边距上、
 *   上下决定内容垂直中心，缺失边为 0；
 * - background：条背景色，null = 不画（现行渐变底）；
 * - borderWidthDp / borderColor：条边框，颜色缺失时不画边框；
 * - cornerRadiusDp：背景与边框共用圆角，缺失为直角。
 * 背景图（对端 backgroundStyle 的图片形态）不在此：Jime 条背景
 * 已由面板渐变底 + 用户自定义栏背景覆盖，图片形态属 D 项机制。
 */
data class ToolbarStyleSpec(
    val insets: AreaInsets = AreaInsets(),
    val background: Int? = null,
    val borderWidthDp: Float? = null,
    val borderColor: Int? = null,
    val cornerRadiusDp: Float? = null,
)

/** 解析 toolbarStyle 节点：缺失返回 null，非对象记 issue 并返回 null。 */
fun parseToolbarStyle(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): ToolbarStyleSpec? {
    if (node == null) return null
    val obj = node as? JsonObject
    if (obj == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "toolbar_style_not_object")
        return null
    }
    return ToolbarStyleSpec(
        insets = parseAreaInsets(obj["insets"], "$path.insets", issues),
        background = parseStyleColor(obj["background"], "$path.background", issues),
        borderWidthDp = parseDpField(obj["borderWidthDp"], "$path.borderWidthDp", issues, 0f..8f),
        borderColor = parseStyleColor(obj["borderColor"], "$path.borderColor", issues),
        cornerRadiusDp = parseDpField(obj["cornerRadiusDp"], "$path.cornerRadiusDp", issues, 0f..32f),
    )
}

/**
 * 工具栏内容矩形（纯几何，供渲染器与单测共用同一数学）。
 * 背景与边框画满整条（对端 backgroundStyle 覆盖整条），
 * insets 只约束内容槽位：左右叠加在 App 横向内边距上，
 * 上下决定内容垂直中心 [cy]。
 */
data class ToolbarContentRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cy: Float,
    val radiusPx: Float,
    val borderWidthPx: Float,
)

/** 由样式与条尺寸算内容矩形。 */
fun ToolbarStyleSpec.contentRect(
    width: Int,
    height: Int,
    density: Float,
    appHPadPx: Float,
): ToolbarContentRect {
    val left = appHPadPx + insets.left * density
    val right = (width - appHPadPx - insets.right * density).coerceAtLeast(left)
    val top = insets.top * density
    val bottom = (height - insets.bottom * density).coerceAtLeast(top)
    return ToolbarContentRect(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        cy = (top + bottom) / 2f,
        radiusPx = (cornerRadiusDp ?: 0f) * density,
        borderWidthPx = (borderWidthDp ?: 0f) * density,
    )
}

/**
 * 工具栏槽位（审计 B 项）：单行条的三个区。
 * 对端 toolbarLayout 是 HStack 的 Cell 序列（菜单钮、滑动钮组、
 * 收起钮），Jime 条恰为左槽＋中央区＋右槽三段式，故布局数据
 * 收敛为三槽的排列（中央区对应滑动钮组位置）；多行（VStack）
 * 不在 Jime 单行条的形态内，不接受（见报告）。
 */
enum class ToolbarSlot { MENU, CENTER, CLOSE }

/** 工具栏布局规格：去重后的槽位排列；空排列不出现（解析失败返回 null）。 */
data class ToolbarLayoutSpec(val slots: List<ToolbarSlot>) {
    companion object {
        /** 出厂排列：左菜单、中央区、右收起（与旧固定行一致）。 */
        val DEFAULT = ToolbarLayoutSpec(listOf(ToolbarSlot.MENU, ToolbarSlot.CENTER, ToolbarSlot.CLOSE))
    }
}

/**
 * 解析 toolbarLayout 节点：字符串数组（"menu" / "center" / "close"）。
 * 非法项逐项丢弃并记 issue；全部丢弃或非数组时返回 null（走默认排列）。
 */
fun parseToolbarLayout(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): ToolbarLayoutSpec? {
    if (node == null) return null
    val array = node as? kotlinx.serialization.json.JsonArray
    if (array == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "toolbar_layout_not_array")
        return null
    }
    val slots = LinkedHashSet<ToolbarSlot>()
    for (element in array) {
        val raw = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val slot = when (raw) {
            "menu" -> ToolbarSlot.MENU
            "center" -> ToolbarSlot.CENTER
            "close" -> ToolbarSlot.CLOSE
            else -> null
        }
        if (slot == null) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "toolbar_layout_bad_slot")
        } else if (!slots.add(slot)) {
            issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "toolbar_layout_duplicate_slot")
        }
    }
    if (slots.isEmpty()) return null
    return ToolbarLayoutSpec(slots.toList())
}

/**
 * 工具栏槽位几何（纯函数，绘制与命中共用）。
 * 槽位按排列从左到右落位：固定槽各 32dp，中央区取剩余宽度
 * （两侧各留一个中央内边距）；缺失的槽不占位，中央区缺失时
 * [centerLeft]/[centerWidth] 仍为两固定槽之间的跨度（供复制
 * 药丸等瞬态中央内容与隐藏压制复用，按钮行本身不画）。
 */
data class ToolbarSlotGeometry(
    val menuCenter: Float?,
    val closeCenter: Float?,
    val centerLeft: Float,
    val centerWidth: Float,
) {
    val hasMenu: Boolean get() = menuCenter != null
    val hasClose: Boolean get() = closeCenter != null
}

fun toolbarSlotGeometry(
    width: Int,
    density: Float,
    leftPadPx: Float,
    rightPadPx: Float,
    centerPadPx: Float,
    slots: List<ToolbarSlot>,
): ToolbarSlotGeometry {
    val fixedW = 32f * density
    val hasCenter = ToolbarSlot.CENTER in slots
    // 中央区左/右的固定槽：有中央区时按其分界，无中央区时
    // 首槽在左、其余在右（与旧行“左菜单右收起”一致）。
    val centerIndex = slots.indexOf(ToolbarSlot.CENTER)
    val leftSlots = if (centerIndex >= 0) {
        slots.subList(0, centerIndex)
    } else if (slots.isNotEmpty()) {
        slots.subList(0, 1)
    } else {
        emptyList()
    }
    val rightSlots = if (centerIndex >= 0) {
        slots.subList(centerIndex + 1, slots.size)
    } else if (slots.size > 1) {
        slots.subList(1, slots.size)
    } else {
        emptyList()
    }
    var menuCenter: Float? = null
    var closeCenter: Float? = null
    var leftCursor = leftPadPx
    for (slot in leftSlots) {
        val center = leftCursor + fixedW / 2f
        if (slot == ToolbarSlot.MENU) menuCenter = center else closeCenter = center
        leftCursor += fixedW
    }
    var rightCursor = width - rightPadPx
    for (slot in rightSlots.reversed()) {
        rightCursor -= fixedW
        val center = rightCursor + fixedW / 2f
        if (slot == ToolbarSlot.MENU) menuCenter = center else closeCenter = center
    }
    val centerLeft = if (hasCenter) leftCursor + centerPadPx else leftCursor
    val centerRight = if (hasCenter) rightCursor - centerPadPx else rightCursor
    return ToolbarSlotGeometry(
        menuCenter = menuCenter,
        closeCenter = closeCenter,
        centerLeft = centerLeft,
        centerWidth = (centerRight - centerLeft).coerceAtLeast(0f),
    )
}

/**
 * 预编辑样式（审计 B 项）：顶层 `preeditStyle` 节点。
 * 对端 preeditStyle = backgroundStyle + foregroundStyle + insets；
 * Jime 侧展开为药丸几何与文字：高度另有顶层 preeditHeight
 * （优先于此处的 heightDp），字色/底色缺失时回落 styles.pinner
 * 令牌；insets.left 在 paddingDp 缺失时充当水平内边距，
 * insets.top/bottom 不消费（药丸在其自身高度内垂直居中，
 * 无条内偏移语义——见报告）。
 */
data class PreeditStyleSpec(
    val heightDp: Float? = null,
    val cornerRadiusDp: Float? = null,
    val paddingDp: Float? = null,
    val fontSizeDp: Float? = null,
    val secondaryFontSizeDp: Float? = null,
    val textColor: Int? = null,
    val secondaryTextColor: Int? = null,
    val background: Int? = null,
    val insets: AreaInsets = AreaInsets(),
)

/** 解析 preeditStyle 节点：缺失返回 null，非对象记 issue 并返回 null。 */
fun parsePreeditStyle(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): PreeditStyleSpec? {
    if (node == null) return null
    val obj = node as? JsonObject
    if (obj == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "preedit_style_not_object")
        return null
    }
    return PreeditStyleSpec(
        heightDp = parseDpField(obj["heightDp"], "$path.heightDp", issues, 12f..64f),
        cornerRadiusDp = parseDpField(obj["cornerRadiusDp"], "$path.cornerRadiusDp", issues, 0f..32f),
        paddingDp = parseDpField(obj["paddingDp"], "$path.paddingDp", issues, 0f..32f),
        fontSizeDp = parseDpField(obj["fontSizeDp"], "$path.fontSizeDp", issues, 8f..32f),
        secondaryFontSizeDp = parseDpField(
            obj["secondaryFontSizeDp"], "$path.secondaryFontSizeDp", issues, 8f..32f,
        ),
        textColor = parseStyleColor(obj["textColor"], "$path.textColor", issues),
        secondaryTextColor = parseStyleColor(
            obj["secondaryTextColor"], "$path.secondaryTextColor", issues,
        ),
        background = parseStyleColor(obj["background"], "$path.background", issues),
        insets = parseAreaInsets(obj["insets"], "$path.insets", issues),
    )
}

/**
 * 候选区样式（审计 B 项）：`horizontalCandidatesStyle` /
 * `verticalCandidatesStyle` 节点（对端同名）。
 * - insets：区内边距（dp）。横条：左右叠加到条侧边距上、
 *   上下压缩药丸高度；竖网格：画布内缩（命中同步偏移）。
 * - background：区背景色。横条缺失时用面板底；竖网格缺失时
 *   用 panel.background（网格旧口径）。
 * - 候选项内边距（审计 I 项，null = 旧写死值）：横条节点读
 *   pillPadDp / pillGapDp；竖网格节点读 cellPadDp /
 *   minWordSpaceDp / minRowHeightDp。字段写错节点时被消费点
 *   忽略（两节点共用一规格类型，解析不分节点）。
 */
data class CandidateAreaStyleSpec(
    val insets: AreaInsets = AreaInsets(),
    val background: Int? = null,
    val pillPadDp: Float? = null,
    val pillGapDp: Float? = null,
    val cellPadDp: Float? = null,
    val minWordSpaceDp: Float? = null,
    val minRowHeightDp: Float? = null,
)

/** 解析候选区样式节点：缺失返回 null，非对象记 issue 并返回 null。 */
fun parseCandidateAreaStyle(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): CandidateAreaStyleSpec? {
    if (node == null) return null
    val obj = node as? JsonObject
    if (obj == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "candidate_area_style_not_object")
        return null
    }
    return CandidateAreaStyleSpec(
        insets = parseAreaInsets(obj["insets"], "$path.insets", issues),
        background = parseStyleColor(obj["background"], "$path.background", issues),
        pillPadDp = parseDpField(obj["pillPadDp"], "$path.pillPadDp", issues, 0f..32f),
        pillGapDp = parseDpField(obj["pillGapDp"], "$path.pillGapDp", issues, 0f..32f),
        cellPadDp = parseDpField(obj["cellPadDp"], "$path.cellPadDp", issues, 0f..32f),
        minWordSpaceDp = parseDpField(obj["minWordSpaceDp"], "$path.minWordSpaceDp", issues, 0f..32f),
        minRowHeightDp = parseDpField(obj["minRowHeightDp"], "$path.minRowHeightDp", issues, 16f..96f),
    )
}

/**
 * 横条候选节点（审计 I 项）：`horizontalCandidates`（对端同名）。
 * 仅 maxColumns 生效：横条一次布局的候选上限；null = 不限
 * （现行无界滚动口径。对端文档默认 7，但节点缺失时 Jime
 * 不套用 7——套用会截断现行滚动条，属回归；显式写值才生效）。
 */
data class HorizontalCandidatesSpec(
    val maxColumns: Int? = null,
)

/** 解析横条候选节点：缺失返回 null，非对象记 issue 并返回 null。 */
fun parseHorizontalCandidates(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): HorizontalCandidatesSpec? {
    if (node == null) return null
    val obj = node as? JsonObject
    if (obj == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "horizontal_candidates_not_object")
        return null
    }
    val raw = (obj["maxColumns"] as? JsonPrimitive)?.intOrNull
    val maxColumns = when {
        raw == null -> {
            if (obj["maxColumns"] != null) {
                issues += SkinIssue("$path.maxColumns", SkinIssue.Kind.FIELD_FALLBACK, "max_columns_not_int")
            }
            null
        }
        raw !in 1..20 -> {
            issues += SkinIssue("$path.maxColumns", SkinIssue.Kind.FIELD_FALLBACK, "max_columns_out_of_range")
            null
        }
        else -> raw
    }
    return HorizontalCandidatesSpec(maxColumns = maxColumns)
}

/** 解析颜色字符串节点：非法记 issue 并返回 null（调用方回落）。 */
fun parseStyleColor(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
): Int? {
    if (node == null) return null
    val raw = (node as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val parsed = raw?.let { SkinJson.parseColor(it) }
    if (parsed == null) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "color_invalid")
    }
    return parsed
}

/** 解析 dp 数值节点：非法记 issue 并返回 null（调用方回落）。 */
fun parseDpField(
    node: JsonElement?,
    path: String,
    issues: MutableList<SkinIssue>,
    range: ClosedFloatingPointRange<Float>,
): Float? {
    if (node == null) return null
    val value = (node as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()
    if (value == null || !value.isFinite() || value !in range) {
        issues += SkinIssue(path, SkinIssue.Kind.FIELD_FALLBACK, "dp_invalid")
        return null
    }
    return value
}
