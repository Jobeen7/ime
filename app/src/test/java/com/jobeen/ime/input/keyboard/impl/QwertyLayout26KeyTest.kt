// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.R
import com.jobeen.ime.input.EnterFieldAction
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26 键布局双分支（2026-11-06 施工单 3＋施工单 4 收口）：
 * 绿预设分支 = 对端排布（字母大写无副标、第四行排布、图标与长按
 * 提示符、行宽和为 1.00、回车绿档集合、竖屏高度 25）；默认分支 =
 * .19 出厂布局原样（小写字母＋副标、?123／!?#／方案名空格、实心图标）。
 */
class QwertyLayout26KeyTest {

    private val greenLayout = QwertyKeyboard.buildGreenLayout(Variant.Accent)
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

    // ---- 绿预设分支（对端排布）----

    @Test
    fun greenAllRowWidthsSumToOne() {
        assertRowWidth(greenLayout, 0, 1.0f)
        assertRowWidth(greenLayout, 1, 1.0f)
        assertRowWidth(greenLayout, 2, 1.0f)
        assertRowWidth(greenLayout, 3, 1.0f)
    }

    @Test
    fun greenLettersAreUppercaseTextWithoutAlt() {
        val letters = listOf(
            "KEY_Q", "KEY_W", "KEY_E", "KEY_R", "KEY_T",
            "KEY_Y", "KEY_U", "KEY_I", "KEY_O", "KEY_P",
            "KEY_A", "KEY_S", "KEY_D", "KEY_F", "KEY_G",
            "KEY_H", "KEY_J", "KEY_K", "KEY_L",
            "KEY_Z", "KEY_X", "KEY_C", "KEY_V", "KEY_B", "KEY_N", "KEY_M",
        )
        assertEquals(26, letters.size)
        for (id in letters) {
            val def = byId(greenLayout, id)
            val appearance = def.appearance
            // Text（非 AltText）：无副标。
            assertFalse("$id 应为 Text 外观", appearance is KeyDef.Appearance.AltText)
            val text = appearance as KeyDef.Appearance.Text
            assertEquals(id, text.displayText, text.displayText.uppercase())
            assertEquals("$id 字号 16", 16f, text.textSize, 0.001f)
            assertEquals("$id 变体 Normal", Variant.Normal, text.variant)
            // 点按动作仍为小写字母（大写由 transformAction 按 Caps 态变换）。
            assertEquals(
                id,
                KeyboardAction.KeySequenceAction(text.displayText.lowercase()),
                pressOf(def),
            )
        }
    }

    @Test
    fun greenRow2EndsAreWideCells() {
        assertEquals(0.15f, byId(greenLayout, KeyIds.Qwerty.KEY_A).appearance.percentWidth, 0.0001f)
        assertEquals(0.15f, byId(greenLayout, KeyIds.Qwerty.KEY_L).appearance.percentWidth, 0.0001f)
        for (id in listOf("KEY_S", "KEY_D", "KEY_F", "KEY_G", "KEY_H", "KEY_J", "KEY_K")) {
            assertEquals(id, 0.1f, byId(greenLayout, id).appearance.percentWidth, 0.0001f)
        }
    }

