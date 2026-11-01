package com.jobeen.ime.skin.layout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宽度分数写法（审计 A 项）："a/b" 与 {"percentage": n} 转权重，行和守恒。 */
class LayoutFractionWeightTest {

    private fun textTap(value: String = "q"): JsonObject = buildJsonObject {
        put("type", "text")
        put("value", value)
    }

    private fun key(stableId: String, weightJson: String): JsonObject = buildJsonObject {
        put("stableId", stableId)
        put("label", stableId)
        put("widthWeight", kotlinx.serialization.json.Json.parseToJsonElement(weightJson))
        put("tap", textTap())
    }

    private fun layout(vararg rows: JsonArray): JsonObject = buildJsonObject {
        put("formatVersion", 3)
        put("meta", buildJsonObject {
            put("id", "lay-frac")
            put("name", "分数布局")
        })
        put("rows", JsonArray(rows.map { row ->
            buildJsonObject { put("keys", row) }
        }))
    }

    @Test
    fun fractionAndPercentageWeightsParse() {
        val tree = layout(
            JsonArray(listOf(
                key("a", """"1/2""""),
                key("b", """{"percentage": 0.25}"""),
                key("c", "0.25"),
            )),
        )
        val result = LayoutValidator.validate(tree, "lay-frac")
        assertTrue(result is LayoutValidation.Valid)
        val model = (result as LayoutValidation.Valid).layout
        assertEquals(0.5, model.rows[0][0].widthWeight, 1e-9)
        assertEquals(0.25, model.rows[0][1].widthWeight, 1e-9)
        assertEquals(0.25, model.rows[0][2].widthWeight, 1e-9)
        // 构建期行和恒为 1.0（行和不变量）。
        val built = LayoutKeyDefBuilder.build(model, emptyMap())
        val sum = built[0].sumOf { it.appearance.percentWidth.toDouble() }
        assertEquals(1.0, sum, 1e-4)
    }

    @Test
    fun invalidFractionInvalidatesLayout() {
        val tree = layout(
            JsonArray(listOf(key("a", """"1/0""""))),
        )
        val result = LayoutValidator.validate(tree, "lay-frac")
        assertTrue(result is LayoutValidation.Invalid)
    }

    @Test
    fun fractionOutOfRangeInvalidatesLayout() {
        val tree = layout(
            JsonArray(listOf(key("a", """"30/1""""))),
        )
        val result = LayoutValidator.validate(tree, "lay-frac")
        assertTrue(result is LayoutValidation.Invalid)
    }
}
