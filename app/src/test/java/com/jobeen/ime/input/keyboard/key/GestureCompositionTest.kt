// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

import android.view.View
import com.jobeen.ime.input.keyboard.impl.combineLongClickListeners
import com.jobeen.ime.input.keyboard.impl.composeGestureHandlers
import com.jobeen.ime.input.keyboard.key.CustomGestureView.Event
import com.jobeen.ime.input.keyboard.key.CustomGestureView.GestureType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单槽位监听合成（I-M1）与长按／连发互斥（I-L6）：
 * 弹窗优先于长按动作、滑动与移光标两者都跑、长按触发后连发不起跑。
 */
class GestureCompositionTest {

    private fun moveEvent(countX: Int = 0) =
        Event(GestureType.Move, false, 0f, 0f, countX, 0, 0, 0)

    private fun upEvent() = Event(GestureType.Up, false, 0f, 0f, 0, 0, 0, 0)

    @Test
    fun longClickFirstTrueWins() {
        val calls = mutableListOf<String>()
        val combined = combineLongClickListeners(
            listOf(
                View.OnLongClickListener { calls += "popup"; true },
                View.OnLongClickListener { calls += "longPress"; true },
            ),
        )
        // 弹窗在先且返回 true：胜出，长按动作不再调用。
        assertTrue(combined.onLongClick(null))
        assertEquals(listOf("popup"), calls)
    }

    @Test
    fun longClickFallsThroughWhenPopupDeclines() {
        val calls = mutableListOf<String>()
        val combined = combineLongClickListeners(
            listOf(
                View.OnLongClickListener { calls += "popup"; false },
                View.OnLongClickListener { calls += "longPress"; true },
            ),
        )
        assertTrue(combined.onLongClick(null))
        assertEquals(listOf("popup", "longPress"), calls)
    }

    @Test
    fun longClickAllFalseReturnsFalse() {
        val combined = combineLongClickListeners(
            listOf(
                View.OnLongClickListener { false },
                View.OnLongClickListener { false },
            ),
        )
        assertFalse(combined.onLongClick(null))
        // 空表恒 false。
        assertFalse(combineLongClickListeners(emptyList()).onLongClick(null))
    }

    @Test
    fun gestureHandlersBothSeeSameEvent() {
        val seen = mutableListOf<String>()
        val composed = composeGestureHandlers(
            listOf(
                { _: Event -> seen += "swipe"; false },
                { event: Event -> seen += "moveCursor"; event.countX != 0 },
            ),
        )
        // Move 计步：移光标消费，滑动也看到了事件（ transient 色等）。
        assertTrue(composed(moveEvent(countX = 2)))
        assertEquals(listOf("swipe", "moveCursor"), seen)
    }

    @Test
    fun gestureHandlersOrSemantics() {
        val composed = composeGestureHandlers(
            listOf(
                { _: Event -> false },
                { _: Event -> false },
            ),
        )
        assertFalse(composed(upEvent()))
        assertFalse(composeGestureHandlers(emptyList())(upEvent()))
        val oneConsumes = composeGestureHandlers(
            listOf(
                { _: Event -> false },
                { _: Event -> true },
            ),
        )
        assertTrue(oneConsumes(upEvent()))
    }

    @Test
    fun swipeInterceptOnlyAtPageDistance() {
        // 8～48dp 的横向小拖动不拦截，仍归子键点按（I-M2）。
        assertFalse(shouldInterceptHorizontalSwipe(20f, 0f, 48f))
        assertFalse(shouldInterceptHorizontalSwipe(47.9f, 0f, 48f))
        assertFalse(shouldInterceptHorizontalSwipe(48f, 0f, 48f))
        // 达到翻页距离且横向占优才拦截。
        assertTrue(shouldInterceptHorizontalSwipe(48.1f, 0f, 48f))
        assertTrue(shouldInterceptHorizontalSwipe(-60f, 0f, 48f))
        // 横向未占优（竖向主导）不拦截，归网格滚动。
        assertFalse(shouldInterceptHorizontalSwipe(60f, 70f, 48f))
        assertFalse(shouldInterceptHorizontalSwipe(60f, -60f, 48f))
    }

    @Test
    fun repeatSuppressedOnlyWhenLongPressFired() {
        // 长按已触发：摘除连发／不起跑（I-L6）。
        assertTrue(shouldCancelRepeatOnLongPress(true))
        assertFalse(shouldStartRepeat(true, true))
        // 长按未触发（监听返回 false）：连发照常。
        assertFalse(shouldCancelRepeatOnLongPress(false))
        assertTrue(shouldStartRepeat(false, true))
        // 未声明连发：不起跑。
        assertFalse(shouldStartRepeat(false, false))
        assertFalse(shouldStartRepeat(true, false))
    }
}
