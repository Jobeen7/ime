// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GridKeyboardView 拖动纯决策（I-L2 首帧跳变＋I-L6 橡皮筋）：
 * View 实例化与 MotionEvent 在纯 JUnit 下测不了，故拦截判定、
 * 拖动解析、橡皮筋公式抽为纯函数（见 GridKeyboardView.kt 末尾），
 * 本文件按状态空间分区枚举。
 *
 * I-L2：拦截成功的 MOVE 即本次拖动的首帧——调用方同步 lastY＝
 * 当前 event.y，否则同一事件随后进 onTouchEvent 时 dy 按自 DOWN
 * 起全量算、首帧跳约一个 touchSlop。同步后首帧 dy=0（见
 * firstFrameAfterInterceptSyncDoesNotMove）；View 内 lastY 赋值
 * 的接线本身需真机验收。
 *
 * I-L6：stretch 是内容在边界外的真实位移（顶下拉正、底上拉负），
 * 经 onLayout／applyScrollOffset 落地、回弹逐帧播回；本文件钉
 * 解析层，动画与手感需真机验收（三板共用：emoji／颜文字／符号）。
 */
class GridKeyboardViewDragTest {

    private val delta = 0.001f

    // ---- rubberBandValue ----

    @Test
    fun rubberBandZeroIsZero() {
        assertEquals(0f, rubberBandValue(0f, 100f), delta)
    }

    @Test
    fun rubberBandIsBelowOvershootAndBelowLimit() {
        // d=50、limit=100：50*100/150 ≈ 33.33——小于越界量、小于上限。
        val value = rubberBandValue(50f, 100f)
        assertEquals(100f * 50f / 150f, value, delta)
        assertTrue(value < 50f)
        assertTrue(value < 100f)
    }

    @Test
    fun rubberBandApproachesLimitButNeverReachesIt() {
        val value = rubberBandValue(10_000f, 100f)
        assertTrue(value < 100f)
        assertTrue(value > 99f)
    }

    @Test
    fun rubberBandIsMonotonic() {
        val small = rubberBandValue(10f, 100f)
        val mid = rubberBandValue(50f, 100f)
        val large = rubberBandValue(200f, 100f)
        assertTrue(small < mid)
        assertTrue(mid < large)
    }

    // ---- resolveDragOffset ----

    @Test
    fun inRangeDragScrollsFullyWithNoStretch() {
        val resolved = resolveDragOffset(120f, 500, 100f)
        assertEquals(120f, resolved.scrollOffset, delta)
        assertEquals(0f, resolved.stretch, delta)
    }

    @Test
    fun topOvershootPinsScrollAtZeroWithPositiveStretch() {
        val resolved = resolveDragOffset(-30f, 500, 100f)
        assertEquals(0f, resolved.scrollOffset, delta)
        assertEquals(rubberBandValue(30f, 100f), resolved.stretch, delta)
        assertTrue(resolved.stretch > 0f)
    }

    @Test
    fun bottomOvershootPinsScrollAtMaxWithNegativeStretch() {
        val resolved = resolveDragOffset(530f, 500, 100f)
        assertEquals(500f, resolved.scrollOffset, delta)
        assertEquals(-rubberBandValue(30f, 100f), resolved.stretch, delta)
        assertTrue(resolved.stretch < 0f)
    }

    @Test
    fun exactBoundariesHaveNoStretch() {
        assertEquals(0f, resolveDragOffset(0f, 500, 100f).stretch, delta)
        assertEquals(0f, resolveDragOffset(500f, 500, 100f).stretch, delta)
    }

    @Test
    fun zeroMaxScrollMakesAnyDragOverscroll() {
        // 短内容滚不动：任何拖动都是越界拉伸，滚动恒为 0。
        val down = resolveDragOffset(-20f, 0, 100f)
        assertEquals(0f, down.scrollOffset, delta)
        assertTrue(down.stretch > 0f)
        val up = resolveDragOffset(20f, 0, 100f)
        assertEquals(0f, up.scrollOffset, delta)
        assertTrue(up.stretch < 0f)
    }

    @Test
    fun firstFrameAfterInterceptSyncDoesNotMove() {
        // I-L2 的等价条件：拦截同步 lastY 后首帧 dy=0，提议偏移即
        // 当前偏移——解析后滚动与拉伸都不动，不跳一个 touchSlop。
        val resolved = resolveDragOffset(120f, 500, 100f)
        assertEquals(120f, resolved.scrollOffset, delta)
        assertEquals(0f, resolved.stretch, delta)
    }

    // ---- shouldInterceptVerticalDrag ----

    @Test
    fun interceptRequiresMayIntercept() {
        // WO31 开关开且内容滚不动／横向占优时不拦截。
        assertFalse(shouldInterceptVerticalDrag(
            mayIntercept = false, absDy = 100f, touchSlop = 8f,
        ))
    }

    @Test
    fun interceptRequiresBeyondTouchSlop() {
        assertFalse(shouldInterceptVerticalDrag(
            mayIntercept = true, absDy = 8f, touchSlop = 8f,
        ))
        assertTrue(shouldInterceptVerticalDrag(
            mayIntercept = true, absDy = 8.5f, touchSlop = 8f,
        ))
    }

    @Test
    fun tinyMoveNeverIntercepts() {
        assertFalse(shouldInterceptVerticalDrag(
            mayIntercept = true, absDy = 0f, touchSlop = 8f,
        ))
    }
}
