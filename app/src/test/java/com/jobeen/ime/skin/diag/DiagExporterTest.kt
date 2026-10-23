package com.jobeen.ime.skin.diag

import com.jobeen.ime.base.CrashTrap
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出 zip（详设十三.5）：只含诊断日志与崩溃文件，其他文件不混入。 */
class DiagExporterTest {

    @Test
    fun zipContainsOnlyLogsAndCrashes() {
        val root = Files.createTempDirectory("diag-export-test").toFile()
        val logsDir = File(root, "logs").apply { mkdirs() }
        val crashDir = File(root, "crash").apply { mkdirs() }
        val outDir = File(root, "out")

        File(logsDir, "diag-20261023-100000-000.log").writeText("line a\n")
        File(logsDir, "diag-20261023-110000-000.log").writeText("line b\n")
        File(logsDir, "unrelated.txt").writeText("nope")
        val crashName = CrashTrap.crashFileName("com.jobeen.ime")
        File(crashDir, crashName).writeText("stack")
        File(crashDir, "other.log").writeText("nope")

        val zip = DiagExporter.buildExportZip(logsDir, crashDir, outDir) { 1_762_000_000_000L }
        assertTrue(zip.isFile)
        ZipFile(zip).use { zf ->
            val names = zf.entries().toList().map { it.name }.toSet()
            assertEquals(
                setOf(
                    "logs/diag-20261023-100000-000.log",
                    "logs/diag-20261023-110000-000.log",
                    "crash/$crashName",
                ),
                names,
            )
            val logEntry = zf.getEntry("logs/diag-20261023-100000-000.log")
            val content = zf.getInputStream(logEntry).readBytes().decodeToString()
            assertEquals("line a\n", content)
        }
    }

    @Test
    fun zipWorksWithoutCrashDir() {
        val root = Files.createTempDirectory("diag-export-test2").toFile()
        val logsDir = File(root, "logs").apply { mkdirs() }
        File(logsDir, "diag-20261023-100000-000.log").writeText("only\n")
        val zip = DiagExporter.buildExportZip(logsDir, null, File(root, "out")) { 1_762_000_000_000L }
        ZipFile(zip).use { zf ->
            assertEquals(
                setOf("logs/diag-20261023-100000-000.log"),
                zf.entries().toList().map { it.name }.toSet(),
            )
        }
    }
}
