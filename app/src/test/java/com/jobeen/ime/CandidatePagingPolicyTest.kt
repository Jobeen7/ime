package com.jobeen.ime

import com.jobeen.ime.engine.CandidatePagingMode
import com.jobeen.ime.engine.CandidatePagingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO52 1.1 候选分页双模式：模式分派＋页号／回第 0 页＋高亮参数
 * ＋翻页钮可见性（纯决策单测，不碰引擎／UI）。
 */
class CandidatePagingPolicyTest {

    @Test
    fun swipeEnabledWithoutFloatingIsSwipe() {
        assertEquals(
            CandidatePagingMode.SWIPE,
            CandidatePagingPolicy.resolveMode(swipePagingEnabled = true, floatingActive = false),
        )
    }

    @Test
    fun swipeDisabledIsNative() {
        assertEquals(
            CandidatePagingMode.NATIVE,
            CandidatePagingPolicy.resolveMode(swipePagingEnabled = false, floatingActive = false),
        )
    }

    @Test
    fun floatingActiveForcesNativeEvenWhenSwipeEnabled() {
        assertEquals(
            CandidatePagingMode.NATIVE,
            CandidatePagingPolicy.resolveMode(swipePagingEnabled = true, floatingActive = true),
        )
    }

    @Test
    fun highlightFlagIsNegatedSwipeMode() {
        // 元书 highlightCandidate(session, i, !滑动模式)：
        // 滑动模式全局下标（false），native 模式页内下标（true）。
        assertFalse(CandidatePagingPolicy.highlightOnCurrentPage(swipeMode = true))
        assertTrue(CandidatePagingPolicy.highlightOnCurrentPage(swipeMode = false))
    }

    @Test
    fun swipePageIncrementsAndEmptyReturnsToZero() {
        assertEquals(1, CandidatePagingPolicy.nextSwipePage(0, batchEmpty = false))
        assertEquals(3, CandidatePagingPolicy.nextSwipePage(2, batchEmpty = false))
        assertEquals(0, CandidatePagingPolicy.nextSwipePage(2, batchEmpty = true))
        assertEquals(0, CandidatePagingPolicy.nextSwipePage(0, batchEmpty = true))
    }

    @Test
    fun nativePageMovesAndClampsAtZero() {
        assertEquals(1, CandidatePagingPolicy.nextNativePage(0, backward = false, moved = true))
        assertEquals(2, CandidatePagingPolicy.nextNativePage(1, backward = false, moved = true))
        assertEquals(0, CandidatePagingPolicy.nextNativePage(1, backward = true, moved = true))
        assertEquals(0, CandidatePagingPolicy.nextNativePage(0, backward = true, moved = true))
    }

    @Test
    fun nativePageFailureReturnsToZero() {
        // 翻页失败（已到尽头、无更多页）则回到第 0 页。
        assertEquals(0, CandidatePagingPolicy.nextNativePage(3, backward = false, moved = false))
        assertEquals(0, CandidatePagingPolicy.nextNativePage(3, backward = true, moved = false))
    }

    @Test
    fun pagingButtonsVisibleByPageAndLastPage() {
        // 首页：上一页隐、下一页显；末页：上一页显、下一页隐。
        assertEquals(
            false to true,
            CandidatePagingPolicy.pagingButtonsVisible(true, pageNo = 0, isLastPage = false),
        )
        assertEquals(
            true to false,
            CandidatePagingPolicy.pagingButtonsVisible(true, pageNo = 1, isLastPage = true),
        )
        assertEquals(
            true to true,
            CandidatePagingPolicy.pagingButtonsVisible(true, pageNo = 1, isLastPage = false),
        )
        // 单页：两钮全隐。
        assertEquals(
            false to false,
            CandidatePagingPolicy.pagingButtonsVisible(true, pageNo = 0, isLastPage = true),
        )
    }

    @Test
    fun pagingButtonsHiddenWhenSwitchOff() {
        assertEquals(
            false to false,
            CandidatePagingPolicy.pagingButtonsVisible(false, pageNo = 1, isLastPage = false),
        )
    }
}
