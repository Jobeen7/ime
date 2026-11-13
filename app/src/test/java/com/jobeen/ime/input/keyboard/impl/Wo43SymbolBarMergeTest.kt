// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard.Companion.buildSidePanelItemDefs
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard.Companion.sidePanelGeometry
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 符号板左侧竖栏合并（WO43 v7，他 2026-11-13 18:37 定稿）：
 * 侧栏占 contentH 的 4/5、返回区占 1/5，一屏 4 格、每格高等于
 * 主行高（contentH/5），与主行逐格对齐，格间无分割线；
 * “自定义”不再是固定底栏，是符号列表第 17 项（滑到底才可见），
 * 与九宫格左竖栏同口径。
 * onMeasure／onLayout 的接线本身与真机手感需真机验收。
 */
class Wo43SymbolBarMergeTest {

    private val symbols16 = listOf(
        "，", "。", "？", "！", "…", "～", "、", "=",
        "+", "-", "*", "/", "%", "#", "@", "&",
    )

    // ---------- 几何：侧栏 4/5＋返回区 1/5 ----------

    @Test
    fun geometry_fourFifthsPlusOneFifth() {
        for (contentH in listOf(100, 101, 500, 624, 832, 1000)) {
            val (sidePanelH, returnRegionH) = sidePanelGeometry(contentH)
            assertEquals(contentH * 4 / 5, sidePanelH)
            assertEquals(contentH - sidePanelH, returnRegionH)
            assertEquals(contentH, sidePanelH + returnRegionH)
        }
    }

    @Test
    fun geometry_portraitDefault() {
        // S24 Ultra 竖屏 contentH 624dp：侧栏 499dp（4 格×124.75dp）、
        // 返回区 125dp；每格高与主行高（624/5＝124.8dp）只差整除余数。
        val (sidePanelH, returnRegionH) = sidePanelGeometry(624)
        assertEquals(499, sidePanelH)
        assertEquals(125, returnRegionH)
    }

    // ---------- 条目：16 符号＋第 17 项“自定义” ----------

    private fun textOf(def: KeyDef): KeyDef.Appearance.Text =
        def.appearance as KeyDef.Appearance.Text

    private fun pressActionOf(def: KeyDef): KeyboardAction =
        (def.behaviors.single() as KeyDef.Behavior.Press).action

    @Test
    fun portrait_sixteenSymbolsPlusCustomEntrySeventeenth() {
        val defs = buildSidePanelItemDefs(symbols16, landscape = false)
        assertEquals(17, defs.size)
        defs.take(16).forEachIndexed { i, def ->
            assertEquals(symbols16[i], textOf(def).displayText)
            assertEquals(15f, textOf(def).textSize)
            assertEquals(KeyDef.Appearance.Variant.Alternative, textOf(def).variant)
            assertEquals(
                KeyboardAction.CommitAction(symbols16[i]),
                pressActionOf(def),
            )
        }
        val entry = defs.last()
        assertEquals("⊕\n自定义", textOf(entry).displayText)
        assertEquals(12f, textOf(entry).textSize)
        assertEquals(KeyDef.Appearance.Variant.Normal, textOf(entry).variant)
        assertTrue(pressActionOf(entry) is KeyboardAction.OpenSymbolBarSettingsAction)
    }

    @Test
    fun landscape_customEntryIsSingleLine() {
        val defs = buildSidePanelItemDefs(symbols16, landscape = true)
        assertEquals(17, defs.size)
        val entry = defs.last()
        assertEquals("⊕ 自定义", textOf(entry).displayText)
        assertEquals(12f, textOf(entry).textSize)
        assertEquals(KeyDef.Appearance.Variant.Normal, textOf(entry).variant)
        assertTrue(pressActionOf(entry) is KeyboardAction.OpenSymbolBarSettingsAction)
        // 符号项不受方向影响。
        assertEquals("，", textOf(defs.first()).displayText)
        assertEquals(15f, textOf(defs.first()).textSize)
    }
}
