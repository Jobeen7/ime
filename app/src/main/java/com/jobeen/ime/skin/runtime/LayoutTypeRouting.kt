package com.jobeen.ime.skin.runtime

import com.jobeen.ime.input.keyboard.impl.EmojiKeyboard
import com.jobeen.ime.input.keyboard.impl.FloatPanelKeyboard
import com.jobeen.ime.input.keyboard.impl.LayoutSwitchKeyboard
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.ReturnPathKeyboard
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard
import com.jobeen.ime.input.keyboard.impl.T14Keyboard
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.keyboard.impl.T18Keyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.model.WhitelistAction

/**
 * 自创布局运行时接缝（快照无关简化版，自 CustomLayoutRuntime.kt 迁出；
 * 皮肤 v3 死壳删除后快照恒为 null，全部快照分支按 null 分支语义折叠：
 * 类型目标直接走内置出厂键盘名；custom: 名恒无类型、切换恒回落方案默认）。
 */
object LayoutTypeRouting {

    /** 自创布局运行时名前缀：`custom:<layoutId>`。 */
    const val PREFIX: String = WhitelistAction.CUSTOM_LAYOUT_PREFIX

    fun nameFor(layoutId: String): String = PREFIX + layoutId

    /** 运行时名 → layoutId；非自创布局名返回 null。 */
    fun layoutIdOf(name: String): String? =
        if (name.startsWith(PREFIX)) {
            name.removePrefix(PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /**
     * 类型 → 目标布局名（快照恒 null：原 resolveTypeTarget 的快照绑定／
     * 旧布局分支恒不命中，直接走内置出厂键盘名）。
     */
    fun resolveTypeTarget(type: String, schemaDefault: String): String =
        builtinKeyboardName(type, schemaDefault)

    /**
     * 类型 → 内置出厂键盘名（回退链末级，永不返回未知名）：
     * createKeyboard 对未知名亦回落全键盘，故本表外再无空白
     * 路径。images 暂指表情键盘是过渡口径，不是类型语义。
     */
    fun builtinKeyboardName(type: String, schemaDefault: String): String = when (type) {
        KeyboardTypes.ALPHABETIC -> QwertyKeyboard.NAME
        KeyboardTypes.PINYIN -> schemaDefault
        KeyboardTypes.EMOJIS -> EmojiKeyboard.NAME
        KeyboardTypes.IMAGES -> EmojiKeyboard.NAME
        KeyboardTypes.NUMERIC, KeyboardTypes.NUMBER_PAD -> NumberKeyboard.NAME
        KeyboardTypes.SYMBOLIC -> SymbolKeyboard.NAME
        // 颜文字板已删，但老皮肤的 keyboards.kaomoji 类型仍应开一块
        // 语义最接近的板：符号板（A-33；不复活颜文字板）。
        "kaomoji" -> SymbolKeyboard.NAME
        // 工具类型无绑定时走内置工具板（不再回落方案默认主键盘，
        // 否则用户要点工具板却看到主键盘，且无从察觉切错了类型）。
        KeyboardTypes.LAYOUT_SWITCH -> LayoutSwitchKeyboard.NAME
        KeyboardTypes.RETURN_PATH -> ReturnPathKeyboard.NAME
        KeyboardTypes.FLOAT_PANEL -> FloatPanelKeyboard.NAME
        else -> schemaDefault
    }


    /**
     * 布局名 → 类型（快照恒 null：custom: 名恒返回 null——原先只有
     * 快照表命中才返回类型；内置名按原表映射）。
     */
    fun typeForLayoutName(name: String): String? {
        if (layoutIdOf(name) != null) return null
        return when (name) {
            QwertyKeyboard.NAME -> KeyboardTypes.ALPHABETIC
            T9Keyboard.NAME, T14Keyboard.NAME, T15Keyboard.NAME, T18Keyboard.NAME -> KeyboardTypes.PINYIN
            SymbolKeyboard.NAME -> KeyboardTypes.SYMBOLIC
            EmojiKeyboard.NAME -> KeyboardTypes.EMOJIS
            NumberKeyboard.NAME -> KeyboardTypes.NUMERIC
            else -> null
        }
    }

    /**
     * 切换目标解析（快照恒 null：custom: 名恒无效 → 回落方案默认；
     * 非 custom: 名直接返回原样）。
     */
    fun resolveSwitchTarget(requested: String, schemaDefault: String): String =
        if (layoutIdOf(requested) != null) schemaDefault else requested
}
