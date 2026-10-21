package com.jobeen.ime

import com.jobeen.ime.base.CrashTrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃文件名口径（4.8）：按进程分文件、进程名里的冒号等字符安全
 * 化；识别函数须同时认现行命名与旧版单文件名（升级兼容读/清），
 * 且不误认其他文件（读取与清理都按它圈定候选）。
 */
class CrashTrapTest {

    @Test
    fun crashFileName_sanitizesProcessName() {
        assertEquals(
            "last_crash-com.jobeen.ime.txt",
            CrashTrap.crashFileName("com.jobeen.ime"),
        )
        assertEquals(
            "last_crash-com.jobeen.ime_speech.txt",
            CrashTrap.crashFileName("com.jobeen.ime:speech"),
        )
    }

    @Test
    fun isCrashFileName_matchesCurrentAndLegacyNames() {
        assertTrue(CrashTrap.isCrashFileName("last_crash.txt"))
        assertTrue(CrashTrap.isCrashFileName("last_crash-com.jobeen.ime.txt"))
        assertTrue(CrashTrap.isCrashFileName("last_crash-com.jobeen.ime_speech.txt"))
        assertFalse(CrashTrap.isCrashFileName("voice_corrections.tsv"))
        assertFalse(CrashTrap.isCrashFileName("last_crash.txt.bak"))
        assertFalse(CrashTrap.isCrashFileName("crash"))
    }
}
