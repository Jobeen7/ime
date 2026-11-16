// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.layout

import com.jobeen.ime.skin.model.SkinJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LayoutEditorModel 操作测试（2026-11-16 松烟可取项 3a）。 */
class LayoutEditorModelTest {

    private fun sampleTree(): JsonObject = Json.parseToJsonElement(
        """
        {
          "formatVersion": 3,
          "meta": {"id": "test", "name": "测试"},
          "customNote": "未知节点，操作后须保留",
          "rows": [
            {"keys": [
              {"stableId": "a1", "label": "Q", "widthWeight": 1.0, "tap": {"type": "text", "value": "q"}},
              {"stableId": "a2", "label": "W", "widthWeight": 1.0, "tap": {"type": "text", "value": "w"}, "foo": 1}
            ]},
            {"keys": [
              {"stableId": "b1", "label": "A", "widthWeight": 2.0, "tap": {"type": "text", "value": "a"}}
            ]}
          ]
        }
        """.trimIndent(),
    ).jsonObject

    private fun labelOf(tree: JsonObject, stableId: String): String? =
        (LayoutEditorModel.keyByStableId(tree, stableId)?.get("label") as? kotlinx.serialization.json.JsonPrimitive)?.content

    @Test
    fun rowsAndFindKey() {
        val tree = sampleTree()
        val rows = LayoutEditorModel.rows(tree)
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].size)
        assertEquals(1, rows[1].size)
        assertEquals(LayoutEditorModel.KeyLocation(0, 1), LayoutEditorModel.findKey(tree, "a2"))
        assertNull(LayoutEditorModel.findKey(tree, "missing"))
    }

    @Test
    fun setLabel_keepsOtherFieldsAndUnknownNodes() {
        val tree = LayoutEditorModel.setLabel(sampleTree(), "a2", "E")!!
        assertEquals("E", labelOf(tree, "a2"))
        // tap 与未知字段原样保留。
        val key = LayoutEditorModel.keyByStableId(tree, "a2")!!
        assertEquals("w", key["tap"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals(1, key["foo"]!!.jsonPrimitive.content.toInt())
        assertEquals("未知节点，操作后须保留", tree["customNote"]!!.jsonPrimitive.content)
    }

    @Test
    fun setWeight_clampsToValidatorRange() {
        val tree = sampleTree()
        val high = LayoutEditorModel.setWeight(tree, "a1", 100.0)!!
        assertEquals(
            LayoutValidator.MAX_WEIGHT,
            (LayoutEditorModel.keyByStableId(high, "a1")!!["widthWeight"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(),
            0.0,
        )
        val low = LayoutEditorModel.setWeight(tree, "a1", 0.001)!!
        assertEquals(
            LayoutValidator.MIN_WEIGHT,
            (LayoutEditorModel.keyByStableId(low, "a1")!!["widthWeight"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(),
            0.0,
        )
        assertNull(LayoutEditorModel.setWeight(tree, "a1", Double.NaN))
        assertNull(LayoutEditorModel.setWeight(tree, "missing", 1.0))
    }

    @Test
    fun setTapAndLongPress() {
        val tree = sampleTree()
        val tapped = LayoutEditorModel.setTap(
            tree, "a1", LayoutEditorModel.actionCommand("enter"),
        )!!
        val tap = LayoutEditorModel.keyByStableId(tapped, "a1")!!["tap"]!!.jsonObject
        assertEquals("command", tap["type"]!!.jsonPrimitive.content)
        assertEquals("enter", tap["action"]!!.jsonPrimitive.content)
        val noTap = LayoutEditorModel.setTap(tree, "a1", null)!!
        assertNull(LayoutEditorModel.keyByStableId(noTap, "a1")!!["tap"])
        val pressed = LayoutEditorModel.setLongPress(
            tree, "a1", listOf(
                LayoutEditorModel.actionText("1"),
                LayoutEditorModel.actionSymbol("!"),
            ),
        )!!
        assertEquals(
            2,
            LayoutEditorModel.keyByStableId(pressed, "a1")!!["longPress"]!!.jsonArray.size,
        )
        val cleared = LayoutEditorModel.setLongPress(tree, "a1", emptyList())!!
        assertNull(LayoutEditorModel.keyByStableId(cleared, "a1")!!["longPress"])
    }

    @Test
    fun addAndRemoveKey() {
        val tree = sampleTree()
        val added = LayoutEditorModel.addKey(tree, 0, "E")!!
        val row = LayoutEditorModel.rows(added)[0]
        assertEquals(3, row.size)
        assertEquals("E", row[2]["label"]!!.jsonPrimitive.content)
        val newId = row[2]["stableId"]!!.jsonPrimitive.content
        assertTrue(newId !in setOf("a1", "a2", "b1"))
        assertEquals("E", row[2]["tap"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        val removed = LayoutEditorModel.removeKey(added, "a2")!!
        assertEquals(2, LayoutEditorModel.rows(removed)[0].size)
        assertNull(LayoutEditorModel.findKey(removed, "a2"))
        assertNull(LayoutEditorModel.addKey(tree, 5, "X"))
    }

    @Test
    fun moveKey() {
        val tree = sampleTree()
        // 行内左移：a2 从下标 1 到 0。
        val left = LayoutEditorModel.moveKey(tree, "a2", LayoutEditorModel.MoveDirection.LEFT)!!
        assertEquals("a2", LayoutEditorModel.rows(left)[0][0]["stableId"]!!.jsonPrimitive.content)
        assertNull(LayoutEditorModel.moveKey(tree, "a1", LayoutEditorModel.MoveDirection.LEFT))
        // 行内右移：a1 从下标 0 到 1。
        val right = LayoutEditorModel.moveKey(tree, "a1", LayoutEditorModel.MoveDirection.RIGHT)!!
        assertEquals("a1", LayoutEditorModel.rows(right)[0][1]["stableId"]!!.jsonPrimitive.content)
        // 跨行上移：b1 从行 1 移到行 0 尾。
        val up = LayoutEditorModel.moveKey(tree, "b1", LayoutEditorModel.MoveDirection.UP)!!
        assertEquals(3, LayoutEditorModel.rows(up)[0].size)
        assertEquals(0, LayoutEditorModel.rows(up)[1].size)
        // 跨行下移：a1 从行 0 移到行 1 尾。
        val down = LayoutEditorModel.moveKey(tree, "a1", LayoutEditorModel.MoveDirection.DOWN)!!
        assertEquals(1, LayoutEditorModel.rows(down)[0].size)
        assertEquals(2, LayoutEditorModel.rows(down)[1].size)
        assertNull(LayoutEditorModel.moveKey(tree, "b1", LayoutEditorModel.MoveDirection.DOWN))
    }

    @Test
    fun addAndRemoveRow() {
        val tree = sampleTree()
        val added = LayoutEditorModel.addRow(tree)!!
        assertEquals(3, LayoutEditorModel.rows(added).size)
        assertEquals(1, LayoutEditorModel.rows(added)[2].size)
        val removed = LayoutEditorModel.removeRow(added, 2)!!
        assertEquals(2, LayoutEditorModel.rows(removed).size)
        val single = LayoutEditorModel.removeRow(tree, 1)!!
        assertEquals(1, LayoutEditorModel.rows(single).size)
        assertNull(LayoutEditorModel.removeRow(single, 0))
    }

    @Test
    fun toPrettyJson_roundTrips() {
        val tree = LayoutEditorModel.setLabel(sampleTree(), "a1", "Z")!!
        val text = LayoutEditorModel.toPrettyJson(tree)
        val reparsed = SkinJson.parseObject(text)!!
        assertEquals("Z", labelOf(reparsed, "a1"))
        assertEquals(2, LayoutEditorModel.rows(reparsed).size)
    }

    @Test
    fun newStableId_avoidsExisting() {
        val existing = setOf("a1", "a2", "b1")
        repeat(20) {
            val id = LayoutEditorModel.newStableId(existing)
            assertTrue(id !in existing)
            assertEquals(8, id.length)
        }
    }
}
