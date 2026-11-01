package com.jobeen.ime.skin.model

import com.jobeen.ime.engine.event.KeyModifier
import com.jobeen.ime.engine.event.KeyModifiers

/**
 * 皮肤动作键名表（审计 E：sendKeys / sendKeyEvent 的纯解析层）：
 * 键名 → Android 键码（稳定 API 取值，与 android.view.KeyEvent
 * 常量同值），不依赖 Android 运行时，可直接单测。
 *
 * 修饰键名：ctrl/control、shift、alt/option、meta/command；
 * function 在 Android 侧无可靠等价键码，不接受（解析期剔除）。
 */
object SkinKeyCodes {

    /** 修饰键解析结果（sendKeys 转引擎位图，sendKeyEvent 转直发标志）。 */
    data class ParsedModifiers(
        val ctrl: Boolean = false,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    ) {
        fun toKeyModifiers(): KeyModifiers {
            val mods = mutableListOf<KeyModifier>()
            if (ctrl) mods += KeyModifier.Control
            if (shift) mods += KeyModifier.Shift
            if (alt) mods += KeyModifier.Alt
            if (meta) mods += KeyModifier.Meta
            return KeyModifiers(*mods.toTypedArray())
        }
    }

    private val named: Map<String, Int> = mapOf(
        "enter" to 66,
        "return" to 66,
        "tab" to 61,
        "escape" to 111,
        "esc" to 111,
        "space" to 62,
        "backspace" to 67,
        "delete" to 67,
        "del" to 67,
        "home" to 122,
        "end" to 123,
        "pageup" to 92,
        "page_up" to 92,
        "pagedown" to 93,
        "page_down" to 93,
        "up" to 19,
        "arrowup" to 19,
        "down" to 20,
        "arrowdown" to 20,
        "left" to 21,
        "arrowleft" to 21,
        "right" to 22,
        "arrowright" to 22,
        "insert" to 124,
        "ctrl" to 113,
        "control" to 113,
        "alt" to 57,
        "option" to 57,
        "meta" to 117,
        "command" to 117,
        "shift" to 59,
        "capslock" to 115,
        "f1" to 131,
        "f2" to 132,
        "f3" to 133,
        "f4" to 134,
        "f5" to 135,
        "f6" to 136,
        "f7" to 137,
        "f8" to 138,
        "f9" to 139,
        "f10" to 140,
        "f11" to 141,
        "f12" to 142,
    )

    /** 键名 → 键码；未知返回 null（调用方按 VALUE_INVALID 剔除）。 */
    fun keyCode(name: String): Int? {
        val lower = name.lowercase()
        if (lower.length == 1) {
            val c = lower[0]
            if (c in 'a'..'z') return 29 + (c - 'a')
            if (c in '0'..'9') return 7 + (c - '0')
            return null
        }
        return named[lower]
    }

    /** 修饰键名表 → 解析结果；任一未知名返回 null。 */
    fun parseModifiers(names: List<String>): ParsedModifiers? {
        var ctrl = false
        var shift = false
        var alt = false
        var meta = false
        for (raw in names) {
            when (raw.lowercase()) {
                "ctrl", "control" -> ctrl = true
                "shift" -> shift = true
                "alt", "option" -> alt = true
                "meta", "command" -> meta = true
                else -> return null
            }
        }
        return ParsedModifiers(ctrl, shift, alt, meta)
    }
}
