package com.jobeen.ime

import com.jobeen.ime.engine.BeforeCursorCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 前文缓存（S16 简化后）的规格：只存最近一次读取到的前文整体，
 * 发布整体替换、失效整体置空。原快照三件套（内容＋读取时刻＋
 * 所属代次、TTL 命中判定、发布期代次复检）已随 S16 拆除——防串
 * 框改由 RimeEngine 以输入框序号整数比对整轮作废，不属本类职责，
 * 原对应五条用例（publishThenHitWithinTtlAndSameGen 的 TTL/代次
 * 部分、staleGenWriteBackIsRejected、
 * rejectedWriteBackDoesNotClobberNewerSnapshot、
 * snapshotOfOldGenMissesAfterGenAdvance、expiredSnapshotMisses）
 * 一并删除。
 */
class BeforeCursorCacheTest {

    @Test
    fun publishThenHit() {
        val cache = BeforeCursorCache()
        cache.publish("FIELD_A")
        assertEquals("FIELD_A", cache.cachedText())
    }

    @Test
    fun publishReplacesPrevious() {
        val cache = BeforeCursorCache()
        cache.publish("FIELD_A")
        cache.publish("FIELD_B")
        assertEquals("FIELD_B", cache.cachedText())
    }

    @Test
    fun invalidateDropsText() {
        val cache = BeforeCursorCache()
        cache.publish("FIELD_A")
        cache.invalidate()
        assertNull(cache.cachedText())
    }

    @Test
    fun emptyCacheReturnsNull() {
        val cache = BeforeCursorCache()
        assertNull(cache.cachedText())
    }
}
