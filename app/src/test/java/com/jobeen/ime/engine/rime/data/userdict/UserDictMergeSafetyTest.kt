// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 词库备份/合并安全流程（UserDictMergeSafety）的 JVM 可测部分。
 *
 * 覆盖口径（均从本仓 vendored librime 的导入语义推导，不依赖网络与引擎）：
 * - 校验分两层：批级问题（非 UTF-8/空批/码在前快照/非法词典名）
 *   整批拒绝，且失败说明点名批次、行号与原因；行级问题（负权重/
 *   非法权重/坏列数/空词空码/非法码）只跳过该行、其余照常
 *   导入，跳过的行号与原因可查；空文件在同步路径（allowEmpty）下
 *   直接跳过、不记失败。
 * - 批内去重：同词条只留最大权重；无重复不重写文件；BOM 去掉。
 * - 安全副本：导出文本→before-merge.json 往返一致（含引号/反斜杠/
 *   表情词条）；损坏的备份拒绝读取；非法词典名拒绝建备份。
 * - 恢复计划：合并后新增的删掉、被抬高的恢复备份权重、不动的
 *   不动；删除标记行只在恢复文件里出现，且先于重写行。
 * - 一站式合并：校验失败时不导出、不备份、不导入；成功时备份
 *   是合并前的导出、导入的是去重后的文件；备份失败同样中止。
 * 引擎侧（热导入/热导出本身）不在此测，需真机复核。
 */
class UserDictMergeSafetyTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun createTempDir(): File {
        val dir = Files.createTempDirectory("merge-safety-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    private fun writeBatch(dir: File, name: String, text: String): File =
        File(dir, name).apply { writeText(text, Charsets.UTF_8) }

    private fun valid(result: UserDictMergeSafety.BatchValidation): UserDictMergeSafety.BatchValidation.Valid {
        assertTrue("expected Valid, got $result", result is UserDictMergeSafety.BatchValidation.Valid)
        return result as UserDictMergeSafety.BatchValidation.Valid
    }

    private fun invalid(result: UserDictMergeSafety.BatchValidation): UserDictMergeSafety.BatchValidation.Invalid {
        assertTrue("expected Invalid, got $result", result is UserDictMergeSafety.BatchValidation.Invalid)
        return result as UserDictMergeSafety.BatchValidation.Invalid
    }

    // ---- 校验：通过 ----

    @Test
    fun validTwoColumnBatchPasses() {
        val dir = createTempDir()
        val file = writeBatch(dir, "import-wanxiang_lite.txt", "你好\tni hao\n世界\tshi jie\n# 注释\n\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(2, result.entryCount)
        assertEquals(2, result.distinctKeys)
        assertNull(result.maxCommits)
        assertFalse(result.crossDict)
    }

    @Test
    fun validWeightedBatchReportsMaxCommits() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t5\n世界\tshi jie\t12\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(2, result.entryCount)
        assertEquals(12, result.maxCommits)
    }

    @Test
    fun crossDictSourceIsNotedNotRejected() {
        val dir = createTempDir()
        val file = writeBatch(dir, "wanxiang.userdict.txt", "你好\tni hao\t1\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals("wanxiang", result.sourceDict)
        assertTrue(result.crossDict)
    }

    @Test
    fun sameDictSourceIsNotCrossDict() {
        val dir = createTempDir()
        val file = writeBatch(dir, "wanxiang_lite.userdict.txt", "你好\tni hao\t1\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals("wanxiang_lite", result.sourceDict)
        assertFalse(result.crossDict)
    }

    @Test
    fun phraseFirstMajorityPasses() {
        val dir = createTempDir()
        val file = writeBatch(
            dir, "batch.txt",
            "是\tshi\t1\n陈\tchen\t2\n好\thao\t3\n",
        )
        valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
    }

    @Test
    fun digitCodesAreAllowed() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao 2\t1\n")
        valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
    }

    // ---- 校验：拒绝 ----

    @Test
    fun negativeWeightRowIsSkippedGoodRowImports() {
        // 负权重行是删除标记：跳过该行（永不喂给导入器），好行照常导入。
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t5\n世界\tshi jie\t-1\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(1, result.entryCount)
        assertEquals(1, result.skippedRows)
        assertEquals(UserDictMergeSafety.IssueCode.NEGATIVE_WEIGHT, result.skipped[0].code)
        assertEquals(2, result.skipped[0].lineNumber)
        assertEquals(setOf(2), result.skippedLineNumbers)
    }

    @Test
    fun snapshotStyleWeightRowIsSkipped() {
        // .userdb.txt 快照第 3 列是 "c=5 d=… t=…"，不是整数：native 会静默当成 0。
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\tc=5 d=0.1 t=123\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(0, result.entryCount)
        assertEquals(1, result.skippedRows)
        assertEquals(UserDictMergeSafety.IssueCode.BAD_WEIGHT, result.skipped[0].code)
    }

    @Test
    fun overflowingWeightRowIsSkipped() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t99999999999999999999\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(0, result.entryCount)
        assertEquals(1, result.skippedRows)
        assertEquals(UserDictMergeSafety.IssueCode.BAD_WEIGHT, result.skipped[0].code)
    }

    @Test
    fun badColumnCountsAreSkipped() {
        val dir = createTempDir()
        val oneCol = writeBatch(dir, "a.txt", "你好\n")
        assertEquals(
            UserDictMergeSafety.IssueCode.BAD_COLUMNS,
            valid(UserDictMergeSafety.validateBatch(oneCol, "a.txt", "wanxiang_lite")).skipped[0].code,
        )
        val fourCol = writeBatch(dir, "b.txt", "你好\tni hao\t1\textra\n")
        assertEquals(
            UserDictMergeSafety.IssueCode.BAD_COLUMNS,
            valid(UserDictMergeSafety.validateBatch(fourCol, "b.txt", "wanxiang_lite")).skipped[0].code,
        )
    }

    @Test
    fun emptyPhraseAndCodeAreSkipped() {
        val dir = createTempDir()
        val emptyPhrase = writeBatch(dir, "a.txt", "\tni hao\t1\n")
        assertEquals(
            UserDictMergeSafety.IssueCode.EMPTY_PHRASE,
            valid(UserDictMergeSafety.validateBatch(emptyPhrase, "a.txt", "wanxiang_lite")).skipped[0].code,
        )
        val emptyCode = writeBatch(dir, "b.txt", "你好\t \t1\n")
        assertEquals(
            UserDictMergeSafety.IssueCode.EMPTY_CODE,
            valid(UserDictMergeSafety.validateBatch(emptyCode, "b.txt", "wanxiang_lite")).skipped[0].code,
        )
    }

    @Test
    fun illegalCodesAreSkippedWithNamedCause() {
        val dir = createTempDir()
        val cases = mapOf(
            "大写码" to ("你好\tNi Hao\t1\n" to "不是小写字母/数字/空格"),
            "带声调码" to ("陈\tchén\t1\n" to "不是小写字母/数字/空格"),
            "连续空格码" to ("你好\tni  hao\t1\n" to "连续空格"),
        )
        for ((label, pair) in cases) {
            val (text, cause) = pair
            val file = writeBatch(dir, "batch.txt", text)
            val result = valid(UserDictMergeSafety.validateBatch(file, "batch.txt", "wanxiang_lite"))
            assertEquals(label, UserDictMergeSafety.IssueCode.BAD_CODE, result.skipped[0].code)
            assertTrue(label, result.skipped[0].detail.contains(cause))
        }
    }

    @Test
    fun longCodeRowsAreImportedWithoutLengthLimit() {
        // 2026-11-17 删掉 Jime 自定的 64 字码长上限（librime 解析器本身不限
        // 码长）：wanxiang_lite 19180 行里曾被拒的 53 行超长码（全是小写单
        // 空格）现在正常导入。
        val dir = createTempDir()
        val longDing = List(64) { "ding" }.joinToString(" ") // 319 字
        val longLa = List(23) { "la" }.joinToString(" ") // 68 字
        val file = writeBatch(
            dir, "batch.txt",
            "你好\tni hao\t1\n叮叮叮\t$longDing\t1\n啦啦啦\t$longLa\t1\n世界\tshi jie\t2\n",
        )
        val result = valid(UserDictMergeSafety.validateBatch(file, "batch.txt", "wanxiang_lite"))
        assertEquals(4, result.entryCount)
        assertEquals(0, result.skippedRows)
        assertTrue(result.skippedLineNumbers.isEmpty())
    }

    @Test
    fun codeFirstMajorityRejectsAsSnapshot() {
        // 多数行码在前：疑似 .userdb.txt 快照，整批拒绝。
        val dir = createTempDir()
        val file = writeBatch(
            dir, "snapshot.txt",
            "shi\t是\t1\nchen\t陈\t2\nhao\t好\t3\n是\tshi\t1\n",
        )
        val result = invalid(UserDictMergeSafety.validateBatch(file, "snapshot.txt", "wanxiang_lite"))
        assertTrue(result.issues.any { it.code == UserDictMergeSafety.IssueCode.COLUMN_ORDER })
        assertEquals(0, result.issues.first { it.code == UserDictMergeSafety.IssueCode.COLUMN_ORDER }.lineNumber)
    }

    @Test
    fun transposedSnapshotBatchValidatesWithZeroSkipped() {
        // 同步下载的断点证明：诊断 jime-diag-20261118-162554 里 23413 行
        // 快照没转置权重就直接校验，整批被跳过、0 条导入；先经
        // convertUserDbSnapshot 转置（列序＋c= 权重转整数）再校验，
        // 应全数通过、0 行跳过。手动导入的校验本身不动（见上两测）。
        val dir = createTempDir()
        val snapshot = "shi\t是\tc=723 d=6.14592 t=79121\n" +
            "chen\t陈\tc=14 d=0.00138093 t=79121\n" +
            "hao\t好\tc=1 d=1.67191e-47 t=79121\n"
        val transposed = WebDavSync.convertUserDbSnapshot(
            snapshot.toByteArray(Charsets.UTF_8),
        ).toString(Charsets.UTF_8)
        val file = writeBatch(dir, "wanxiang.userdb.txt", transposed)
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(3, result.entryCount)
        assertEquals(0, result.skippedRows)
    }

    @Test
    fun emptyBatchRejects() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "# 只有注释\n\n")
        val result = invalid(UserDictMergeSafety.validateBatch(file, "batch.txt", "wanxiang_lite"))
        assertEquals(UserDictMergeSafety.IssueCode.EMPTY_BATCH, result.issues[0].code)
    }

    @Test
    fun nonUtf8Rejects() {
        val dir = createTempDir()
        val file = File(dir, "batch.txt")
        file.writeBytes(byteArrayOf(0x61, 0xFF.toByte(), 0x0A, 0x62, 0x09, 0x63, 0x0A))
        val result = invalid(UserDictMergeSafety.validateBatch(file, "batch.txt", "wanxiang_lite"))
        assertEquals(UserDictMergeSafety.IssueCode.FILE_NOT_UTF8, result.issues[0].code)
    }

    @Test
    fun illegalDictNameRejects() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t1\n")
        val result = invalid(UserDictMergeSafety.validateBatch(file, "batch.txt", "../wanxiang"))
        assertEquals(UserDictMergeSafety.IssueCode.DICT_NAME, result.issues[0].code)
    }

    @Test
    fun tooManyBadRowsAreSkippedAndCounted() {
        val dir = createTempDir()
        val text = buildString {
            repeat(UserDictMergeSafety.MAX_ISSUES + 5) { append("坏行\n") }
        }
        val file = writeBatch(dir, "batch.txt", text)
        val result = valid(UserDictMergeSafety.validateBatch(file, "batch.txt", "wanxiang_lite"))
        assertEquals(0, result.entryCount)
        assertEquals(UserDictMergeSafety.MAX_ISSUES + 5, result.skippedRows)
        assertEquals(UserDictMergeSafety.MAX_ISSUES, result.skipped.size)
        assertEquals(UserDictMergeSafety.MAX_ISSUES + 5, result.skippedLineNumbers.size)
    }

    // ---- 批内去重 ----

    @Test
    fun duplicateKeepsMaxWeight() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t3\n世界\tshi jie\t1\n你好\tni hao\t9\n")
        val normalized = UserDictMergeSafety.normalizeBatchFile(file, dir)
        assertNotSame(file, normalized)
        assertEquals("你好\tni hao\t9\n世界\tshi jie\t1\n", normalized.readText(Charsets.UTF_8))
    }

    @Test
    fun weightlessVsWeightedKeepsWeighted() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\n你好\tni hao\t4\n")
        val normalized = UserDictMergeSafety.normalizeBatchFile(file, dir)
        assertEquals("你好\tni hao\t4\n", normalized.readText(Charsets.UTF_8))
    }

    @Test
    fun noDuplicateReturnsSameFile() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t3\n世界\tshi jie\t1\n")
        assertEquals(file, UserDictMergeSafety.normalizeBatchFile(file, dir))
    }

    @Test
    fun normalizeBatchFileDropsSkippedLines() {
        val dir = createTempDir()
        val file = writeBatch(
            dir, "batch.txt",
            "你好\tni hao\t3\n世界\tshi jie\t-1\n你好\tni hao\t9\n",
        )
        val normalized = UserDictMergeSafety.normalizeBatchFile(file, dir, setOf(2))
        assertNotSame(file, normalized)
        assertEquals("你好\tni hao\t9\n", normalized.readText(Charsets.UTF_8))
    }

    @Test
    fun bomIsStrippedByNormalizeButToleratedByValidation() {
        val dir = createTempDir()
        val file = File(dir, "batch.txt")
        file.writeText("\uFEFF你好\tni hao\t3\n世界\tshi jie\t1\n", Charsets.UTF_8)
        valid(UserDictMergeSafety.validateBatch(file, "batch.txt", "wanxiang_lite"))
        val normalized = UserDictMergeSafety.normalizeBatchFile(file, dir)
        assertNotSame(file, normalized)
        assertFalse(normalized.readText(Charsets.UTF_8).startsWith("\uFEFF"))
    }

    // ---- 安全副本 ----

    @Test
    fun backupRoundTrip() {
        val dir = createTempDir()
        val export = writeBatch(
            dir, "export.txt",
            "你好\tni hao\t5\n他说\"你好\"\tta shuo\t3\nC:\\词\tci\t1\n😀\txiao\t2\n",
        )
        val snapshot = UserDictMergeSafety.writeBeforeMergeBackup(
            dir, "wanxiang_lite", "wanxiang.userdict.txt", export, nowMs = 123456789L,
        )
        assertEquals(4, snapshot.entries.size)
        assertEquals("wanxiang_lite", snapshot.dictName)
        assertEquals(123456789L, snapshot.createdAtMs)
        val readBack = UserDictMergeSafety.readBeforeMergeBackup(
            UserDictMergeSafety.backupFileFor(dir, "wanxiang_lite"),
        )
        assertEquals(snapshot, readBack)
        assertTrue(UserDictMergeSafety.hasBeforeMergeBackup(dir, "wanxiang_lite"))
        assertEquals(snapshot, UserDictMergeSafety.readBackupMeta(dir, "wanxiang_lite"))
    }

    @Test
    fun backupPathIsPerDictJson() {
        val dir = createTempDir()
        val file = UserDictMergeSafety.backupFileFor(dir, "wanxiang_lite")
        assertEquals("before-merge.json", file.name)
        assertTrue(file.absolutePath.contains("userdict_backups"))
        assertTrue(file.absolutePath.contains("wanxiang_lite"))
    }

    @Test
    fun noBackupReadsNull() {
        val dir = createTempDir()
        assertFalse(UserDictMergeSafety.hasBeforeMergeBackup(dir, "wanxiang_lite"))
        assertNull(UserDictMergeSafety.readBackupMeta(dir, "wanxiang_lite"))
    }

    @Test
    fun backupForIllegalDictNameThrows() {
        val dir = createTempDir()
        try {
            UserDictMergeSafety.backupFileFor(dir, "../x")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun corruptedBackupIsRejected() {
        val dir = createTempDir()
        val file = UserDictMergeSafety.backupFileFor(dir, "wanxiang_lite")
        file.parentFile.mkdirs()
        file.writeText("{\"version\":99,\"dict\":\"wanxiang_lite\"}", Charsets.UTF_8)
        try {
            UserDictMergeSafety.readBeforeMergeBackup(file)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("版本"))
        }
        file.writeText("{\"version\":1,", Charsets.UTF_8)
        try {
            UserDictMergeSafety.readBeforeMergeBackup(file)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected: 截断的 JSON
        }
    }

    @Test
    fun parseExportRejectsMalformedRows() {
        val dir = createTempDir()
        val twoCol = writeBatch(dir, "export.txt", "你好\tni hao\n")
        try {
            UserDictMergeSafety.parseExportFile(twoCol)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // 导出行必须带权重列
        }
        val negative = writeBatch(dir, "export2.txt", "你好\tni hao\t-2\n")
        try {
            UserDictMergeSafety.parseExportFile(negative)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // 导出里不应有负权重
        }
    }

    // ---- 恢复计划 ----

    @Test
    fun planRestoreDeletesAddedAndLowersRaised() {
        val backup = listOf(
            UserDictMergeSafety.Entry("你好", "ni hao", 5),
            UserDictMergeSafety.Entry("世界", "shi jie", 3),
            UserDictMergeSafety.Entry("旧词", "jiu ci", 2),
        )
        val current = listOf(
            UserDictMergeSafety.Entry("你好", "ni hao", 9),
            UserDictMergeSafety.Entry("世界", "shi jie", 3),
            UserDictMergeSafety.Entry("新增", "xin zeng", 2),
        )
        val plan = UserDictMergeSafety.planRestore(backup, current)
        assertEquals(1, plan.removed)
        assertEquals(2, plan.restored) // 你好权重恢复 + 旧词失而复得
        assertEquals(1, plan.unchanged)
        val dest = File(createTempDir(), "restore.txt")
        assertEquals(4, UserDictMergeSafety.writeRestoreFile(plan, dest))
        // 删除标记行在前、重写行在后。
        assertEquals(
            "你好\tni hao\t-1\n新增\txin zeng\t-1\n你好\tni hao\t5\n旧词\tjiu ci\t2\n",
            dest.readText(Charsets.UTF_8),
        )
    }

    @Test
    fun planRestoreNoopWhenIdentical() {
        val entries = listOf(UserDictMergeSafety.Entry("你好", "ni hao", 5))
        val plan = UserDictMergeSafety.planRestore(entries, entries)
        assertEquals(0, plan.totalRows)
        assertEquals(1, plan.unchanged)
    }

    // ---- 一站式合并与恢复 ----

    @Test
    fun mergeValidatedBatchRejectsBatchLevelProblemWithoutExportOrImport() = runBlocking {
        // 批级问题（多数行码在前、疑似原生快照）仍整批拒绝：不导出、
        // 不备份、不导入。行级问题不再走这条路（见跳过相关测试）。
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(
            dir, "snapshot.txt",
            "shi\t是\t1\nchen\t陈\t2\nhao\t好\t3\n是\tshi\t1\n",
        )
        var exportCalled = false
        var importCalled = false
        try {
            UserDictMergeSafety.mergeValidatedBatch(
                dictName = "wanxiang_lite",
                batchFile = file,
                batchLabel = file.name,
                filesDir = filesDir,
                cacheDir = dir,
                exportCurrent = { exportCalled = true; 0 },
                importFile = { importCalled = true; 0 },
            )
            fail("expected UserDictMergeException")
        } catch (e: UserDictMergeSafety.UserDictMergeException) {
            assertTrue(e.message!!.contains("snapshot.txt"))
        }
        assertFalse(exportCalled)
        assertFalse(importCalled)
        assertFalse(UserDictMergeSafety.hasBeforeMergeBackup(filesDir, "wanxiang_lite"))
    }

    @Test
    fun mergeValidatedBatchBacksUpThenImportsDeduped() = runBlocking {
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(
            dir, "batch.txt",
            "你好\tni hao\t2\n你好\tni hao\t9\n世界\tshi jie\t1\n",
        )
        var importedText: String? = null
        val result = UserDictMergeSafety.mergeValidatedBatch(
            dictName = "wanxiang_lite",
            batchFile = file,
            batchLabel = file.name,
            filesDir = filesDir,
            cacheDir = dir,
            exportCurrent = { tmp ->
                tmp.writeText("旧词\tjiu ci\t4\n", Charsets.UTF_8)
                1
            },
            importFile = { f ->
                importedText = f.readText(Charsets.UTF_8)
                importedText!!.lineSequence().filter { it.isNotEmpty() }.count()
            },
        )
        assertEquals(2, result.importedCount)
        assertEquals(0, result.skippedRows)
        assertEquals("你好\tni hao\t9\n世界\tshi jie\t1\n", importedText)
        val backup = UserDictMergeSafety.readBackupMeta(filesDir, "wanxiang_lite")
        assertEquals(listOf(UserDictMergeSafety.Entry("旧词", "jiu ci", 4)), backup!!.entries)
        assertEquals("batch.txt", backup.sourceLabel)
    }

    @Test
    fun mergeValidatedBatchSkipsBadRowsAndImportsGoodOnes() = runBlocking {
        val dir = createTempDir()
        val filesDir = createTempDir()
        val longCode = List(30) { "la" }.joinToString(" ") // 89 字，码不限长
        val file = writeBatch(
            dir, "batch.txt",
            "你好\tni hao\t2\n世界\tshi jie\t-1\n啦啦啦\t$longCode\t1\n你好\tni hao\t9\n",
        )
        var importedText: String? = null
        val result = UserDictMergeSafety.mergeValidatedBatch(
            dictName = "wanxiang_lite",
            batchFile = file,
            batchLabel = file.name,
            filesDir = filesDir,
            cacheDir = dir,
            exportCurrent = { tmp ->
                tmp.writeText("旧词\tjiu ci\t4\n", Charsets.UTF_8)
                1
            },
            importFile = { f ->
                importedText = f.readText(Charsets.UTF_8)
                importedText!!.lineSequence().filter { it.isNotEmpty() }.count()
            },
        )
        // 负权重行被跳过，超长码行正常导入，好行去重后导入（你好取最大权重 9）。
        assertEquals(2, result.importedCount)
        assertEquals("你好\tni hao\t9\n啦啦啦\t$longCode\t1\n", importedText)
        assertTrue(UserDictMergeSafety.hasBeforeMergeBackup(filesDir, "wanxiang_lite"))
    }

    @Test
    fun mergeValidatedBatchReturnsSkippedDetails() = runBlocking {
        // 1.6：返回值带跳过明细（数量／行号／原因），供上层决定展示
        // 或中止后续 PUT——只返回导入条数时坏行无声无息就没了。
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(
            dir, "batch.txt",
            "你好\tni hao\t2\n世界\tshi jie\t-1\n只有一列\n你好\tni hao\t9\n",
        )
        var importedText: String? = null
        val result = UserDictMergeSafety.mergeValidatedBatch(
            dictName = "wanxiang_lite",
            batchFile = file,
            batchLabel = file.name,
            filesDir = filesDir,
            cacheDir = dir,
            exportCurrent = { tmp ->
                tmp.writeText("旧词\tjiu ci\t4\n", Charsets.UTF_8)
                1
            },
            importFile = { f ->
                importedText = f.readText(Charsets.UTF_8)
                importedText!!.lineSequence().filter { it.isNotEmpty() }.count()
            },
        )
        // 好行去重后导入（你好取最大权重 9）；第 2 行负权重、第 3 行
        // 列数不对被跳过，行号与原因都在返回值里，不靠日志翻查。
        assertEquals(1, result.importedCount)
        assertEquals("你好\tni hao\t9\n", importedText)
        assertEquals(2, result.skippedRows)
        assertEquals(setOf(2, 3), result.skippedLineNumbers)
        assertEquals(2, result.skipped.size)
        assertEquals(2, result.skipped[0].lineNumber)
        assertEquals(3, result.skipped[1].lineNumber)
        assertTrue(result.skipped[0].detail.isNotEmpty())
        assertTrue(result.skipped[1].detail.isNotEmpty())
    }

    @Test
    fun mergeValidatedBatchSkipsEmptyWhenAllowed() = runBlocking {
        // 同步路径：空文件是“无词可合”，返回 0，不跑导出/备份/导入，不记失败。
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(dir, "en.userdict.txt", "# 只有注释\n\n")
        var exportCalled = false
        var importCalled = false
        val result = UserDictMergeSafety.mergeValidatedBatch(
            dictName = "en",
            batchFile = file,
            batchLabel = file.name,
            filesDir = filesDir,
            cacheDir = dir,
            exportCurrent = { exportCalled = true; 0 },
            importFile = { importCalled = true; 0 },
            allowEmpty = true,
        )
        assertEquals(0, result.importedCount)
        assertEquals(0, result.skippedRows)
        assertFalse(exportCalled)
        assertFalse(importCalled)
        assertFalse(UserDictMergeSafety.hasBeforeMergeBackup(filesDir, "en"))
    }

    @Test
    fun mergeValidatedBatchStillRejectsEmptyWhenNotAllowed() = runBlocking {
        // 手动导入：用户亲手选了个空文件，仍应报错告知。
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "# 只有注释\n\n")
        var exportCalled = false
        var importCalled = false
        try {
            UserDictMergeSafety.mergeValidatedBatch(
                dictName = "wanxiang_lite",
                batchFile = file,
                batchLabel = file.name,
                filesDir = filesDir,
                cacheDir = dir,
                exportCurrent = { exportCalled = true; 0 },
                importFile = { importCalled = true; 0 },
            )
            fail("expected UserDictMergeException")
        } catch (e: UserDictMergeSafety.UserDictMergeException) {
            assertTrue(e.message!!.contains("没有任何词条"))
        }
        assertFalse(exportCalled)
        assertFalse(importCalled)
    }

    @Test
    fun mergeValidatedBatchAbortsWhenBackupFails() = runBlocking {
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t1\n")
        var importCalled = false
        try {
            UserDictMergeSafety.mergeValidatedBatch(
                dictName = "wanxiang_lite",
                batchFile = file,
                batchLabel = file.name,
                filesDir = filesDir,
                cacheDir = dir,
                exportCurrent = { -1 },
                importFile = { importCalled = true; 0 },
            )
            fail("expected UserDictMergeException")
        } catch (e: UserDictMergeSafety.UserDictMergeException) {
            assertTrue(e.message!!.contains("未写入任何词条"))
        }
        assertFalse(importCalled)
    }

    @Test
    fun restoreBeforeMergeWithoutBackupFails() = runBlocking {
        val dir = createTempDir()
        val result = UserDictMergeSafety.restoreBeforeMerge(
            dictName = "wanxiang_lite",
            filesDir = dir,
            cacheDir = dir,
            exportCurrent = { 0 },
            importFile = { 0 },
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("没有"))
    }

    @Test
    fun restoreBeforeMergeNoopWhenCurrentMatchesBackup() = runBlocking {
        val dir = createTempDir()
        val filesDir = createTempDir()
        val export = writeBatch(dir, "export.txt", "你好\tni hao\t5\n")
        UserDictMergeSafety.writeBeforeMergeBackup(filesDir, "wanxiang_lite", "batch.txt", export)
        var importCalled = false
        val result = UserDictMergeSafety.restoreBeforeMerge(
            dictName = "wanxiang_lite",
            filesDir = filesDir,
            cacheDir = dir,
            exportCurrent = { tmp ->
                tmp.writeText("你好\tni hao\t5\n", Charsets.UTF_8)
                1
            },
            importFile = { importCalled = true; 0 },
        )
        assertTrue(result.isSuccess)
        assertEquals(0, result.getOrThrow().importedRows)
        assertFalse(importCalled)
    }

    // ---- 内存预算上限（2026-11-18，报告二 P2） ----
    //
    // 复现测量方法：heapSamples* 两个测试分别跑超多短行档与单条巨长行档，
    // 把校验／归一／导入三阶段边界的四次堆采样打印到标准输出；跑法：
    // ./gradlew :app:testDebugUnitTest --tests
    //   "com.jobeen.ime.engine.rime.data.userdict.UserDictMergeSafetyTest.heapSamples*"
    // 真机低内存峰值主机测不了：采样点加好、方法写清即可，真机看 logcat 的
    // DictMemory 行。

    private fun readBoundedLines(text: String): List<Triple<Int, String, Boolean>> {
        val out = ArrayList<Triple<Int, String, Boolean>>()
        val reader = java.io.BufferedReader(java.io.StringReader(text))
        reader.use {
            UserDictMergeSafety.forEachBoundedLine(it) { lineNumber, line, overlong ->
                out += Triple(lineNumber, line, overlong)
                true
            }
        }
        return out
    }

    private fun writeRows(dir: File, name: String, count: Int, row: (Int) -> String): File {
        val file = File(dir, name)
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            for (i in 0 until count) {
                writer.write(row(i))
                writer.newLine()
            }
        }
        return file
    }

    private fun mergeWithFakes(
        dir: File,
        filesDir: File,
        file: File,
    ): UserDictMergeSafety.MergeResult = runBlocking {
        UserDictMergeSafety.mergeValidatedBatch(
            dictName = "wanxiang_lite",
            batchFile = file,
            batchLabel = file.name,
            filesDir = filesDir,
            cacheDir = dir,
            exportCurrent = { tmp ->
                tmp.writeText("旧词\tjiu ci\t4\n", Charsets.UTF_8)
                1
            },
            importFile = { f ->
                f.readText(Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.count()
            },
        )
    }

    private fun printHeapSamples(tag: String, samples: List<UserDictMergeSafety.HeapSample>) {
        println("DictMemory $tag: " + samples.joinToString(" | ") {
            "${it.stage}=${"%.1f".format(it.usedMiB())}MiB"
        })
    }

    @Test
    fun boundedLineReaderMatchesReadLine() {
        // 行尾口径与 BufferedReader.readLine 一致（\n、\r\n、\r 都是行尾；
        // 末行无行尾也算一行；空文件无行）。
        assertEquals(emptyList<Triple<Int, String, Boolean>>(), readBoundedLines(""))
        assertEquals(listOf(Triple(1, "", false)), readBoundedLines("\n"))
        assertEquals(listOf(Triple(1, "a", false)), readBoundedLines("a\n"))
        assertEquals(
            listOf(Triple(1, "a", false), Triple(2, "", false)),
            readBoundedLines("a\n\n"),
        )
        assertEquals(
            listOf(Triple(1, "a", false), Triple(2, "b", false), Triple(3, "c", false)),
            readBoundedLines("a\r\nb\rc"),
        )
        assertEquals(listOf(Triple(1, "a", false)), readBoundedLines("a\r"))
        assertEquals(
            listOf(Triple(1, "a", false), Triple(2, "", false), Triple(3, "b", false)),
            readBoundedLines("a\r\rb\n"),
        )
        assertEquals(listOf(Triple(1, "ab", false)), readBoundedLines("ab"))
    }

    @Test
    fun boundedLineReaderKeepsLineNumbersAfterGiantLine() {
        // 超长行只保留前 8193 字、余下丢弃；之后的行号不错位。
        val giant = "x".repeat(9000)
        val lines = readBoundedLines("好\thao\n$giant\n世\tshi\n")
        assertEquals(3, lines.size)
        assertEquals(Triple(1, "好\thao", false), lines[0])
        assertEquals(2, lines[1].first)
        assertTrue(lines[1].third)
        assertEquals(UserDictMergeSafety.MAX_LINE_CHARS + 1, lines[1].second.length)
        assertEquals(Triple(3, "世\tshi", false), lines[2])
    }

    @Test
    fun overlongLineIsSkippedRowNotBatchReject() {
        val dir = createTempDir()
        val longCode = "a".repeat(8190) // 整行 2+1+8190=8193 字，超 8192 上限
        val file = writeBatch(
            dir, "batch.txt",
            "你好\tni hao\t2\n你好\t$longCode\t1\n世界\tshi jie\t1\n",
        )
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(2, result.entryCount)
        assertEquals(1, result.skippedRows)
        assertEquals(setOf(2), result.skippedLineNumbers)
        assertEquals(1, result.skipped.size)
        assertEquals(UserDictMergeSafety.IssueCode.LINE_TOO_LONG, result.skipped[0].code)
        assertTrue(result.skipped[0].detail.contains("8192"))
        // 归一同样丢掉超长行、好行照常去重后写出。
        val normalized = UserDictMergeSafety.normalizeBatchFile(file, dir, result.skippedLineNumbers)
        assertEquals("你好\tni hao\t2\n世界\tshi jie\t1\n", normalized.readText(Charsets.UTF_8))
    }

    @Test
    fun lineExactlyAtCapPasses() {
        val dir = createTempDir()
        // 整行恰好 8192 字：128 字词＋1 制表符＋8061 字码＋1 制表符＋1 位权重，
        // 不超限、应通过。
        val phrase = "你".repeat(128)
        val code = "a".repeat(8192 - 128 - 1 - 1 - 1)
        val file = writeBatch(dir, "batch.txt", "$phrase\t$code\t1\n")
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(1, result.entryCount)
        assertEquals(0, result.skippedRows)
    }

    @Test
    fun singleGiantLineIsSkippedWithoutBlowingUp() {
        val dir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "x".repeat(1024 * 1024))
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(0, result.entryCount)
        assertEquals(1, result.skippedRows)
        assertEquals(UserDictMergeSafety.IssueCode.LINE_TOO_LONG, result.skipped[0].code)
        // 直接调归一（不传 skipLines）：好行有重复触发重写时，巨行被丢弃、
        // 绝不写出截断行。
        val dupFile = writeBatch(
            dir, "batch2.txt",
            "你好\tni hao\t2\n" + "x".repeat(1024 * 1024) + "\n你好\tni hao\t9\n",
        )
        val normalized = UserDictMergeSafety.normalizeBatchFile(dupFile, dir)
        assertEquals("你好\tni hao\t9\n", normalized.readText(Charsets.UTF_8))
    }

    @Test
    fun tooManyRowsRejectsWholeBatch() {
        val dir = createTempDir()
        val filesDir = createTempDir()
        // 全是坏列行：只涨检查行数、不涨合格词条，单独测行数上限。
        val file = writeRows(dir, "batch.txt", UserDictMergeSafety.MAX_CHECKED_ROWS + 1) { "只有一列" }
        val rejected = invalid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(1, rejected.issues.size)
        assertEquals(UserDictMergeSafety.IssueCode.TOO_MANY_ROWS, rejected.issues[0].code)
        assertTrue(rejected.issues[0].detail.contains("1,000,000"))
        assertEquals(UserDictMergeSafety.MAX_CHECKED_ROWS + 1, rejected.checkedRows)
        // 合并入口同样整批拒绝：不导出、不备份、不导入。
        var exportCalled = false
        var importCalled = false
        try {
            runBlocking {
                UserDictMergeSafety.mergeValidatedBatch(
                    dictName = "wanxiang_lite",
                    batchFile = file,
                    batchLabel = file.name,
                    filesDir = filesDir,
                    cacheDir = dir,
                    exportCurrent = { exportCalled = true; 0 },
                    importFile = { importCalled = true; 0 },
                )
            }
            fail("expected UserDictMergeException")
        } catch (e: UserDictMergeSafety.UserDictMergeException) {
            assertTrue(e.message!!.contains("1,000,000"))
        }
        assertFalse(exportCalled)
        assertFalse(importCalled)
    }

    @Test
    fun tooManyEntriesRejectsWholeBatch() {
        val dir = createTempDir()
        val filesDir = createTempDir()
        // 500,001 条互不相同的合格行：单独测词条总数上限。
        val file = writeRows(dir, "batch.txt", UserDictMergeSafety.MAX_TOTAL_ENTRIES + 1) {
            "词$it\ta$it\t1"
        }
        val rejected = invalid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(1, rejected.issues.size)
        assertEquals(UserDictMergeSafety.IssueCode.TOO_MANY_ENTRIES, rejected.issues[0].code)
        assertTrue(rejected.issues[0].detail.contains("500,000"))
        var exportCalled = false
        var importCalled = false
        try {
            runBlocking {
                UserDictMergeSafety.mergeValidatedBatch(
                    dictName = "wanxiang_lite",
                    batchFile = file,
                    batchLabel = file.name,
                    filesDir = filesDir,
                    cacheDir = dir,
                    exportCurrent = { exportCalled = true; 0 },
                    importFile = { importCalled = true; 0 },
                )
            }
            fail("expected UserDictMergeException")
        } catch (e: UserDictMergeSafety.UserDictMergeException) {
            assertTrue(e.message!!.contains("500,000"))
        }
        assertFalse(exportCalled)
        assertFalse(importCalled)
    }

    @Test
    fun highDuplicateRateDoesNotGrowDistinctSet() {
        val dir = createTempDir()
        // 20 万行全是同一词条（权重轮换 0..9）：distinct 应为 1，不撑大指纹集。
        val file = writeRows(dir, "batch.txt", 200_000) { "你好\tni hao\t${it % 10}" }
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(200_000, result.entryCount)
        assertEquals(1, result.distinctKeys)
        val normalized = UserDictMergeSafety.normalizeBatchFile(file, dir)
        assertEquals("你好\tni hao\t9\n", normalized.readText(Charsets.UTF_8))
    }

    @Test
    fun distinctKeysCountsDistinctPairsAtScale() {
        val dir = createTempDir()
        val file = writeRows(dir, "batch.txt", 10_000) { "词$it\ta$it\t1" }
        val result = valid(UserDictMergeSafety.validateBatch(file, file.name, "wanxiang_lite"))
        assertEquals(10_000, result.entryCount)
        assertEquals(10_000, result.distinctKeys)
    }

    @Test
    fun heapSamplesCoverThreeStages() {
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(dir, "batch.txt", "你好\tni hao\t2\n世界\tshi jie\t1\n")
        val result = mergeWithFakes(dir, filesDir, file)
        assertEquals(2, result.importedCount)
        assertEquals(
            listOf("validate-before", "validate-after", "normalize-after", "import-after"),
            result.heapSamples.map { it.stage },
        )
        for (sample in result.heapSamples) {
            assertTrue(sample.usedBytes >= 0)
            assertTrue(sample.maxBytes > 0)
        }
        printHeapSamples("small-batch", result.heapSamples)
    }

    @Test
    fun heapSamplesManyShortRows() {
        // 测量档 A：超多短行（20 万条互不相同的短行）。
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeRows(dir, "batch.txt", 200_000) { "词$it\ta$it\t1" }
        val result = mergeWithFakes(dir, filesDir, file)
        assertEquals(200_000, result.importedCount)
        assertEquals(4, result.heapSamples.size)
        printHeapSamples("many-short-rows", result.heapSamples)
    }

    @Test
    fun heapSamplesSingleGiantLine() {
        // 测量档 B：单条巨长行（1 MiB 单行＋两行好行）。
        val dir = createTempDir()
        val filesDir = createTempDir()
        val file = writeBatch(
            dir, "batch.txt",
            "你好\tni hao\t2\n" + "x".repeat(1024 * 1024) + "\n世界\tshi jie\t1\n",
        )
        val result = mergeWithFakes(dir, filesDir, file)
        assertEquals(2, result.importedCount)
        assertEquals(1, result.skippedRows)
        assertEquals(4, result.heapSamples.size)
        printHeapSamples("single-giant-line", result.heapSamples)
    }

    @Test
    fun restoreBeforeMergeDeletesAddedAndRestoresRaised() = runBlocking {
        val dir = createTempDir()
        val filesDir = createTempDir()
        val export = writeBatch(dir, "export.txt", "你好\tni hao\t5\n世界\tshi jie\t3\n")
        UserDictMergeSafety.writeBeforeMergeBackup(filesDir, "wanxiang_lite", "batch.txt", export)
        var importedText: String? = null
        val result = UserDictMergeSafety.restoreBeforeMerge(
            dictName = "wanxiang_lite",
            filesDir = filesDir,
            cacheDir = dir,
            exportCurrent = { tmp ->
                tmp.writeText("你好\tni hao\t9\n世界\tshi jie\t3\n新增\txin zeng\t2\n", Charsets.UTF_8)
                3
            },
            importFile = { f ->
                importedText = f.readText(Charsets.UTF_8)
                importedText!!.lineSequence().filter { it.isNotEmpty() }.count()
            },
        )
        assertTrue(result.isSuccess)
        val report = result.getOrThrow()
        assertEquals(1, report.removed)
        assertEquals(1, report.restored)
        assertEquals(1, report.unchanged)
        assertEquals(3, report.importedRows)
        assertEquals("你好\tni hao\t-1\n新增\txin zeng\t-1\n你好\tni hao\t5\n", importedText)
    }
}
