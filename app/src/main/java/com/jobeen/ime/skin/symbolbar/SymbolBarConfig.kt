package com.jobeen.ime.skin.symbolbar

import android.content.Context
import com.jobeen.ime.skin.icon.MaterialSymbols
import com.jobeen.ime.skin.model.IconSlot
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import java.io.File

/**
 * 符号栏配置模型（对端符号栏指南的应用级形态；与皮肤顶层
 * `symbolBar` 规格无关——本配置不在皮肤内，存在应用用户配置
 * 文件 `symbol-bar.yaml` 中，打开时读取）。
 *
 * 颜色：[LightDark] 为浅／深两档；单值字符串表示两档相同。
 * 非法色值回落各槽内建默认值（见解析器）；前景色 null = 主题
 * 文字色（渲染层取当前配色 keyText）。
 */
data class LightDark(val light: Int, val dark: Int)

data class CellInsetsDp(
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
)

/** 单个位置（center 或一角）的配置。 */
data class SymbolBarPosition(
    val action: SkinAction?,
    val text: String?,
    val systemImageName: String?,
    val showOnKey: Boolean = true,
    val fontSizeSp: Float? = null,
    val offsetXDp: Float? = null,
    val offsetYDp: Float? = null,
    val normalColor: LightDark? = null,
    val highlightColor: LightDark? = null,
) {
    /** 有无可执行内容（动作解析成功）。 */
    val hasContent: Boolean get() = action != null

    /**
     * 显示内容三级回落（指南口径）：图标字形 → 文本 → 动作字面值。
     * 图标须经 SF→Material 表且在 Material 注册表内，否则落下一级。
     * 返回 (显示串, 是否图标字形)；无可显示内容时为空串。
     */
    fun display(): Pair<String, Boolean> {
        val glyph = systemImageName
            ?.takeIf { it.isNotBlank() }
            ?.let { IconSlot.SfMaterialTable[it] }
            ?.let { MaterialSymbols.glyphFor(it) }
        if (glyph != null) return glyph to true
        if (text != null) return text to false
        return (actionLiteral(action) ?: "") to false
    }
}

/** 动作字面值（显示回落用）：仅符号／模板类动作有字面值。 */
fun actionLiteral(action: SkinAction?): String? = when (action) {
    is SkinAction.Text -> action.value
    is SkinAction.Command ->
        if (action.action == WhitelistAction.SYMBOL) action.value else null
    is SkinAction.Snippet -> action.template
    else -> null
}

/** 一个符号键：1 点按＋最多 4 角长按＋6 个颜色字段。 */
data class SymbolBarKey(
    val center: SymbolBarPosition,
    val topLeft: SymbolBarPosition? = null,
    val topRight: SymbolBarPosition? = null,
    val bottomLeft: SymbolBarPosition? = null,
    val bottomRight: SymbolBarPosition? = null,
    val normalBackground: LightDark = LightDark(0x00000000, 0x00000000),
    val highlightBackground: LightDark = LightDark(0x1F000000, 0x1FFFFFFF),
    val gridBackground: LightDark = LightDark(0xFFEEEEEE.toInt(), 0xFF222222.toInt()),
    val gridSelectedBackground: LightDark = LightDark(0xFFCCCCCC.toInt(), 0xFF555555.toInt()),
    val gridNormalForeground: LightDark? = null,
    val gridHighlightForeground: LightDark? = null,
) {
    /** 顺时针有内容的角（TL → TR → BR → BL；空角跳过）。 */
    fun orderedCorners(): List<SymbolBarPosition> = listOfNotNull(
        topLeft?.takeIf { it.hasContent },
        topRight?.takeIf { it.hasContent },
        bottomRight?.takeIf { it.hasContent },
        bottomLeft?.takeIf { it.hasContent },
    )

    /**
     * 是否画右下角小三角：有长按内容、且有内容的角全被藏起
     * （showOnKey=false）时提示「长按还有东西」。
     */
    val showsLongPressHint: Boolean
        get() {
            val corners = orderedCorners()
            return corners.isNotEmpty() && corners.none { it.showOnKey }
        }

    companion object {
        /** 空白键（无内容，仍占位）。 */
        val BLANK = SymbolBarKey(center = SymbolBarPosition(null, null, null))
    }
}

