// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.pkg

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主题视频导入门（2026-10-29 外观轮）：数量／大小／维度纯门＋导入端接线。 */
class VideoImportTest {

    private class FakeImageProbe : ImageInfoProbe {
        override fun bounds(bytes: ByteArray): ImageSize? =
            if (bytes.isNotEmpty()) ImageSize(100, 50) else null
    }

    private class FakeAudioProbe : AudioInfoProbe {
        override fun probe(bytes: ByteArray): AudioInfo? = null
    }

    private class FakePreparer : BackgroundPreparer {
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
            val dir = File(request.genDir, "prepared").apply { mkdirs() }
            File(dir, "${request.preparedKey}.webp").writeBytes(byteArrayOf(1, 2, 3))
            return BackgroundPreparer.Result.Done(
                request.preparedKey, request.targetWidthPx, request.targetHeightPx,
            )
        }
    }

    private class FakeVideoValidator(
        private val result: VideoAssetValidator.VideoCheck,
    ) : VideoAssetValidator {
        var calls = 0
        override fun check(
            bytes: ByteArray,
            tmpDir: File,
        ): VideoAssetValidator.VideoCheck {
            calls++
            return result
        }
    }

    private class Fixture(
        val root: File,
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val importer: JimeImporter,
    )

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
    )

    private fun fixture(validator: VideoAssetValidator): Fixture {
        val root = Files.createTempDirectory("jime-video-import").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        val store = SkinLibraryStore(layout)
        val importer = JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = FakeImageProbe(),
            audioProbe = FakeAudioProbe(),
            backgroundPreparer = FakePreparer(),
            defaultScheme = defaultScheme,
            videoValidator = validator,
        )
        return Fixture(root, layout, store, importer)
    }

    private val ctx = JimeImporter.Context(
        currentVersionCode = 143,
        fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        tier = MemoryTier.NORMAL,
        targetWidthPx = 100,
        targetHeightPx = 50,
    )

    private fun sha(bytes: ByteArray): String = DigestUtil.sha256Hex(bytes)

    private fun packageFiles(
        videoAssets: List<Triple<String, String, ByteArray>>,
        videoField: String? = null,
    ): List<ZipSafeExtractor.ExtractedFile> {
        val skinJson = buildString {
            append("""{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""")
            append(""""styles":{"keyBackground":"#FF112233"},"stylesDark":{"keyBackground":"#FF222222"}""")
            if (videoField != null) {
                append(""","appearance":{"background":{"type":"asset","asset":"img-1","video":"$videoField"}}""")
            }
            append("}")
        }
        val skinBytes = skinJson.encodeToByteArray()
        val imgBytes = "fake-image".encodeToByteArray()
        val contents = mutableListOf(
            PackageManifest.Content(PackageManifest.TYPE_SKIN, "skin-a", "skin.json", sha(skinBytes)),
            PackageManifest.Content(PackageManifest.TYPE_ASSET, "img-1", "assets/bg.png", sha(imgBytes)),
        )
        val files = mutableListOf(
            ZipSafeExtractor.ExtractedFile("skin.json", skinBytes),
            ZipSafeExtractor.ExtractedFile("assets/bg.png", imgBytes),
        )
        for ((id, path, bytes) in videoAssets) {
            contents += PackageManifest.Content(PackageManifest.TYPE_ASSET, id, path, sha(bytes))
            files += ZipSafeExtractor.ExtractedFile(path, bytes)
        }
        val manifestJson = PackageManifest.toJson(PackageManifest(3, contents))
        files += ZipSafeExtractor.ExtractedFile(
            JimeImporter.MANIFEST_PATH, manifestJson.encodeToByteArray(),
        )
        return files
    }

    @Test
    fun pureGates() {
        assertTrue(VideoAssetValidator.countOk(2))
        assertTrue(!VideoAssetValidator.countOk(3))
        assertTrue(VideoAssetValidator.sizeOk(VideoAssetValidator.MAX_VIDEO_FILE_BYTES))
        assertTrue(!VideoAssetValidator.sizeOk(VideoAssetValidator.MAX_VIDEO_FILE_BYTES + 1))
        assertTrue(VideoAssetValidator.dimensionsOk(1920, 1080))
        // 4K 恰好通过，超 4K 拒绝。
        assertTrue(VideoAssetValidator.dimensionsOk(3840, 2160))
        assertTrue(!VideoAssetValidator.dimensionsOk(3840, 2161))
        assertTrue(!VideoAssetValidator.dimensionsOk(0, 1080))
        assertTrue(!VideoAssetValidator.dimensionsOk(1920, 0))
    }

    @Test
    fun videoAssetImportsWhenValidatorOk() = runBlocking {
        val validator = FakeVideoValidator(VideoAssetValidator.VideoCheck.Ok(1920, 1080))
        val f = fixture(validator)
        val outcome = f.importer.importFiles(
            packageFiles(
                listOf(Triple("vid-1", "assets/bg.mp4", "fake-video".encodeToByteArray())),
                videoField = "vid-1",
            ),
            ctx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        assertEquals(1, validator.calls)
    }

    @Test
    fun videoAssetRejectedWhenValidatorFails() = runBlocking {
        val validator = FakeVideoValidator(VideoAssetValidator.VideoCheck.Failed("no_dimensions"))
        val f = fixture(validator)
        val outcome = f.importer.importFiles(
            packageFiles(
                listOf(Triple("vid-1", "assets/bg.mp4", "fake-video".encodeToByteArray())),
            ),
            ctx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue("expected Rejected, got $outcome", outcome is JimeImporter.Outcome.Rejected)
        assertTrue(
            (outcome as JimeImporter.Outcome.Rejected).reason.startsWith("video_invalid:"),
        )
    }

    @Test
    fun threeVideoAssetsRejectedBeforeValidator() = runBlocking {
        val validator = FakeVideoValidator(VideoAssetValidator.VideoCheck.Ok(1920, 1080))
        val f = fixture(validator)
        val assets = (1..3).map {
            Triple("vid-$it", "assets/bg$it.mp4", "fake-video-$it".encodeToByteArray())
        }
        val outcome = f.importer.importFiles(
            packageFiles(assets), ctx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue("expected Rejected, got $outcome", outcome is JimeImporter.Outcome.Rejected)
        assertEquals(
            "video_count_exceeded",
            (outcome as JimeImporter.Outcome.Rejected).reason,
        )
        assertEquals(0, validator.calls)
    }

    @Test
    fun videoKindByExtension() {
        assertEquals(AssetKind.VIDEO, PackageManifest.assetKindOf("assets/bg.mp4"))
        assertEquals(AssetKind.VIDEO, PackageManifest.assetKindOf("assets/bg.WEBM"))
        assertEquals(AssetKind.IMAGE, PackageManifest.assetKindOf("assets/bg.gif"))
        assertEquals(AssetKind.OTHER, PackageManifest.assetKindOf("assets/bg.avi"))
    }
}
