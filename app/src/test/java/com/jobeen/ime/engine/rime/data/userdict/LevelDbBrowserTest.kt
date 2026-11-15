// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WO52 12-⑭：LevelDB 查看——列表＋键值浏览、引擎线程串行（只读）。 */
class LevelDbBrowserTest {

    /** 假引擎：记录调用线程与顺序；导出时把给定文本写进目标文件。 */
    private class FakeEngine(
        private val dicts: List<String> = listOf("wanxiang", "custom"),
        private val exportText: String = "",
    ) : UserDictEngine {
        val threads = ArrayList<String>()
        val calls = ArrayList<String>()

        override suspend fun listDicts(): List<String> {
            threads += Thread.currentThread().name
            calls += "list"
            return dicts
        }

        override suspend fun exportDict(dictName: String, textFile: String): Int {
            threads += Thread.currentThread().name
            calls += "export:$dictName"
            File(textFile).writeText(exportText, Charsets.UTF_8)
            return exportText.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
        }
    }

    @Test
    fun parse_codeFirstColumns() {
        val entries = LevelDbTextParser.parse(
            "ni hao\t你好\t100\n" +
                "zhong guo\t中国\t50\n",
        )
        assertEquals(2, entries.size)
        assertEquals(LevelDbEntry("ni hao", "你好", 100L), entries[0])
        assertEquals(LevelDbEntry("zhong guo", "中国", 50L), entries[1])
    }

    @Test
    fun parse_phraseFirstColumns() {
        val entries = LevelDbTextParser.parse("你好\tni hao\t100\n")
        assertEquals(listOf(LevelDbEntry("ni hao", "你好", 100L)), entries)
    }

    @Test
    fun parse_skipsCommentsBlankAndSingleColumn() {
        val entries = LevelDbTextParser.parse(
            "# 注释行\n" +
                "\n" +
                "ni hao\t你好\n" +
                "孤行\n" +
                "\uFEFFwo\t我\t3\n",
        )
        assertEquals(
            listOf(
                LevelDbEntry("ni hao", "你好", null),
                LevelDbEntry("wo", "我", 3L),
            ),
            entries,
        )
    }

    @Test
    fun parse_nonCjkPairs_keepFirstAsKey() {
        val entries = LevelDbTextParser.parse("hello\tworld\t7\n")
        assertEquals(listOf(LevelDbEntry("hello", "world", 7L)), entries)
    }

    @Test
    fun page_clampsAndSlices() {
        val entries = (1..120).map { LevelDbEntry("k$it", "词$it", it.toLong()) }
        assertEquals(50, LevelDbTextParser.page(entries, 0, 50).size)
        assertEquals("k51", LevelDbTextParser.page(entries, 1, 50).first().key)
        assertEquals(20, LevelDbTextParser.page(entries, 2, 50).size)
        // 越界回末页。
        assertEquals(20, LevelDbTextParser.page(entries, 9, 50).size)
        assertEquals(50, LevelDbTextParser.page(entries, -1, 50).size)
        assertTrue(LevelDbTextParser.page(emptyList(), 0, 50).isEmpty())
    }

    @Test
    fun browse_listAndPagedEntries_tempFileDeleted() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("leveldb-test").toFile()
        try {
            val text = (1..120).joinToString("\n") { "code$it\t词$it\t$it" }
            val engine = FakeEngine(exportText = text)
            val browser = LevelDbBrowser(engine, tempDir = { tmp })
            assertEquals(listOf("wanxiang", "custom"), browser.listDatabases())
            val first = browser.browse("wanxiang", 0)
            assertEquals("wanxiang", first.dictName)
            assertEquals(120, first.total)
            assertEquals(3, first.totalPages)
            assertEquals(50, first.entries.size)
            assertTrue(first.hasNext)
            assertFalse(first.hasPrev)
            val last = browser.browse("wanxiang", 2)
            assertEquals(20, last.entries.size)
            assertFalse(last.hasNext)
            assertTrue(last.hasPrev)
            // 临时文件读完即删（只读浏览不留文件）。
            assertEquals(0, tmp.listFiles()?.size ?: 0)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun browse_exportFailure_throws() = runBlocking {
        val tmp = kotlin.io.path.createTempDirectory("leveldb-test").toFile()
        try {
            val failing = object : UserDictEngine {
                override suspend fun listDicts(): List<String> = emptyList()
                override suspend fun exportDict(dictName: String, textFile: String): Int = -1
            }
            val browser = LevelDbBrowser(failing, tempDir = { tmp })
            var thrown = false
            try {
                browser.browse("wanxiang", 0)
            } catch (e: IllegalStateException) {
                thrown = true
            }
            assertTrue(thrown)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun engineCalls_runOnSingleSerialThread() = runBlocking {
        // 引擎线程串行断言：浏览器层全部调用走同一单线程分发器，
        // 假引擎看到的调用线程恒为该线程、顺序与发起顺序一致。
        val tmp = kotlin.io.path.createTempDirectory("leveldb-test").toFile()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "leveldb-test-thread")
        }
        try {
            val engine = FakeEngine(exportText = "ni hao\t你好\t1\n")
            val browser = LevelDbBrowser(
                engine,
                engineDispatcher = executor.asCoroutineDispatcher(),
                tempDir = { tmp },
            )
            browser.listDatabases()
            browser.browse("wanxiang", 0)
            browser.listDatabases()
            assertEquals(listOf("list", "export:wanxiang", "list"), engine.calls)
            assertTrue(engine.threads.isNotEmpty())
            assertTrue(
                "调用线程应全为串行线程，实为 ${engine.threads}",
                engine.threads.all { it.startsWith("leveldb-test-thread") },
            )
        } finally {
            executor.shutdown()
            tmp.deleteRecursively()
        }
    }
}
