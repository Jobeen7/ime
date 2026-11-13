// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key.widget

import com.jobeen.ime.input.keyboard.key.shouldInterceptHorizontalSwipe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧栏横滑交接（P-低3）：DOWN 不再急着禁拦截，竖向拖动开始才禁——
 * 从侧栏起手的横滑在越过父板翻页阈值（48dp）时能被父板接手，
 * 点按与竖滚行为不变。复查报告说法核实：两处逻辑（DOWN 即禁、
 * 点按只看 |dy|）在 1e5be7ed 已存在，是既有行为、不是本批回归。
 * 本文件只钉纯判定；View 的事件接线本身需真机验收。
 * 刻意不动：ACTION_UP 点按判定仍只看 |dy|（小于 48dp 的横向小
 * 拖动抬手仍按既有口径判点按提交）；斜滑（竖向先起拖）仍归子栏。
 */
class SidePanelHandoffTest {

    private val touchSlopPx = 8f
    private val swipeMinPx = 48f

    // ── 竖向起拖判定（shouldStartVerticalDrag）──

    @Test
    fun noMove_isNotDrag() {
        assertFalse(shouldStartVerticalDrag(0f, touchSlopPx))
    }

    @Test
    fun smallVerticalMove_isNotDrag() {
        assertFalse(shouldStartVerticalDrag(5f, touchSlopPx))
        assertFalse(shouldStartVerticalDrag(-5f, touchSlopPx))
    }

    @Test
    fun atTouchSlop_isNotDrag() {
        // 严格大于：恰等于 touchSlop 不算起拖。
        assertFalse(shouldStartVerticalDrag(8f, touchSlopPx))
        assertFalse(shouldStartVerticalDrag(-8f, touchSlopPx))
    }

    @Test
    fun beyondTouchSlop_isDrag() {
        assertTrue(shouldStartVerticalDrag(8.1f, touchSlopPx))
        assertTrue(shouldStartVerticalDrag(20f, touchSlopPx))
        assertTrue(shouldStartVerticalDrag(-20f, touchSlopPx))
    }

    @Test
    fun horizontalMoveAlone_isNotVerticalDrag() {
        // 横向位移再大也不触发竖向起拖：横滑归父板，不归子栏竖滚。
        assertFalse(shouldStartVerticalDrag(0f, touchSlopPx))
    }

    // ── 交接组合：子栏判定 × 父板拦截谓词 ──

    @Test
    fun horizontalSwipeFromSidePanel_parentTakesIt() {
        // 侧栏起手横滑 60dp、竖向 5dp：子栏不起拖（不禁拦截），
        // 父板拦截谓词成立 → 父板接手翻页／切分类。
        assertFalse(shouldStartVerticalDrag(5f, touchSlopPx))
        assertTrue(shouldInterceptHorizontalSwipe(60f, 5f, swipeMinPx))
    }

    @Test
    fun verticalDragFromSidePanel_childKeepsIt() {
        // 侧栏起手竖滑：子栏起拖（禁拦截），父板谓词不成立。
        assertTrue(shouldStartVerticalDrag(60f, touchSlopPx))
        assertFalse(shouldInterceptHorizontalSwipe(5f, 60f, swipeMinPx))
    }

    @Test
    fun tapFromSidePanel_neitherTakesIt() {
        // 点按／小拖动：两边都不接手，抬手走既有点按判定。
        assertFalse(shouldStartVerticalDrag(2f, touchSlopPx))
        assertFalse(shouldInterceptHorizontalSwipe(2f, 2f, swipeMinPx))
    }

    @Test
    fun diagonalSwipe_childKeepsItAsBefore() {
        // 斜滑（竖向先超 touchSlop）：子栏起拖禁拦截，父板谓词也不
        // 成立——与既有行为一致，仍归子栏竖滚，不硬修。
        assertTrue(shouldStartVerticalDrag(60f, touchSlopPx))
        assertFalse(shouldInterceptHorizontalSwipe(60f, 60f, swipeMinPx))
    }
}
