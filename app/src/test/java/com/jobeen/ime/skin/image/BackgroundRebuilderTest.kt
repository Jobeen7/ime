package com.jobeen.ime.skin.image

import com.jobeen.ime.skin.model.BackgroundSnapshot
import com.jobeen.ime.skin.model.ImageAdjust
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 运行期背景产物自愈：按快照自带键/尺寸重建，不重算键（2026-10-28 修复轮）。 */
class BackgroundRebuilderTest {

    private fun background() = BackgroundSnapshot(
        assetId = "bg_demo",
        assetSha256 = "0de5bd68b0d0c7ab6a691609d0741960224e8a43ba1a7897bb0a7d50fc8b8eb6",
        blurRadius = 6f,
        overlayAlpha = 0f,
        preparedKey = "bg_0de5bd68_1080x561_b6_adj_bri8500_blr600_rot0_cl800_cr800",
        imageAdjust = ImageAdjust(
            brightnessPercent = 85f, blurRadiusDp = 6f, rotationDegrees = 0f,
            cropLeft = 0.08f, cropRight = 0.08f,
        ),
        targetWidthPx = 1080,
        targetHeightPx = 561,
    )

    private class FakePreparer(
        var result: BackgroundPreparer.Result = BackgroundPreparer.Result.Done("k", 1080, 561),
    ) : BackgroundPreparer {
        val requests = mutableListOf<BackgroundPreparer.Request>()
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
            requests += request
            if (result is BackgroundPreparer.Result.Done) {
                File(File(request.genDir, "prepared"), "${request.preparedKey}.webp")
                    .also { it.parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
            }
            return result
        }
    }

    @Test
    fun rebuildWritesSnapshotKeyFileWithSnapshotParams() {
        val genDir = Files.createTempDirectory("bg-rebuild").toFile()
        val preparer = FakePreparer()
        val outcome = BackgroundRebuilder.rebuild(
            genDir, background(), 0xFF2E3440.toInt(), byteArrayOf(9), preparer,
        )
        val expected = File(File(genDir, "prepared"), "${background().preparedKey}.webp")
        assertTrue(outcome is BackgroundRebuilder.Outcome.Rebuilt)
        assertEquals(expected, (outcome as BackgroundRebuilder.Outcome.Rebuilt).file)
        assertTrue(expected.isFile)
        val req = preparer.requests.single()
        assertEquals(1080, req.targetWidthPx)
        assertEquals(561, req.targetHeightPx)
        assertEquals(6f, req.blurRadiusDp)
        assertEquals(background().preparedKey, req.preparedKey)
        assertEquals(85f, req.imageAdjust?.brightnessPercent)
        assertEquals(0xFF2E3440.toInt(), req.overlayColorArgb)
        assertEquals(background().assetSha256, req.assetSha256)
    }

    @Test
    fun preparerFailureMapsToFailedWithoutFile() {
        val genDir = Files.createTempDirectory("bg-rebuild-fail").toFile()
        val preparer = FakePreparer(BackgroundPreparer.Result.Failed("undecodable"))
        val outcome = BackgroundRebuilder.rebuild(
            genDir, background(), 0xFF000000.toInt(), byteArrayOf(9), preparer,
        )
        assertTrue(outcome is BackgroundRebuilder.Outcome.Failed)
        assertEquals("undecodable", (outcome as BackgroundRebuilder.Outcome.Failed).reason)
        assertTrue(File(File(genDir, "prepared"), "${background().preparedKey}.webp").exists().not())
    }
}
