package com.jobeen.ime.skin.library

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.skin.layout.LayoutKeyDefBuilder
import com.jobeen.ime.skin.layout.LayoutKeyModel
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.model.SkinAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 布局感知迷你预览（全布局矩阵）：行/键结构、宽度权重、降级路径。 */
class SkinMiniPreviewTest {

    private fun textKey(
        width: Float,
        variant: KeyDef.Appearance.Variant = KeyDef.Appearance.Variant.Normal,
    ) = KeyDef(
        KeyDef.Appearance.Text(
            displayText = "A",
            textSize = 14f,
            percentWidth = width,
            variant = variant,
        ),
        emptySet(),
    )

    @Test
    fun layoutRowsPreserveStructureAndWeights() {
        // 11 键自定义布局：4 + 4 + 3，含一个双倍宽键。
        val rows = layoutPreviewRows(
            listOf(
                listOf(textKey(0.25f), textKey(0.25f), textKey(0.25f), textKey(0.25f)),
                listOf(textKey(0.2f), textKey(0.2f), textKey(0.4f), textKey(0.2f)),
                listOf(textKey(0.33f), textKey(0.34f), textKey(0.33f)),
            ),
        )!!
        assertEquals(listOf(4, 4, 3), rows.map { it.size })
        for (row in rows) {
            assertEquals(1.0, row.sumOf { it.weight.toDouble() }, 0.01)
        }
        assertTrue(rows[1][2].weight > rows[1][0].weight)
    }

    @Test
    fun variantAndSidePanelKinds() {
        val rows = layoutPreviewRows(
            listOf(
                listOf(
                    textKey(0.25f, KeyDef.Appearance.Variant.Accent),
                    textKey(0.25f, KeyDef.Appearance.Variant.Alternative),
                    KeyDef(
                        KeyDef.Appearance.SidePannel(percentWidth = 0.25f),
                        emptySet(),
                    ),
                    textKey(0.25f),
                ),
            ),
        )!!
        assertEquals(
            listOf(
                MiniKeyKind.ACCENT, MiniKeyKind.SPECIAL,
                MiniKeyKind.SPECIAL, MiniKeyKind.KEY,
            ),
            rows[0].map { it.kind },
        )
    }

    @Test
    fun invalidWeightsFallBackToEqual() {
        val rows = layoutPreviewRows(
            listOf(listOf(textKey(0f), textKey(-1f), textKey(Float.NaN))),
        )!!
        for (key in rows[0]) {
            assertEquals(1.0 / 3.0, key.weight.toDouble(), 0.001)
        }
    }

    @Test
    fun emptyLayoutReturnsNullAndEmptyRowsSkipped() {
        assertNull(layoutPreviewRows(emptyList()))
        assertNull(layoutPreviewRows(listOf(emptyList(), emptyList())))
        val rows = layoutPreviewRows(
            listOf(emptyList(), listOf(textKey(0.5f), textKey(0.5f))),
        )!!
        assertEquals(1, rows.size)
        assertEquals(2, rows[0].size)
    }

    @Test
    fun builderBased18KeyLayoutKeepsAllKeys() {
        // 经真实自创布局构建器：18 实键（7 + 6 + 5），权重在构建期
        // 换算；行和 7/7/5 → 短行（第 3 行）补 1 个空白格，预览
        // 行格数为 7/6/6，实键仍 18 个（2026-10-29 item-1 D4）。
        fun key(id: String, weight: Double) = LayoutKeyModel(
            stableId = id,
            label = id,
            widthWeight = weight,
            rowSpan = 1,
            alignSpanEnd = false,
            tap = SkinAction.Text(id),
            swipeUp = null,
            swipeDown = null,
            longPress = null,
        )
        val layout = LayoutModel(
            layoutId = "lay-18",
            name = "lay-18",
            rows = listOf(
                (1..7).map { key("r1-$it", 1.0) },
                (1..6).map { key("r2-$it", if (it == 3) 2.0 else 1.0) },
                (1..5).map { key("r3-$it", 1.0) },
            ),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        val rows = layoutPreviewRows(built)!!
        assertEquals(listOf(7, 6, 6), rows.map { it.size })
        assertEquals(18, built.flatten().count { it.id != null })
        assertEquals(19, rows.sumOf { it.size })
        for (row in rows) {
            assertEquals(1.0, row.sumOf { it.weight.toDouble() }, 0.01)
        }
        assertTrue(rows[1][2].weight > rows[1][0].weight)
    }

    @Test
    fun genericRowsAreEqualWeighted() {
        for (row in genericPreviewRows()) {
            assertEquals(1.0, row.sumOf { it.weight.toDouble() }, 0.001)
        }
    }

    @Test
    fun builtinRowsUseRealFactoryGeometry() {
        // 出厂 buildLayout 的真实行/键数（与各键盘实现逐项对过）：
        // 九宫格 5/4/4/4（含侧栏），26 键 10/9/9/5，十五键 7/6/6/5
        // （首行含侧栏键：侧栏 + 5 字母键 + 删除键），数字 5/4/4/7。
        val expected = mapOf(
            BuiltinPreviewLayout.T9 to listOf(5, 4, 4, 4),
            BuiltinPreviewLayout.QWERTY to listOf(10, 9, 9, 5),
            BuiltinPreviewLayout.T15 to listOf(7, 6, 6, 5),
            BuiltinPreviewLayout.NUMBER to listOf(5, 4, 4, 7),
        )
        for ((layout, sizes) in expected) {
            val rows = builtinPreviewRows(layout)!!
            assertEquals(sizes, rows.map { it.size })
            for (row in rows) {
                assertEquals(1.0, row.sumOf { it.weight.toDouble() }, 0.01)
            }
        }
    }

    @Test
    fun builtinRowsContainSpecialKeys() {
        // 侧栏/功能键在预览中计特殊色，普通字母键计普通色。
        val t9 = builtinPreviewRows(BuiltinPreviewLayout.T9)!!
        assertTrue(t9[0].any { it.kind == MiniKeyKind.SPECIAL })
        assertTrue(t9[0].any { it.kind == MiniKeyKind.KEY })
        val qwerty = builtinPreviewRows(BuiltinPreviewLayout.QWERTY)!!
        assertTrue(qwerty[2].any { it.kind == MiniKeyKind.SPECIAL })
    }
}
