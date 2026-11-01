// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 二级分类符号（顶层 section）：解析透传、非法项丢弃不拒包。 */
class SubClassifiedSymbolsValidatorTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(
            tree, 157, fallbacks, assetKind = { id ->
                when (id) {
                    "bg_main" -> AssetKind.IMAGE
                    else -> null
                }
            },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private val base = """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
        "stylesDark": {"keyBackground": "#FF222222"},
        "appearance": {"background": {"type": "asset", "asset": "bg_main"}}}"""

    private fun withSection(section: String): String =
        base.dropLast(1) + ", $section}"

    @Test
    fun sectionParsesAndDropsInvalidParents() {
        val valid = validate(
            withSection(
                """"subClassifiedSymbols": {"parents": [
                    {"label": "数学", "subCategories": [
                        {"label": "运算", "symbols": ["+", "-"]},
                        {"label": "比较", "symbols": ["<"], "badge": "新"}
                    ]},
                    {"label": "空类", "subCategories": []}
                ]}""",
            ),
        )
        val symbols = valid.skin.subClassifiedSymbols!!
        assertEquals(listOf("数学"), symbols.parents.map { it.label })
        assertEquals("3", symbols.parents[0].badgeText)
        assertTrue(
            valid.issues.any { it.path == "subClassifiedSymbols" },
        )
    }

    @Test
    fun sectionAbsentIsNullAndWrongTypeIsIssue() {
        val absent = validate(base)
        assertNull(absent.skin.subClassifiedSymbols)
        val wrongType = validate(withSection(""""subClassifiedSymbols": "数学""""))
        assertNull(wrongType.skin.subClassifiedSymbols)
        assertTrue(
            wrongType.issues.any { it.path == "subClassifiedSymbols" },
        )
    }
}