/** 一套类型的 8 键面板（不足 8 项补空白键，超 8 项截断）。 */
data class SymbolBarPanel(val keys: List<SymbolBarKey>) {
    init {
        require(keys.size == 8)
    }

    companion object {
        val BLANK = SymbolBarPanel(List(8) { SymbolBarKey.BLANK })
    }
}

/** 栏上 10 格：关闭钮／符号键／类型切换钮。 */
sealed class SymbolBarCell {
    data object Close : SymbolBarCell()
    data class Symbol(val index: Int) : SymbolBarCell()
    data object TypeSwitch : SymbolBarCell()
}

/**
 * 10 格顺序：符号键恒居中间 8 格（顺序 1–8 不反转），关闭钮与
 * 类型切换钮在两端互换（closeButtonOnLeft=true 时关闭在首格）。
 */
fun symbolBarCells(closeButtonOnLeft: Boolean): List<SymbolBarCell> {
    val symbols = (0 until 8).map { SymbolBarCell.Symbol(it) }
    return if (closeButtonOnLeft) {
        listOf(SymbolBarCell.Close) + symbols + SymbolBarCell.TypeSwitch
    } else {
        listOf(SymbolBarCell.TypeSwitch) + symbols + SymbolBarCell.Close
    }
}

/** 符号键开气泡方向：左 4 键向右开，右 4 键向左开。 */
fun symbolOpensRight(symbolIndex: Int): Boolean = symbolIndex < 4

/**
 * 气泡显示顺序：向右开 = 顺时针序；向左开 = 镜像（反序），
 * 使离手指最近的永远是第一格（序号 0）。
 */
fun bubbleDisplayOrder(
    corners: List<SymbolBarPosition>,
    openRight: Boolean,
): List<SymbolBarPosition> = if (openRight) corners else corners.reversed()

/**
 * 位移 → 气泡选中序号（0 = 最近格；纯函数）。
 * [dx] 为自气泡浮出瞬间指位的横向位移；顺开方向位移计格，
 * 反方向位移停在最近格；超界钳制到末格；无角时返回 null。
 */
fun bubbleIndexForDisplacement(
    dx: Float,
    cellWidthPx: Float,
    count: Int,
    openRight: Boolean,
): Int? {
    if (count <= 0 || cellWidthPx <= 0f) return null
    val forward = if (openRight) dx else -dx
    if (forward <= 0f) return 0
    val steps = (forward / cellWidthPx).toInt()
    return minOf(steps, count - 1)
}

/** 记录的类型名回落：不在列表中时取首项（指南口径）。 */
fun resolveCurrentType(types: List<String>, stored: String?): String =
    if (stored != null && stored in types) stored else types[0]

/**
 * 横竖屏切换是否复位符号栏打开态（指南口径：收起/切换/横竖屏切换后
 * 均关闭）。纯函数；方向值取 Configuration.orientation（0=未定义，
 * 1=竖屏，2=横屏，3=方形）：未定义不触发，已定义且变化才触发。
 */
fun shouldResetSymbolBarOnOrientationChange(oldOrientation: Int, newOrientation: Int): Boolean =
    oldOrientation != 0 && newOrientation != 0 && oldOrientation != newOrientation

/** 完整符号栏配置（解析结果；panels 含不在 type 列表中的类型——忽略但保留）。 */
data class SymbolBarConfig(
    val closeButtonOnLeft: Boolean,
    val heightDp: Int,
    val cellInsetsDp: CellInsetsDp,
    val background: LightDark?,
    val longPressHintColor: LightDark,
    val types: List<String>,
    val panels: Map<String, SymbolBarPanel>,
) {
    /** 某类型的面板：缺失时 8 格全空白（指南口径）。 */
    fun panelFor(type: String): SymbolBarPanel = panels[type] ?: SymbolBarPanel.BLANK
}

/** 配置加载结果（Missing → 提示「符号栏未配置」；Invalid → 「符号栏配置无效」）。 */
sealed class SymbolBarLoadResult {
    data object Missing : SymbolBarLoadResult()
    data object Invalid : SymbolBarLoadResult()
    data class Ok(val config: SymbolBarConfig) : SymbolBarLoadResult()
}

/** 配置文件加载（文件 IO；解析为纯函数，见 [SymbolBarConfigParser]）。 */
object SymbolBarConfigLoader {
    const val FILE_NAME = "symbol-bar.yaml"

