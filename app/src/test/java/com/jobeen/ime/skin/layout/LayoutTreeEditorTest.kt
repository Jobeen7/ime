package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.SkinJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 布局树编辑操作（B3 详设 §4.2）：身份稳定性与未知节点保真。 */
class LayoutTreeEditorTest {

    private fun tree(json: String): JsonObject = SkinJson.parseObject(json)!!

    private val sample = tree(
        """{"formatVersion":3,"meta":{"id":"lay-t","name":"T"},"rows":[""" +
            """{"keys":[{"stableId":"1","label":"q","tap":{"type":"text","value":"q"}},""" +
            """{"stableId":"2","label":"w","tap":{"type":"text","value":"w"}}],"rowFuture":1},""" +
            """{"keys":[{"stableId":"3","label":"a","tap":{"type":"text","value":"a"}}]}]}""",
    )

    @Test
    fun readsAndCounts() {
        assertEquals(2, LayoutTreeEditor.rowsOf(sample).size)
        assertEquals(3, LayoutTreeEditor.keyCount(sample))
        assertEquals("lay-t", LayoutTreeEditor.layoutIdOf(sample))
        assertEquals("T", LayoutTreeEditor.nameOf(sample))
        assertEquals("4", LayoutTreeEditor.nextStableId(sample))
    }

    @Test
    fun setNamePreservesUnknownNodes() {
        val renamed = LayoutTreeEditor.setName(sample, "新名")
        assertEquals("新名", LayoutTreeEditor.nameOf(renamed))
        // 行级未知节点仍在
        val row0 = LayoutTreeEditor.rowsOf(renamed)[0]
        assertEquals(1, (row0["rowFuture"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun addRemoveKeyKeepsOtherStableIds() {
        val grown = LayoutTreeEditor.addKey(
            sample, 1, LayoutTreeEditor.letterKey("4", "s"),
        )
        assertEquals(4, LayoutTreeEditor.keyCount(grown))
        val shrunk = LayoutTreeEditor.removeKey(grown, 0, 0)
        val ids = LayoutTreeEditor.rowsOf(shrunk)
            .flatMap { LayoutTreeEditor.keysOf(it) }
            .map { (it["stableId"] as JsonPrimitive).content }
        // 删 q 后其余键 id 不变（身份与坐标分离）
        assertEquals(listOf("2", "3", "4"), ids)
    }

    @Test
    fun moveKeyWithinAndAcrossRows() {
        val within = LayoutTreeEditor.moveKey(sample, 0, 0, 0, 1)
        val row0 = LayoutTreeEditor.keysOf(LayoutTreeEditor.rowsOf(within)[0])
        assertEquals(
            listOf("2", "1"),
            row0.map { (it["stableId"] as JsonPrimitive).content },
        )
        val across = LayoutTreeEditor.moveKey(sample, 0, 1, 1, 0)
        assertEquals(1, LayoutTreeEditor.keysOf(LayoutTreeEditor.rowsOf(across)[0]).size)
        assertEquals(
            "2",
            (LayoutTreeEditor.keysOf(LayoutTreeEditor.rowsOf(across)[1])[0]["stableId"]
                as JsonPrimitive).content,
        )
    }

    @Test
    fun addRemoveRowAndOutOfRangeNoOps() {
        val grown = LayoutTreeEditor.addRow(sample)
        assertEquals(3, LayoutTreeEditor.rowsOf(grown).size)
        assertEquals(sample, LayoutTreeEditor.removeRow(sample, 9))
        assertEquals(sample, LayoutTreeEditor.removeKey(sample, 0, 42))
        assertEquals(sample, LayoutTreeEditor.addKey(sample, 7, JsonObject(emptyMap())))
    }

    @Test
    fun setKeyNodeReplacesExactlyOne() {
        val node = LayoutTreeEditor.letterKey("2", "W")
        val edited = LayoutTreeEditor.setKeyNode(sample, 0, 1, node)
        val keys = LayoutTreeEditor.keysOf(LayoutTreeEditor.rowsOf(edited)[0])
        assertEquals("W", (keys[1]["label"] as JsonPrimitive).content)
        assertEquals("q", (keys[0]["label"] as JsonPrimitive).content)
    }

    @Test
    fun blankStarterIsValidAndFunctional() {
        val starter = LayoutTreeEditor.blankStarter("lay-new", "未命名布局")
        val validation = LayoutValidator.validate(starter, "lay-new")
        assertTrue("starter must validate: $validation", validation is LayoutValidation.Valid)
        val model = (validation as LayoutValidation.Valid).layout
        assertEquals(4, model.rows.size)
        assertEquals(31, model.keyCount)
        // stableId 全局唯一
        val ids = model.rows.flatten().map { it.stableId }
        assertEquals(ids.size, ids.toSet().size)
    }
}
