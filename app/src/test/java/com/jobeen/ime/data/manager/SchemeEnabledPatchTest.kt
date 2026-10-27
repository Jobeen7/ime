// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 案-O3：patch 截断语义与解析跳行语义对齐。
 *
 * 每条用例都以解析结果为准：先确认旧文本经
 * [SchemePackageImport.parseSchemaListFromCustomYaml] 确实读到旧项
 * （即旧项是解析可见的、会复活的项），再断言 patch 后解析结果恰好
 * 等于新传入的 ID（旧项不复活、新项不丢失、顺序与传入一致）。
 * patch 本身不跑 Gradle 验证，由协调员统一跑；此处只给用例。
 */
class SchemeEnabledPatchTest {

    private fun roundTrip(old: String, ids: List<String>): List<String> {
        val patched = SchemeEnabled.patchSchemaListYaml(old, ids)
        return SchemePackageImport.parseSchemaListFromCustomYaml(patched)
    }

    private fun patchedText(old: String, ids: List<String>): String {
        return SchemeEnabled.patchSchemaListYaml(old, ids)
    }

    @Test
    fun patch_replacesItemsAcrossBlankLineInsideSequence() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "\n" +
                "    - schema: old2\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1", "n2"), roundTrip(old, listOf("n1", "n2")))
        val patched = patchedText(old, listOf("n1", "n2"))
        assertFalse(patched.contains("old1"))
        assertFalse(patched.contains("old2"))
    }

    @Test
    fun patch_replacesItemsAcrossCommentLineInsideSequence() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "    # note\n" +
                "    - schema: old2\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1"), roundTrip(old, listOf("n1")))
        assertFalse(patchedText(old, listOf("n1")).contains("old2"))
    }

    @Test
    fun patch_replacesItemsAcrossMultipleSkippedLinesInsideSequence() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "\n" +
                "    # note\n" +
                "   \n" +
                "    - schema: old2\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1", "n2"), roundTrip(old, listOf("n1", "n2")))
    }

    @Test
    fun patch_replacesItemsAfterTrailingBlankAndCommentBeforeNextKey() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "\n" +
                "    # note\n" +
                "    - schema: old2\n" +
                "  something: x\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1"), roundTrip(old, listOf("n1")))
        val patched = patchedText(old, listOf("n1"))
        assertFalse(patched.contains("old1"))
        assertFalse(patched.contains("old2"))
        assertTrue(patched.contains("  something: x\n"))
    }

    @Test
    fun patch_replacesItemsAcrossFullwidthSpaceLineInsideSequence() {
        // 行首 4 空格 + 全角空格：解析侧缩进 4 > 列表缩进 2 且不是序列项，
        // 故跳过不终结；旧实现按 trim 后非 `- ` 行截断，old2 会复活。
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "    \u3000\n" +
                "    - schema: old2\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1", "n2"), roundTrip(old, listOf("n1", "n2")))
        assertFalse(patchedText(old, listOf("n1", "n2")).contains("old2"))
    }

    @Test
    fun patch_replacesItemsAcrossTabOnlyBlankLineInsideSequence() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "\t\n" +
                "    - schema: old2\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1"), roundTrip(old, listOf("n1")))
    }

    @Test
    fun patch_replacesItemsAcrossIndentedNonItemLineInsideSequence() {
        // 缩进更深的非项行：解析侧正则取不到项，只跳过不终结。
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "    stray: x\n" +
                "    - schema: old2\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1", "n2"), roundTrip(old, listOf("n1", "n2")))
        assertFalse(patchedText(old, listOf("n1", "n2")).contains("old2"))
    }

    @Test
    fun patch_replacesItemsAcrossBareDashLineInsideSequence() {
        // 单独破折号行：解析侧 `- ` 项正则要求 `- ` 后有非空白内容，
        // 故跳过不终结；旧实现按 trim 后非 `- ` 行截断，old2 会复活。
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "    -\n" +
                "    - schema: old2\n"
        assertEquals(listOf("old1", "old2"), SchemePackageImport.parseSchemaListFromCustomYaml(old))
        assertEquals(listOf("n1"), roundTrip(old, listOf("n1")))
    }

    @Test
    fun patch_replacesMixedBareAndMapItemsAcrossSkippedLines() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - old1\n" +
                "    # note\n" +
                "    - schema: old2\n" +
                "\n" +
                "    - old3\n"
        assertEquals(
            listOf("old1", "old2", "old3"),
            SchemePackageImport.parseSchemaListFromCustomYaml(old),
        )
        assertEquals(listOf("n1", "n2"), roundTrip(old, listOf("n1", "n2")))
    }

    @Test
    fun patch_emptyNewListRemovesAllItemsAcrossSkippedLines() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "\n" +
                "    # note\n" +
                "    - schema: old2\n"
        assertEquals(emptyList<String>(), roundTrip(old, emptyList()))
        assertFalse(patchedText(old, emptyList()).contains("old1"))
        assertFalse(patchedText(old, emptyList()).contains("old2"))
    }

    @Test
    fun patch_contiguousSequenceRegressionAndOrder() {
        // 无跳过行的旧行为回归：前置键保留、新项顺序与传入一致。
        val old =
            "patch:\n" +
                "  something: x\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "    - schema: old2\n"
        assertEquals(listOf("n2", "n1"), roundTrip(old, listOf("n2", "n1")))
        val patched = patchedText(old, listOf("n2", "n1"))
        assertTrue(patched.contains("  something: x\n"))
        assertTrue(patched.indexOf("- schema: n2") < patched.indexOf("- schema: n1"))
        assertFalse(patched.contains("old1"))
    }

    @Test
    fun patch_preservesTopLevelKeyAfterPatchBlock() {
        val old =
            "patch:\n" +
                "  schema_list:\n" +
                "    - schema: old1\n" +
                "\n" +
                "    - schema: old2\n" +
                "other:\n" +
                "  x: 1\n"
        assertEquals(listOf("n1"), roundTrip(old, listOf("n1")))
        val patched = patchedText(old, listOf("n1"))
        assertTrue(patched.contains("other:\n"))
        assertTrue(patched.contains("  x: 1\n"))
        assertFalse(patched.contains("old2"))
    }
}
