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
            .flatten().map { it.id }.toSet()
        val after = LayoutKeyDefBuilder.build(rearranged, emptyMap())
            .flatten().map { it.id }.toSet()
        assertEquals(setOf("lay-1:K-1", "lay-1:K-2", "lay-1:K-3"), before)
        assertEquals(before + "lay-1:K-9", after)
        // 删除键 2：其 ID 消失，其余不变
        val deleted = layoutOf(listOf(key("1")), listOf(key("3")))
        val afterDelete = LayoutKeyDefBuilder.build(deleted, emptyMap())
            .flatten().map { it.id }.toSet()
        assertEquals(setOf("lay-1:K-1", "lay-1:K-3"), afterDelete)
    }

    @Test
    fun sampleLayoutsBuild() {
        // 11/14/17/18 键样例（十六.3）：构建成功、ID 全唯一且带命名空间前缀
        for (count in listOf(11, 14, 17, 18)) {
            val keys = (1..count).map { key("$it") }
            val rows = keys.chunked(6)
            val built = LayoutKeyDefBuilder.build(layoutOf(*rows.toTypedArray()), emptyMap())
            val ids = built.flatten().map { it.id }
            assertEquals("count=$count", count, ids.size)
            assertEquals("count=$count", count, ids.toSet().size)
            assertTrue(ids.all { it!!.startsWith("lay-1:K-") })
            // 每行 percentWidth 合计 ≈ 1
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
}