    @Test
    fun greenRow4LayoutMatchesCounterpart() {
        val row = greenLayout[3]
        assertEquals(5, row.size)
        // 123（灰）：文字键、“123”、15f、0.21。
        val num = row[0]
        assertEquals(KeyIds.Qwerty.KEY_LAYOUT_NUMBER, num.id)
        assertEquals(0.21f, num.appearance.percentWidth, 0.0001f)
        val numText = num.appearance as KeyDef.Appearance.Text
        assertEquals("123", numText.displayText)
        assertEquals(15f, numText.textSize, 0.001f)
        assertEquals(Variant.Alternative, numText.variant)
        // 中/英（白）：cnen 图、Normal、0.10、图标 24dp。
        val schema = row[1]
        assertEquals(KeyIds.Qwerty.KEY_SCHEMA, schema.id)
        assertEquals(0.10f, schema.appearance.percentWidth, 0.0001f)
        val schemaImage = schema.appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.cnen, schemaImage.src)
        assertEquals(Variant.Normal, schemaImage.variant)
        assertEquals(24f, schemaImage.iconSizeDp)
        // 空格（白底 ⏎）：⊔ 图、Normal、0.38。
        val space = row[2]
        assertEquals(KeyIds.Qwerty.KEY_SPACE, space.id)
        assertEquals(0.38f, space.appearance.percentWidth, 0.0001f)
        val spaceImage = space.appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_space, spaceImage.src)
        assertEquals(Variant.Normal, spaceImage.variant)
        assertEquals(KeyView.button_space, spaceImage.viewId)
        // 。，（白）：上“。”14、下“，”16、0.10。
        val period = row[3]
        assertEquals(KeyIds.Qwerty.KEY_PERIOD, period.id)
        assertEquals(0.10f, period.appearance.percentWidth, 0.0001f)
        val periodAlt = period.appearance as KeyDef.Appearance.AltText
        assertEquals("。", periodAlt.displayText)
        assertEquals("，", periodAlt.altText)
        assertEquals(14f, periodAlt.textSize, 0.001f)
        assertEquals(16f, periodAlt.altTextSize, 0.001f)
        assertEquals(Variant.Normal, periodAlt.variant)
        // 发送（绿文字）：文字键、0.21、Accent（buildGreenLayout 传入 Accent）。
        val enter = row[4]
        assertEquals(KeyIds.Qwerty.KEY_ENTER, enter.id)
        assertEquals(0.21f, enter.appearance.percentWidth, 0.0001f)
        val enterText = enter.appearance as KeyDef.Appearance.Text
        assertEquals(16f, enterText.textSize, 0.001f)
        assertEquals(Variant.Accent, enterText.variant)
        assertEquals(KeyView.button_return, enterText.viewId)
    }

    @Test
    fun greenReturnKeyIsTextWithReturnActions() {
        val enter = byId(greenLayout, KeyIds.Qwerty.KEY_ENTER)
        assertTrue(enter.appearance is KeyDef.Appearance.Text)
        assertEquals(
            KeyboardAction.ReturnAction(),
            pressOf(enter),
        )
        assertEquals(
            KeyboardAction.ReturnAction(true),
            longPressOf(enter),
        )
    }

    @Test
    fun greenLetterPopupsMatchHintSymbols() {
        // q [q,1,Q] 选中 0；w–o [小写，数字，大写] 选中 1；p [p,0,P] 选中 2。
        val cases = mapOf(
            "KEY_Q" to (listOf("q", "1", "Q") to 0),
            "KEY_W" to (listOf("w", "2", "W") to 1),
            "KEY_E" to (listOf("e", "3", "E") to 1),
            "KEY_O" to (listOf("o", "9", "O") to 1),
            "KEY_P" to (listOf("p", "0", "P") to 2),
        )
        for ((id, expected) in cases) {
            val (items, index) = expected
            val popup = popupOf(byId(greenLayout, id))
            assertEquals(id, items.map { KeyboardAction.KeySequenceAction(it) }, popup?.keys)
            assertEquals(id, index, popup?.initialSelectedIndex)
            assertNull("$id 有弹窗时不另挂长按", longPressOf(byId(greenLayout, id)))
        }
        // h [https://, http://] 选中 1。
        val hPopup = popupOf(byId(greenLayout, KeyIds.Qwerty.KEY_H))
        assertEquals(
            listOf(
                KeyboardAction.KeySequenceAction("https://"),
                KeyboardAction.KeySequenceAction("http://"),
            ),
            hPopup?.keys,
        )
        assertEquals(1, hPopup?.initialSelectedIndex)
        assertNull("h 有弹窗时不另挂长按", longPressOf(byId(greenLayout, KeyIds.Qwerty.KEY_H)))
        // 123 [+ − = × ÷ √] 选中 1。
        val numPopup = popupOf(byId(greenLayout, KeyIds.Qwerty.KEY_LAYOUT_NUMBER))
        assertEquals(
            listOf("+", "-", "=", "×", "÷", "√").map { KeyboardAction.KeySequenceAction(it) },
            numPopup?.keys,
        )
        assertEquals(1, numPopup?.initialSelectedIndex)
        assertNull("123 有弹窗时不另挂长按 /", longPressOf(byId(greenLayout, KeyIds.Qwerty.KEY_LAYOUT_NUMBER)))
    }

    @Test
    fun greenRow2LettersKeepLongPressPunctuation() {
        val cases = mapOf(
            "KEY_A" to "~",
            "KEY_S" to "!",
            "KEY_D" to "@",
            "KEY_F" to "#",
            "KEY_G" to "$",
            "KEY_J" to "^",
            "KEY_K" to "&",
            "KEY_L" to "*",
        )
        for ((id, punct) in cases) {
            assertEquals(
                id,
                KeyboardAction.CommitAction(punct),
                longPressOf(byId(greenLayout, id)),
            )
            assertNull(id, popupOf(byId(greenLayout, id)))
        }
    }

    @Test
    fun greenSpaceKeyActionsUnchanged() {
        val space = byId(greenLayout, KeyIds.Qwerty.KEY_SPACE)
        assertEquals(KeyboardAction.SpaceAction, pressOf(space))
        assertEquals(KeyboardAction.VoiceInputAction(), longPressOf(space))
    }

    @Test
    fun greenCommaKeyCommitsFullWidthComma() {
        val period = byId(greenLayout, KeyIds.Qwerty.KEY_PERIOD)
        assertEquals(KeyboardAction.CommitAction("，"), pressOf(period))
        assertEquals(
            KeyboardAction.LayoutSwitchAction(SymbolKeyboard.NAME),
            longPressOf(period),
        )
    }

    @Test
    fun greenSchemaKeySwitchesScheme() {
        val schema = byId(greenLayout, KeyIds.Qwerty.KEY_SCHEMA)
        assertEquals(KeyboardAction.RotateSchema, pressOf(schema))
        assertEquals(
            KeyboardAction.ShowInputMethodPickerAction,
            longPressOf(schema),
        )
    }

    @Test
    fun greenCapsAndBackspaceIconsAreOutlineSized() {
        val caps = byId(greenLayout, KeyIds.Qwerty.KEY_CAPS).appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_capslock_none, caps.src)
        assertEquals(19f, caps.iconSizeDp)
        val backspace = byId(greenLayout, KeyIds.Qwerty.KEY_DELETE).appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_backspace_outline, backspace.src)
        assertEquals(21f, backspace.iconSizeDp)
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
        // 回车（图标键）：0.15、Accent（buildLayout 传入 Accent）。
        val enter = row[4]
        assertEquals(KeyIds.Qwerty.KEY_ENTER, enter.id)
        assertEquals(0.15f, enter.appearance.percentWidth, 0.0001f)
        val enterImage = enter.appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_return, enterImage.src)
        assertEquals(Variant.Accent, enterImage.variant)
        assertEquals(KeyView.button_return, enterImage.viewId)
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

    @Test
    fun heightPercentIs25() {
        assertEquals(25, QwertyKeyboard.HEIGHT_PERCENT_PORTRAIT)
    }

    @Test
    fun greenReturnActionSetMatchesSource() {
        // 源包绿档：1 go／4 next／6 search／7 send／9 done。
        for (action in listOf(
            EnterFieldAction.GO,
            EnterFieldAction.NEXT,
            EnterFieldAction.SEARCH,
            EnterFieldAction.SEND,
            EnterFieldAction.DONE,
        )) {
            assertTrue(action.name, QwertyKeyboard.isGreenReturnAction(action))
        }
        assertFalse(
            EnterFieldAction.PREVIOUS.name,
            QwertyKeyboard.isGreenReturnAction(EnterFieldAction.PREVIOUS),
        )
        assertFalse(
            EnterFieldAction.UNSPECIFIED.name,
            QwertyKeyboard.isGreenReturnAction(EnterFieldAction.UNSPECIFIED),
        )
        assertFalse("null", QwertyKeyboard.isGreenReturnAction(null))
    }
}
