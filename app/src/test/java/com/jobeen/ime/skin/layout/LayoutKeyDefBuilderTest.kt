package com.jobeen.ime.skin.layout

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 布局构建（B3 详设 §3.2/§2.4）：权重换算、动作装配、ID 稳定性。 */
class LayoutKeyDefBuilderTest {

    private fun key(
        stableId: String,
        label: String = stableId,
        weight: Double = 1.0,
        rowSpan: Int = 1,
        alignSpanEnd: Boolean = false,
        tap: SkinAction = SkinAction.Text(stableId),
        swipeUp: SkinAction? = null,
        swipeDown: SkinAction? = null,
        longPress: List<SkinAction>? = null,
        landscapeWeight: Double? = null,
        styleRef: String? = null,
        styleOverrides: Map<com.jobeen.ime.skin.model.KeyStyleRole, String>? = null,
    ) = LayoutKeyModel(
        stableId = stableId,
        label = label,
        widthWeight = weight,
        rowSpan = rowSpan,
        alignSpanEnd = alignSpanEnd,
        tap = tap,
        swipeUp = swipeUp,
        swipeDown = swipeDown,
        longPress = longPress,
        landscapeWidthWeight = landscapeWeight,
        styleRef = styleRef,
        styleOverrides = styleOverrides,
    )

    private fun layoutOf(vararg rows: List<LayoutKeyModel>, id: String = "lay-1") =
        LayoutModel(layoutId = id, name = id, rows = rows.toList())

