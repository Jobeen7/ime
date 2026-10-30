package com.jobeen.ime.skin.pkg

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.StyleField
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随包交付的 验证皮肤（files/validation-skin.json）的回归夹具：
 * 校验器零拒绝 + 导入管线零跳过零问题。皮肤文件本身不在仓库产品代码内，
 * 本测试只保证其 skin.json 格式与当前 v3 模型兼容。
 */
class ValidationSkinImportTest {

    private class FakeImageProbe : ImageInfoProbe {
        override fun bounds(bytes: ByteArray): ImageSize? = null
    }

    private class FakeAudioProbe : AudioInfoProbe {
        override fun probe(bytes: ByteArray): AudioInfo? = null
    }

    private class FakePreparer : BackgroundPreparer {
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result =
            BackgroundPreparer.Result.Done(
                request.preparedKey, request.targetWidthPx, request.targetHeightPx,
            )
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

    private fun skinBytes(): ByteArray {
        val stream = requireNotNull(javaClass.classLoader).getResourceAsStream("validation-skin.json")
            ?: error("missing test resource validation-skin.json")
        return stream.readBytes()
    }

    private fun skinJson(): String = skinBytes().decodeToString()

    @Test
    fun validatorAcceptsValidationSkinWithoutRejections() {
        val tree = Json.parseToJsonElement(skinJson()).jsonObject
        val result = SkinValidator.validate(
            tree = tree,
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
            assetKind = { null },
        )
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        assertTrue("validator issues: ${result.issues}", result.issues.isEmpty())
        assertEquals("validation-skin", skin.metaId)
        assertEquals("验证皮肤", skin.metaName)
        assertEquals(0xFFFFFFFF.toInt(), skin.styles.colors[StyleField.KEY_BACKGROUND])
        assertEquals(0xFF9C824A.toInt(), skin.styles.colors[StyleField.ACCENT_KEY_BACKGROUND])
        assertEquals(0xFFD1D1D6.toInt(), skin.styles.colors[StyleField.BACKGROUND])
        assertEquals(0x66000000, skin.styles.colors[StyleField.ALT_TEXT])
        assertEquals(KeyboardColors.SurfaceStyle.Flat, skin.styles.surfaceStyle)
        assertEquals(
            setOf(
                GeometryField.CORNER_RADIUS, GeometryField.KEY_H_MARGIN,
                GeometryField.KEY_V_MARGIN, GeometryField.BOTTOM_INSET,
            ),
            skin.appearance.presence,
        )
        assertEquals(8.5f, skin.appearance.effectiveGeometry[GeometryField.CORNER_RADIUS])
        assertEquals(4f, skin.appearance.effectiveGeometry[GeometryField.KEY_H_MARGIN])
        assertEquals(3f, skin.appearance.effectiveGeometry[GeometryField.KEY_V_MARGIN])
        assertEquals(10f, skin.appearance.effectiveGeometry[GeometryField.BOTTOM_INSET])

        assertTrue("key rejections: ${skin.keyRejections}", skin.keyRejections.isEmpty())
        val overrides: Map<String, KeyOverride> = skin.keyOverrides
        assertEquals(
            setOf("T9_LAYOUT_NUMBER", "T9_PERIOD", "T9_SCHEMA", "KEY_SCHEMA", "KEY_PERIOD"),
            overrides.keys,
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "Symbol"),
            overrides.getValue("T9_LAYOUT_NUMBER").swipeUp,
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "Emoji"),
            overrides.getValue("T9_LAYOUT_NUMBER").swipeDown,
        )
        assertEquals(
            SkinAction.Text("。"),
            overrides.getValue("T9_PERIOD").swipeUp,
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "Qwerty"),
            overrides.getValue("T9_SCHEMA").swipeDown,
        )
        assertEquals(
            SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "T9"),
            overrides.getValue("KEY_SCHEMA").swipeDown,
        )
        assertEquals(
            SkinAction.Text("，"),
            overrides.getValue("KEY_PERIOD").swipeUp,
        )
    }

    @Test
    fun shippedJimeZipPassesExtractorAndImports() = runBlocking {
        val zipBytes = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("validation-skin.jime")?.readBytes()
            ?: error("missing test resource validation-skin.jime")
        val root = Files.createTempDirectory("bin-skin-zip").toFile()
        val zipFile = File(root, "validation-skin.jime").apply { writeBytes(zipBytes) }
        val extracted = ZipSafeExtractor.extract(zipFile)
        assertTrue("extractor rejected shipped zip: $extracted", extracted is ZipSafeExtractor.Result.Ok)
        val files = (extracted as ZipSafeExtractor.Result.Ok).files
        assertEquals(setOf("skin.json", "manifest.json"), files.map { it.path }.toSet())
        assertTrue(
            files.first { it.path == "skin.json" }.bytes.contentEquals(skinBytes()),
        )
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
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
        val outcome = importer.importZip(
            zipFile,
            JimeImporter.Context(
                currentVersionCode = 143,
                fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
                tier = MemoryTier.NORMAL,
                targetWidthPx = 100,
                targetHeightPx = 50,
            ),
            JimeImporter.ConflictMode.FAIL,
        )
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        val report = (outcome as JimeImporter.Outcome.Imported).report
        assertEquals(listOf("validation-skin"), report.importedSkinIds)
        assertTrue("issues: ${report.issues}", report.issues.isEmpty())
    }

    @Test
    fun binSkinImportsCleanly() = runBlocking {
        val root = Files.createTempDirectory("bin-skin-import").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
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
        val bytes = skinBytes()
        val manifest = PackageManifest.toJson(
            PackageManifest(
                3,
                listOf(
                    PackageManifest.Content(
                        PackageManifest.TYPE_SKIN, "validation-skin", "skin.json",
                        DigestUtil.sha256Hex(bytes),
                    ),
                ),
            ),
        )
        val files = listOf(
            ZipSafeExtractor.ExtractedFile("skin.json", bytes),
            ZipSafeExtractor.ExtractedFile(JimeImporter.MANIFEST_PATH, manifest.encodeToByteArray()),
        )
        val outcome = importer.importFiles(
            files,
            JimeImporter.Context(
                currentVersionCode = 143,
                fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
                tier = MemoryTier.NORMAL,
                targetWidthPx = 100,
                targetHeightPx = 50,
            ),
            JimeImporter.ConflictMode.FAIL,
        )
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        val report = (outcome as JimeImporter.Outcome.Imported).report
        assertEquals(listOf("validation-skin"), report.importedSkinIds)
        assertTrue("skipped: ${report.skippedSkinIds}", report.skippedSkinIds.isEmpty())
        assertTrue("issues: ${report.issues}", report.issues.isEmpty())
        val index = (store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.skins.size)
        assertEquals("validation-skin", index.skins.single().id)
        val genDir = layout.skinGenDir("validation-skin", 1)
        assertTrue(File(genDir, "skin.json").isFile)
        assertTrue(File(genDir, "manifest.json").isFile)
    }
}
