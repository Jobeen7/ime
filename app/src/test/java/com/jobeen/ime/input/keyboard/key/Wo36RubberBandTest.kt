// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回弹内再拖首帧跳变（WO36 I-L6）：拦截成功时进行中的残余拉伸
 * 按 [overshootFromStretch] 反推为拖动基线（虚拟位置）、累计位移
 * 在其上解析，不再 `stretch = 0f` 清零。
 *
 * 旧口径的跳变机理：拦截分支把逻辑 stretch 清零，但子 View 仍带
 * 旧位移（appliedStretch）；下一帧 applyScrollOffset 按
 * stretchDelta = 0 − appliedStretch 一次推回整段残余拉伸，首帧
 * 跳几 px。新口径首帧 dy=0 时解析结果即锚定状态本身（下证），
 * 残余拉伸连续过渡。
 *
 * 附带纠正（同一修法，有意行为变化）：旧 dragBy 拿当前偏移＋单帧
 * dy 解析，越界钉住后拉伸恒等于单帧 dy 的橡皮筋量、从不随总越界
 * 量累积——与 resolveDragOffset 的越界量口径不符；现基线＋累计
 * 位移一次解析，拉伸随总越界量渐近趋近上限（符合橡皮筋设计）。
 *
 * View 实例化与 MotionEvent 在纯 JUnit 下测不了，本文件钉纯函数
 * 层（反推＋基线合成）；拦截分支锚定／dragBy 累计的接线需真机
 * 验收（回弹 260ms 内再按住拖动，首帧无跳变、越界拉伸随拖动渐长）。
 */
class Wo36RubberBandTest {

    private val delta = 0.01f

    // ---- overshootFromStretch ----

    @Test
    fun zeroStretchMapsToZeroOvershoot() {
        assertEquals(0f, overshootFromStretch(0f, 450f), delta)
    }

    @Test
    fun roundTripPositiveOvershoot() {
        // 越界量 → 橡皮筋量 → 反推，应回到原越界量。
        for (overshoot in floatArrayOf(5f, 20f, 80f, 200f, 1000f)) {
            val stretched = rubberBandValue(overshoot, 450f)
            assertEquals(
                "round-trip o=$overshoot",
                overshoot, overshootFromStretch(stretched, 450f), 0.05f,
            )
        }
    }

    @Test
    fun roundTripNegativeOvershoot() {
        for (overshoot in floatArrayOf(5f, 20f, 80f, 200f)) {
            val stretched = -rubberBandValue(overshoot, 450f)
            assertEquals(
                "round-trip o=-$overshoot",
                -overshoot, overshootFromStretch(stretched, 450f), 0.05f,
            )
        }
    }

    @Test
    fun overshootIsLargerThanStretch() {
        // 橡皮筋压缩：反推的越界量恒大于拉伸量（同号）。
        val overshoot = overshootFromStretch(20f, 450f)
        assertTrue(overshoot > 20f)
        assertEquals(450f * 20f / 430f, overshoot, delta)
    }

    @Test
    fun nonPositiveLimitReturnsZero() {
        assertEquals(0f, overshootFromStretch(20f, 0f), delta)
        assertEquals(0f, overshootFromStretch(20f, -10f), delta)
    }

    @Test
    fun stretchAtOrBeyondLimitIsClampedFinite() {
        // 非法输入不把分母压到 0、不返回无穷／NaN。
        val atLimit = overshootFromStretch(450f, 450f)
        assertTrue(atLimit.isFinite())
        assertTrue(atLimit > 0f)
        val beyond = overshootFromStretch(900f, 450f)
        assertTrue(beyond.isFinite())
        assertEquals(atLimit, beyond, delta)
    }

    // ---- 拖动基线合成（模拟新 dragBy：锚定虚拟位置＋累计位移） ----

    @Test
    fun regrabMidSpringbackFirstFrameKeepsResidualStretch() {
        // 顶部回弹中再按住：残余拉伸 20px、滚动 0。锚定虚拟位置＝
        // −反推越界量；首帧 dy=0 解析后拉伸仍为 20px——无跳变。
        val anchor = 0f - overshootFromStretch(20f, 450f)
        val firstFrame = resolveDragOffset(anchor + 0f, 500, 450f)
        assertEquals(0f, firstFrame.scrollOffset, delta)
        assertEquals(20f, firstFrame.stretch, delta)
    }

    @Test
    fun regrabThenDragTowardBoundsDecaysStretchSmoothly() {
        // 接上：往回拖 10px，拉伸由 20px 连续衰到约 10.7px（不是清零）；
        // 再拖 15px（累计 25px）越过边界，拉伸归零、滚动接上 4.06px。
        val anchor = 0f - overshootFromStretch(20f, 450f)
        val after10 = resolveDragOffset(anchor + 10f, 500, 450f)
        assertEquals(0f, after10.scrollOffset, delta)
        assertTrue("stretch decays, not cleared", after10.stretch > 0f)
        assertTrue("stretch decays below residual", after10.stretch < 20f)
        val after25 = resolveDragOffset(anchor + 25f, 500, 450f)
        assertEquals(0f, after25.stretch, delta)
        assertTrue("scroll takes over past the boundary", after25.scrollOffset > 0f)
    }

    @Test
    fun regrabMidSpringbackBottomIsSymmetric() {
        // 底部回弹中再按住：残余拉伸 −30px、滚动钉在 maxScroll。首帧
        // 解析后拉伸仍为 −30px、滚动仍为 500——无跳变。
        val anchor = 500f - overshootFromStretch(-30f, 450f)
        val firstFrame = resolveDragOffset(anchor + 0f, 500, 450f)
        assertEquals(500f, firstFrame.scrollOffset, delta)
        assertEquals(-30f, firstFrame.stretch, delta)
    }

    @Test
    fun normalInBoundsDragIsUnchanged() {
        // 界内拖动：残余拉伸为 0，锚定即当前偏移，累计位移与旧
        // 逐帧解析同一结果（120 ＋ 30 ＝ 150，无拉伸）。
        val anchor = 120f - overshootFromStretch(0f, 450f)
        val resolved = resolveDragOffset(anchor + 30f, 500, 450f)
        assertEquals(150f, resolved.scrollOffset, delta)
        assertEquals(0f, resolved.stretch, delta)
    }

    @Test
    fun sustainedOverscrollNowAccumulatesWithTotalDrag() {
        // 越界持续拖动：累计位移 −100px 时拉伸为总越界量的橡皮筋
        // 量（约 81.8px），不再是单帧 dy 的量。
        val anchor = 0f - overshootFromStretch(0f, 450f)
        val resolved = resolveDragOffset(anchor - 100f, 500, 450f)
        assertEquals(0f, resolved.scrollOffset, delta)
        assertEquals(rubberBandValue(100f, 450f), resolved.stretch, delta)
        assertTrue("stretch grows with total overshoot", resolved.stretch > 50f)
    }
}