    @Test
    fun weightProportionsComputedAtBuildOnly() {
        val layout = layoutOf(
            listOf(key("a", weight = 1.0), key("b", weight = 3.0), key("c", weight = 4.0)),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        val widths = built[0].map { it.appearance.percentWidth }
        assertEquals(listOf(0.125f, 0.375f, 0.5f), widths)
        // 原始权重仍在模型里（构建不改模型、不归一化回写，裁定 B1）
        assertEquals(listOf(1.0, 3.0, 4.0), layout.rows[0].map { it.widthWeight })
    }

    @Test
    fun undeclaredWeightsShareEqually() {
        val layout = layoutOf(listOf(key("a"), key("b"), key("c"), key("d")))
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        assertTrue(built[0].all { it.appearance.percentWidth == 0.25f })
    }

    @Test
    fun idsAreLogicalAndStableAcrossReorderAndInsert() {
        val original = layoutOf(
            listOf(key("1"), key("2")),
            listOf(key("3")),
        )
        // 重排（整行互换 + 行内互换）并插入新键 9：存量键 ID 不变
        val rearranged = layoutOf(
            listOf(key("3")),
            listOf(key("9"), key("2"), key("1")),
        )
        val before = LayoutKeyDefBuilder.build(original, emptyMap())
            .flatten().mapNotNull { it.id }.toSet()
        val after = LayoutKeyDefBuilder.build(rearranged, emptyMap())
            .flatten().mapNotNull { it.id }.toSet()
        assertEquals(setOf("lay-1:K-1", "lay-1:K-2", "lay-1:K-3"), before)
        assertEquals(before + "lay-1:K-9", after)
        // 删除键 2：其 ID 消失，其余不变
        val deleted = layoutOf(listOf(key("1")), listOf(key("3")))
        val afterDelete = LayoutKeyDefBuilder.build(deleted, emptyMap())
            .flatten().mapNotNull { it.id }.toSet()
        assertEquals(setOf("lay-1:K-1", "lay-1:K-3"), afterDelete)
    }

    @Test
    fun sampleLayoutsBuild() {
        // 11/14/17/18 键样例（十六.3）：构建成功、实键 ID 全唯一且
        // 带命名空间前缀；短行补的空白键 id 为 null，不计入实键
        // （2026-10-29 item-1 D4：行和不等时短行补空白键）
        for (count in listOf(11, 14, 17, 18)) {
            val keys = (1..count).map { key("$it") }
            val rows = keys.chunked(6)
            val built = LayoutKeyDefBuilder.build(layoutOf(*rows.toTypedArray()), emptyMap())
            val ids = built.flatten().mapNotNull { it.id }
            assertEquals("count=$count", count, ids.size)
            assertEquals("count=$count", count, ids.toSet().size)
            assertTrue(ids.all { it.startsWith("lay-1:K-") })
            // 每行 percentWidth 合计 ≈ 1（含补位空白键）
            for (row in built) {
                assertEquals(1.0f, row.sumOf { it.appearance.percentWidth.toDouble() }.toFloat(), 1e-4f)
            }
        }
    }

    @Test
    fun spanFieldsCarriedToAppearance() {
        val layout = layoutOf(
            listOf(key("a", rowSpan = 2, alignSpanEnd = true), key("b")),
            listOf(key("c")),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        val span = built[0][0].appearance
        assertEquals(2, span.rowSpan)
        assertEquals(true, span.alignSpanEnd)
        assertEquals(1, built[0][1].appearance.rowSpan)
    }

    @Test
    fun actionsAssembledLikeOverrides() {
        val layout = layoutOf(
            listOf(
                key(
                    "a",
                    tap = SkinAction.Command(WhitelistAction.DELETE_FORWARD),
                    swipeUp = SkinAction.Text("!"),
                    longPress = listOf(SkinAction.Text("1"), SkinAction.Text("2")),
                ),
                key(
                    "b",
                    tap = SkinAction.Command(WhitelistAction.COMMIT_DIRECT, "x"),
                    longPress = listOf(SkinAction.Command(WhitelistAction.CLEAR)),
                ),
            ),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        val a = built[0][0]
        val pressA = a.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single()
        assertTrue(pressA.action is KeyboardAction.DeleteForwardAction)
        val swipeA = a.behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertTrue(swipeA.upAction is KeyboardAction.KeySequenceAction)
        assertNull(swipeA.downAction)
        // 多项长按只挂候选条（B2 装配规），不挂 LongPress 行为
        assertTrue(a.behaviors.none { it is KeyDef.Behavior.LongPress })
        val popup = a.popups!!.filterIsInstance<KeyDef.Popup.Keyboard>().single()
        assertEquals(2, popup.keys.size)
        val b = built[0][1]
        val pressB = b.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single()
        assertTrue(pressB.action is KeyboardAction.LiteralCommitAction)
        assertTrue(
            b.behaviors.filterIsInstance<KeyDef.Behavior.LongPress>().single().action
                is KeyboardAction.ClearAction,
        )
    }

    @Test
    fun spaceKeyGetsFactorySpaceTreatment() {
        val layout = layoutOf(
            listOf(key("sp", label = "", tap = SkinAction.Command(WhitelistAction.SPACE))),
        )
        val def = LayoutKeyDefBuilder.build(layout, emptyMap())[0][0]
        assertEquals(KeyView.button_space, def.appearance.viewId)
        assertEquals(KeyDef.Appearance.Border.Special, def.appearance.border)
    }

    @Test
    fun placeholderSubstitutesLabelOnly() {
        val layout = layoutOf(
            listOf(
                key(
                    "a",
                    label = "\$schemaName 键",
                    tap = SkinAction.Text("\$schemaName"),
                ),
            ),
        )
        val def = LayoutKeyDefBuilder.build(
            layout, mapOf("schemaName" to "万象"),
        )[0][0]
        assertEquals("万象 键", (def.appearance as KeyDef.Appearance.Text).displayText)
        // 动作 value 是字面内容：不替换（B2 施工裁量，替换只及标签）
        val press = def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single()
        assertEquals(
            "\$schemaName",
            (press.action as KeyboardAction.KeySequenceAction).sequence,
        )
        assertNotNull(def.id)
    }

    // ---- 2026-10-27 并入项：单键横竖双宽 ----

    @Test
    fun landscapeWeightsSelectedOnlyInLandscapeBuild() {
        val layout = layoutOf(
            listOf(
                key("a", weight = 1.0, landscapeWeight = 3.0),
                key("b", weight = 3.0),
                key("c", weight = 4.0, landscapeWeight = 1.0),
            ),
        )
        val portrait = LayoutKeyDefBuilder.build(layout, emptyMap())
        assertEquals(
            listOf(0.125f, 0.375f, 0.5f),
            portrait[0].map { it.appearance.percentWidth },
        )
        // 横屏：a=3（指定），b=3（跟竖屏），c=1（指定），和 7
        val landscape = LayoutKeyDefBuilder.build(layout, emptyMap(), landscape = true)
        val widths = landscape[0].map { it.appearance.percentWidth }
        assertEquals(3f / 7f, widths[0], 0.0001f)
        assertEquals(3f / 7f, widths[1], 0.0001f)
        assertEquals(1f / 7f, widths[2], 0.0001f)
        // 模型原始权重不受构建影响
        assertEquals(1.0, layout.rows[0][0].widthWeight, 0.0)
        assertEquals(3.0, layout.rows[0][0].landscapeWidthWeight)
    }

    // ---- 2026-10-29 item-1 D4：行宽不足补空白键 ----

    @Test
    fun shortRowPaddedWithBlankKeyInsteadOfStretching() {
        // 行和 3 vs 2：分母取 3；短行键宽 1/3（旧版会拉伸到 1/2），
        // 行末补一个 1/3 宽的空白键。
        val layout = layoutOf(
            listOf(key("a"), key("b"), key("c")),
            listOf(key("d"), key("e")),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        assertEquals(listOf(1f / 3f, 1f / 3f, 1f / 3f), built[0].map { it.appearance.percentWidth })
        assertEquals(3, built[1].size)
        // 补位键宽 = 1 - 两实键宽和（浮点末位与 1/3 有 1e-7 级差，用容差比）
        assertEquals(1f / 3f, built[1][0].appearance.percentWidth, 1e-4f)
        assertEquals(1f / 3f, built[1][1].appearance.percentWidth, 1e-4f)
        assertEquals(1f / 3f, built[1][2].appearance.percentWidth, 1e-4f)
        val blank = built[1][2]
        assertNull(blank.id)
        assertTrue(blank.behaviors.isEmpty())
        val appearance = blank.appearance as KeyDef.Appearance.Text
        assertEquals("", appearance.displayText)
        assertEquals(KeyDef.Appearance.Variant.None, appearance.variant)
        assertEquals(KeyDef.Appearance.Border.Off, appearance.border)
        assertEquals(1, built[1].count { it.id == null })
    }

    @Test
    fun equalRowsGetNoBlankKeys() {
        val layout = layoutOf(
            listOf(key("a"), key("b")),
            listOf(key("c"), key("d")),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        assertEquals(2, built[0].size)
        assertEquals(2, built[1].size)
        assertTrue(built.flatten().all { it.id != null })
        assertEquals(listOf(0.5f, 0.5f), built[0].map { it.appearance.percentWidth })
    }

    @Test
    fun emptyRowLeftUntouched() {
        val layout = layoutOf(
            listOf(key("a"), key("b")),
            emptyList(),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap())
        assertTrue(built[1].isEmpty())
        assertEquals(2, built[0].size)
    }

    @Test
    fun landscapeShortRowPaddedByLandscapeWeights() {
        // 横屏权重：a=3、b=1（和 4）vs c=1、d=1（和 2）：分母取 4；
        // 短行键宽 1/4，补 1/2 空白键。
        val layout = layoutOf(
            listOf(
                key("a", weight = 1.0, landscapeWeight = 3.0),
                key("b", weight = 1.0, landscapeWeight = 1.0),
            ),
            listOf(
                key("c", weight = 1.0, landscapeWeight = 1.0),
                key("d", weight = 1.0, landscapeWeight = 1.0),
            ),
        )
        val built = LayoutKeyDefBuilder.build(layout, emptyMap(), landscape = true)
        assertEquals(listOf(0.75f, 0.25f), built[0].map { it.appearance.percentWidth })
        assertEquals(3, built[1].size)
        assertEquals(0.5f, built[1][2].appearance.percentWidth, 1e-4f)
        assertNull(built[1][2].id)
    }

    // ---- 2026-10-27 并入项：布局键样式原始引用随结构携带 ----

    @Test
    fun layoutKeyStyleRefsCarriedOnKeyDef() {
        val layout = layoutOf(
            listOf(
                key(
                    "a", styleRef = "gold",
                    styleOverrides = mapOf(
                        com.jobeen.ime.skin.model.KeyStyleRole.FACE to "#FF112233",
                    ),
                ),
            ),
        )
        val def = LayoutKeyDefBuilder.build(layout, emptyMap())[0][0]
        assertEquals("gold", def.skinStyleRef)
        assertEquals(
            mapOf("face" to "#FF112233"),
            def.skinStyleOverrides,
        )
        // 无样式键不携带
        val plain = LayoutKeyDefBuilder.build(layoutOf(listOf(key("b"))), emptyMap())[0][0]
        assertNull(plain.skinStyleRef)
        assertNull(plain.skinStyleOverrides)
    }
}
