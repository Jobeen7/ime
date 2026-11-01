package com.jobeen.ime.skin.export

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
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.ZipSafeExtractor
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导出 + round-trip（定稿十六.2）：未知/未支持节点逐节点无损、资产字节一致。 */
class JimeExporterTest {

    private class FakePreparer : BackgroundPreparer {
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result {
            val dir = File(request.genDir, "prepared").apply { mkdirs() }
            File(dir, "${request.preparedKey}.webp").writeBytes(byteArrayOf(7))
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

    private val skinJson = """
        {
          "formatVersion": 3,
          "meta": {"id": "skin-a", "name": "往返", "author": "T", "version": "2.0"},
          "layoutId": "lay-1",
          "appearance": {
            "background": {"type": "asset", "asset": "bg_main", "blurRadius": 12, "overlayAlpha": 0.3},
            "cornerRadius": 8.0
          },
          "styles": {"keyBackground": "#FF112233", "futureStyle": {"nested": [1, 2.5, "x"]}},
          "stylesDark": {"keyBackground": "#FF222222"},
          "keyMappings": {
            "KEY_Q": {"label": "Q", "tap": {"type": "text", "value": "q"},
              "futureAction": {"type": "sequence", "steps": [1, 2]}}
          },
          "audio": {"tap": "tap_main"},
          "futureTopLevel": {"keep": ["me", {"deep": true}]}
        }
    """.trimIndent()

    // B3 冻结结构内的合法布局 + 两级未知节点（往返保真断言对象不变）
    private val layoutJson = """{"formatVersion":3,"meta":{"id":"lay-1","name":"往返布局"},""" +
        """"rows":[{"keys":[{"stableId":"1","label":"q","widthWeight":1.5,""" +
        """"tap":{"type":"text","value":"q"},"futureKeyNode":{"x":1}}]}],"futureLayoutNode":{"x":1}}"""

    @Test
    fun roundTripPreservesUnknownNodesAndAssets() = runBlocking {
        val root = Files.createTempDirectory("jime-roundtrip").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val importer = JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = object : ImageInfoProbe {
                override fun bounds(bytes: ByteArray) = ImageSize(64, 32)
            },
            audioProbe = object : AudioInfoProbe {
                override fun probe(bytes: ByteArray) = AudioInfo(500, 44100, 2)
            },
            backgroundPreparer = FakePreparer(),
            defaultScheme = defaultScheme,
        )
        val imageBytes = byteArrayOf(11, 22, 33)
        val audioBytes = byteArrayOf(44, 55)
        val skinBytes = skinJson.encodeToByteArray()
        val layoutBytes = layoutJson.encodeToByteArray()
        val contents = listOf(
            PackageManifest.Content("skin", "skin-a", "skin.json", DigestUtil.sha256Hex(skinBytes)),
            PackageManifest.Content(
                "layout", "lay-1", "layouts/lay-1.json", DigestUtil.sha256Hex(layoutBytes),
            ),
            PackageManifest.Content(
                "asset", "bg_main", "assets/bg.png", DigestUtil.sha256Hex(imageBytes),
            ),
            PackageManifest.Content(
                "asset", "tap_main", "assets/tap.ogg", DigestUtil.sha256Hex(audioBytes),
            ),
        )
        val files = listOf(
            ZipSafeExtractor.ExtractedFile("skin.json", skinBytes),
            ZipSafeExtractor.ExtractedFile("layouts/lay-1.json", layoutBytes),
            ZipSafeExtractor.ExtractedFile("assets/bg.png", imageBytes),
            ZipSafeExtractor.ExtractedFile("assets/tap.ogg", audioBytes),
            ZipSafeExtractor.ExtractedFile(
                "manifest.json", PackageManifest.toJson(PackageManifest(3, contents))
                    .encodeToByteArray(),
            ),
        )
        val ctx = JimeImporter.Context(
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
            tier = MemoryTier.NORMAL,
            targetWidthPx = 64,
            targetHeightPx = 32,
        )
        val outcome = importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue(outcome is JimeImporter.Outcome.Imported)

        val out = ByteArrayOutputStream()
        val exported = JimeExporter(layout, store).export("skin-a", out)
        assertTrue(exported is JimeExporter.Result.Exported)
        exported as JimeExporter.Result.Exported
        assertEquals(listOf("lay-1"), exported.layoutIds)
        assertEquals(setOf("bg_main", "tap_main"), exported.assetIds.toSet())

        // 导出包重新解包，逐节点比对
        val zipFile = File(root, "out.jime").apply { writeBytes(out.toByteArray()) }
        val extracted = ZipSafeExtractor.extract(zipFile)
        assertTrue(extracted is ZipSafeExtractor.Result.Ok)
        val byPath = (extracted as ZipSafeExtractor.Result.Ok).files.associateBy { it.path }
        val exportedSkinTree = Json.parseToJsonElement(
            byPath.getValue("skin.json").bytes.decodeToString(),
        ) as JsonObject
        val originalSkinTree = Json.parseToJsonElement(skinJson) as JsonObject
        assertTrue(
            "skin tree must round-trip node by node",
            SkinJson.deepEquals(originalSkinTree, exportedSkinTree),
        )
        assertTrue(byPath.getValue("layouts/lay-1.json").bytes.contentEquals(layoutBytes))
        assertTrue(byPath.getValue("assets/bg.png").bytes.contentEquals(imageBytes))
        assertTrue(byPath.getValue("assets/tap.ogg").bytes.contentEquals(audioBytes))
        // 导出清单 sha 与导出字节相符
        val exportedManifest = PackageManifest.parse(
            byPath.getValue("manifest.json").bytes.decodeToString(),
        )!!
        for (content in exportedManifest.contents) {
            assertEquals(
                content.sha256,
                DigestUtil.sha256Hex(byPath.getValue(content.path).bytes),
            )
        }
    }

