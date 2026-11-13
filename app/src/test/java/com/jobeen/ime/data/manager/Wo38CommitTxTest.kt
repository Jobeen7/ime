// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选提交恰好一次守卫（WO38 E1-1）：状态空间分区——
 * 首次／重复／键区分／TTL 过期／撤销释放／归属失效／容量淘汰。
 * 接线本身在红线文件内（见 CommitTxGuard 类注释），本测试只钉
 * 守卫语义；时钟可注入，不 sleep。
 */
class Wo38CommitTxTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun advance(ms: Long) {
            now += ms
        }
    }

    private fun guard(clock: FakeClock, maxKeys: Int = 1024, ttlMs: Long = 60_000L) =
        CommitTxGuard(maxKeys = maxKeys, ttlMs = ttlMs, nowMs = { clock.now })

    @Test
    fun firstClaim_accepted_secondDuplicate_rejected() {
        val clock = FakeClock()
        val g = guard(clock)
        val key = CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)
        assertTrue(g.tryClaim(key))
        assertFalse(g.tryClaim(key))
        assertTrue(g.isClaimed(key))
    }

    @Test
    fun differentText_isIndependentClaim() {
        val g = guard(FakeClock())
        assertTrue(g.tryClaim(CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)))
        assertTrue(g.tryClaim(CommitTxGuard.key("你", "ni", "wanxiang", 7L)))
    }

    @Test
    fun differentPreedit_isIndependentClaim() {
        // 同一词、不同组字码 = 两次独立提交，各计一次
        val g = guard(FakeClock())
        assertTrue(g.tryClaim(CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)))
        assertTrue(g.tryClaim(CommitTxGuard.key("你好", "ni'hao", "wanxiang", 7L)))
    }

    @Test
    fun differentSchemaOrField_isIndependentClaim() {
        val g = guard(FakeClock())
        assertTrue(g.tryClaim(CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)))
        assertTrue(g.tryClaim(CommitTxGuard.key("你好", "nihao", "wanxiang_lite", 7L)))
        assertTrue(g.tryClaim(CommitTxGuard.key("你好", "nihao", "wanxiang", 8L)))
    }

    @Test
    fun expiredClaim_canBeClaimedAgain() {
        val clock = FakeClock()
        val g = guard(clock, ttlMs = 1_000L)
        val key = CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)
        assertTrue(g.tryClaim(key))
        clock.advance(999L)
        assertFalse(g.tryClaim(key))
        clock.advance(1L)
        assertTrue(g.tryClaim(key))
    }

    @Test
    fun release_allowsReclaim_unknownRelease_isFalse() {
        val g = guard(FakeClock())
        val key = CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)
        assertFalse(g.release(key))
        assertTrue(g.tryClaim(key))
        assertTrue(g.release(key))
        assertFalse(g.isClaimed(key))
        assertTrue(g.tryClaim(key))
    }

    @Test
    fun invalidateAll_dropsAllClaims() {
        val g = guard(FakeClock())
        val k1 = CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)
        val k2 = CommitTxGuard.key("世界", "shijie", "wanxiang", 7L)
        assertTrue(g.tryClaim(k1))
        assertTrue(g.tryClaim(k2))
        g.invalidateAll()
        assertFalse(g.isClaimed(k1))
        assertFalse(g.isClaimed(k2))
        assertTrue(g.tryClaim(k1))
    }

    @Test
    fun overCapacity_evictsEldest_eldestReclaimable() {
        val g = guard(FakeClock(), maxKeys = 2)
        val k1 = CommitTxGuard.key("甲", "jia", "wanxiang", 1L)
        val k2 = CommitTxGuard.key("乙", "yi", "wanxiang", 1L)
        val k3 = CommitTxGuard.key("丙", "bing", "wanxiang", 1L)
        assertTrue(g.tryClaim(k1))
        assertTrue(g.tryClaim(k2))
        assertTrue(g.tryClaim(k3))
        assertEquals(2, g.claimedCount())
        // k1 最旧被淘汰：可再次认领；认领时淘汰次旧的 k2，k3 不动
        assertTrue(g.tryClaim(k1))
        assertEquals(2, g.claimedCount())
        assertTrue(g.isClaimed(k1))
        assertTrue(g.isClaimed(k3))
        assertFalse(g.isClaimed(k2))
        // 再认领 k2 同样成功，并淘汰当时最旧的 k3
        assertTrue(g.tryClaim(k2))
        assertFalse(g.isClaimed(k3))
        assertEquals(2, g.claimedCount())
    }

    @Test
    fun key_isDeterministic_andDistinguishesFields() {
        val a = CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)
        val b = CommitTxGuard.key("你好", "nihao", "wanxiang", 7L)
        assertEquals(a, b)
        assertFalse(a == CommitTxGuard.key("你好", "nihao", "wanxiang", 8L))
        assertFalse(a == CommitTxGuard.key("你好", "ni'hao", "wanxiang", 7L))
    }
}
