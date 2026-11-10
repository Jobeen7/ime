// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.R
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26 键默认分支（.19 出厂布局原样）：小写字母＋副标、?123／!?#／
 * 方案名空格、实心图标。绿预设分支已随预设删除（2026-11-07
 * 施工单 5 第 1 条）。
 */
class QwertyLayout26KeyTest {

    private val defaultLayout = QwertyKeyboard.buildLayout(Variant.Accent)

    private fun pressOf(def: KeyDef): KeyboardAction? =
        def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().singleOrNull()?.action

    private fun longPressOf(def: KeyDef): KeyboardAction? =
        def.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().singleOrNull()?.action

    private fun popupOf(def: KeyDef): KeyDef.Popup.Keyboard? =
        def.popups?.filterIsInstance<KeyDef.Popup.Keyboard>()?.singleOrNull()

    private fun byId(layout: List<List<KeyDef>>, id: String): KeyDef =
        layout.flatten().single { it.id == id }

    private fun assertRowWidth(layout: List<List<KeyDef>>, row: Int, expected: Float) {
        val sum = layout[row].sumOf { it.appearance.percentWidth.toDouble() }.toFloat()
        assertEquals("第 ${row + 1} 行宽和", expected, sum, 0.0001f)
    }

    // ---- 默认分支（.19 出厂布局原样）----

    @Test
    fun defaultRowWidthsMatchFactory() {
        // .19 原样：第 2 行 9 键 × 0.1 = 0.9，其余行和为 1.00。
        assertRowWidth(defaultLayout, 0, 1.0f)
        assertRowWidth(defaultLayout, 1, 0.9f)
        assertRowWidth(defaultLayout, 2, 1.0f)
        assertRowWidth(defaultLayout, 3, 1.0f)
    }

    @Test
    fun defaultLettersAreLowercaseAltTextWithPunctuation() {
        val cases = mapOf(
            "KEY_Q" to "1", "KEY_W" to "2", "KEY_E" to "3", "KEY_R" to "4",
            "KEY_T" to "5", "KEY_Y" to "6", "KEY_U" to "7", "KEY_I" to "8",
            "KEY_O" to "9", "KEY_P" to "0", "KEY_A" to "~", "KEY_S" to "!",
            "KEY_D" to "@", "KEY_F" to "#", "KEY_G" to "$", "KEY_H" to "%",
            "KEY_J" to "^", "KEY_K" to "&", "KEY_L" to "*", "KEY_Z" to "(",
            "KEY_X" to ")", "KEY_C" to ":", "KEY_V" to ";", "KEY_B" to ",",
            "KEY_N" to "?", "KEY_M" to "/",
        )
        assertEquals(26, cases.size)
        for ((id, punct) in cases) {
            val def = byId(defaultLayout, id)
            val appearance = def.appearance
            assertTrue("$id 应为 AltText 外观", appearance is KeyDef.Appearance.AltText)
            val alt = appearance as KeyDef.Appearance.AltText
            assertEquals(id, alt.displayText, alt.displayText.lowercase())
            assertEquals("$id 副标", punct, alt.altText)
            assertEquals("$id 字号 22", 22f, alt.textSize, 0.001f)
            assertEquals("$id 变体 Normal", Variant.Normal, alt.variant)
            assertEquals(
                id,
                KeyboardAction.KeySequenceAction(alt.displayText),
                pressOf(def),
            )
            assertEquals(id, KeyboardAction.CommitAction(punct), longPressOf(def))
            assertNull("$id 默认分支无长按弹窗", popupOf(def))
        }
        // .19 平移原样：g 主文 -2，j／l 副标 +4。
        assertEquals(
            -2,
            (byId(defaultLayout, "KEY_G").appearance as KeyDef.Appearance.AltText)
                .mainTextTranslationY,
        )
        assertEquals(
            4,
            (byId(defaultLayout, "KEY_J").appearance as KeyDef.Appearance.AltText)
                .altTextTranslationY,
        )
        assertEquals(
            4,
            (byId(defaultLayout, "KEY_L").appearance as KeyDef.Appearance.AltText)
                .altTextTranslationY,
        )
    }

