// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.DictDownloadAction
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.HttpGetResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * WebDAV 同步的 JVM 可测部分：词典/方案下载四态映射与原子替换。
 * 旧目录同步（mtime 末写胜出、删除传播、骤减暂停）已随多目录
 * 改写退场，其纯函数与用例一并删除，不再覆盖。
 */
class WebDavSyncTest {

    @Test
    fun dictDownloadAction_mapsTriState() {
        assertEquals(DictDownloadAction.IMPORT, WebDavSync.dictDownloadAction(HttpGetResult.OK))
        assertEquals(DictDownloadAction.SKIP, WebDavSync.dictDownloadAction(HttpGetResult.NOT_FOUND))
        assertEquals(DictDownloadAction.FAIL, WebDavSync.dictDownloadAction(HttpGetResult.FAILED))
        // 远端空文件在词典侧仍按本词典失败计（现行部分成功口径不变）。
        assertEquals(DictDownloadAction.FAIL, WebDavSync.dictDownloadAction(HttpGetResult.EMPTY))
    }

    @Test
    fun schemeDownloadAction_mapsQuadState() {
        assertEquals(WebDavSync.SchemeDownloadAction.COUNT, WebDavSync.schemeDownloadAction(HttpGetResult.OK))
        assertEquals(WebDavSync.SchemeDownloadAction.IGNORE, WebDavSync.schemeDownloadAction(HttpGetResult.NOT_FOUND))
        assertEquals(WebDavSync.SchemeDownloadAction.FAIL, WebDavSync.schemeDownloadAction(HttpGetResult.FAILED))
        // 远端空文件在方案侧只跳过、不阻断其余文件提交。
        assertEquals(WebDavSync.SchemeDownloadAction.SKIP_EMPTY, WebDavSync.schemeDownloadAction(HttpGetResult.EMPTY))
    }

    @Test
    fun isSyncSkippedDict_statsOnly() {
        // stats 是输入统计数据库（方案 input_stats.db_name），不是用户词库，同步跳过。
        assertTrue(WebDavSync.isSyncSkippedDict("stats"))
        assertFalse(WebDavSync.isSyncSkippedDict("wanxiang_lite"))
        assertFalse(WebDavSync.isSyncSkippedDict("wanxiang"))
    }

    // ---- 截图 6 项：词典同步健壮性 ----

    @Test
    fun safeDictName_rejectsTraversal() {
        assertTrue(WebDavSync.isSafeDictName("wanxiang_lite"))
        assertFalse(WebDavSync.isSafeDictName(""))
        assertFalse(WebDavSync.isSafeDictName("   "))
        assertFalse(WebDavSync.isSafeDictName("../escape"))
        assertFalse(WebDavSync.isSafeDictName("a/b"))
        assertFalse(WebDavSync.isSafeDictName("a\\b"))
        assertFalse(WebDavSync.isSafeDictName(".."))
        assertFalse(WebDavSync.isSafeDictName("a..b"))
        assertFalse(WebDavSync.isSafeDictName("."))
    }

    @Test
    fun safeRemoteFile_rejectsTraversalSegments() {
        assertTrue(WebDavSync.isSafeRemoteFile("wanxiang_lite.userdict.txt"))
        assertTrue(WebDavSync.isSafeRemoteFile("sub/dir/file.txt"))
        assertFalse(WebDavSync.isSafeRemoteFile(""))
        assertFalse(WebDavSync.isSafeRemoteFile("../escape.txt"))
        assertFalse(WebDavSync.isSafeRemoteFile("a//b.txt"))
        assertFalse(WebDavSync.isSafeRemoteFile("a/./b.txt"))
        assertFalse(WebDavSync.isSafeRemoteFile("a\\b.txt"))
        assertFalse(WebDavSync.isSafeRemoteFile("/abs.txt"))
    }

    @Test
    fun syncQuota_blocksOverBudget() {
        val quota = WebDavSync.SyncQuota(100L)
        assertTrue(quota.tryAdd(60L))
        assertTrue(quota.tryAdd(40L))
        assertEquals(100L, quota.usedBytes)
        assertFalse(quota.tryAdd(1L))
        assertEquals(100L, quota.usedBytes)
        assertFalse(quota.tryAdd(-1L))
    }

    @Test
    fun throttleWaitMs_math() {
        // 8MB/s：已读 8MB、耗时 500ms → 还需等 500ms。
        assertEquals(500L, WebDavSync.throttleWaitMs(8L * 1024 * 1024, 500L, 8L * 1024 * 1024))
        // 已读 8MB、耗时 1200ms → 不超速，不等。
        assertEquals(0L, WebDavSync.throttleWaitMs(8L * 1024 * 1024, 1200L, 8L * 1024 * 1024))
        assertEquals(0L, WebDavSync.throttleWaitMs(0L, 0L, 8L * 1024 * 1024))
        assertEquals(0L, WebDavSync.throttleWaitMs(100L, 0L, 0L))
    }

