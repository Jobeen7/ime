// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.keyboard.key.resolveSwipeReleaseForward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 符号板／颜文字板左右滑抬手决议（P-低1）：拦截瞬间锁存的方向优先，
 * 抬手不再按抬手瞬间位移重判——“滑出 48dp 后回缩再抬手”不再落入
 * 死区（点按已被拦截取消，翻页／切分类也不发生）。
 * 锁存语义：越过阈值后反向滑回再抬手，仍按锁存方向翻页（锁定决定）。
 * 未锁存时才按抬手位移判定；小于 48dp 仍是点按（W13-1 定论，不动）。
 * 板级触摸接线本身需 View/MotionEvent，走真机验收（见 SymbolSwipeTest）。
 */
class SymbolSwipeLatchTest {

    private val min = 48f

    // ── 锁存优先：回缩／漂移不再重判 ──

    @Test
    fun latchedLeft_releaseRetracted_stillNext() {
        // 左滑越过 48dp 后回缩到 10dp 再抬手：旧口径判 null（死区）。
        assertEquals(true, resolveSwipeReleaseForward(-120f, 10f, 0f, min))
    }

    @Test
    fun latchedLeft_releaseDriftedVertical_stillNext() {
        // 左滑越过后手指漂移成竖向占优再抬手：旧口径判 null（死区）。
        assertEquals(true, resolveSwipeReleaseForward(-120f, 10f, 100f, min))
    }

    @Test
    fun latchedRight_releaseRetracted_stillPrev() {
        assertEquals(false, resolveSwipeReleaseForward(120f, -5f, 0f, min))
    }

    @Test
    fun latchedRight_releaseDriftedVertical_stillPrev() {
        assertEquals(false, resolveSwipeReleaseForward(120f, -5f, 100f, min))
    }

    @Test
    fun latchedLeft_releaseReversedPastThreshold_stillNext() {
        // 锁定决定：越过阈值后反向滑过对侧阈值再抬手，仍按锁存方向。
        assertEquals(true, resolveSwipeReleaseForward(-120f, 80f, 0f, min))
    }

    @Test
    fun latchedAtMinDistance_counts() {
        assertEquals(true, resolveSwipeReleaseForward(-48f, 0f, 0f, min))
        assertEquals(false, resolveSwipeReleaseForward(48f, 0f, 0f, min))
    }

    @Test
    fun latchedZero_isNone() {
        // 退化输入：锁存位移为 0（拦截不可能产生）按无方向处理，不翻页。
        assertNull(resolveSwipeReleaseForward(0f, 120f, 0f, min))
    }

    // ── 未锁存：按抬手位移判定（旧口径原样保留）──

    @Test
    fun unlatched_releaseBeyondThreshold_judgedByRelease() {
        assertEquals(true, resolveSwipeReleaseForward(null, -120f, 5f, min))
        assertEquals(false, resolveSwipeReleaseForward(null, 120f, -5f, min))
    }

    @Test
    fun unlatched_releaseBelowThreshold_isNone() {
        // 小于 48dp 的滑动仍是点按，不翻页。
        assertNull(resolveSwipeReleaseForward(null, -47f, 0f, min))
        assertNull(resolveSwipeReleaseForward(null, 47f, 0f, min))
        assertNull(resolveSwipeReleaseForward(null, 20f, 0f, min))
    }

    @Test
    fun unlatched_releaseVerticalDominant_isNone() {
        assertNull(resolveSwipeReleaseForward(null, -120f, 130f, min))
        assertNull(resolveSwipeReleaseForward(null, 60f, -80f, min))
    }

    @Test
    fun unlatched_releaseEqualAxes_isNone() {
        assertNull(resolveSwipeReleaseForward(null, -100f, 100f, min))
    }
}
