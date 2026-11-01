package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 单键样式（2026-10-27 并入项，样式名引用 + 单键覆盖）：
 *
 * - 皮肤文件 `keyStyles` 表：样式名 → 5 角色 → 取值字符串；
 * - 键（keyMappings 条目 / 自创布局键节点）以 `style` 引用样式名，
 *   以 `styleOverrides` 逐角色覆盖（单键样式覆盖，逐角色优先）。
 * - 取值字符串先按令牌解析：命中 [StyleField] 路径名时取快照
 *   该令牌值（链路：派生 → 快照 → 样式引用）；非令牌时按字面
 *   颜色解析；两者皆非 → 该角色回退（跳过 + 警告，不拒整包）。
 *
 * 6 角色（渲染面，与 KeyDef 皮肤色槽一一对应）：
 * face=键面，pressed=按压面，text=主文字，altText=副文字，border=描边，
 * icon=图标染色（G1 补：Image/ImageText 键的图标 tint；无图标的键忽略该角色）。
 */
enum class KeyStyleRole(val jsonName: String) {
    FACE("face"),
    PRESSED("pressed"),
    TEXT("text"),
    ALT_TEXT("altText"),
    BORDER("border"),
    ICON("icon"),
    /** 按压态描边（审计 A 项：highlightBorderColor 的角色面）。 */
    PRESSED_BORDER("pressedBorder"),
    /** 下边缘色（审计 A 项：normalLowerEdgeColor 的角色面）。 */
    LOWER_EDGE("lowerEdge"),
    /** 按压态下边缘色（审计 A 项：highlightLowerEdgeColor 的角色面）。 */
    PRESSED_LOWER_EDGE("pressedLowerEdge"),
    ;

    companion object {
        fun byJsonName(name: String): KeyStyleRole? = entries.firstOrNull { it.jsonName == name }
    }
}

/** 样式解析警告（字段级回退记录：键/样式、角色、原因）。 */
data class KeyStyleWarning(
    val owner: String,
    val role: KeyStyleRole?,
    val reason: String,
)

object KeyStyleParser {

    /**
     * 几何面键（由样式几何解析器消费，见 KeyStyleGeometryParser）：
     * 角色解析时静默跳过，不记未知角色警告（含 geometry 子对象
     * 与平铺对端字段两种写法）。逐键 styleOverrides 里的几何键
     * 同样跳过——几何面只在样式表层绑定，不随逐键覆盖生效。
     */
    val GEOMETRY_KEYS: Set<String> = setOf(
        "geometry", "animations",
        "normalColor", "highlightColor", "colorLocation", "colorGradientType",
        "colorStartPoint", "colorEndPoint",
        "normalShadowColor", "highlightShadowColor", "shadowOpacity",
        "highlightShadowOpacity", "shadowRadius", "shadowOffset",
        "borderSize", "normalBorderColor", "highlightBorderColor",
        "normalLowerEdgeColor", "highlightLowerEdgeColor",
        "cornerRadius", "cornerCurve", "bounds",
    )

    /**
     * 解析一个角色 → 取值字符串对象（样式表项 / styleOverrides 共用）。
     * 返回（有效映射， 警告）：未知角色/非字符串值逐项丢弃并记警告。
     */
    fun parseRoles(
        owner: String,
        node: JsonObject,
    ): Pair<Map<KeyStyleRole, String>, List<KeyStyleWarning>> {
        val roles = LinkedHashMap<KeyStyleRole, String>()
        val warnings = mutableListOf<KeyStyleWarning>()
        for ((name, valueNode) in node) {
            if (name in GEOMETRY_KEYS) continue
            val role = KeyStyleRole.byJsonName(name)
            if (role == null) {
                warnings += KeyStyleWarning(owner, null, "unknown_role:$name")
                continue
            }
            val raw = (valueNode as? JsonPrimitive)?.contentOrNull
            if (raw == null) {
                warnings += KeyStyleWarning(owner, role, "value_not_string")
                continue
            }
            roles[role] = raw
        }
        return roles to warnings
    }

    /** 解析 keyStyles 整表：样式名 → 角色映射（非对象项整项丢弃并记警告）。 */
    fun parseTable(
        node: JsonObject,
    ): Pair<Map<String, Map<KeyStyleRole, String>>, List<KeyStyleWarning>> {
        val table = LinkedHashMap<String, Map<KeyStyleRole, String>>()
        val warnings = mutableListOf<KeyStyleWarning>()
        for ((name, styleNode) in node) {
            val styleObj = styleNode as? JsonObject
            if (styleObj == null) {
                warnings += KeyStyleWarning(name, null, "style_not_object")
                continue
            }
            val (roles, roleWarnings) = parseRoles(name, styleObj)
            warnings += roleWarnings
            table[name] = roles
        }
        return table to warnings
    }
}

/**
 * 长按符号全节点（注册表值）：显示文本 + 动作。
 *
 * 格式（顶层 `hintSymbolNodes` 表，名 → 节点）：
 * `{"text": "↑", "action": {"type": "symbol", "value": "↑"}}`。
 * - text：面板格内显示文本；缺失／空白 = null，面板按
 *   [displayText] 回落（符号／字面提交取字面值，其余为空白格，
 *   空白格仍可选中执行）；
 * - action：必填，经 [SkinKeyMappingsParser.parseActionNode]
 *   与键映射同口径解析；非法则整节点丢弃。
 *
 * 颜色与几何不进本节点：同名在 `keyStyles`／`keyGeometries`
 * 表中有条目时面板按名取用（名是跨表连接键，与对端样式名
 * 跨表引用同口径）；无条目时面板用默认色。
 */
data class HintSymbolNode(
    val text: String?,
    val action: SkinAction,
) {
    /** 面板格显示文本：显式文本优先，次为符号／字面提交的字面值，其余为空。 */
    val displayText: String get() = text?.takeIf { it.isNotEmpty() } ?: when (action) {
        is SkinAction.Text -> action.value
        is SkinAction.Command ->
            if (action.action == WhitelistAction.SYMBOL) action.value.orEmpty() else ""
        else -> ""
    }
}

/** 长按符号全节点纯解析（非法节点返回 null，调用方记 issue 丢弃）。 */
object HintSymbolNodeParser {

    fun parse(node: JsonElement?): HintSymbolNode? {
        val obj = node as? JsonObject ?: return null
        val actionNode = obj["action"] ?: return null
        val action = SkinKeyMappingsParser.parseActionNode(actionNode) ?: return null
        val text = (obj["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotEmpty() }
        return HintSymbolNode(text, action)
    }
}
