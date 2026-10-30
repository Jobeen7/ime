package com.jobeen.ime.skin.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 单键样式（2026-10-27 并入项，元书样式名引用 + 仓单键样式覆盖）：
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