    @Test
    fun docsRoundTrip_unlistedRootDemoAndReadmeSurviveImportAndExport() = runBlocking {
        val root = Files.createTempDirectory("jime-docs-roundtrip").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val importer = JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = object : ImageInfoProbe {
                override fun bounds(bytes: ByteArray) = ImageSize(64, 32)
            },
            audioProbe = object : AudioInfoProbe {
                override fun probe(bytes: ByteArray) = AudioInfo(500, 44100, 2)
            },
            backgroundPreparer = FakePreparer(),
            defaultScheme = defaultScheme,
        )
        val skinBytes = skinJson.encodeToByteArray()
        val layoutBytes = layoutJson.encodeToByteArray()
        val demoBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3,
        )
        val readmeBytes = "用法：切换中英文。".encodeToByteArray()
        val contents = listOf(
            PackageManifest.Content("skin", "skin-a", "skin.json", DigestUtil.sha256Hex(skinBytes)),
            PackageManifest.Content(
                "layout", "lay-1", "layouts/lay-1.json", DigestUtil.sha256Hex(layoutBytes),
            ),
        )
        val files = listOf(
            ZipSafeExtractor.ExtractedFile("skin.json", skinBytes),
            ZipSafeExtractor.ExtractedFile("layouts/lay-1.json", layoutBytes),
            ZipSafeExtractor.ExtractedFile(
                "manifest.json", PackageManifest.toJson(PackageManifest(3, contents))
                    .encodeToByteArray(),
            ),
            // 包根演示资料：故意不列入清单——豁免后导入不得拒绝。
            ZipSafeExtractor.ExtractedFile("demo.png", demoBytes),
            ZipSafeExtractor.ExtractedFile("README.md", readmeBytes),
        )
        val ctx = JimeImporter.Context(
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
            tier = MemoryTier.NORMAL,
            targetWidthPx = 64,
            targetHeightPx = 32,
        )
        val outcome = importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("unlisted root docs must not reject the package", outcome is JimeImporter.Outcome.Imported)

