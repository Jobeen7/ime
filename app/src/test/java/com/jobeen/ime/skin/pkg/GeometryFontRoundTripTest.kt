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
import com.jobeen.ime.skin.model.KeyboardGeometryField
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘级几何 + 字族的 .jime 往返（2026-10-28 交付口径补充）：导入入库与再导出后字段一致。 */
class GeometryFontRoundTripTest {

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

    /** 与演示包同值：圆角 14 / 间距 6 / 6 / 高度 30 / 字族 serif。 */
    private val skinJson = "{" +
        "\"formatVersion\": 3, " +
        "\"meta\": {\"id\": \"geo-roundtrip\", \"name\": \"Geo\"}, " +
        "\"styles\": {\"keyBackground\": \"#FF112233\"}, " +
        "\"stylesDark\": {\"keyBackground\": \"#FF222222\"}, " +
        "\"geometry\": {\"keyRadiusDp\": 14, \"keyHGapDp\": 6, \"keyVGapDp\": 6, \"heightPercent\": 30}, " +
        "\"fontFamily\": \"serif\"}"

    private data class Fixture(
        val root: File,
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val importer: JimeImporter,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-geo-roundtrip").toFile()
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
        currentVersionCode = 143,
        fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        tier = MemoryTier.NORMAL,
        targetWidthPx = 100,
        targetHeightPx = 50,
    )

    private fun packageFiles(): List<ZipSafeExtractor.ExtractedFile> {
        val skinBytes = skinJson.encodeToByteArray()
        val contents = listOf(
            PackageManifest.Content(
                PackageManifest.TYPE_SKIN, "geo-roundtrip", "skin.json",
                DigestUtil.sha256Hex(skinBytes),
            ),
        )
        return listOf(
            ZipSafeExtractor.ExtractedFile("skin.json", skinBytes),
            ZipSafeExtractor.ExtractedFile(
                JimeImporter.MANIFEST_PATH,
                PackageManifest.toJson(PackageManifest(3, contents)).encodeToByteArray(),
            ),
        )
    }

    private fun assertGeometryAndFont(tree: JsonObject) {
        val geometry = tree.getValue("geometry").jsonObject
        assertEquals(14, geometry.getValue("keyRadiusDp").jsonPrimitive.content.toInt())
        assertEquals(6, geometry.getValue("keyHGapDp").jsonPrimitive.content.toInt())
        assertEquals(6, geometry.getValue("keyVGapDp").jsonPrimitive.content.toInt())
        assertEquals(30, geometry.getValue("heightPercent").jsonPrimitive.content.toInt())
        assertEquals("serif", tree.getValue("fontFamily").jsonPrimitive.content)
    }

    @Test
    fun geometryAndFontSurviveImport() = runBlocking {
        val f = fixture()
        val outcome = f.importer.importFiles(packageFiles(), ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        // 2026-10-28 修复轮：几何单源（无 appearance 旧字段并存）应 0 issue；
        // 旧演示包因新旧几何字段并存且值不同，曾带 3 条 INFO（已修演示包）。
        val report = (outcome as JimeImporter.Outcome.Imported).report
        assertTrue("expected no issues, got ${report.issues}", report.issues.isEmpty())
        val stored = File(f.layout.skinGenDir("geo-roundtrip", 1), "skin.json").readText()
        val tree = Json.parseToJsonElement(stored) as JsonObject
        assertGeometryAndFont(tree)
        // 入库形态再经校验器：字段仍为有效值（非回退/丢弃）。
        val result = SkinValidator.validate(tree, 157, ctx.fallbacks, assetKind = { null })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        assertEquals(14f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_RADIUS))
        assertEquals(6f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_H_GAP))
        assertEquals(6f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.KEY_V_GAP))
        assertEquals(30f, skin.keyboardGeometry.values.getValue(KeyboardGeometryField.HEIGHT_PERCENT))
        assertEquals("serif", skin.fontFamily)
    }

    @Test
    fun geometryAndFontSurviveExport() = runBlocking {
        val f = fixture()
        val outcome = f.importer.importFiles(packageFiles(), ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        val out = ByteArrayOutputStream()
        val exported = JimeExporter(f.layout, f.store).export("geo-roundtrip", out)
        assertTrue("expected Exported, got $exported", exported is JimeExporter.Result.Exported)
        val zipFile = File(f.root, "out.jime").apply { writeBytes(out.toByteArray()) }
        val extracted = ZipSafeExtractor.extract(zipFile)
        assertTrue(extracted is ZipSafeExtractor.Result.Ok)
        val byPath = (extracted as ZipSafeExtractor.Result.Ok).files.associateBy { it.path }
        val exportedTree = Json.parseToJsonElement(
            byPath.getValue("skin.json").bytes.decodeToString(),
        ) as JsonObject
        assertGeometryAndFont(exportedTree)
        // 整树逐节点一致：新字段不是孤立断言，而是往返无损的一部分。
        val originalTree = Json.parseToJsonElement(skinJson) as JsonObject
        assertTrue(SkinJson.deepEquals(originalTree, exportedTree))
    }
}
