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
        val store = UserCollocationStore(File("nonexistent-dir-test/store.tsv"))
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
        val store = UserCollocationStore(File("nonexistent-dir-test/store2.tsv"))
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
    fun parseSkipsMalformedLines() {
        val back = HashMap<String, HashMap<String, UserCollocationStore.Entry>>()
        UserCollocationStore.parseInto(
            "坏行\n我想\t吃饭\t不是数字\t100\n我想\t喝水\t3\t100\n\t\t5\t100\n我想\t睡觉\t0\t100\n",
            back
        )
        assertEquals(1, back["我想"]?.size)
        assertEquals(3, back["我想"]?.get("喝水")?.count)
    }
}
