package com.jobeen.ime

import com.jobeen.ime.base.ngram.UserCollocationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UserCollocationStoreTest {

    @Test
    fun segmentFilter() {
        assertTrue(UserCollocationStore.isLearnableSegment("我们"))
        assertTrue(UserCollocationStore.isLearnableSegment("去吃饭"))
        assertFalse(UserCollocationStore.isLearnableSegment(""))
        assertFalse(UserCollocationStore.isLearnableSegment("。"))
        assertFalse(UserCollocationStore.isLearnableSegment("123"))
        assertFalse(UserCollocationStore.isLearnableSegment("hello"))
        assertFalse(UserCollocationStore.isLearnableSegment("我们 一起"))
        assertFalse(UserCollocationStore.isLearnableSegment("这是一个非常非常长的段落文本"))
    }

    @Test
    fun learnAndContinuationsOrderedByCount() {
        // 临时目录：延时写盘可能在 30 秒后落盘，不得写进工作区
        val dir = java.nio.file.Files.createTempDirectory("collocation-test").toFile()
        val store = UserCollocationStore(File(dir, "store.tsv"))
        repeat(3) { store.learn("我想", "吃饭") }
        repeat(1) { store.learn("我想", "睡觉") }
        repeat(2) { store.learn("我想", "喝水") }
        val result = store.continuations("我想")
        assertEquals(listOf("吃饭", "喝水", "睡觉"), result.map { it.first })
        assertEquals(listOf(3, 2, 1), result.map { it.second })
        assertTrue(store.continuations("没学过").isEmpty())
    }

    @Test
    fun learnRejectsInvalidSegments() {
        val dir = java.nio.file.Files.createTempDirectory("collocation-test").toFile()
        val store = UserCollocationStore(File(dir, "store2.tsv"))
        store.learn("我想", "。")
        store.learn("123", "吃饭")
        assertTrue(store.continuations("我想").isEmpty())
        assertTrue(store.continuations("123").isEmpty())
    }

    @Test
    fun serializeParseRoundtrip() {
        val table = HashMap<String, HashMap<String, UserCollocationStore.Entry>>()
        table["我想"] = hashMapOf(
            "吃饭" to UserCollocationStore.Entry(5, 100L),
            "喝水" to UserCollocationStore.Entry(2, 99L)
        )
        table["然后"] = hashMapOf("回家" to UserCollocationStore.Entry(1, 98L))
        val text = UserCollocationStore.serialize(table)
        val back = HashMap<String, HashMap<String, UserCollocationStore.Entry>>()
        UserCollocationStore.parseInto(text, back)
        assertEquals(5, back["我想"]?.get("吃饭")?.count)
        assertEquals(100L, back["我想"]?.get("吃饭")?.lastDay)
        assertEquals(2, back["我想"]?.get("喝水")?.count)
        assertEquals(1, back["然后"]?.get("回家")?.count)
    }

    @Test
    fun loadFailureDisablesWrites() {
        // 路径被目录占据：读整体失败，本进程内不得落盘覆写历史
        val dir = File.createTempFile("collocation-blocked", "")
        assertTrue(dir.delete() && dir.mkdir())
        val file = File(dir, "store.tsv")
        assertTrue(file.mkdir()) // 目标路径本身是个目录
        val store = UserCollocationStore(file)
        store.learn("我想", "吃饭")
        store.flush()
        Thread.sleep(400) // 若有落盘尝试，给执行器时间跑完
        assertTrue("读失败时不得覆写原路径", file.isDirectory)
        assertFalse(
            "读失败时不得产生 tmp 文件",
            File(file.parentFile, file.name + ".tmp").exists()
        )
    }

    @Test
    fun parseSkipsMalformedLines() {
        val back = HashMap<String, HashMap<String, UserCollocationStore.Entry>>()
        UserCollocationStore.parseInto(
            "坏行\n我想\t吃饭\t不是数字\t100\n我想\t喝水\t3\t100\n\t\t5\t100\n我想\t睡觉\t0\t100\n",
            back
        )
        assertEquals(1, back["我想"]?.size)
        assertEquals(3, back["我想"]?.get("喝水")?.count)
    }

    /** 等条件成立（轮询）；超时返回 false，供异步落盘断言用。 */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun burstWritesAreThrottled() {
        // 突发 400 次学习：旧逻辑每 40 次写盘一次（10 次全表写）；
        // 新逻辑量 cap 撞上节流间隔，只立即写 1 次，其余并进一个延时任务
        val dir = java.nio.file.Files.createTempDirectory("collocation-test").toFile()
        val file = File(dir, "store.tsv")
        val store = UserCollocationStore(file, flushDelayMs = 10_000L)
        repeat(400) { store.learn("我想", "吃饭") }
        assertTrue("量 cap 应触发一次立即写盘", waitFor(5_000) { store.flushCount == 1 })
        Thread.sleep(300)
        assertEquals("节流间隔内不得再写盘", 1, store.flushCount)
        assertTrue("写盘内容应含学到的搭配", file.readText(Charsets.UTF_8).contains("吃饭"))
    }

    @Test
    fun delayedFlushPersistsSparseLearns() {
        // 稀疏打字（不足 40 条）：旧逻辑永远不写盘，进程一死就丢；
        // 新逻辑一个间隔后延时写盘，重载后学习仍在
        val dir = java.nio.file.Files.createTempDirectory("collocation-test").toFile()
        val file = File(dir, "store.tsv")
        val store = UserCollocationStore(file, flushDelayMs = 100L)
        repeat(5) { store.learn("我想", "吃饭") }
        assertTrue("延时写盘应在间隔后落盘", waitFor(5_000) { store.flushCount == 1 })
        val reloaded = UserCollocationStore(file, flushDelayMs = 100L)
        assertEquals(listOf("吃饭" to 5), reloaded.continuations("我想"))
    }

    @Test
    fun flushIsNoopWhenClean() {
        // 无脏数据时 flush() 不写盘：键盘收起／销毁高频调用零开销
        val dir = java.nio.file.Files.createTempDirectory("collocation-test").toFile()
        val store = UserCollocationStore(File(dir, "store.tsv"), flushDelayMs = 10_000L)
        repeat(3) { store.learn("我想", "吃饭") }
        store.flush()
        assertTrue(waitFor(5_000) { store.flushCount == 1 })
        store.flush()
        store.flush()
        Thread.sleep(300)
        assertEquals("干净时重复 flush 不得多写", 1, store.flushCount)
    }
}