    @Test
    fun throttledStream_passesBytesAtHighRate() {
        val data = "hello webdav".toByteArray()
        val stream = WebDavSync.ThrottledInputStream(
            data.inputStream(), Long.MAX_VALUE / 2,
        )
        val out = stream.readBytes()
        assertTrue(out.contentEquals(data))
        stream.close()
    }

    // ---- C5 原子替换 ----

    @Test
    fun atomicReplace_success_replacesTarget() {
        val dir = Files.createTempDirectory("webdav-atomic").toFile()
        try {
            val target = File(dir, "t.yaml").apply { writeText("old") }
            val tmp = File(dir, "t.yaml.tmp").apply { writeText("new") }
            assertTrue(WebDavSync.atomicReplace(tmp, target))
            assertEquals("new", target.readText())
            assertFalse(tmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun atomicReplace_failure_keepsOldTargetAndDeletesTmp() {
        val dir = Files.createTempDirectory("webdav-atomic").toFile()
        try {
            val target = File(dir, "t.yaml").apply { writeText("old") }
            val tmp = File(dir, "t.yaml.tmp").apply { writeText("new") }
            assertFalse(WebDavSync.atomicReplace(tmp, target) { _, _ -> false })
            assertEquals("old", target.readText())
            assertFalse(tmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 上传前父目录链（FAB 方案上传 HTTP 409 修复） ----
    // WebDAV MKCOL 只建单级，PUT 的父目录缺失即 409；上传前必须自顶向下逐级建。

    @Test
    fun remoteParentDirRels_topLevelFile() {
        assertEquals(
            listOf("schemes"),
            WebDavSync.remoteParentDirRels("schemes/file.yaml"),
        )
    }

    @Test
    fun remoteParentDirRels_oneDirLevel() {
        assertEquals(
            listOf("schemes", "schemes/default"),
            WebDavSync.remoteParentDirRels("schemes/default/wanxiang_lite.schema.yaml"),
        )
    }

    @Test
    fun remoteParentDirRels_deepNesting_topDown() {
        assertEquals(
            listOf("schemes", "schemes/default", "schemes/default/a", "schemes/default/a/b"),
            WebDavSync.remoteParentDirRels("schemes/default/a/b/c.yaml"),
        )
    }

    // ---- PUT 前复读重试（词典上传轻量加固，不用 ETag/If-Match） ----
    // 只测决策循环本身（哈希序列驱动），网络与引擎不在此测。

    @Test
    fun putWithRereadRetry_unchanged_putsWithoutMerge() = runBlocking {
        var merges = 0
        var puts = 0
        WebDavSync.putWithRereadRetry(
            initialHash = "A",
            fetchHash = { "A" },
            mergeAndReexport = { merges++; "A" },
            put = { puts++ },
        )
        assertEquals(0, merges)
        assertEquals(1, puts)
    }

    @Test
    fun putWithRereadRetry_firstCreate_putsWithoutMerge() = runBlocking {
        var merges = 0
        var puts = 0
        WebDavSync.putWithRereadRetry(
            initialHash = null,
            fetchHash = { null },
            mergeAndReexport = { merges++; null },
            put = { puts++ },
        )
        assertEquals(0, merges)
        assertEquals(1, puts)
    }

    @Test
    fun putWithRereadRetry_changedOnce_mergesOnceThenPuts() = runBlocking {
        val fetched = mutableListOf("B", "B")
        var merges = 0
        var puts = 0
        WebDavSync.putWithRereadRetry(
            initialHash = "A",
            fetchHash = { fetched.removeAt(0) },
            mergeAndReexport = { merges++; "B" },
            put = { puts++ },
        )
        assertEquals(1, merges)
        assertEquals(1, puts)
    }

    @Test
    fun putWithRereadRetry_missingThenCreated_mergesOnceThenPuts() = runBlocking {
        val fetched = mutableListOf<String?>("X", "X")
        var merges = 0
        var puts = 0
        WebDavSync.putWithRereadRetry(
            initialHash = null,
            fetchHash = { fetched.removeAt(0) },
            mergeAndReexport = { merges++; "X" },
            put = { puts++ },
        )
        assertEquals(1, merges)
        assertEquals(1, puts)
    }

    @Test
    fun putWithRereadRetry_alwaysChanging_putsLatestAfterBound() = runBlocking {
        var fetched = 0
        var merges = 0
        var puts = 0
        WebDavSync.putWithRereadRetry(
            initialHash = "A",
            fetchHash = { "R${++fetched}" },
            mergeAndReexport = { merges++; "R$fetched" },
            put = { puts++ },
            maxAttempts = 3,
        )
        assertEquals(3, merges)
        assertEquals(1, puts)
    }

    @Test
    fun putWithRereadRetry_fetchFails_abortsWithoutPut() = runBlocking {
        var puts = 0
        try {
            WebDavSync.putWithRereadRetry(
                initialHash = "A",
                fetchHash = { throw IllegalStateException("复读失败") },
                mergeAndReexport = { "A" },
                put = { puts++ },
            )
            fail("复读失败应直接抛错，不应 PUT")
        } catch (e: IllegalStateException) {
            assertEquals("复读失败", e.message)
        }
        assertEquals(0, puts)
    }
}