        val out = ByteArrayOutputStream()
        val exported = JimeExporter(layout, store).export("skin-a", out)
        assertTrue(exported is JimeExporter.Result.Exported)
        val zipFile = File(root, "out.jime").apply { writeBytes(out.toByteArray()) }
        val extracted = ZipSafeExtractor.extract(zipFile)
        assertTrue(extracted is ZipSafeExtractor.Result.Ok)
        val byPath = (extracted as ZipSafeExtractor.Result.Ok).files.associateBy { it.path }
        assertTrue(byPath.getValue("demo.png").bytes.contentEquals(demoBytes))
        assertTrue(byPath.getValue("README.md").bytes.contentEquals(readmeBytes))
        // 演示资料仍是清单外松散文件，不进入 manifest 资产闭包。
        val exportedManifest = PackageManifest.parse(
            byPath.getValue("manifest.json").bytes.decodeToString(),
        )!!
        assertTrue(exportedManifest.contents.none { it.path == "demo.png" })
        assertTrue(exportedManifest.contents.none { it.path == "README.md" })
    }

    @Test
    fun sidecarRoundTrip_unlistedYamlSurvivesImportAndExport() = runBlocking {
        val root = Files.createTempDirectory("jime-sidecar-roundtrip").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val importer = JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = object : ImageInfoProbe {
                override fun bounds(bytes: ByteArray) = ImageSize(64, 32)
            },
            audioProbe = object : AudioInfoProbe {
                override fun probe(bytes: ByteArray) = AudioInfo(500, 44100, 2)
            },
            backgroundPreparer = FakePreparer(),
            defaultScheme = defaultScheme,
        )
        val imageBytes = byteArrayOf(11, 22, 33)
        val imageSha = DigestUtil.sha256Hex(imageBytes)
        val audioBytes = byteArrayOf(44, 55)
        val skinBytes = skinJson.encodeToByteArray()
        val layoutBytes = layoutJson.encodeToByteArray()
        val sidecarBytes = "btn:\n  rect: {x: 0, y: 0, width: 10, height: 10}\n"
            .encodeToByteArray()
        val contents = listOf(
            PackageManifest.Content("skin", "skin-a", "skin.json", DigestUtil.sha256Hex(skinBytes)),
            PackageManifest.Content(
                "layout", "lay-1", "layouts/lay-1.json", DigestUtil.sha256Hex(layoutBytes),
            ),
            PackageManifest.Content(
                "asset", "bg_main", "assets/bg.png", imageSha,
            ),
            PackageManifest.Content(
                "asset", "tap_main", "assets/tap.ogg", DigestUtil.sha256Hex(audioBytes),
            ),
        )
        val files = listOf(
            ZipSafeExtractor.ExtractedFile("skin.json", skinBytes),
            ZipSafeExtractor.ExtractedFile("layouts/lay-1.json", layoutBytes),
            ZipSafeExtractor.ExtractedFile("assets/bg.png", imageBytes),
            ZipSafeExtractor.ExtractedFile("assets/tap.ogg", audioBytes),
            ZipSafeExtractor.ExtractedFile(
                "manifest.json", PackageManifest.toJson(PackageManifest(3, contents))
                    .encodeToByteArray(),
            ),
            // sidecar：故意不列入清单——豁免后导入不得拒绝。
            ZipSafeExtractor.ExtractedFile("assets/bg.yaml", sidecarBytes),
        )
        val ctx = JimeImporter.Context(
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
            tier = MemoryTier.NORMAL,
            targetWidthPx = 64,
            targetHeightPx = 32,
        )
        val outcome = importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("unlisted sidecar must not reject the package", outcome is JimeImporter.Outcome.Imported)

        // 导入落盘：代次目录 sidecars/<资产 sha>.yaml。
        val index = (store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        val skinEntry = index.skins.first { it.id == "skin-a" }
        val genDir = layout.fileFromRelative(skinEntry.dir)
        val stored = java.io.File(java.io.File(genDir, "sidecars"), "$imageSha.yaml")
        assertTrue(stored.isFile)
        assertTrue(stored.readBytes().contentEquals(sidecarBytes))

        val out = ByteArrayOutputStream()
        val exported = JimeExporter(layout, store).export("skin-a", out)
        assertTrue(exported is JimeExporter.Result.Exported)
        val zipFile = File(root, "out.jime").apply { writeBytes(out.toByteArray()) }
        val extracted = ZipSafeExtractor.extract(zipFile)
        assertTrue(extracted is ZipSafeExtractor.Result.Ok)
        val byPath = (extracted as ZipSafeExtractor.Result.Ok).files.associateBy { it.path }
        assertTrue(byPath.getValue("assets/bg.yaml").bytes.contentEquals(sidecarBytes))
        // sidecar 仍是清单外松散文件，不进入 manifest 资产闭包。
        val exportedManifest = PackageManifest.parse(
            byPath.getValue("manifest.json").bytes.decodeToString(),
        )!!
        assertTrue(exportedManifest.contents.none { it.path == "assets/bg.yaml" })
    }

    @Test
    fun exportMissingSkinFails() = runBlocking {
        val root = Files.createTempDirectory("jime-export-missing").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val result = JimeExporter(layout, store).export("nope", ByteArrayOutputStream())
        assertTrue(result is JimeExporter.Result.Failed)
    }
}
