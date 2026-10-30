package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.StyleField

/**
 * 单键样式解析（2026-10-27 并入项，链路末环）：
 * 派生展开后的令牌快照就是解析目标——样式取值先按令牌路径
 * 在快照方案中取值，命中失败时按字面颜色解析，再失败则该
 * 角色回退（跳过，不拒整键、不崩溃）。
 *
 * 优先级（高 → 低，逐角色）：
 * 1. 键级 styleOverrides（单键样式覆盖）；
 * 2. 引用样式表（keyStyles[styleRef]）的该角色；
 * 3. 无（该角色保持方案默认色）。
 *
 * 纯函数：调用方（覆写装配器）负责把 warnings 记入诊断日志。
 */
object KeyStyleResolver {

    /** 已解析的单键色（null 角色 = 该角色保持方案默认）。 */
    data class ResolvedKeyStyle(
        val face: Int? = null,
        val pressed: Int? = null,
        val text: Int? = null,
        val altText: Int? = null,
        val border: Int? = null,
        /** G1 图标染色（无图标的键渲染层忽略本值）。 */
        val iconTint: Int? = null,
    ) {
        val isEmpty: Boolean get() =
            face == null && pressed == null && text == null && altText == null &&
                border == null && iconTint == null
    }

    data class Resolution(
        val style: ResolvedKeyStyle?,
        val warnings: List<String>,
    )

    fun resolve(
        styleRef: String?,
        overrides: Map<KeyStyleRole, String>?,
        keyStyles: Map<String, Map<KeyStyleRole, String>>,
        scheme: KeyboardColors.ColorScheme,
        /** 条件样式门控（重点候选 8-1）：被门控且未激活的样式名集合。 */
        gatedStyleNames: Set<String> = emptySet(),
        /** 条件样式激活集（ConditionEvaluator.activeStyleNames 结果）。 */
        activeStyleNames: Set<String> = emptySet(),
    ): Resolution {
        val warnings = mutableListOf<String>()
        val named: Map<KeyStyleRole, String>? = when {
            styleRef == null -> null
            styleRef in gatedStyleNames && styleRef !in activeStyleNames -> {
                warnings += "style_condition_inactive:$styleRef"
                null
            }
            keyStyles.containsKey(styleRef) -> keyStyles.getValue(styleRef)
            else -> {
                warnings += "style_missing:$styleRef"
                null
            }
        }
        fun valueFor(role: KeyStyleRole): Int? {
            val raw = overrides?.get(role) ?: named?.get(role) ?: return null
            val field = StyleField.entries.firstOrNull { it.path == raw }
            if (field != null) return field.valueIn(scheme)
            val literal = SkinJson.parseColor(raw)
            if (literal != null) return literal
            warnings += "style_value_invalid:${role.jsonName}"
            return null
        }
        val resolved = ResolvedKeyStyle(
            face = valueFor(KeyStyleRole.FACE),
            pressed = valueFor(KeyStyleRole.PRESSED),
            text = valueFor(KeyStyleRole.TEXT),
            altText = valueFor(KeyStyleRole.ALT_TEXT),
            border = valueFor(KeyStyleRole.BORDER),
            iconTint = valueFor(KeyStyleRole.ICON),
        )
        return Resolution(resolved.takeIf { !it.isEmpty }, warnings)
    }

    /**
     * G1 取值字符串解析（前景层颜色共用）：先按令牌路径在快照
     * 方案中取值，命中失败按字面颜色解析，再失败返回 null
     * （调用方整层丢弃并记日志，不崩溃）。
     */
    fun resolveValue(raw: String?, scheme: KeyboardColors.ColorScheme): Int? {
        if (raw == null) return null
        val field = StyleField.entries.firstOrNull { it.path == raw }
        if (field != null) return field.valueIn(scheme)
        return SkinJson.parseColor(raw)
    }
}