    /** 配置上限 1MB（超限按无效处理，防异常大文件）。 */
    const val MAX_BYTES = 1024 * 1024L

    fun defaultFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun load(file: File): SymbolBarLoadResult {
        if (!file.isFile) return SymbolBarLoadResult.Missing
        if (file.length() > MAX_BYTES) return SymbolBarLoadResult.Invalid
        val text = runCatching { file.readText() }.getOrNull()
            ?: return SymbolBarLoadResult.Invalid
        val root = SymbolBarYaml.parse(text) ?: return SymbolBarLoadResult.Invalid
        val config = SymbolBarConfigParser.parse(root)
            ?: return SymbolBarLoadResult.Invalid
        return SymbolBarLoadResult.Ok(config)
    }
}

/**
 * 配置解析（纯函数）：YAML 树 → [SymbolBarConfig]。
 * 回落口径（指南「出错时会怎样」表）：
 * - type 缺失／空／全非字符串 → null（配置无效）
 * - height 非法／过大（≤0 或 ＞200）→ 44；内距负值／非法 → 该边 4
 * - 色值非法（非 #RRGGBB、3 位缩写、拼错）→ 该槽内建默认值
 * - 位置动作解析失败 → 该位置空白（action=null）；类型缺面板／
 *   不足 8 项 → 空白格补齐；超 8 项截断
 * - closeButtonOnLeft 缺失／非法 → true（关闭在首格）
 */
object SymbolBarConfigParser {

    fun parse(root: YamlNode?): SymbolBarConfig? {
        val map = root as? YamlNode.Map ?: return null
        val types = parseTypes(map.entries["type"]) ?: return null
        val panels = parsePanels(map.entries["panels"])
        return SymbolBarConfig(
            closeButtonOnLeft = parseBoolean(map.entries["closeButtonOnLeft"], default = true),
            heightDp = parseHeight(map.entries["height"]),
            cellInsetsDp = parseInsets(map.entries["cellInsets"]),
            background = parseColor(map.entries["backgroundColor"]),
            longPressHintColor = parseColor(map.entries["longPressHintColor"])
                ?: LightDark(0xFF888888.toInt(), 0xFF888888.toInt()),
            types = types,
            panels = panels,
        )
    }

    private fun scalar(node: YamlNode?): String? = (node as? YamlNode.Scalar)?.value

    private fun parseBoolean(node: YamlNode?, default: Boolean): Boolean {
        val raw = scalar(node)?.trim()?.lowercase() ?: return default
        return when (raw) {
            "true" -> true
            "false" -> false
            else -> default
        }
    }

    private fun parseHeight(node: YamlNode?): Int {
        val value = scalar(node)?.trim()?.toIntOrNull() ?: return 44
        return if (value in 1..200) value else 44
    }

    private fun parseInsets(node: YamlNode?): CellInsetsDp {
        val map = node as? YamlNode.Map
        fun edge(key: String): Float {
            val value = map?.entries?.get(key)?.let { scalar(it)?.trim()?.toFloatOrNull() }
            return if (value != null && value >= 0f) value else 4f
        }
        return CellInsetsDp(edge("left"), edge("right"), edge("top"), edge("bottom"))
    }

    /** 色值：单串（两档相同）或 {light, dark}；任一档非法 → 整色无效（调用方取默认值）。 */
    fun parseColor(node: YamlNode?): LightDark? {
        return when (node) {
            is YamlNode.Scalar -> {
                val color = parseHex(node.value) ?: return null
                LightDark(color, color)
            }
            is YamlNode.Map -> {
                val light = node.entries["light"]?.let { scalar(it)?.let { raw -> parseHex(raw) } }
                    ?: return null
                val dark = node.entries["dark"]?.let { scalar(it)?.let { raw -> parseHex(raw) } }
                    ?: return null
                LightDark(light, dark)
            }
            else -> null
        }
    }

    /** #RRGGBB → ARGB（其余形态一律无效，含 3 位缩写）。 */
    fun parseHex(raw: String): Int? {
        val text = raw.trim()
        if (!text.startsWith("#") || text.length != 7) return null
        val rgb = text.substring(1).toIntOrNull(16) ?: return null
        if (text.substring(1).any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) {
            return null
        }
        return 0xFF000000.toInt() or rgb
    }

