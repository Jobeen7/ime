// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.image

import com.jobeen.ime.skin.model.BackgroundSnapshot
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主题图片事务发布（WO38 E1-10/3）：BackgroundRebuilder 的
 * staging＋rename 发布——成功进位／失败零变化／异常传播＋清理。
 * 假 preparer 只写 staging 内路径（与生产 AndroidBackgroundPreparer
 * 同契约：按 Request.genDir/prepared/<key>.webp 落盘）。
 */
class Wo38BackgroundRebuildPublishTest {

    private fun background(key: String = "bg_abc_10x20_b0") = BackgroundSnapshot(
        assetId = "bg",
        assetSha256 = "abc",
        blurRadius = 0f,
        overlayAlpha = 0f,
        preparedKey = key,
        targetWidthPx = 10,
        targetHeightPx = 20,
    )

    private fun newGenDir(): File = Files.createTempDirectory("wo38-rebuild").toFile()

    /** 按请求落盘并报 Done（生产 preparer 的写盘契约）。 */
    private class WritingPreparer(private val bytes: ByteArray) : BackgroundPreparer {
        var seenGenDir: File? = null
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
            seenGenDir = request.genDir
            val out = File(File(request.genDir, "prepared"), "${request.preparedKey}.webp")
            out.parentFile.mkdirs()
            out.writeBytes(bytes)
            return BackgroundPreparer.Result.Done(request.preparedKey, request.targetWidthPx, request.targetHeightPx)
        }
    }

    /** 写半截文件后报 Failed（模拟压缩中途失败留下的半写产物）。 */
    private class PartialThenFailPreparer : BackgroundPreparer {
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
            val out = File(File(request.genDir, "prepared"), "${request.preparedKey}.webp")
            out.parentFile.mkdirs()
            out.writeBytes(byteArrayOf(1, 2, 3))
            return BackgroundPreparer.Result.Failed("compress_failed")
        }
    }

    /** 报 Done 却未落产物（不应发生）：不得发布空位。 */
    private class DoneWithoutFilePreparer : BackgroundPreparer {
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result =
            BackgroundPreparer.Result.Done(request.preparedKey, request.targetWidthPx, request.targetHeightPx)
    }

    private fun stagingLeftovers(genDir: File): List<File> =
        genDir.listFiles()?.filter { it.name.startsWith(".prepared-staging-") } ?: emptyList()

    @Test
    fun success_publishesToFinalPath_andCleansStaging() {
        val genDir = newGenDir()
        val preparer = WritingPreparer("webp-bytes".toByteArray())
        val outcome = BackgroundRebuilder.rebuild(
            genDir, background(), 0xFF000000.toInt(), byteArrayOf(9), preparer,
        )
        val expected = File(File(genDir, "prepared"), "bg_abc_10x20_b0.webp")
        assertTrue(outcome is BackgroundRebuilder.Outcome.Rebuilt)
        assertEquals(expected, (outcome as BackgroundRebuilder.Outcome.Rebuilt).file)
        assertEquals("webp-bytes", expected.readText())
        // preparer 只见到 staging 目录，未直写最终路径
        assertTrue(preparer.seenGenDir!!.name.startsWith(".prepared-staging-"))
        assertTrue(stagingLeftovers(genDir).isEmpty())
    }

    @Test
    fun failedPrepare_leavesFinalPathUntouched_andCleansStaging() {
        val genDir = newGenDir()
        val outcome = BackgroundRebuilder.rebuild(
            genDir, background(), 0xFF000000.toInt(), byteArrayOf(9), PartialThenFailPreparer(),
        )
        assertTrue(outcome is BackgroundRebuilder.Outcome.Failed)
        assertEquals("compress_failed", (outcome as BackgroundRebuilder.Outcome.Failed).reason)
        // 半写文件只在 staging 里：最终路径不存在，不会被“非空即 Done”误认
        assertFalse(File(File(genDir, "prepared"), "bg_abc_10x20_b0.webp").exists())
        assertTrue(stagingLeftovers(genDir).isEmpty())
    }

    @Test
    fun success_overwritesStaleFinalFile_atomically() {
        val genDir = newGenDir()
        val dest = File(File(genDir, "prepared"), "bg_abc_10x20_b0.webp").apply {
            parentFile.mkdirs()
            writeText("stale")
        }
        val outcome = BackgroundRebuilder.rebuild(
            genDir, background(), 0xFF000000.toInt(), byteArrayOf(9), WritingPreparer("fresh".toByteArray()),
        )
        assertTrue(outcome is BackgroundRebuilder.Outcome.Rebuilt)
        assertEquals("fresh", dest.readText())
        assertTrue(stagingLeftovers(genDir).isEmpty())
    }

    @Test
    fun doneWithoutFile_reportsPublishMissing_andLeavesNoFile() {
        val genDir = newGenDir()
        val outcome = BackgroundRebuilder.rebuild(
            genDir, background(), 0xFF000000.toInt(), byteArrayOf(9), DoneWithoutFilePreparer(),
        )
        assertTrue(outcome is BackgroundRebuilder.Outcome.Failed)
        assertEquals("publish_missing", (outcome as BackgroundRebuilder.Outcome.Failed).reason)
        assertFalse(File(File(genDir, "prepared"), "bg_abc_10x20_b0.webp").exists())
        assertTrue(stagingLeftovers(genDir).isEmpty())
    }

    @Test
    fun preparerThrows_propagates_andCleansStaging() {
        val genDir = newGenDir()
        val throwing = object : BackgroundPreparer {
            override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result =
                throw IllegalStateException("boom")
        }
        var caught: Throwable? = null
        try {
            BackgroundRebuilder.rebuild(
                genDir, background(), 0xFF000000.toInt(), byteArrayOf(9), throwing,
            )
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue(caught is IllegalStateException)
        assertTrue(stagingLeftovers(genDir).isEmpty())
        assertFalse(File(File(genDir, "prepared"), "bg_abc_10x20_b0.webp").exists())
    }
}
