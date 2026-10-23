package com.jobeen.ime.skin.pkg

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinIssue
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** .jime 导入管线（详设 §3.1）：身份/冲突/副本、资源回退、整包拒绝。 */
class JimeImporterTest {

    private class FakeImageProbe : ImageInfoProbe {
        override fun bounds(bytes: ByteArray): ImageSize? =
            if (bytes.isNotEmpty() && bytes[0] == 'I'.code.toByte()) ImageSize(100, 50) else null
    }

    private class FakeAudioProbe : AudioInfoProbe {
        override fun probe(bytes: ByteArray): AudioInfo? =
            if (bytes.isNotEmpty() && bytes[0] == 'A'.code.toByte()) {
                AudioInfo(durationMs = 1000, sampleRate = 44100, channels = 2)
            } else {
                null
            }
    }

    private class FakePreparer : BackgroundPreparer {
        var lastRequest: BackgroundPreparer.Request? = null
        var fail: Boolean = false

        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
            lastRequest = request
            if (fail) return BackgroundPreparer.Result.Failed("fake_failure")
            val dir = File(request.genDir, "prepared").apply { mkdirs() }
            File(dir, "${request.preparedKey}.webp").writeBytes(byteArrayOf(1, 2, 3))
            return BackgroundPreparer.Result.Done(
                request.preparedKey, request.targetWidthPx, request.targetHeightPx,
            )
        }
    }

    private data class Fixture(
        val root: File,
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val importer: JimeImporter,
        val preparer: FakePreparer,
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

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-import").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        val store = SkinLibraryStore(layout)
        val preparer = FakePreparer()
        val importer = JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = FakeImageProbe(),
            audioProbe = FakeAudioProbe(),
            backgroundPreparer = preparer,
            defaultScheme = defaultScheme,
        )
        return Fixture(root, layout, store, importer, preparer)
    }

    private val ctx = JimeImporter.Context(
        currentVersionCode = 143,
        fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        tier = MemoryTier.NORMAL,
        targetWidthPx = 100,
        targetHeightPx = 50,
    )

    private fun sha(bytes: ByteArray): String = DigestUtil.sha256Hex(bytes)

    /** 组装包文件清单：skin 必备，可选布局/资产（资产以 (id, path, bytes) 给出）。 */
    private fun packageFiles(
        skinId: String = "skin-a",
        skinJson: String = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""" +
            """"styles":{"keyBackground":"#FF112233"}}""",
        layoutId: String? = null,
        layoutJson: String = """{"rows":[]}""",
        assets: List<Triple<String, String, ByteArray>> = emptyList(),
    ): List<ZipSafeExtractor.ExtractedFile> {
        val skinBytes = skinJson.encodeToByteArray()
        val contents = mutableListOf(
            PackageManifest.Content(PackageManifest.TYPE_SKIN, skinId, "skin.json", sha(skinBytes)),
        )
        val files = mutableListOf(ZipSafeExtractor.ExtractedFile("skin.json", skinBytes))
        if (layoutId != null) {
            val layoutBytes = layoutJson.encodeToByteArray()
            contents += PackageManifest.Content(
                PackageManifest.TYPE_LAYOUT, layoutId, "layouts/$layoutId.json", sha(layoutBytes),
            )
            files += ZipSafeExtractor.ExtractedFile("layouts/$layoutId.json", layoutBytes)
        }
        for ((id, path, bytes) in assets) {
            contents += PackageManifest.Content(PackageManifest.TYPE_ASSET, id, path, sha(bytes))
            files += ZipSafeExtractor.ExtractedFile(path, bytes)
        }
        val manifestJson = PackageManifest.toJson(PackageManifest(3, contents))
        files += ZipSafeExtractor.ExtractedFile(
            JimeImporter.MANIFEST_PATH, manifestJson.encodeToByteArray(),
        )
        return files
    }

    private fun imported(outcome: JimeImporter.Outcome): JimeImporter.ImportReport {
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        return (outcome as JimeImporter.Outcome.Imported).report
    }

    @Test
    fun minimalPackageImports() = runBlocking {
        val f = fixture()
        val report = imported(
            f.importer.importFiles(packageFiles(), ctx, JimeImporter.ConflictMode.FAIL),
        )
        assertEquals(listOf("skin-a"), report.importedSkinIds)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.skins.size)
        assertEquals("skin-a", index.skins.single().id)
        val genDir = f.layout.skinGenDir("skin-a", 1)
        assertTrue(File(genDir, "skin.json").isFile)
        assertTrue(File(genDir, "manifest.json").isFile)
    }

    @Test
    fun sameIdSameHashSkips() = runBlocking {
        val f = fixture()
        val files = packageFiles()
        imported(f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL))
        val second = imported(f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL))
        assertEquals(listOf("skin-a"), second.skippedSkinIds)
        assertTrue(second.importedSkinIds.isEmpty())
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.skins.single().generation)
    }

    @Test
    fun conflictFailThenCopy() = runBlocking {
        val f = fixture()
        var n = 0
        val importer = JimeImporter(
            f.layout, f.store, ImportTransaction(f.layout, f.store),
            FakeImageProbe(), FakeAudioProbe(), f.preparer, defaultScheme,
            newId = { "copy-${++n}" },
        )
        imported(importer.importFiles(packageFiles(), ctx, JimeImporter.ConflictMode.FAIL))
        val changed = packageFiles(
            skinJson = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A2"},""" +
                """"styles":{"keyBackground":"#FF445566"}}""",
        )
        val pending = importer.importFiles(changed, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue(pending is JimeImporter.Outcome.ConflictPending)
        assertEquals(listOf("skin-a"), (pending as JimeImporter.Outcome.ConflictPending).conflicts)

        val copied = imported(importer.importFiles(changed, ctx, JimeImporter.ConflictMode.COPY))
        assertEquals(mapOf("skin-a" to "copy-1"), copied.copies)
        assertEquals(listOf("copy-1"), copied.importedSkinIds)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(setOf("skin-a", "copy-1"), index.skins.map { it.id }.toSet())
        // 副本树内的 meta.id 已改写为新 id
        val copyGen = f.layout.skinGenDir("copy-1", 1)
        assertTrue(File(copyGen, "skin.json").readText().contains("\"copy-1\""))
    }

    @Test
    fun manifestShaMismatchRejected() = runBlocking {
        val f = fixture()
        val files = packageFiles().map {
            if (it.path == "skin.json") {
                ZipSafeExtractor.ExtractedFile(it.path, "{}".encodeToByteArray())
            } else {
                it
            }
        }
        val outcome = f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue(outcome is JimeImporter.Outcome.Rejected)
        assertTrue(
            (outcome as JimeImporter.Outcome.Rejected).reason.contains("manifest_sha_mismatch"),
        )
    }

    @Test
    fun unlistedFileRejected() = runBlocking {
        val f = fixture()
        val files = packageFiles() + ZipSafeExtractor.ExtractedFile(
            "stray.txt", "x".encodeToByteArray(),
        )
        val outcome = f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue(outcome is JimeImporter.Outcome.Rejected)
    }

    @Test
    fun metaIdMismatchRejected() = runBlocking {
        val f = fixture()
        val files = packageFiles(
            skinJson = """{"formatVersion":3,"meta":{"id":"other","name":"A"}}""",
        )
        val outcome = f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue(outcome is JimeImporter.Outcome.Rejected)
        assertEquals(
            "skin_meta_id_mismatch",
            (outcome as JimeImporter.Outcome.Rejected).reason,
        )
    }

    @Test
    fun backgroundPreparedOnImport() = runBlocking {
        val f = fixture()
        val imageBytes = byteArrayOf('I'.code.toByte(), 1, 2, 3)
        val files = packageFiles(
            skinJson = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""" +
                """"appearance":{"background":{"type":"asset","asset":"bg_main","blurRadius":10}},""" +
                """"styles":{"background":"#FF000000"}}""",
            assets = listOf(Triple("bg_main", "assets/bg.png", imageBytes)),
        )
        val report = imported(f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL))
        assertFalse(report.backgroundDropped)
        assertNotNull(f.preparer.lastRequest)
        val genDir = f.layout.skinGenDir("skin-a", 1)
        val prepared = File(genDir, "prepared")
        assertTrue(prepared.listFiles()?.any { it.name.endsWith(".webp") } == true)
        // 资产实体按 sha 落盘
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.assets.size)
    }

    @Test
    fun backgroundUndecodableDropsToColor() = runBlocking {
        val f = fixture()
        val badImage = byteArrayOf(9, 9, 9)
        val files = packageFiles(
            skinJson = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""" +
                """"appearance":{"background":{"type":"asset","asset":"bg_main"}}}""",
            assets = listOf(Triple("bg_main", "assets/bg.png", badImage)),
        )
        val report = imported(f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL))
        assertTrue(report.backgroundDropped)
        assertTrue(
            report.issues.any {
                it.kind == SkinIssue.Kind.RESOURCE_FALLBACK &&
                    it.detail == "background_undecodable"
            },
        )
    }

    @Test
    fun layoutStoredButNotApplied() = runBlocking {
        val f = fixture()
        val files = packageFiles(
            skinJson = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},"layoutId":"lay-1"}""",
            layoutId = "lay-1",
        )
        val report = imported(f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL))
        assertEquals(listOf("lay-1"), report.importedLayoutIds)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.layouts.size)
    }

    @Test
    fun zipRoundTripThroughExtractor() = runBlocking {
        val f = fixture()
        val zipFile = File(f.root, "pkg.jime")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            for (file in packageFiles()) {
                zip.putNextEntry(ZipEntry(file.path))
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
        val report = imported(f.importer.importZip(zipFile, ctx, JimeImporter.ConflictMode.FAIL))
        assertEquals(listOf("skin-a"), report.importedSkinIds)
    }
}
