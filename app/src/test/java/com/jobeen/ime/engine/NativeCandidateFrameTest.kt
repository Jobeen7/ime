// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import com.jobeen.ime.engine.data.EngineMessage.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B-P-1/P-2：native 分页帧定帧（[frameNativeCandidatePage]）。
 *
 * 覆盖：确切总数的末页判定、总数未知时按本页条数判定、页内偏移
 * 还原全局下标、第 0 页不偏移。
 */
class NativeCandidateFrameTest {

    private fun candidates(n: Int, from: Int = 0): List<Candidate> =
        (0 until n).map { Candidate(index = from + it, text = "词$it") }

    @Test
    fun firstPage_knownTotal_notLast_noOffset() {
        val (list, page, isLast) = frameNativeCandidatePage(
            candidates = candidates(6),
            total = 30,
            pageSize = 6,
            pageNo = 0,
            bulkLimit = 24,
        )
        assertEquals(0, page)
        assertFalse(isLast)
        assertEquals((0 until 6).toList(), list.map { it.index })
    }

    @Test
    fun lastPage_knownTotal_isLast_withOffset() {
        val (list, page, isLast) = frameNativeCandidatePage(
            candidates = candidates(6),
            total = 30,
            pageSize = 6,
            pageNo = 4,
            bulkLimit = 24,
        )
        assertEquals(4, page)
        assertTrue(isLast)
        // 第 4 页起于全局 24：窗口内下标 0..5 还原为 24..29。
        assertEquals((24 until 30).toList(), list.map { it.index })
    }

    @Test
    fun unknownTotal_fullBulk_notLast() {
        val (_, _, isLast) = frameNativeCandidatePage(
            candidates = candidates(24),
            total = -1,
            pageSize = 6,
            pageNo = 1,
            bulkLimit = 24,
        )
        assertFalse(isLast)
    }

    @Test
    fun unknownTotal_shortPage_isLast() {
        val (list, page, isLast) = frameNativeCandidatePage(
            candidates = candidates(10),
            total = -1,
            pageSize = 6,
            pageNo = 1,
            bulkLimit = 24,
        )
        assertEquals(1, page)
        assertTrue(isLast)
        assertEquals((6 until 16).toList(), list.map { it.index })
    }

    @Test
    fun unknownPageSize_noOffset_notLastWhenFull() {
        val (list, _, isLast) = frameNativeCandidatePage(
            candidates = candidates(24),
            total = -1,
            pageSize = 0,
            pageNo = 0,
            bulkLimit = 24,
        )
        assertFalse(isLast)
        assertEquals((0 until 24).toList(), list.map { it.index })
    }

    // 1.8：生僻字补字帧沿用入帧的页号／末页。旧实现硬编码页号 0、
    // 末页 false，翻页后的补字帧会把面板打回首屏、末页判定也丢了。
    @Test
    fun supplementedFrame_keepsPageAndLastPage() {
        val frame = supplementedCandidateFrame(
            list = candidates(10),
            highlighted = 0,
            total = 30,
            spellings = listOf("qu"),
            firstRawInput = "qu",
            page = 2,
            isLastPage = true,
            isDeleted = { false },
        )
        assertTrue(frame != null)
        assertEquals(2, frame!!.page)
        assertTrue(frame.isLastPage)
        // 补字本身口径不变：佢插到第 10 位，总数 +1。
        assertEquals(11, frame.list.size)
        assertEquals("佢", frame.list[9].text)
        assertEquals(31, frame.total)
    }

    @Test
    fun supplementedFrame_noSupplement_returnsNull() {
        assertEquals(
            null,
            supplementedCandidateFrame(
                list = candidates(10),
                highlighted = 0,
                total = 30,
                spellings = listOf("zzz"),
                firstRawInput = "zzz",
                page = 2,
                isLastPage = true,
                isDeleted = { false },
            ),
        )
    }
}
