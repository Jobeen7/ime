package com.jobeen.ime

import android.content.ContextWrapper
import com.jobeen.ime.base.ngram.GramDb
import com.jobeen.ime.engine.data.EngineMessage.Candidate
import com.jobeen.ime.engine.manager.CandidateRerankManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重排窗口与 gram 分缓存（2026-11-16 性能 ②）：
 *
 * - 窗口：只重排首屏可见数——下标 0 固定，1..11 进窗；窗口外保持
 *   引擎原始序（旧窗 1..25 时每批对 24 个候选各做一次 gram 查询）。
 * - gram 缓存：同一组字会话内按（上下文，词）缓存 gram 分；gramDb
 *   实例变化时整表清掉。manager 实例间不共享缓存，测试每例用新
 *   manager（与 CandidateRerankKeycapTest 共用单例的做法不同）。
 *
 * CandidateRerankManager 的构造 Context 在 rerank 路径上不被调用，
 * 用 ContextWrapper(null) 占位（mockable android.jar 下可实例化）。
 */
class CandidateRerankWindowTest {

    private fun manager() = CandidateRerankManager(ContextWrapper(null))

    private fun candidates(vararg texts: String): List<Candidate> =
        texts.mapIndexed { i, t -> Candidate(index = i, text = t) }

    /** 按 .gram 文件头布局造最小有效文件：32 字节格式串＋空数组（见 GramDbTest）。 */
    private fun gramFile(dir: File, name: String = "test.gram"): File {
        val totalSize = 44
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        val fmtBytes = "Rime::Grammar/test".toByteArray(Charsets.UTF_8)
        buf.put(fmtBytes.copyOf(minOf(32, totalSize)))
        buf.position(36)
        buf.putInt(0) // arraySize = 0：查询恒 0.0 分，缓存仍记分
        buf.putInt(0) // arrayOffset
        val file = File(dir, name)
        file.writeBytes(buf.array())
        return file
    }

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("rerank-gram-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun window_onlyFirstScreenReranked_tailKeepsEngineOrder() {
        val input = candidates(*(0 until 25).map { "w%02d".format(it) }.toTypedArray())
        val result = runBlocking {
            manager().rerank(
                input, inputContext = "", gramDb = null,
                prefers = mapOf("w20" to 100, "w05" to 50),
            )
        }
        // 下标 0 固定；窗内偏好最高的 w05 升到首位
        assertEquals("w00", result[0].text)
        assertEquals("w05", result[1].text)
        assertEquals(5, result[1].index)
        // 窗外 w20 偏好再高也不进窗：位置与原始序一致，尾段原样
        assertEquals(20, result.indexOfFirst { it.text == "w20" })
        assertEquals(
            (11 until 25).map { "w%02d".format(it) },
            result.subList(11, 25).map { it.text },
        )
        assertEquals((0 until 25).toList(), result.map { it.index }.sorted())
    }

    @Test
    fun gramCache_recordsWindowScoresOnce_andHitsOnSecondRerank() {
        runWithTempDir { root ->
            val db = GramDb(gramFile(root).absolutePath)
            val m = manager()
            val input = candidates(*(0 until 15).map { ('a' + it).toString() }.toTypedArray())
            val first = runBlocking {
                m.rerank(input, inputContext = "你好", gramDb = db, prefers = emptyMap())
            }
            // 窗内 10 个候选各查一次 gram：缓存记 10 条
            assertEquals(10, m.gramCacheSize())
            val second = runBlocking {
                m.rerank(input, inputContext = "你好", gramDb = db, prefers = emptyMap())
            }
            // 同一批再排全是命中：不新增条目，结果与首排一致
            assertEquals(10, m.gramCacheSize())
            assertEquals(first.map { it.text }, second.map { it.text })
            // 空模型分恒 0：零偏好时重排保序
            assertEquals(input.map { it.text }, first.map { it.text })
        }
    }

    @Test
    fun gramCache_clearedWhenGramDbInstanceChanges() {
        runWithTempDir { root ->
            val m = manager()
            val input = candidates(*(0 until 15).map { ('a' + it).toString() }.toTypedArray())
            val db1 = GramDb(gramFile(root, "one.gram").absolutePath)
            runBlocking { m.rerank(input, inputContext = "你好", gramDb = db1, prefers = emptyMap()) }
            assertEquals(10, m.gramCacheSize())
            // 换实例＋换上下文（新键）：不清表会是 20 条，清表后仍是 10 条
            val db2 = GramDb(gramFile(root, "two.gram").absolutePath)
            runBlocking { m.rerank(input, inputContext = "世界", gramDb = db2, prefers = emptyMap()) }
            assertEquals(10, m.gramCacheSize())
        }
    }

    @Test
    fun gramCache_notUsedWithoutGramDbOrContext() {
        val m = manager()
        val input = candidates(*(0 until 15).map { ('a' + it).toString() }.toTypedArray())
        runBlocking { m.rerank(input, inputContext = "你好", gramDb = null, prefers = emptyMap()) }
        assertEquals(0, m.gramCacheSize())
        runWithTempDir { root ->
            val db = GramDb(gramFile(root).absolutePath)
            runBlocking { m.rerank(input, inputContext = "", gramDb = db, prefers = emptyMap()) }
            assertEquals(0, m.gramCacheSize())
        }
    }

    @Test
    fun window_shortListUnchanged() {
        // 候选只有 2 个时窗口只有下标 1 一个元素：重排不改变顺序
        //（rerank 对 size<=1 才原样返回，2 个候选会新建列表，故
        // 只断言内容与顺序不变，不断言同一实例）
        val input = candidates("去", "丘")
        val result = runBlocking {
            manager().rerank(input, inputContext = "", gramDb = null, prefers = mapOf("丘" to 9))
        }
        assertEquals(listOf("去", "丘"), result.map { it.text })
        assertEquals(listOf(0, 1), result.map { it.index })
    }
}
