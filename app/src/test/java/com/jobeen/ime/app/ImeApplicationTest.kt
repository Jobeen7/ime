package com.jobeen.ime.app

import com.jobeen.ime.ImeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImeApplication 修复单测（app＋backdrop 批）：
 * A-M1 进程名解析、A-S1 失败态、A-L1 onCreate 前 getInstance() 口径。
 * 均为纯 JVM 测试（无 Robolectric）：只碰伴生对象纯函数与枚举，
 * 不实例化 Application、不调任何 Android 桩方法。
 */
class ImeApplicationTest {

    private companion object {
        const val PID = 1234
        const val MAIN = "com.jobeen.ime"
        const val SPEECH = "com.jobeen.ime:speech"
    }

    // ── A-M1：API 24–27 进程名解析 ──

    @Test
    fun resolveProcessName_runningListHitMain() {
        assertEquals(
            MAIN,
            ImeApplication.resolveProcessNamePreP(
                PID,
                listOf(111 to MAIN, PID to MAIN),
            ) { error("running 命中时不应读 cmdline") },
        )
    }

    @Test
    fun resolveProcessName_runningListHitSpeech() {
        assertEquals(
            SPEECH,
            ImeApplication.resolveProcessNamePreP(
                PID,
                listOf(PID to SPEECH),
            ) { error("running 命中时不应读 cmdline") },
        )
    }

    @Test
    fun resolveProcessName_fallsBackToCmdlineWhenListNull() {
        assertEquals(
            SPEECH,
            ImeApplication.resolveProcessNamePreP(PID, null) { SPEECH },
        )
        assertEquals(
            MAIN,
            ImeApplication.resolveProcessNamePreP(PID, null) { MAIN },
        )
    }

    @Test
    fun resolveProcessName_fallsBackToCmdlineWhenPidMissing() {
        assertEquals(
            SPEECH,
            ImeApplication.resolveProcessNamePreP(
                PID,
                listOf(111 to MAIN, 222 to SPEECH),
            ) { SPEECH },
        )
    }

    @Test
    fun resolveProcessName_undeterminedWhenBothUnavailable() {
        assertNull(ImeApplication.resolveProcessNamePreP(PID, null) { null })
        assertNull(
            ImeApplication.resolveProcessNamePreP(PID, listOf(111 to MAIN)) { null },
        )
    }

    @Test
    fun resolveProcessName_cmdlineTakesFirstNulSegment() {
        // /proc/self/cmdline 原始内容：进程名后是 NUL＋启动参数。
        assertEquals(
            SPEECH,
            ImeApplication.resolveProcessNamePreP(PID, null) { "$SPEECH\u0000--extra-arg" },
        )
    }

    @Test
    fun resolveProcessName_blankCmdlineIsUndetermined() {
        assertNull(ImeApplication.resolveProcessNamePreP(PID, null) { "" })
        assertNull(ImeApplication.resolveProcessNamePreP(PID, null) { "   " })
        assertEquals(
            MAIN,
            ImeApplication.resolveProcessNamePreP(PID, null) { "  $MAIN  " },
        )
    }

    @Test
    fun resolveProcessName_runningListWinsOverCmdline() {
        assertEquals(
            MAIN,
            ImeApplication.resolveProcessNamePreP(
                PID,
                listOf(PID to MAIN),
            ) { SPEECH },
        )
    }

    // ── A-S1：启动状态机失败态 ──

    @Test
    fun appState_failedExistsAndIsDistinctFromFinished() {
        assertEquals(ImeApplication.AppState.Failed, ImeApplication.AppState.valueOf("Failed"))
        assertTrue(ImeApplication.AppState.Failed != ImeApplication.AppState.Finished)
        assertTrue(ImeApplication.AppState.Failed != ImeApplication.AppState.Starting)
    }

    // ── A-L1：onCreate 前 getInstance() 抛错（原有口径不变） ──

    @Test
    fun getInstance_throwsBeforeOnCreate() {
        assertThrows(IllegalStateException::class.java) {
            ImeApplication.getInstance()
        }
    }
}
