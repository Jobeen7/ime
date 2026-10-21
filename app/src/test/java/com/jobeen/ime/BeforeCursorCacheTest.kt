package com.jobeen.ime

import com.jobeen.ime.engine.BeforeCursorCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前文缓存快照（R4）的规格：内容＋读取时刻＋所属代次单快照发
 * 布；命中须未过 TTL 且代次与当前一致；发布须发布时点代次仍
 * 等于读取时捕获的代次——读取飞行中换框的旧结果不得回填。
 * 时间与代次全部显式喂入，确定性可测。
 */
class BeforeCursorCacheTest {

    @Test
    fun publishThenHitWithinTtlAndSameGen() {
        val cache = BeforeCursorCache()
        assertTrue(cache.publish("OLD_FIELD_A", capturedAtMs = 1_000L, capturedGen = 3L, currentGen = 3L))
        assertEquals("OLD_FIELD_A", cache.freshText(nowMs = 1_500L, currentGen = 3L))
    }

    @Test
    fun staleGenWriteBackIsRejected() {
        // R4 本体：A 框（代次 3）读取飞行中换到 B 框（代次推进
        // 到 4）并已失效，旧 worker 随后提交——发布被拒，缓存
        // 保持为空，B 的后续读取不会命中 A 的前文
        val cache = BeforeCursorCache()
        assertFalse(cache.publish("OLD_FIELD_A", capturedAtMs = 1_000L, capturedGen = 3L, currentGen = 4L))
        assertNull(cache.freshText(nowMs = 1_100L, currentGen = 4L))
    }

    @Test
    fun rejectedWriteBackDoesNotClobberNewerSnapshot() {
        // B 框已发布自己的快照后，A 的迟到回填（代次不符）不
        // 得覆盖 B 的快照
        val cache = BeforeCursorCache()
        assertTrue(cache.publish("FIELD_B", capturedAtMs = 2_000L, capturedGen = 4L, currentGen = 4L))
        assertFalse(cache.publish("OLD_FIELD_A", capturedAtMs = 1_000L, capturedGen = 3L, currentGen = 4L))
        assertEquals("FIELD_B", cache.freshText(nowMs = 2_100L, currentGen = 4L))
    }

    @Test
    fun snapshotOfOldGenMissesAfterGenAdvance() {
        // 代次一致正常命中；代次推进后同一快照即使未过期也不命中
        val cache = BeforeCursorCache()
        assertTrue(cache.publish("FIELD_A", capturedAtMs = 1_000L, capturedGen = 3L, currentGen = 3L))
        assertEquals("FIELD_A", cache.freshText(nowMs = 1_200L, currentGen = 3L))
        assertNull(cache.freshText(nowMs = 1_200L, currentGen = 4L))
    }

    @Test
    fun expiredSnapshotMisses() {
        val cache = BeforeCursorCache()
        assertTrue(cache.publish("FIELD_A", capturedAtMs = 1_000L, capturedGen = 3L, currentGen = 3L))
        // 恰在 TTL 边界内仍命中，越界未命中
        assertEquals("FIELD_A", cache.freshText(nowMs = 1_000L + BeforeCursorCache.TTL_MS, currentGen = 3L))
        assertNull(cache.freshText(nowMs = 1_000L + BeforeCursorCache.TTL_MS + 1L, currentGen = 3L))
    }

    @Test
    fun invalidateDropsSnapshot() {
        val cache = BeforeCursorCache()
        assertTrue(cache.publish("FIELD_A", capturedAtMs = 1_000L, capturedGen = 3L, currentGen = 3L))
        cache.invalidate()
        assertNull(cache.freshText(nowMs = 1_100L, currentGen = 3L))
    }

    @Test
    fun emptyCacheMisses() {
        val cache = BeforeCursorCache()
        assertNull(cache.freshText(nowMs = 1_000L, currentGen = 0L))
    }
}
