package com.jobeen.ime.skin.layout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 布局校验器（B3 详设 §2.5 处置矩阵逐项）。 */
class LayoutValidatorTest {

    private val layoutId = "lay-test"

    private fun textTap(value: String = "q"): JsonObject = buildJsonObject {
        put("type", "text")
        put("value", value)
    }

    private fun key(
        stableId: String? = "1",
        label: String? = "q",
        weight: Double? = null,
        rowSpan: Int? = null,
        alignSpanEnd: Boolean? = null,
        tap: JsonElement? = textTap(),
        extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ): JsonObject = buildJsonObject {
        if (stableId != null) put("stableId", stableId)
        if (label != null) put("label", label)
        if (weight != null) put("widthWeight", weight)
        if (rowSpan != null) put("rowSpan", rowSpan)
        if (alignSpanEnd != null) put("alignSpanEnd", alignSpanEnd)
        if (tap != null) put("tap", tap)
        extra?.invoke(this)
    }

    private fun row(vararg keys: JsonElement): JsonObject = buildJsonObject {
        put("keys", JsonArray(keys.toList()))
    }

    private fun layout(
        rows: JsonArray,
        id: String? = layoutId,
        name: String? = "测试布局",
        formatVersion: Int? = 3,
    ): JsonObject = buildJsonObject {
        if (formatVersion != null) put("formatVersion", formatVersion)
        put("meta", buildJsonObject {
            if (id != null) put("id", id)
            if (name != null) put("name", name)
        })
        put("rows", rows)
    }

    private fun rowsOf(vararg rows: JsonObject): JsonArray = JsonArray(rows.toList())

    private fun valid(tree: JsonElement?): LayoutModel {
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue("expected Valid, got $result", result is LayoutValidation.Valid)
        return (result as LayoutValidation.Valid).layout
    }

    private fun invalid(tree: JsonElement?): List<LayoutIssue> {
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue("expected Invalid, got $result", result is LayoutValidation.Invalid)
        return (result as LayoutValidation.Invalid).issues
    }

    private fun List<LayoutIssue>.reasonsAt(path: String): List<String> =
        filter { it.path == path }.map { it.reason }

    // ---------- 有效基线 ----------

    @Test
    fun minimalLayoutValidWithDefaults() {
        val model = valid(layout(rowsOf(row(key()))))
        assertEquals(layoutId, model.layoutId)
        assertEquals("测试布局", model.name)
        val k = model.rows.single().single()
        assertEquals("1", k.stableId)
        assertEquals("q", k.label)
        assertEquals(1.0, k.widthWeight, 0.0)
        assertEquals(1, k.rowSpan)
        assertEquals(false, k.alignSpanEnd)
        assertNotNull(k.tap)
        assertNull(k.swipeUp)
        assertNull(k.longPress)
        assertEquals("$layoutId:K-1", model.logicalId(k))
    }

    @Test
    fun unknownFieldsIgnored() {
        val tree = layout(rowsOf(row(key(extra = { put("futureNode", buildJsonObject { put("x", 1) }) }))))
        valid(tree)
    }

    @Test
    fun exactly64KeysValid() {
        val rows = (0 until 5).map {
            row(*(0 until 12).map { i -> key(stableId = "r$it-$i") }.toTypedArray())
        } + row(*(0 until 4).map { i -> key(stableId = "last-$i") }.toTypedArray())
        val model = valid(layout(JsonArray(rows)))
        assertEquals(64, model.keyCount)
    }

    // ---------- 结构类：整布局无效 ----------

    @Test
    fun nullAndNonObjectInvalid() {
        assertEquals("unparseable", invalid(null).single().reason)
        assertEquals("not_an_object", invalid(JsonPrimitive("x")).single().reason)
    }

    @Test
    fun formatVersionMustBe3() {
        assertEquals(
            "format_version_invalid",
            invalid(layout(rowsOf(row(key())), formatVersion = 2))
                .reasonsAt("formatVersion").single(),
        )
        assertEquals(
            "format_version_invalid",
            invalid(layout(rowsOf(row(key())), formatVersion = null))
                .reasonsAt("formatVersion").single(),
        )
    }

