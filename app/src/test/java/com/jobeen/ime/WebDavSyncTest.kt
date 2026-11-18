// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.UserDictMergeSafety
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.DictDownloadAction
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.HttpGetResult
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * WebDAV 同步的 JVM 可测部分：词典/方案下载四态映射与原子替换。
 * 旧目录同步（mtime 末写胜出、删除传播、骤减暂停）已随多目录
 * 改写退场，其纯函数与用例一并删除，不再覆盖。
 * 1.6：远端有坏行时停 PUT（守卫＋模拟服务全流程，见文件末尾）。
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

    // ---- 1.6：远端有坏行时停 PUT ----

    @Test
    fun checkNoSkippedRowsForUpload_noSkips_passes() {
        // 无跳过行不拦：正常上传不受影响。
        WebDavSync.checkNoSkippedRowsForUpload(
            UserDictMergeSafety.MergeResult(importedCount = 3),
            "wanxiang_lite",
        )
    }

    @Test
    fun checkNoSkippedRowsForUpload_withSkips_throwsNamingRows() {
        val error = assertThrows(IllegalStateException::class.java) {
            WebDavSync.checkNoSkippedRowsForUpload(
                UserDictMergeSafety.MergeResult(
                    importedCount = 2,
                    skippedRows = 2,
                    skippedLineNumbers = setOf(2, 5),
                    skipped = listOf(
                        UserDictMergeSafety.Issue(2, "BAD_WEIGHT", "权重不是整数"),
                        UserDictMergeSafety.Issue(5, "BAD_COLUMNS", "应为 2 或 3 列"),
                    ),
                ),
                "wanxiang_lite",
            )
        }
        val message = error.message ?: ""
        assertTrue(message.contains("wanxiang_lite"))
        assertTrue(message.contains("2 行"))
        assertTrue(message.contains("第 2 行"))
        assertTrue(message.contains("第 5 行"))
        assertTrue(message.contains("未覆盖远端"))
    }

    /**
     * 模拟 WebDAV 服务：内存里的远端词典文件，GET 读、PUT 写，
     * 记下每次 PUT 的请求体。只供 1.6 全流程测试。
     */
    private class MockDictServer(initialContent: String) {
        private val content = AtomicReference(initialContent)
        val putBodies: MutableList<String> =
            Collections.synchronizedList(mutableListOf())
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/wanxiang_lite.userdict.txt") { exchange ->
                when (exchange.requestMethod) {
                    "GET" -> {
                        val bytes = content.get().toByteArray(Charsets.UTF_8)
                        exchange.sendResponseHeaders(200, bytes.size.toLong())
                        exchange.responseBody.write(bytes)
                        exchange.responseBody.close()
                    }
                    "PUT" -> {
                        val body = exchange.requestBody.bufferedReader().readText()
                        putBodies += body
                        content.set(body)
                        exchange.sendResponseHeaders(200, -1)
                        exchange.close()
                    }
                    else -> {
                        exchange.sendResponseHeaders(405, -1)
                        exchange.close()
                    }
                }
            }
            start()
        }

        val url: String get() = "http://127.0.0.1:${server.address.port}/wanxiang_lite.userdict.txt"

        fun stop() = server.stop(0)
    }

    private fun getRemote(client: OkHttpClient, url: String): String {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            assertTrue(response.isSuccessful)
            return response.body!!.string()
        }
    }

    private fun putRemote(client: OkHttpClient, url: String, body: String) {
        client.newCall(
            Request.Builder().url(url)
                .put(body.toRequestBody("text/plain".toMediaType())).build(),
        ).execute().use { response -> assertTrue(response.isSuccessful) }
    }

    /** 词典文本里的词条集合（每行首列的词，空行与注释行除外）。 */
    private fun entryPhrases(text: String): Set<String> =
        text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.substringBefore('\t') }
            .toSet()

    private suspend fun mergeDownloaded(
        dir: File,
        filesDir: File,
        file: File,
    ): Pair<UserDictMergeSafety.MergeResult, String?> {
        var importedText: String? = null
        val result = UserDictMergeSafety.mergeValidatedBatch(
            dictName = "wanxiang_lite",
            batchFile = file,
            batchLabel = "wanxiang_lite.userdict.txt",
            filesDir = filesDir,
            cacheDir = dir,
            allowEmpty = true,
            exportCurrent = { tmp ->
                tmp.writeText("旧词\tjiu ci\t4\n", Charsets.UTF_8)
                1
            },
            importFile = { f ->
                importedText = f.readText(Charsets.UTF_8)
                importedText!!.lineSequence().filter { it.isNotEmpty() }.count()
            },
        )
        return result to importedText
    }

    @Test
    fun badRemoteRows_downloadMergesGoodRows_uploadStopsPut_remoteUnchanged() = runBlocking {
        // 1.6 全流程（可测接缝级）：真 GET 下载 → 真合并（好行进、坏行
        // 跳）→ 守卫中止上传 → 模拟服务上 PUT 零次、远端词条集合不变。
        // 不经过 uploadLocked 本身——它要 Android appContext 与原生
        // RimeSession，JVM 单测无此环境（无 Robolectric）；生产接线
        // （downloadLocked→守卫→putWithRereadRetry）与本测试的组合
        // 同构：初次下载后的守卫与复读重合并内的守卫各一处，见
        // uploadLocked 内注释。
        val remoteBefore = "你好\tni hao\t2\n世界\tshi jie\t-1\n啦啦啦\tla la\t1\n"
        val server = MockDictServer(remoteBefore)
        val client = OkHttpClient()
        val dir = Files.createTempDirectory("webdav-bad-remote-test").toFile()
        val filesDir = Files.createTempDirectory("webdav-bad-remote-files").toFile()
        try {
            val downloaded = File(dir, "download.txt")
                .apply { writeText(getRemote(client, server.url), Charsets.UTF_8) }
            val (result, importedText) = mergeDownloaded(dir, filesDir, downloaded)
            // 下载合并：好行 2 条进本地，坏行（第 2 行负权重）只跳过该行。
            assertEquals(2, result.importedCount)
            assertEquals(1, result.skippedRows)
            assertEquals(setOf(2), result.skippedLineNumbers)
            assertEquals("你好\tni hao\t2\n啦啦啦\tla la\t1\n", importedText)
            // 初次下载后的守卫（uploadLocked 第一处）：有跳过行即中止。
            val error = assertThrows(IllegalStateException::class.java) {
                WebDavSync.checkNoSkippedRowsForUpload(result, "wanxiang_lite")
            }
            assertTrue((error.message ?: "").contains("第 2 行"))
            // 复读重合并内的守卫（uploadLocked 第二处）：远端又变了、
            // 重合并仍有坏行时，putWithRereadRetry 不得 PUT。
            var puts = 0
            try {
                WebDavSync.putWithRereadRetry(
                    initialHash = "stale",
                    fetchHash = { getRemote(client, server.url) },
                    mergeAndReexport = {
                        WebDavSync.checkNoSkippedRowsForUpload(result, "wanxiang_lite")
                        "merged"
                    },
                    put = {
                        puts++
                        putRemote(client, server.url, "你好\tni hao\t2\n")
                    },
                )
                fail("重合并有坏行应中止，不应 PUT")
            } catch (e: IllegalStateException) {
                assertTrue((e.message ?: "").contains("第 2 行"))
            }
            assertEquals(0, puts)
            // 模拟服务验明：PUT 零次，远端词条集合前后一致（坏行未被覆盖）。
            assertTrue(server.putBodies.isEmpty())
            assertEquals(entryPhrases(remoteBefore), entryPhrases(getRemote(client, server.url)))
        } finally {
            server.stop()
            dir.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun checkNoSkippedRowsForUpload_onlyCorruptSkips_passes() = runBlocking {
        // 损坏行（码含 U+FFFD 替换字符）只跳过该行：丢掉无损失，守卫放行、
        // 上传照常。先红后绿：修前守卫见跳过行＞0 即中止。
        val remoteBefore = "你好\tni hao\t2\n世界\tshi jie\t1\n坏行\t�\t1\n啦啦啦\tla la\t1\n"
        val server = MockDictServer(remoteBefore)
        val client = OkHttpClient()
        val dir = Files.createTempDirectory("webdav-corrupt-row-test").toFile()
        val filesDir = Files.createTempDirectory("webdav-corrupt-row-files").toFile()
        try {
            val downloaded = File(dir, "download.txt")
                .apply { writeText(getRemote(client, server.url), Charsets.UTF_8) }
            val (result, importedText) = mergeDownloaded(dir, filesDir, downloaded)
            assertEquals(3, result.importedCount)
            assertEquals(1, result.skippedRows)
            assertEquals(setOf(3), result.skippedLineNumbers)
            assertEquals(setOf(3), result.corruptSkippedLineNumbers)
            assertEquals("你好\tni hao\t2\n世界\tshi jie\t1\n啦啦啦\tla la\t1\n", importedText)
            // 只有损坏行被跳过，守卫放行。
            WebDavSync.checkNoSkippedRowsForUpload(result, "wanxiang_lite")
        } finally {
            server.stop()
            dir.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun checkNoSkippedRowsForUpload_corruptPlusValidSkip_stillThrows() = runBlocking {
        // 损坏行＋有效词被跳过混在一起：有效词（第 2 行负权重）仍要拦，
        // 报错只点名有效词那行，不点名损坏行。
        val remoteBefore = "你好\tni hao\t2\n世界\tshi jie\t-1\n坏行\t�\t1\n啦啦啦\tla la\t1\n"
        val server = MockDictServer(remoteBefore)
        val client = OkHttpClient()
        val dir = Files.createTempDirectory("webdav-corrupt-valid-test").toFile()
        val filesDir = Files.createTempDirectory("webdav-corrupt-valid-files").toFile()
        try {
            val downloaded = File(dir, "download.txt")
                .apply { writeText(getRemote(client, server.url), Charsets.UTF_8) }
            val (result, _) = mergeDownloaded(dir, filesDir, downloaded)
            assertEquals(2, result.importedCount)
            assertEquals(2, result.skippedRows)
            assertEquals(setOf(2, 3), result.skippedLineNumbers)
            assertEquals(setOf(3), result.corruptSkippedLineNumbers)
            val error = assertThrows(IllegalStateException::class.java) {
                WebDavSync.checkNoSkippedRowsForUpload(result, "wanxiang_lite")
            }
            val message = error.message ?: ""
            assertTrue(message.contains("1 行"))
            assertTrue(message.contains("第 2 行"))
            assertFalse(message.contains("第 3 行"))
        } finally {
            server.stop()
            dir.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun checkNoSkippedRowsForUpload_allSkipsCorrupt_passes() {
        // 守卫单测：跳过行全是损坏行即放行（真机第 858 行码“�”的情形）。
        WebDavSync.checkNoSkippedRowsForUpload(
            UserDictMergeSafety.MergeResult(
                importedCount = 2,
                skippedRows = 1,
                skippedLineNumbers = setOf(858),
                skipped = listOf(
                    UserDictMergeSafety.Issue(
                        858,
                        "bad_code",
                        "码“�”不是小写字母/数字/空格组成的拼音编码",
                    ),
                ),
                corruptSkippedLineNumbers = setOf(858),
            ),
            "wanxiang_lite",
        )
    }

    @Test
    fun cleanRemoteRows_uploadPutsMergedEntries_remoteUpdated() = runBlocking {
        // 对照：远端全是好行时守卫放行，PUT 正常发生，远端词条集合
        // 按合并结果更新（旧行保留＋本地新增词写回）。
        val remoteBefore = "你好\tni hao\t2\n啦啦啦\tla la\t1\n"
        val mergedExport = "你好\tni hao\t2\n啦啦啦\tla la\t1\n本地词\tben di ci\t1\n"
        val server = MockDictServer(remoteBefore)
        val client = OkHttpClient()
        val dir = Files.createTempDirectory("webdav-clean-remote-test").toFile()
        val filesDir = Files.createTempDirectory("webdav-clean-remote-files").toFile()
        try {
            val downloaded = File(dir, "download.txt")
                .apply { writeText(getRemote(client, server.url), Charsets.UTF_8) }
            val (result, _) = mergeDownloaded(dir, filesDir, downloaded)
            assertEquals(2, result.importedCount)
            assertEquals(0, result.skippedRows)
            // 无跳过行，守卫放行。
            WebDavSync.checkNoSkippedRowsForUpload(result, "wanxiang_lite")
            var puts = 0
            WebDavSync.putWithRereadRetry(
                initialHash = getRemote(client, server.url),
                fetchHash = { getRemote(client, server.url) },
                mergeAndReexport = { getRemote(client, server.url) },
                put = {
                    puts++
                    putRemote(client, server.url, mergedExport)
                },
            )
            assertEquals(1, puts)
            assertEquals(1, server.putBodies.size)
            assertEquals(mergedExport, server.putBodies.single())
            assertEquals(
                setOf("你好", "啦啦啦", "本地词"),
                entryPhrases(getRemote(client, server.url)),
            )
        } finally {
            server.stop()
            dir.deleteRecursively()
            filesDir.deleteRecursively()
        }
    }
}
