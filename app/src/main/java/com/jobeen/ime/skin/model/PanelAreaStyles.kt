package com.jobeen.ime.skin.model

/**
 * 面板区域样式规格（自 AreaStyles.kt 迁出的活声明；
 * JSON 解析函数随皮肤 v3 死壳删除，规格本身仍被候选区／
 * 工具栏／预编辑等活渲染代码用作类型与默认值）。
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

/**
 * MENU 槽位动作（W19-6b，2026-11-10 用户定）：工具栏 MENU 钮开什么，由皮肤顶层
 * `menuAction` 决定——缺失／非法回落 MENU（原 16 项菜单；默认皮肤无此字段，故不改），
 * 自定义皮肤写 `"panel"` 即把 MENU 绑到 8 钮快捷面板（皮肤可配）。
 */
enum class MenuAction { MENU, PANEL }

/** 工具栏布局规格：去重后的槽位排列；空排列不出现（解析失败返回 null）。 */
data class ToolbarLayoutSpec(val slots: List<ToolbarSlot>) {
    companion object {
        /** 出厂排列：左菜单、中央区、右收起（与旧固定行一致）。 */
        val DEFAULT = ToolbarLayoutSpec(listOf(ToolbarSlot.MENU, ToolbarSlot.CENTER, ToolbarSlot.CLOSE))
    }
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
    /** 分隔线可见（S10）：null = 未写 = 可见（默认）。 */
    val dividerVisible: Boolean? = null,
)


/**
 * 竖网格底部钮行（对端 verticalCandidatesLayout 第二行）：
 * 依次为翻页上／翻页下／返回或收起／退格四钮。Jime JSON 以
 * 顶层 `verticalBottomRow` 对象显式声明各钮有无（转换器把
 * 对端布局数组第二行的四格映射到此）；缺失 = 不声明（旧网格
 * 无钮行，行为不变）。钮行渲染挂载为后续项；动作映射已定：
 * 翻页 → 纵向翻页动作，返回或收起 → 候选栏展开／收起切换，
 * 退格 → 退格。
 */
data class VerticalBottomRowSpec(
    val pageUp: Boolean = false,
    val pageDown: Boolean = false,
    val returnOrCollapse: Boolean = false,
    val backspace: Boolean = false,
    /** 返回钮文案（转换器原值）；判定归属只认 [VerticalBottomRowNames]。 */
    val returnLabel: String? = null,
) {
    /** 任一钮声明时为真（全否 = 无钮行，与缺失同效）。 */
    val hasAny: Boolean get() = pageUp || pageDown || returnOrCollapse || backspace
}


/**
 * 底部返回钮名称归属：返回钮在不同皮肤中写作“返回”或“收起”，
 * 两者皆指同一钮位（动作 = 候选栏展开／收起切换），转换器与
 * 解析必须双名同认，绝不可只认其一。
 */
object VerticalBottomRowNames {
    const val RETURN = "返回"
    const val COLLAPSE = "收起"

    fun isReturnLabel(label: String?): Boolean =
        label == RETURN || label == COLLAPSE
}


/** 候选上下文菜单动作（审计 I 项）：点选／复制／遗忘三者之一。 */
enum class CandidateMenuAction { SELECT, COPY, FORGET }

/** 候选上下文菜单项（审计 I 项）：皮肤 candidateContextMenu 数组元素。 */
data class CandidateMenuItem(
    val name: String,
    val action: CandidateMenuAction,
)
