package com.jobeen.ime.skin.library

import com.jobeen.ime.skin.layout.LayoutDisposition
import com.jobeen.ime.skin.layout.LayoutIssue
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinIssue
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置皮肤文件编辑器保存裁决（W19-7，纯决策）：
 * 合法才落盘；非法不写文件、回落旧值、页内明示。
 */
class SkinEditorSaveTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    /** 最小可 Valid 的皮肤（浅色板至少一色＋深色段声明，见 SkinValidator 硬要求）。 */
    private val baseValid: SkinValidator.Result.Valid by lazy {
        val tree = SkinJson.parseObject(
            """{"formatVersion":3,"styles":{"keyBackground":"#FFFFFFFF"},"stylesDark":{"keyBackground":"#FF222222"}}""",
        )!!
        val result = SkinValidator.validate(tree, 0, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Valid)
        result as SkinValidator.Result.Valid
    }

    @Test
    fun skinJsonUnparseableBlocks() {
        val outcome = SkinEditorSave.decideSkinJson("{not json") { baseValid }
        assertEquals(EditorSaveOutcome.Block("skin_unparseable"), outcome)
    }

    @Test
    fun skinJsonValidCommitsWithoutIssues() {
        val outcome = SkinEditorSave.decideSkinJson(
            """{"formatVersion":3,"styles":{"keyBackground":"#FFFFFFFF"},"stylesDark":{"keyBackground":"#FF222222"}}""",
        ) { baseValid }
        assertEquals(EditorSaveOutcome.Commit(emptyList()), outcome)
    }

    @Test
    fun skinJsonValidCommitsWithFormattedIssues() {
        val withIssues = baseValid.copy(
            issues = listOf(
                SkinIssue("geometry.cornerRadius", SkinIssue.Kind.FIELD_FALLBACK, "out_of_range"),
            ),
        )
        val outcome = SkinEditorSave.decideSkinJson(
            """{"formatVersion":3,"styles":{"keyBackground":"#FFFFFFFF"},"stylesDark":{"keyBackground":"#FF222222"}}""",
        ) { withIssues }
        assertEquals(
            EditorSaveOutcome.Commit(listOf("geometry.cornerRadius：out_of_range")),
            outcome,
        )
    }

    @Test
    fun skinJsonRejectedBlocksWithReason() {
        val outcome = SkinEditorSave.decideSkinJson(
            """{"formatVersion":3,"styles":{"keyBackground":"#FFFFFFFF"},"stylesDark":{"keyBackground":"#FF222222"}}""",
        ) {
            SkinValidator.Result.Rejected("format_version_unsupported:99")
        }
        assertEquals(
            EditorSaveOutcome.Block("format_version_unsupported:99"),
            outcome,
        )
    }

    @Test
    fun skinJsonTooDeepBlocks() {
        // 9 层嵌套，超 MAX_JSON_DEPTH（8）。
        var text = "1"
        repeat(9) { text = """{"a":$text}""" }
        val outcome = SkinEditorSave.decideSkinJson(text) { baseValid }
        assertEquals(EditorSaveOutcome.Block("skin_json_limits_exceeded"), outcome)
    }

    @Test
    fun layoutUnparseableBlocks() {
        val outcome = SkinEditorSave.decideLayout("{not json", "bin-pinyin") { _, _ ->
            LayoutValidation.Valid(LayoutModel("bin-pinyin", "bin-pinyin", emptyList()), emptyList())
        }
        assertEquals(EditorSaveOutcome.Block("layout_unparseable"), outcome)
    }

    @Test
    fun layoutValidCommits() {
        val outcome = SkinEditorSave.decideLayout("""{"formatVersion": 3}""", "bin-pinyin") { _, _ ->
            LayoutValidation.Valid(LayoutModel("bin-pinyin", "bin-pinyin", emptyList()), emptyList())
        }
        assertEquals(EditorSaveOutcome.Commit(emptyList()), outcome)
    }

    @Test
    fun layoutInvalidBlocksWithIssues() {
        val outcome = SkinEditorSave.decideLayout("""{"formatVersion": 3}""", "bin-pinyin") { _, _ ->
            LayoutValidation.Invalid(
                listOf(
                    LayoutIssue("rows[0]", "row_empty", LayoutDisposition.INVALID_LAYOUT),
                ),
            )
        }
        assertEquals(
            EditorSaveOutcome.Block(
                reason = "layout_invalid:bin-pinyin",
                issues = listOf("rows[0]：row_empty"),
            ),
            outcome,
        )
    }

    @Test
    fun manifestShaUpdatesMatchingPathOnly() {
        val manifest = PackageManifest(
            contents = listOf(
                PackageManifest.Content("skin", "bin-b01", "skin.json", "old-sha", 100L),
                PackageManifest.Content("layout", "bin-pinyin", "layouts/bin-pinyin.json", "lay-sha", 200L),
            ),
        )
        val updated = SkinEditorSave.manifestWithUpdatedSha(manifest, "skin.json", "new-sha", 120L)
        assertEquals("new-sha", updated.skin()?.sha256)
        assertEquals(120L, updated.skin()?.sizeBytes)
        assertEquals("lay-sha", updated.layouts().single().sha256)
        assertEquals(200L, updated.layouts().single().sizeBytes)
    }

    @Test
    fun manifestShaMissingPathReturnsUnchanged() {
        val manifest = PackageManifest(
            contents = listOf(
                PackageManifest.Content("skin", "bin-b01", "skin.json", "old-sha", 100L),
            ),
        )
        val updated = SkinEditorSave.manifestWithUpdatedSha(manifest, "nope.json", "x", 1L)
        assertTrue(updated === manifest)
    }
}