    @Test
    fun metaIdMustMatchExpected() {
        assertEquals(
            "meta_id_mismatch",
            invalid(layout(rowsOf(row(key())), id = "other")).reasonsAt("meta.id").single(),
        )
        assertEquals(
            "meta_id_missing",
            invalid(layout(rowsOf(row(key())), id = null)).reasonsAt("meta.id").single(),
        )
    }

    @Test
    fun rowsLimits() {
        assertEquals(
            "rows_empty",
            invalid(layout(JsonArray(emptyList()))).reasonsAt("rows").single(),
        )
        val nineRows = JsonArray((0 until 9).map { row(key(stableId = "k$it")) })
        assertEquals(
            "too_many_rows",
            invalid(layout(nineRows)).reasonsAt("rows").single(),
        )
        val thirteen = row(*(0 until 13).map { key(stableId = "k$it") }.toTypedArray())
        assertEquals(
            "too_many_keys_in_row",
            invalid(layout(rowsOf(thirteen))).reasonsAt("rows[0].keys").single(),
        )
        val emptyRow = buildJsonObject { put("keys", JsonArray(emptyList())) }
        assertEquals(
            "keys_empty",
            invalid(layout(rowsOf(emptyRow))).reasonsAt("rows[0].keys").single(),
        )
    }

    @Test
    fun totalKeysLimit() {
        // 6 行 × 11 键 = 66 > 64
        val rows = JsonArray((0 until 6).map { r ->
            row(*(0 until 11).map { i -> key(stableId = "$r-$i") }.toTypedArray())
        })
        assertEquals(
            "too_many_keys",
            invalid(layout(rows)).reasonsAt("rows").single(),
        )
    }

    @Test
    fun stableIdRequiredAndUnique() {
        assertEquals(
            "stable_id_missing",
            invalid(layout(rowsOf(row(key(stableId = null)))))
                .reasonsAt("rows[0].keys[0].stableId").single(),
        )
        val dup = layout(rowsOf(row(key(stableId = "1"), key(stableId = "1", label = "w"))))
        assertEquals(
            "stable_id_duplicate",
            invalid(dup).reasonsAt("rows[0].keys[1].stableId").single(),
        )
    }

    @Test
    fun weightDomain() {
        for (bad in listOf(0.04, 20.01, 0.0, -1.0)) {
            val issues = invalid(layout(rowsOf(row(key(weight = bad)))))
            assertEquals(
                "weight $bad", "width_weight_invalid",
                issues.reasonsAt("rows[0].keys[0].widthWeight").single(),
            )
        }
        // 字符串数值不算数（严格读取）：widthWeight 写成字符串 "1.0"
        val stringWeight = layout(rowsOf(row(key(extra = { put("widthWeight", "1.0") }))))
        assertEquals(
            "width_weight_invalid",
            invalid(stringWeight).reasonsAt("rows[0].keys[0].widthWeight").single(),
        )
        valid(layout(rowsOf(row(key(weight = 0.05), key(stableId = "2", weight = 20.0)))))
    }

    @Test
    fun rowWeightSumBounds() {
        // 11 键 × 20 = 220 > 200 → 整布局无效
        val over = row(*(0 until 11).map { key(stableId = "k$it", weight = 20.0) }.toTypedArray())
        assertEquals(
            "row_weight_sum_invalid",
            invalid(layout(rowsOf(over))).reasonsAt("rows[0]").single(),
        )
        // 10 键 × 20 = 200 恰在边界 → 有效
        val atBound = row(*(0 until 10).map { key(stableId = "k$it", weight = 20.0) }.toTypedArray())
        valid(layout(rowsOf(atBound)))
        // 单键 0.05 恰为下界 → 有效
        valid(layout(rowsOf(row(key(weight = 0.05)))))
    }

