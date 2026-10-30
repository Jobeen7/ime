package com.jobeen.ime.skin.pkg

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.export.JimeExporter
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置布局导入分流（2026-10-28 修复轮）：内置布局随皮肤代次落盘、
 * 不进全局布局索引、不参与跨皮肤冲突；无效内置布局只丢覆盖不拦包；
 * 导出闭包含内置布局（缺文件时跳过而非失败）。
 */
class BuiltinLayoutImportTest {

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
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
            val dir = File(request.genDir, "prepared").apply { mkdirs() }
            File(dir, "${request.preparedKey}.webp").writeBytes(byteArrayOf(1, 2, 3))
            return BackgroundPreparer.Result.Done(
                request.preparedKey, request.targetWidthPx, request.targetHeightPx,
            )
        }
    }

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

    private data class Fixture(
        val root: File,
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val importer: JimeImporter,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-builtin-import").toFile()
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
        )
        return Fixture(root, layout, store, importer)
    }

    private val ctx = JimeImporter.Context(
        currentVersionCode = 157,
        fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        tier = MemoryTier.NORMAL,
        targetWidthPx = 100,
        targetHeightPx = 50,
    )

    private fun skinJson(id: String): String =
        "{\"formatVersion\": 3, \"meta\": {\"id\": \"$id\", \"name\": \"$id\"}, " +
            "\"styles\": {\"keyBackground\": \"#FF112233\"}, \"stylesDark\": {\"keyBackground\": \"#FF222222\"}}"

    private fun t9Layout(label2: String): String =
        "{\"formatVersion\": 3, \"meta\": {\"id\": \"T9\", \"name\": \"T9\"}, " +
            "\"rows\": [{\"keys\": [" +
            "{\"stableId\": \"T9_KEY_2\", \"label\": \"$label2\", \"widthWeight\": 1.0, \"rowSpan\": 1}, " +
            "{\"stableId\": \"T9_KEY_3\", \"label\": \"3\", \"widthWeight\": 1.0, \"rowSpan\": 1}" +
            "]}]}"

    private fun packageFiles(skinId: String, layoutBytes: ByteArray): List<ZipSafeExtractor.ExtractedFile> {
        val skinBytes = skinJson(skinId).encodeToByteArray()
        val contents = listOf(
            PackageManifest.Content(
                PackageManifest.TYPE_SKIN, skinId, "skin.json",
                DigestUtil.sha256Hex(skinBytes),
            ),
            PackageManifest.Content(
                PackageManifest.TYPE_LAYOUT, "T9", "layouts/T9.json",
                DigestUtil.sha256Hex(layoutBytes),
            ),
        )
        return listOf(
            ZipSafeExtractor.ExtractedFile("skin.json", skinBytes),
            ZipSafeExtractor.ExtractedFile("layouts/T9.json", layoutBytes),
            ZipSafeExtractor.ExtractedFile(
                JimeImporter.MANIFEST_PATH,
                PackageManifest.toJson(PackageManifest(3, contents)).encodeToByteArray(),
            ),
        )
    }

    private suspend fun indexLayouts(f: Fixture): List<String> {
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        return index.layouts.map { it.id }
    }

    @Test
    fun builtinLayoutGoesToSkinGenDir_notGlobalIndex() = runBlocking {
        val f = fixture()
        val layoutBytes = t9Layout("2").encodeToByteArray()
        val outcome = f.importer.importFiles(packageFiles("skin-a", layoutBytes), ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        assertFalse("T9 must not enter the global layout index", "T9" in indexLayouts(f))
        val staged = File(f.layout.skinGenDir("skin-a", 1), "layouts/T9.json")
        assertTrue(staged.isFile)
        assertEquals(layoutBytes.decodeToString(), staged.readText())
    }

    @Test
    fun twoSkinsWithDifferentT9DoNotConflict() = runBlocking {
        val f = fixture()
        val a = f.importer.importFiles(packageFiles("skin-a", t9Layout("2").encodeToByteArray()), ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue(a is JimeImporter.Outcome.Imported)
        val bBytes = t9Layout("二").encodeToByteArray()
        val b = f.importer.importFiles(packageFiles("skin-b", bBytes), ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("builtin overrides are per-skin, must not ConflictPending: $b", b is JimeImporter.Outcome.Imported)
        assertEquals(
            t9Layout("2"),
            File(f.layout.skinGenDir("skin-a", 1), "layouts/T9.json").readText(),
        )
        assertEquals(bBytes.decodeToString(), File(f.layout.skinGenDir("skin-b", 1), "layouts/T9.json").readText())
    }

    @Test
    fun invalidBuiltinDropsOverrideWithoutRejectingSkin() = runBlocking {
        val f = fixture()
        val badLayout = "{\"formatVersion\": 3, \"meta\": {\"id\": \"T9\", \"name\": \"T9\"}, " +
            "\"rows\": [{\"keys\": [" +
            "{\"stableId\": \"T9_KEY_2\", \"label\": \"2\", \"widthWeight\": 1.0, \"rowSpan\": 1}, " +
            "{\"stableId\": \"T9_KEY_2\", \"label\": \"2\", \"widthWeight\": 1.0, \"rowSpan\": 1}" +
            "]}]}"
        val outcome = f.importer.importFiles(packageFiles("skin-c", badLayout.encodeToByteArray()), ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        val report = (outcome as JimeImporter.Outcome.Imported).report
        assertTrue(report.issues.any { it.path.startsWith("layouts.T9") })
        assertFalse(File(f.layout.skinGenDir("skin-c", 1), "layouts/T9.json").exists())
        // 导出不因缺失的内置覆盖失败，且导出包不再含该布局。
        val out = ByteArrayOutputStream()
        val exported = JimeExporter(f.layout, f.store).export("skin-c", out)
        assertTrue("expected Exported, got $exported", exported is JimeExporter.Result.Exported)
        val zipFile = File(f.root, "out-c.jime").apply { writeBytes(out.toByteArray()) }
        val extracted = ZipSafeExtractor.extract(zipFile)
        assertTrue(extracted is ZipSafeExtractor.Result.Ok)
        val paths = (extracted as ZipSafeExtractor.Result.Ok).files.map { it.path }
        assertFalse("layouts/T9.json" in paths)
    }

    @Test
    fun exportCarriesBuiltinLayout() = runBlocking {
        val f = fixture()
        val layoutBytes = t9Layout("2").encodeToByteArray()
        val outcome = f.importer.importFiles(packageFiles("skin-a", layoutBytes), ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue(outcome is JimeImporter.Outcome.Imported)
        val out = ByteArrayOutputStream()
        val exported = JimeExporter(f.layout, f.store).export("skin-a", out)
        assertTrue("expected Exported, got $exported", exported is JimeExporter.Result.Exported)
        val zipFile = File(f.root, "out-a.jime").apply { writeBytes(out.toByteArray()) }
        val extracted = ZipSafeExtractor.extract(zipFile)
        assertTrue(extracted is ZipSafeExtractor.Result.Ok)
        val byPath = (extracted as ZipSafeExtractor.Result.Ok).files.associateBy { it.path }
        assertEquals(layoutBytes.decodeToString(), byPath.getValue("layouts/T9.json").bytes.decodeToString())
    }
}