    @Test
    fun defaultRow4MatchesFactory() {
        val row = defaultLayout[3]
        assertEquals(5, row.size)
        // ?123（灰）：文字键、15f、0.15。
        val num = row[0]
        assertEquals(KeyIds.Qwerty.KEY_LAYOUT_NUMBER, num.id)
        assertEquals(0.15f, num.appearance.percentWidth, 0.0001f)
        val numText = num.appearance as KeyDef.Appearance.Text
        assertEquals("?123", numText.displayText)
        assertEquals(15f, numText.textSize, 0.001f)
        assertEquals(Variant.Alternative, numText.variant)
        assertEquals(
            KeyboardAction.LayoutSwitchAction(NumberKeyboard.NAME),
            pressOf(num),
        )
        assertEquals(
            KeyboardAction.KeySequenceAction("/"),
            longPressOf(num),
        )
        // !?#（灰）：图文键、18f、0.13。
        val period = row[1]
        assertEquals(KeyIds.Qwerty.KEY_PERIOD, period.id)
        assertEquals(0.13f, period.appearance.percentWidth, 0.0001f)
        val periodImageText = period.appearance as KeyDef.Appearance.ImageText
        assertEquals(".", periodImageText.displayText)
        assertEquals(R.drawable.ic_keyboard_symbol, periodImageText.src)
        assertEquals(18f, periodImageText.textSize, 0.001f)
        assertEquals(Variant.Alternative, periodImageText.variant)
        assertEquals(KeyView.button_peroid, periodImageText.viewId)
        assertEquals(
            KeyboardAction.KeySequenceAction("."),
            pressOf(period),
        )
        assertEquals(
            KeyboardAction.LayoutSwitchAction(SymbolKeyboard.NAME),
            longPressOf(period),
        )
        // 空格（灰、方案名空格）：文字键、空文案、13f、0.44。
        val space = row[2]
        assertEquals(KeyIds.Qwerty.KEY_SPACE, space.id)
        assertEquals(0.44f, space.appearance.percentWidth, 0.0001f)
        val spaceText = space.appearance as KeyDef.Appearance.Text
        assertEquals("", spaceText.displayText)
        assertEquals(13f, spaceText.textSize, 0.001f)
        assertEquals(Variant.Alternative, spaceText.variant)
        assertEquals(KeyView.button_space, spaceText.viewId)
        assertEquals(KeyboardAction.SpaceAction, pressOf(space))
        assertEquals(KeyboardAction.VoiceInputAction(), longPressOf(space))
        // 方案切换（灰）：语言图、0.13。
        val schema = row[3]
        assertEquals(KeyIds.Qwerty.KEY_SCHEMA, schema.id)
        assertEquals(0.13f, schema.appearance.percentWidth, 0.0001f)
        val schemaImage = schema.appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_language, schemaImage.src)
        assertEquals(Variant.Alternative, schemaImage.variant)
        assertEquals(KeyboardAction.RotateSchema, pressOf(schema))
        assertEquals(
            KeyboardAction.ShowInputMethodPickerAction,
            longPressOf(schema),
        )
        // 回车（文字键，W19-2a：组字中显示“确认”需文字键，图标改文字是用户定的；
        // 旧断言为图标键）：0.15、Accent（buildLayout 传入 Accent）、初始文案“换行”、16f。
        val enter = row[4]
        assertEquals(KeyIds.Qwerty.KEY_ENTER, enter.id)
        assertEquals(0.15f, enter.appearance.percentWidth, 0.0001f)
        val enterText = enter.appearance as KeyDef.Appearance.Text
        assertEquals("换行", enterText.displayText)
        assertEquals(16f, enterText.textSize, 0.001f)
        assertEquals(Variant.Accent, enterText.variant)
        assertEquals(KeyView.button_return, enterText.viewId)
        assertEquals(KeyboardAction.ReturnAction(), pressOf(enter))
        assertEquals(KeyboardAction.ReturnAction(true), longPressOf(enter))
    }

    @Test
    fun defaultCapsAndBackspaceIconsAreFactory() {
        val caps = byId(defaultLayout, KeyIds.Qwerty.KEY_CAPS).appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_capslock_none, caps.src)
        assertNull(caps.iconSizeDp)
        val backspace = byId(defaultLayout, KeyIds.Qwerty.KEY_DELETE).appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_backspace, backspace.src)
        assertNull(backspace.iconSizeDp)
    }
}