    private fun parseTypes(node: YamlNode?): List<String>? {
        val list = node as? YamlNode.List ?: return null
        val types = list.items.mapNotNull { scalar(it)?.takeIf { raw -> raw.isNotBlank() } }
        return types.ifEmpty { null }
    }

    private fun parsePanels(node: YamlNode?): Map<String, SymbolBarPanel> {
        val map = node as? YamlNode.Map ?: return emptyMap()
        val out = LinkedHashMap<String, SymbolBarPanel>()
        for ((type, panelNode) in map.entries) {
            out[type] = parsePanel(panelNode)
        }
        return out
    }

    private fun parsePanel(node: YamlNode?): SymbolBarPanel {
        val list = node as? YamlNode.List ?: return SymbolBarPanel.BLANK
        val keys = ArrayList<SymbolBarKey>()
        for (item in list.items.take(8)) {
            keys += parseKey(item)
        }
        while (keys.size < 8) keys += SymbolBarKey.BLANK
        return SymbolBarPanel(keys)
    }

    private fun parseKey(node: YamlNode?): SymbolBarKey {
        val map = node as? YamlNode.Map ?: return SymbolBarKey.BLANK
        fun color(key: String, default: LightDark): LightDark =
            parseColor(map.entries[key]) ?: default
        val blank = SymbolBarKey.BLANK
        return SymbolBarKey(
            center = parsePosition(map.entries["center"]) ?: blank.center,
            topLeft = parsePosition(map.entries["topLeft"]),
            topRight = parsePosition(map.entries["topRight"]),
            bottomLeft = parsePosition(map.entries["bottomLeft"]),
            bottomRight = parsePosition(map.entries["bottomRight"]),
            normalBackground = color("normalBackgroundColor", blank.normalBackground),
            highlightBackground = color("highlightBackgroundColor", blank.highlightBackground),
            gridBackground = color("gridBackgroundColor", blank.gridBackground),
            gridSelectedBackground = color(
                "gridSelectedBackgroundColor", blank.gridSelectedBackground,
            ),
            gridNormalForeground = parseColor(map.entries["gridNormalForegroundColor"]),
            gridHighlightForeground = parseColor(map.entries["gridHighlightForegroundColor"]),
        )
    }

    private fun parsePosition(node: YamlNode?): SymbolBarPosition? {
        val map = node as? YamlNode.Map ?: return null
        fun offset(key: String): Float? {
            val offset = map.entries["offset"] as? YamlNode.Map ?: return null
            return offset.entries[key]?.let { scalar(it)?.trim()?.toFloatOrNull() }
        }
        return SymbolBarPosition(
            action = parsePeerAction(map.entries["action"]),
            text = scalar(map.entries["text"]),
            systemImageName = scalar(map.entries["systemImageName"])?.takeIf { it.isNotBlank() },
            showOnKey = parseBoolean(map.entries["showOnKey"], default = true),
            fontSizeSp = scalar(map.entries["fontSize"])?.trim()?.toFloatOrNull()
                ?.takeIf { it > 0f },
            offsetXDp = offset("x"),
            offsetYDp = offset("y"),
            normalColor = parseColor(map.entries["normalColor"]),
            highlightColor = parseColor(map.entries["highlightColor"]),
        )
    }

    /**
     * 对端动作转换（指南：写法与皮肤、键盘菜单里的 action 一致）：
     * `{symbol: X}` → 字面提交；`{snippet: X}` → 模板；
     * `{shortcut: "#名"}` → 白名单命令（FORBIDDEN 槽；REQUIRED
     * 或未知名 → 失败）。多键映射取 symbol → snippet → shortcut
     * 顺序首个命中；其余形态 → null（该位置空白）。
     */
    fun parsePeerAction(node: YamlNode?): SkinAction? {
        val map = node as? YamlNode.Map ?: return null
        scalar(map.entries["symbol"])?.takeIf { it.isNotBlank() }?.let { value ->
            return SkinAction.Command(WhitelistAction.SYMBOL, value)
        }
        scalar(map.entries["snippet"])?.takeIf { it.isNotBlank() }?.let { value ->
            return SkinAction.Snippet(value)
        }
        val shortcut = scalar(map.entries["shortcut"])?.takeIf { it.isNotBlank() }
            ?: return null
        val action = WhitelistAction.fromFormatName(shortcut) ?: return null
        if (action.valueRule == WhitelistAction.ValueRule.REQUIRED) return null
        return SkinAction.Command(action, null)
    }
}
