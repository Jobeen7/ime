// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.base.log.AppLogEntry
import com.jobeen.ime.base.log.DiagExport
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 诊断日志导出纯逻辑：落盘根、文本格式、zip 往返、异常分支。
 * FileProvider 授权与分享框拉起需真机复核（不在此测）。
 */
class DiagExportTest {

    private fun tmpDir(): File =
        File(System.getProperty("java.io.tmpdir"), "diag-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun diagZipFile_isUnderSharedRoot() {
        val cache = tmpDir()
        val zip = DiagExport.diagZipFile(cache, "20261025-023400")
        assertEquals(File(File(cache, "shared"), "jime-diag-20261025-023400.zip"), zip)
    }

    @Test
    fun buildDiagnosticText_headersAndPriorityLetters() {
        val txt = DiagExport.buildDiagnosticText(
            versionName = "1.1.14",
            packageName = "com.jobeen.ime.debug",
            androidRelease = "15",
            sdkInt = 35,
            manufacturer = "Samsung",
            model = "SM-S9280",
            now = "20261025-023400",
            entries = listOf(
                AppLogEntry("02:34:00.000", 4, "APP", "hello"),
                AppLogEntry("02:34:01.000", 6, "Rime", "boom"),
                AppLogEntry("02:34:02.000", 99, "X", "unknown"),
            ),
        )
        assertTrue(txt.contains("应用版本：1.1.14"))
        assertTrue(txt.contains("包名：com.jobeen.ime.debug"))
        assertTrue(txt.contains("02:34:00.000 I/APP hello"))
        assertTrue(txt.contains("02:34:01.000 E/Rime boom"))
        assertTrue(txt.contains("02:34:02.000 ?/X unknown"))
    }

    @Test
    fun buildDiagnosticText_emptyEntries_stillValid() {
        val txt = DiagExport.buildDiagnosticText(
            "1.1.14", "com.jobeen.ime.debug", "15", 35, "Samsung", "SM-S9280",
            "20261025-023400", emptyList(),
        )
        assertTrue(txt.contains("Jime 诊断日志"))
        assertTrue(txt.endsWith("----\n"))
    }

    @Test
    fun writeDiagZip_roundTripsAndCreatesParent() {
        val cache = tmpDir()
        val zip = DiagExport.diagZipFile(cache, "20261025-023400")
        DiagExport.writeDiagZip(zip, "line1\nline2\n")
        assertTrue(zip.isFile)
        ZipInputStream(zip.inputStream()).use { zis ->
            val entry = zis.nextEntry
            assertEquals(DiagExport.ZIP_ENTRY_NAME, entry?.name)
            val content = zis.readBytes().toString(Charsets.UTF_8)
            assertEquals("line1\nline2\n", content)
        }
    }

    @Test
    fun writeDiagZip_parentIsFile_throws() {
        val cache = tmpDir()
        val blocker = File(cache, "shared").also { it.createNewFile() }
        val zip = File(blocker, "jime-diag-x.zip")
        try {
            DiagExport.writeDiagZip(zip, "x")
            fail("expected exception")
        } catch (_: Exception) {
            // 异常分支存在即可：调用侧据此出 Toast 并写失败日志
        }
    }
}
