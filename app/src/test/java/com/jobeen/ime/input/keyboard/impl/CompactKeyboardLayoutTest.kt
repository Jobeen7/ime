// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.layout.CompactLayoutGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 14/18 键紧凑键盘出厂布局：分组、键动作、行和 1.0 不变量、
 * T9 句号键快捷符号弹窗。
 */
class CompactKeyboardLayoutTest {

    private fun compactKeys(
        layout: List<List<KeyDef>>,
        prefix: String,
    ): List<KeyDef> = layout.flatten().filter { it.id?.startsWith(prefix) == true }

    private fun pressSequence(def: KeyDef): String? =
        (def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().singleOrNull()?.action
            as? KeyboardAction.KeySequenceAction)?.sequence

    private fun longPressSequence(def: KeyDef): String? =
        (def.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().singleOrNull()?.action
            as? KeyboardAction.KeySequenceAction)?.sequence

    private fun assertRowSumsAreOne(layout: List<List<KeyDef>>) {
        // 与 BaseKeyboard 同口径：行内宽和＋覆盖 span 宽和＝1.0。
        // 侧栏（.15，跨行 3）覆盖行 0–2；回车（.18，跨行 2）覆盖行 2–3。
        for ((index, row) in layout.withIndex()) {
            val inRowSum = row.filter { it.appearance.rowSpan <= 1 }
                .sumOf { it.appearance.percentWidth.toDouble() }
            val spanSum = (if (index <= 2) 0.15 else 0.0) + (if (index >= 2) 0.18 else 0.0)
            assertTrue(
                "row $index sums to ${inRowSum + spanSum}",
                abs(inRowSum + spanSum - 1.0) < 0.001,
            )
        }
    }

    @Test
    fun t14LayoutHasFourteenGroupsCoveringAlphabet() {
        val layout = T14Keyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val keys = compactKeys(layout, "T14_KEY_")
        assertEquals(14, keys.size)
        val grouping = CompactLayoutGenerator.groupingFor(14)!!
        assertEquals(
            grouping.groups.map { it.letters },
            keys.map { (it.appearance as KeyDef.Appearance.AltText).displayText },
        )
        // 26 个字母恰好一次。
        val covered = keys.joinToString("") {
            (it.appearance as KeyDef.Appearance.AltText).displayText
        }
        assertEquals(26, covered.length)
        assertEquals(26, covered.toSet().size)
        // 点按发首字母、长按发次字母（都进组字）。
        for ((key, group) in keys.zip(grouping.groups)) {
            assertEquals(group.first.toString(), pressSequence(key))
            assertEquals(group.second.toString(), longPressSequence(key))
        }
        assertRowSumsAreOne(layout)
        // 首键是侧栏（跨行 3），末行是底行四键。
        assertTrue(layout[0][0].appearance is KeyDef.Appearance.SidePannel)
        assertEquals(3, layout[0][0].appearance.rowSpan)
        assertEquals(4, layout[3].size)
    }

    @Test
    fun t18LayoutHasEighteenGroupsCoveringAlphabet() {
        val layout = T18Keyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val keys = compactKeys(layout, "T18_KEY_")
        assertEquals(18, keys.size)
        val grouping = CompactLayoutGenerator.groupingFor(18)!!
        assertEquals(
            grouping.groups.map { it.letters },
            keys.map { (it.appearance as KeyDef.Appearance.AltText).displayText },
        )
        val covered = keys.joinToString("") {
            (it.appearance as KeyDef.Appearance.AltText).displayText
        }
        assertEquals(26, covered.length)
        assertEquals(26, covered.toSet().size)
        for ((key, group) in keys.zip(grouping.groups)) {
            assertEquals(group.first.toString(), pressSequence(key))
            assertEquals(group.second.toString(), longPressSequence(key))
        }
        assertRowSumsAreOne(layout)
        assertEquals(4, layout[3].size)
    }

    @Test
    fun t9PeriodKeyShowsQuickSymbolsPopupByDefault() {
        val layout = T9Keyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        val period = layout.flatten().single { it.id == "T9_PERIOD" }
        val popup = period.popups?.single() as KeyDef.Popup.Keyboard
        assertEquals(
            listOf("，", "。", "？", "！", "、", "…").map { KeyboardAction.CommitAction(it) },
            popup.keys,
        )
        // 弹窗接管长按槽：不再挂长按进符号板（符号板走 ?123→数字键）。
        assertTrue(period.behaviors.none { it is KeyDef.Behavior.LongPress })
        // 点按仍是句号。
        assertEquals(
            KeyboardAction.KeySequenceAction("."),
            (period.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action),
        )
    }

    @Test
    fun t9PeriodKeyWithEmptyQuickSymbolsKeepsOldLongPress() {
        val layout = T9Keyboard.buildLayout(KeyDef.Appearance.Variant.Accent, emptyList())
        val period = layout.flatten().single { it.id == "T9_PERIOD" }
        assertNull(period.popups)
        val longPress = period.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single()
        assertEquals(
            KeyboardAction.LayoutSwitchAction("Symbol"),
            longPress.action,
        )
    }

    @Test
    fun t9PeriodKeyWithCustomQuickSymbols() {
        val layout = T9Keyboard.buildLayout(
            KeyDef.Appearance.Variant.Accent, listOf("；", "：", "、"),
        )
        val period = layout.flatten().single { it.id == "T9_PERIOD" }
        val popup = period.popups?.single() as KeyDef.Popup.Keyboard
        assertEquals(
            listOf("；", "：", "、").map { KeyboardAction.CommitAction(it) },
            popup.keys,
        )
    }
}
