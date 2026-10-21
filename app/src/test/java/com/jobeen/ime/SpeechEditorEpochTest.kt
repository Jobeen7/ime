package com.jobeen.ime

import com.jobeen.ime.base.speech.EditorEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入框纪元（F4 写屏属主判据）的规格：会话开始捕获纪元，结算尾
 * 写屏前复检——纪元未推进才放行，推进过即判过期整段丢弃。
 */
class SpeechEditorEpochTest {

    @Test
    fun capturedWithoutAdvanceStaysCurrent() {
        val epoch = EditorEpoch()
        val captured = epoch.current()
        assertTrue(epoch.isCurrent(captured))
    }

    @Test
    fun capturedThenAdvanceIsStale() {
        val epoch = EditorEpoch()
        val captured = epoch.current()
        epoch.advance()
        assertFalse(epoch.isCurrent(captured))
    }

    @Test
    fun captureAfterAdvanceIsCurrent() {
        val epoch = EditorEpoch()
        epoch.advance()
        val captured = epoch.current()
        assertTrue(epoch.isCurrent(captured))
    }

    @Test
    fun multipleAdvancesKeepOlderCapturesStale() {
        val epoch = EditorEpoch()
        val first = epoch.current()
        epoch.advance()
        val second = epoch.current()
        epoch.advance()
        assertFalse(epoch.isCurrent(first))
        assertFalse(epoch.isCurrent(second))
        assertTrue(epoch.isCurrent(epoch.current()))
    }

    @Test
    fun advanceReturnsStrictlyIncreasingValues() {
        val epoch = EditorEpoch()
        val a = epoch.advance()
        val b = epoch.advance()
        assertTrue(b > a)
        assertEquals(b, epoch.current())
    }

    @Test
    fun freshEpochStartsAtZero() {
        // 初值 0 与「从未推进」同义：进程内首个会话捕获 0、未换框时放行
        val epoch = EditorEpoch()
        assertEquals(0L, epoch.current())
        assertTrue(epoch.isCurrent(0L))
    }
}
