package com.jobeen.ime

import com.jobeen.ime.base.CrashTrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃文件名口径（4.8）：按进程分文件、进程名里的冒号等字符安全
 * 化；识别函数只认现行命名，且不误认其他文件（读取与清理都按它
 * 圈定候选）。旧版单文件名 last_crash.txt 的兼容读/清已于清点
 * D16 拆除，不再算崩溃记录（下方用例已按新口径改判）。
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
    fun crashHeader_includesVersionLineAfterProcess() {
        // 2026-10-31 观察项：崩溃记录须带版本号，否则旧崩溃
        // 在诊断包里会被误认成当前版本的崩溃
        val header = CrashTrap.crashHeader(
            threadName = "main",
            timeMs = 1793282340490L,
            processName = "com.jobeen.ime",
            versionName = "1.1.16.6",
            versionCode = 163L,
        )
        assertTrue(header.startsWith("thread: main\n"))
        assertTrue(header.contains("time: 1793282340490\n"))
        assertTrue(header.contains("process: com.jobeen.ime\n"))
        assertTrue(header.contains("version: 1.1.16.6 (163)\n"))
        assertTrue(header.indexOf("version: ") > header.indexOf("process: "))
    }

    @Test
    fun isCrashFileName_matchesCurrentNamesOnly() {
        // 原断言：旧版单文件名 last_crash.txt 判 true（兼容读/清）；
        // 新断言（清点 D16）：兼容支已拆，判 false
        assertFalse(CrashTrap.isCrashFileName("last_crash.txt"))
        assertTrue(CrashTrap.isCrashFileName("last_crash-com.jobeen.ime.txt"))
        assertTrue(CrashTrap.isCrashFileName("last_crash-com.jobeen.ime_speech.txt"))
        assertFalse(CrashTrap.isCrashFileName("voice_corrections.tsv"))
        assertFalse(CrashTrap.isCrashFileName("last_crash.txt.bak"))
        assertFalse(CrashTrap.isCrashFileName("crash"))
    }
}
