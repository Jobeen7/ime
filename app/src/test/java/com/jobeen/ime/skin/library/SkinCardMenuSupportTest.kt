package com.jobeen.ime.skin.library

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 库页长按菜单纯函数支持（日志过滤、详情行、格式化）的单测。 */
class SkinCardMenuSupportTest {

    private fun line(level: String, skinId: String?, extra: String = ""): String {
        val tail = if (skinId == null) "" else " skinId=$skinId"
        return "2026-10-28 22:04:39.123 $level [library] preview_failed$tail$extra"
    }

    @Test
    fun filter_keepsWarnAndErrorOnly() {
        val lines = listOf(
            line("INFO", "skin-a"),
            line("DEBUG", "skin-a"),
            line("WARN", "skin-a"),
            line("ERROR", "skin-a"),
            line("WARN", "skin-b"),
        )
        val result = SkinCardMenuSupport.filterSkinLogLines(lines, "skin-a")
        assertEquals(2, result.size)
        assertTrue(result.all { it.contains("skinId=skin-a") })
    }

    @Test
    fun filter_tokenBoundary_noPrefixFalsePositive() {
        val lines = listOf(
            line("WARN", "skin-a"),
            line("WARN", "skin-ab"),
            "2026-10-28 22:04:39.123 WARN [library] preview_failed skinId=skin-a",
        )
        val result = SkinCardMenuSupport.filterSkinLogLines(lines, "skin-a")
        assertEquals(2, result.size)
    }

    @Test
    fun filter_newestFirstAndCapped() {
        val lines = (1..5).map { line("WARN", "skin-a", " n=$it") }
        val capped = SkinCardMenuSupport.filterSkinLogLines(lines, "skin-a", maxLines = 2)
        assertEquals(listOf(lines[4], lines[3]), capped)
        val all = SkinCardMenuSupport.filterSkinLogLines(lines, "skin-a")
        assertEquals(lines.reversed(), all)
    }

    @Test
    fun filter_blankIdOrNonPositiveMax_returnsEmpty() {
        val lines = listOf(line("WARN", "skin-a"))
        assertTrue(SkinCardMenuSupport.filterSkinLogLines(lines, "").isEmpty())
        assertTrue(SkinCardMenuSupport.filterSkinLogLines(lines, "skin-a", 0).isEmpty())
        assertTrue(SkinCardMenuSupport.filterSkinLogLines(lines, "missing").isEmpty())
    }

    @Test
    fun load_readsOnlyDiagFilesInOrder() {
        val dir = Files.createTempDirectory("skin-log-test").toFile()
        try {
            File(dir, "diag-20261028-000-000.log").writeText(
                line("WARN", "skin-a", " old") + "\n" + line("INFO", "skin-a") + "\n",
            )
            File(dir, "diag-20261028-001-000.log").writeText(
                line("ERROR", "skin-a", " new") + "\n",
            )
            File(dir, "notes.txt").writeText(line("WARN", "skin-a", " junk") + "\n")
            val result = SkinCardMenuSupport.loadSkinLogLines(dir, "skin-a")
            assertEquals(2, result.size)
            assertTrue(result[0].contains("new"))
            assertTrue(result[1].contains("old"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun load_missingDir_returnsEmpty() {
        assertTrue(SkinCardMenuSupport.loadSkinLogLines(null, "skin-a").isEmpty())
        assertTrue(
            SkinCardMenuSupport.loadSkinLogLines(File("/nonexistent-dir-xyz"), "skin-a")
                .isEmpty(),
        )
    }

    @Test
    fun formatSkinSize_boundaries() {
        assertEquals("0 B", SkinCardMenuSupport.formatSkinSize(0))
        assertEquals("512 B", SkinCardMenuSupport.formatSkinSize(512))
        assertEquals("1023 B", SkinCardMenuSupport.formatSkinSize(1023))
        assertEquals("1.0 KB", SkinCardMenuSupport.formatSkinSize(1024))
        assertEquals("2.0 KB", SkinCardMenuSupport.formatSkinSize(2048))
        assertEquals("5.0 MB", SkinCardMenuSupport.formatSkinSize(5L * 1024 * 1024))
    }

    @Test
    fun formatSkinTime_patternAndStable() {
        val text = SkinCardMenuSupport.formatSkinTime(1793200000000L)
        assertTrue(text.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")))
        assertEquals(text, SkinCardMenuSupport.formatSkinTime(1793200000000L))
    }

    @Test
    fun layoutSummaryText_branches() {
        assertEquals("无法解析", SkinCardMenuSupport.layoutSummaryText(false, null, null))
        assertEquals("方案默认", SkinCardMenuSupport.layoutSummaryText(true, null, null))
        assertEquals("4 行 33 键", SkinCardMenuSupport.layoutSummaryText(true, 4, 33))
        assertEquals("4 行 0 键", SkinCardMenuSupport.layoutSummaryText(true, 4, null))
    }

    @Test
    fun skinDetailRows_labelsAndFallbacks() {
        val rows = SkinCardMenuSupport.skinDetailRows(
            name = "", id = "skin-a", generation = 3, sizeBytes = 2048,
            updatedAt = 1793200000000L, isActive = false, isLastGood = true,
            layoutParsed = true, layoutRows = 4, layoutKeys = 33,
        )
        val map = rows.toMap()
        assertEquals("skin-a", map["名称"])
        assertEquals("skin-a", map["皮肤 id"])
        assertEquals("第 3 版", map["版本"])
        assertEquals("2.0 KB", map["大小"])
        assertEquals("上个皮肤", map["状态"])
        assertEquals("4 行 33 键", map["布局"])
        assertEquals(
            listOf("名称", "皮肤 id", "版本", "大小", "更新", "状态", "布局"),
            rows.map { it.first },
        )
    }

    @Test
    fun skinDetailRows_activeBeatsLastGood() {
        val rows = SkinCardMenuSupport.skinDetailRows(
            name = "A", id = "skin-a", generation = 1, sizeBytes = 0,
            updatedAt = 0L, isActive = true, isLastGood = true,
            layoutParsed = false, layoutRows = null, layoutKeys = null,
        )
        assertEquals("生效中", rows.toMap()["状态"])
        assertEquals("无法解析", rows.toMap()["布局"])
    }
}
