// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.fontWeightMatches
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.notify.NotificationActionNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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


    // ---- A7／S7／S1／S2：样式几何 ----

    private fun geometry(json: String): KeyStyleGeometry? {
        val entry = Json.parseToJsonElement(json) as JsonObject
        return KeyStyleGeometryParser.parseEntry(entry).first
    }

    @Test
    fun shadowDefaultsAreRadius3Offset03() {
        val parsed = geometry(
            """{"normalShadowColor": "#80000000"}""",
        )
        assertNotNull(parsed)
        val layer = parsed!!.shadow?.normal
        assertNotNull(layer)
        assertEquals(3f, layer!!.radiusDp)
        assertEquals(0f, layer.offsetXDp)
        assertEquals(3f, layer.offsetYDp)
    }

    @Test
    fun boundsHeightAndVerticalApplied() {
        val parsed = geometry(
            """{"bounds": {"width": 0.5, "height": 0.6, "alignment": "bottom"}}""",
        )
        assertNotNull(parsed)
        assertEquals(0.5f, parsed!!.bounds?.widthFraction)
        assertEquals(0.6f, parsed.bounds?.heightFraction)
        assertEquals(KeyBoundsVertical.BOTTOM, parsed.bounds?.vertical)
    }

    @Test
    fun perStyleFontAndInsetsParsed() {
        val parsed = geometry(
            """{"fontSize": 22.5, "altFontSize": 16, "iconFontSize": 20,
                "hInsetDp": 3, "vInsetDp": 2}""",
        )
        assertNotNull(parsed)
        assertEquals(22.5f, parsed!!.fontSizeDp)
        assertEquals(16f, parsed.altFontSizeDp)
        assertEquals(20f, parsed.iconSizeDp)
        assertEquals(3f, parsed.hInsetDp)
        assertEquals(2f, parsed.vInsetDp)
    }

    // ---- G3／G1：keyboards 表 ----

    @Test
    fun numberPadAliasAndStyleSetParsed() {
        val tree = Json.parseToJsonElement(
            """{"keyboards": {
                "numeric": {"layoutId": "num", "styleSet": "dark_keys"},
                "symbolic": {"layoutId": "sym"}}}""",
        ) as JsonObject
        val table = SkinJson.parseKeyboards(tree)
        assertEquals("num", table.getValue("numberPad").layoutId)
        assertEquals("dark_keys", table.getValue("numeric").styleSet)
        assertNull(table.getValue("symbolic").styleSet)
    }

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
