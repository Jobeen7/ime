// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.fontWeightMatches
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.notify.NotificationActionNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.16.13 新增纯逻辑：通知恢复／匹配、条件样式、
 * 阴影缺省、边界高度、逐样式字号／内边距、numberPad 别名、
 * 字体字重、几何内边距、样式集／类型区域／深色布局。
 */
class Preview13AdditionsTest {

    private fun base(extra: String): String =
        """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
            "stylesDark": {"keyBackground": "#FF222222"},
            $extra}"""

    // ---- A3：失配恢复与锁定 ----

    // ---- P1：方案 ID 或名匹配 ----

    // ---- A4：PREEDIT 空／非空信号解析 ----

    // ---- A5：键盘动作值 ----

    @Test
    fun keyboardActionValueOf() {
        assertEquals(
            "numeric",
            NotificationActionNames.valueOf(
                KeyboardAction.SwitchKeyboardTypeAction("numeric"),
            ),
        )
        assertEquals(
            "ascii",
            NotificationActionNames.valueOf(
                KeyboardAction.ToggleRimeOptionAction("ascii"),
            ),
        )
        assertNull(NotificationActionNames.valueOf(KeyboardAction.SpaceAction))
    }

    @Test
    fun parseKeyComboAndSendKeys() {
        val combo = NotificationActionNames.parseKeyCombo("ctrl+shift+enter")
        assertNotNull(combo)
        assertEquals(66, combo!!.keyCode)
        assertTrue(combo.ctrl)
        assertTrue(combo.shift)
        assertFalse(combo.alt)
        assertFalse(combo.meta)
        val combos = NotificationActionNames.parseSendKeysValue("ctrl+a,f1")
        assertNotNull(combos)
        assertEquals(2, combos!!.size)
        assertEquals(29, combos[0].keyCode)
        assertEquals(131, combos[1].keyCode)
        assertNull(NotificationActionNames.parseKeyCombo("ctrl+zzz"))
        assertNull(NotificationActionNames.parseSendKeysValue("ctrl+a,zzz"))
    }

    // ---- A6：returnKeyType 条件 ----

    // ---- P5：字体字重 ----

    @Test
    fun fontWeightMatchesPredicate() {
        assertTrue(fontWeightMatches(null, true))
        assertTrue(fontWeightMatches(null, false))
        assertTrue(fontWeightMatches(700, true))
        assertFalse(fontWeightMatches(700, false))
        assertTrue(fontWeightMatches(400, false))
        assertFalse(fontWeightMatches(400, true))
        assertTrue(fontWeightMatches(600, true))
        assertFalse(fontWeightMatches(599, true))
    }

    // ---- S5／S11／S12／G1／G2：校验层 ----

    // ---- G4：深色布局回落链 ----

}
