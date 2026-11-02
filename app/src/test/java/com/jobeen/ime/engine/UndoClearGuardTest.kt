package com.jobeen.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 组字清除后防撤销复活（2026-11-02 视频报）：守卫的 arm/consume 语义。 */
class UndoClearGuardTest {

    @Test
    fun videoCaseTwoClearsThenUndoStripsWholeAdded() {
        // 视频：第一次上滑清组字 "ming tian"（引擎组字串），第二次上滑清
        // 已上屏 "你好"（差分段被首次守卫挡下不覆盖），下滑撤销复活
        // "你好ming tian"——增量以守卫段结尾，整段增量都应删。
        val guard = UndoClearGuard()
        guard.arm("ming tian")
        guard.armFromDiff("你好", "")
        assertEquals(11, guard.consume("", "你好ming tian"))
    }

    @Test
    fun singleClearStripsResurrectedSuffix() {
        val guard = UndoClearGuard()
        guard.arm("ming tian")
        assertEquals(9, guard.consume("你好", "你好ming tian"))
    }

    @Test
    fun diffArmsRemovedSegmentOnly() {
        val guard = UndoClearGuard()
        guard.armFromDiff("你好", "")
        assertTrue(guard.hasGuard())
        assertEquals(0, guard.consume("", "你好ming tian"))
        // 第二次消费已失效（一次性），即使匹配也不再删。
        assertEquals(0, guard.consume("", "你好"))
    }

    @Test
    fun consumeIsOneShot() {
        val guard = UndoClearGuard()
        guard.arm("X")
        assertEquals(0, guard.consume("a", "b"))
        assertEquals(0, guard.consume("", "X"))
        assertFalse(guard.hasGuard())
    }

    @Test
    fun consumeIgnoresNonPureInsertion() {
        val guard = UndoClearGuard()
        guard.arm("bc")
        // 撤销改动了旧文本（非纯增），不删。
        assertEquals(0, guard.consume("abc", "xbc"))
    }

    @Test
    fun unrelatedUndoIsUntouched() {
        val guard = UndoClearGuard()
        guard.arm("ming tian")
        assertEquals(0, guard.consume("你好", "你好啊"))
        // 未命中也已消费：守卫不滞留。
        assertFalse(guard.hasGuard())
    }

    @Test
    fun clearAndBlankArmsAreNoops() {
        val guard = UndoClearGuard()
        guard.arm("")
        guard.arm(null)
        guard.armFromDiff("同", "同")
        guard.armFromDiff("ab", "xb")
        assertFalse(guard.hasGuard())
        guard.arm("X")
        guard.clear()
        assertEquals(0, guard.consume("", "X"))
    }

    @Test
    fun firstArmWinsOverLaterClears() {
        val guard = UndoClearGuard()
        guard.arm("A")
        guard.arm("B")
        guard.armFromDiff("C", "")
        assertEquals(1, guard.consume("X", "XA"))
    }
}
