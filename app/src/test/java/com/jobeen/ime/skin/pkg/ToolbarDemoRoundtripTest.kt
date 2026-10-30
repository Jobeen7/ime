package com.jobeen.ime.skin.pkg

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.export.JimeExporter
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演示包 .jime 往返（2026-10-27 工具栏进皮肤分流交付物）：
 * 提交的 app/src/test/resources/toolbar-demo.jime 本体经导入管线
 * 导入（零工具栏 issue）→ 导出后 skin.json 的 toolbar 节点与原包
 * 逐项相等，5 项图标资产齐全。导入是文件级（importZip），不是
 * 手工拼 manifest 的夹具包。
 */
class ToolbarDemoRoundtripTest {

    private class FakeImageProbe : ImageInfoProbe {
        override fun bounds(bytes: ByteArray): ImageSize? =
            if (bytes.isNotEmpty() && bytes[0] == 'I'.code.toByte()) ImageSize(100, 50) else null
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

    private val ctx = JimeImporter.Context(
        currentVersionCode = 157,
        fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        tier = MemoryTier.NORMAL,
        targetWidthPx = 100,
        targetHeightPx = 50,
    )

    private fun toolbarOf(skinJson: String): kotlinx.serialization.json.JsonElement {
        val tree = Json.parseToJsonElement(skinJson) as JsonObject
        return tree.jsonObject.getValue("toolbar")
    }

    @Test
    fun demoPackageImportsAndExportsToolbarUnchanged() = runBlocking {
        val root = Files.createTempDirectory("toolbar-demo").toFile()
        val layout = SkinLibraryLayout(root).also { it.ensureDirs() }
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

        val demoBytes = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("toolbar-demo.jime")?.readBytes()
            ?: error("missing test resource toolbar-demo.jime")
        val demoFile = File(root, "toolbar-demo.jime").apply { writeBytes(demoBytes) }

        val outcome = importer.importZip(demoFile, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        val report = (outcome as JimeImporter.Outcome.Imported).report
        assertEquals(listOf("toolbar-demo"), report.importedSkinIds)
        assertTrue(
            "toolbar issues: ${report.issues}",
            report.issues.none { it.path.startsWith("toolbar") },
        )

        val originalToolbar = toolbarOf(
            ZipInputStream(ByteArrayInputStream(demoBytes)).use { zip ->
                var skin: String? = null
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "skin.json") skin = zip.readBytes().decodeToString()
                }
                requireNotNull(skin)
            },
        )
        val storedSkin = File(layout.skinGenDir("toolbar-demo", 1), "skin.json").readText()
        assertEquals(originalToolbar, toolbarOf(storedSkin))

        val exportedBytes = ByteArrayOutputStream()
        val exported = JimeExporter(layout, store).export("toolbar-demo", exportedBytes)
        assertTrue("expected Exported, got $exported", exported is JimeExporter.Result.Exported)
        val result = exported as JimeExporter.Result.Exported
        assertEquals(
            listOf("tb-emoji", "tb-voice", "tb-copy", "tb-expand", "tb-collapse"),
            result.assetIds,
        )
        val exportedSkin = ZipInputStream(ByteArrayInputStream(exportedBytes.toByteArray())).use { zip ->
            var skin: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "skin.json") skin = zip.readBytes().decodeToString()
            }
            requireNotNull(skin)
        }
        assertEquals(originalToolbar, toolbarOf(exportedSkin))
    }
}