    @Test
    fun rowSpanDomain() {
        // 只有 1 行时 rowSpan=2 超出剩余行数
        assertEquals(
            "row_span_invalid",
            invalid(layout(rowsOf(row(key(rowSpan = 2)))))
                .reasonsAt("rows[0].keys[0].rowSpan").single(),
        )
        assertEquals(
            "row_span_invalid",
            invalid(layout(rowsOf(row(key(rowSpan = 0)))))
                .reasonsAt("rows[0].keys[0].rowSpan").single(),
        )
        // 两行布局第一行 rowSpan=2 合法
        val ok = layout(rowsOf(row(key(rowSpan = 2)), row(key(stableId = "2"))))
        assertEquals(2, valid(ok).rows[0][0].rowSpan)
    }

    @Test
    fun spanConflictSameSideOnly() {
        // 同侧（都贴左）区间重叠 → 无效
        val conflict = layout(
            rowsOf(
                row(key(stableId = "a", rowSpan = 2), key(stableId = "b", rowSpan = 2, label = "x")),
                row(key(stableId = "c")),
                row(key(stableId = "d")),
            ),
        )
        assertEquals(
            "span_conflict",
            invalid(conflict).reasonsAt("rows[0].keys[1].rowSpan").single(),
        )
        // 异侧（一个贴右）不冲突
        val ok = layout(
            rowsOf(
                row(
                    key(stableId = "a", rowSpan = 2),
                    key(stableId = "b", rowSpan = 2, label = "x", alignSpanEnd = true),
                ),
                row(key(stableId = "c")),
                row(key(stableId = "d")),
            ),
        )
        valid(ok)
        // 同侧不重叠（先后相接：A 占行 0-1、B 占行 2-3）不冲突
        val sequential = layout(
            rowsOf(
                row(key(stableId = "a", rowSpan = 2)),
                row(key(stableId = "c")),
                row(key(stableId = "b", rowSpan = 2, label = "x")),
                row(key(stableId = "d")),
            ),
        )
        valid(sequential)
    }

    @Test
    fun tapRequiredAndMustBeValid() {
        assertEquals(
            "tap_missing",
            invalid(layout(rowsOf(row(key(tap = null)))))
                .reasonsAt("rows[0].keys[0].tap").single(),
        )
        val badCommand = buildJsonObject {
            put("type", "command")
            put("action", "not_a_command")
        }
        val issues = invalid(layout(rowsOf(row(key(tap = badCommand)))))
        assertEquals(
            "tap_invalid:unknown_action",
            issues.reasonsAt("rows[0].keys[0].tap").single(),
        )
        val sequenceTap = buildJsonObject { put("type", "sequence") }
        assertEquals(
            "tap_sequence_not_allowed",
            invalid(layout(rowsOf(row(key(tap = sequenceTap)))))
                .reasonsAt("rows[0].keys[0].tap").single(),
        )
    }

    // ---------- 字段级：回退 / 丢槽，布局仍有效 ----------

    @Test
    fun metaNameFallbackToId() {
        val result = LayoutValidator.validate(layout(rowsOf(row(key())), name = null), layoutId)
        assertTrue(result is LayoutValidation.Valid)
        val valid = result as LayoutValidation.Valid
        assertEquals(layoutId, valid.layout.name)
        assertEquals("name_missing", valid.issues.reasonsAt("meta.name").single())
        assertEquals(
            LayoutDisposition.FIELD_FALLBACK,
            valid.issues.single { it.path == "meta.name" }.disposition,
        )
    }

    @Test
    fun labelFallbackChain() {
        // label 缺失 → 回退 tap 文本值
        val fromTap = layout(rowsOf(row(key(label = null, tap = textTap("z")))))
        val result = LayoutValidator.validate(fromTap, layoutId)
        assertTrue(result is LayoutValidation.Valid)
        val validResult = result as LayoutValidation.Valid
        assertEquals("z", validResult.layout.rows[0][0].label)
        assertEquals(
            "label_missing",
            validResult.issues.reasonsAt("rows[0].keys[0].label").single(),
        )
        // label 缺失且 tap 为命令 → 空串
        val enterTap = buildJsonObject {
            put("type", "command")
            put("action", "enter")
        }
        val noText = valid(layout(rowsOf(row(key(label = null, tap = enterTap)))))
        assertEquals("", noText.rows[0][0].label)
    }

