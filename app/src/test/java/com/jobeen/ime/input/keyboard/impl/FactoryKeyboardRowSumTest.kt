// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.runtime.BuiltinFallbackLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 出厂键盘行和不变量（AGENTS.md 行和铁律，2026-10-19）：
 * 每行（行内键 percentWidth 之和）＋（覆盖该行的跨行键宽）必须恰好
 * 为 1.0——与 BaseKeyboard 初始化时算 rowSpanScales 的口径一致
 * （行内键宽和为 0 的空行除外，此时 spanScale 取 1、无行内键可缩）。
 * 不变量成立时 spanScale = 1，旧布局逐像素不变；违反时行内键被等比
 * 缩放、行视觉错位。任一出厂键盘改键宽都必须先过本测试。
 *
 * 覆盖全部带 companion buildLayout 的出厂键盘（10 把）＋兜底布局
 * BuiltinFallbackLayout（与 Qwerty 同构）；SymbolKeyboard
 * 是 FrameLayout、不走 BaseKeyboard 行布局路径，不在本测试内。
 */
class FactoryKeyboardRowSumTest {

    @Test
    fun `every factory keyboard row sums to one`() {
        val layouts: Map<String, List<List<KeyDef>>> = mapOf(
            "T9-accent" to T9Keyboard.buildLayout(Variant.Accent),
            "T9-normal" to T9Keyboard.buildLayout(Variant.Normal),
            "Qwerty-accent" to QwertyKeyboard.buildLayout(Variant.Accent),
            "Qwerty-normal" to QwertyKeyboard.buildLayout(Variant.Normal),
            "BuiltinFallback" to BuiltinFallbackLayout.layout(),
            "Number-accent" to NumberKeyboard.buildLayout(Variant.Accent),
            "Number-normal" to NumberKeyboard.buildLayout(Variant.Normal),
            "T14-accent" to T14Keyboard.buildLayout(Variant.Accent),
            "T15-accent" to T15Keyboard.buildLayout(Variant.Accent),
            "T18-accent" to T18Keyboard.buildLayout(Variant.Accent),
            "FloatPanel" to FloatPanelKeyboard.buildLayout(),
            "LayoutSwitch" to LayoutSwitchKeyboard.buildLayout(),
            "Panel" to PanelKeyboard.buildLayout(),
            "ReturnPath" to ReturnPathKeyboard.buildLayout(),
        )
        for ((name, layout) in layouts) {
            assertRowSums(name, layout)
        }
    }

    private fun assertRowSums(name: String, layout: List<List<KeyDef>>) {
        // 跨行键：起始行 ri，覆盖 ri..min(ri + rowSpan - 1, 末行)
        // （与 BaseKeyboard init 的 SpanDef.endRow 同口径）。
        data class Span(val startRow: Int, val endRow: Int, val width: Float)
        val spans = mutableListOf<Span>()
        for (ri in layout.indices) {
            for (def in layout[ri]) {
                if (def.appearance.rowSpan > 1) {
                    spans += Span(
                        ri,
                        (ri + def.appearance.rowSpan - 1).coerceAtMost(layout.size - 1),
                        def.appearance.percentWidth,
                    )
                }
            }
        }
        for (rowIndex in layout.indices) {
            val inRowSum = layout[rowIndex]
                .filter { it.appearance.rowSpan <= 1 }
                .sumOf { it.appearance.percentWidth.toDouble() }
            val spanSum = spans
                .filter { rowIndex in it.startRow..it.endRow }
                .sumOf { it.width.toDouble() }
            if (inRowSum == 0.0) {
                // 空行：无行内键可缩，spanScale 取 1（与生产口径一致）；
                // 但空行必须被跨行键覆盖，否则是一整行空白。
                assertEquals(
                    "$name row $rowIndex: empty row uncovered",
                    true, spanSum > 0.0,
                )
                continue
            }
            assertEquals(
                "$name row $rowIndex: inRow=$inRowSum span=$spanSum",
                1.0, inRowSum + spanSum, 1e-4,
            )
        }
    }
}