    @Test
    fun invalidOptionalSlotsDroppedOnly() {
        val badSwipe = buildJsonObject {
            put("type", "command")
            put("action", "bogus")
        }
        val tree = layout(
            rowsOf(
                row(
                    key(extra = {
                        put("swipeUp", badSwipe)
                        put("swipeDown", textTap("!"))
                    }),
                ),
            ),
        )
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue(result is LayoutValidation.Valid)
        val validResult = result as LayoutValidation.Valid
        val k = validResult.layout.rows[0][0]
        assertNull(k.swipeUp)
        assertNotNull(k.swipeDown)
        val issue = validResult.issues.single { it.path == "rows[0].keys[0].swipeUp" }
        assertEquals(LayoutDisposition.SLOT_DROPPED, issue.disposition)
        assertEquals("unknown_action", issue.reason)
    }

    @Test
    fun longPressElementsFilteredIndividually() {
        val bad = buildJsonObject {
            put("type", "command")
            put("action", "bogus")
        }
        val tree = layout(
            rowsOf(
                row(
                    key(extra = {
                        put(
                            "longPress",
                            buildJsonArray {
                                add(textTap("1"))
                                add(bad)
                                add(textTap("2"))
                            },
                        )
                    }),
                ),
            ),
        )
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue(result is LayoutValidation.Valid)
        val k = (result as LayoutValidation.Valid).layout.rows[0][0]
        assertEquals(2, k.longPress?.size)
    }

    @Test
    fun longPressSequenceElementDropped() {
        val tree = layout(
            rowsOf(
                row(
                    key(extra = {
                        put(
                            "longPress",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "sequence") })
                            },
                        )
                    }),
                ),
            ),
        )
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue(result is LayoutValidation.Valid)
        val validResult = result as LayoutValidation.Valid
        assertNull(validResult.layout.rows[0][0].longPress)
        assertEquals(
            "sequence_placeholder",
            validResult.issues.reasonsAt("rows[0].keys[0].longPress").single(),
        )
    }

    @Test
    fun voiceInputOnlyAllowedInLongPress() {
        val voice = buildJsonObject {
            put("type", "command")
            put("action", "voice_input")
        }
        // voice_input 作 tap → BAD_SLOT → 整布局无效（tap 升格口径）
        val issues = invalid(layout(rowsOf(row(key(tap = voice)))))
        assertEquals(
            "tap_invalid:bad_slot",
            issues.reasonsAt("rows[0].keys[0].tap").single(),
        )
    }

    @Test
    fun switchLayoutTargets() {
        fun cmdTap(action: String, value: String?): JsonObject = buildJsonObject {
            put("type", "command")
            put("action", action)
            if (value != null) put("value", value)
        }
        // 出厂目标合法
        valid(layout(rowsOf(row(key(tap = cmdTap("switch_layout", "T9"))))))
        // 自创布局目标合法（B3）
        valid(layout(rowsOf(row(key(tap = cmdTap("switch_layout", "custom:other-layout"))))))
        // 未知目标无效
        val issues = invalid(layout(rowsOf(row(key(tap = cmdTap("switch_layout", "Dvorak"))))))
        assertEquals(
            "tap_invalid:value_invalid",
            issues.reasonsAt("rows[0].keys[0].tap").single(),
        )
        // custom: 后为空无效
        val blankIssues = invalid(layout(rowsOf(row(key(tap = cmdTap("switch_layout", "custom:"))))))
        assertEquals(
            "tap_invalid:value_invalid",
            blankIssues.reasonsAt("rows[0].keys[0].tap").single(),
        )
    }

    @Test
    fun deepTreeInvalid() {
        var deep: JsonElement = JsonPrimitive(1)
        repeat(12) { deep = buildJsonObject { put("n", deep) } }
        val tree = layout(rowsOf(row(key(extra = { put("future", deep) }))))
        assertEquals(
            "json_depth_exceeded",
            invalid(tree).reasonsAt("").single(),
        )
    }
}
