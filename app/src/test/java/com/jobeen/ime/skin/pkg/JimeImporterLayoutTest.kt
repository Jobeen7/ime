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
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入期布局结构校验与副本改写（B3 详设 §5.1/§5.2）。 */
class JimeImporterLayoutTest {

    private class FakeImageProbe : ImageInfoProbe {
        override fun bounds(bytes: ByteArray): ImageSize? = null
    }

    private class FakeAudioProbe : AudioInfoProbe {
        override fun probe(bytes: ByteArray): AudioInfo? = null
    }

    private class FakePreparer : BackgroundPreparer {
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result =
            BackgroundPreparer.Result.Failed("unused")
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

    private class Fixture(
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val importer: JimeImporter,
    )

    private fun fixture(newId: () -> String = { "copy-1" }): Fixture {
        val root = Files.createTempDirectory("jime-import-layout").toFile()
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
            newId = newId,
        )
        return Fixture(layout, store, importer)
    }

    private val ctx = JimeImporter.Context(
        currentVersionCode = 143,
        fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        tier = MemoryTier.NORMAL,
        targetWidthPx = 100,
        targetHeightPx = 50,
    )

    private fun sha(bytes: ByteArray): String = DigestUtil.sha256Hex(bytes)

    private fun layoutJson(
        id: String = "lay-1",
        keyNode: String = """{"stableId":"1","label":"q","tap":{"type":"text","value":"q"}}""",
    ): String = """{"formatVersion":3,"meta":{"id":"$id","name":"L"},""" +
        """"rows":[{"keys":[$keyNode]}]}"""

    private fun packageFiles(
        skinJson: String,
        layout: Pair<String, String>? = null,
    ): List<ZipSafeExtractor.ExtractedFile> {
        val skinBytes = skinJson.encodeToByteArray()
        val contents = mutableListOf(
            PackageManifest.Content(PackageManifest.TYPE_SKIN, "skin-a", "skin.json", sha(skinBytes)),
        )
        val files = mutableListOf(ZipSafeExtractor.ExtractedFile("skin.json", skinBytes))
        if (layout != null) {
            val layoutBytes = layout.second.encodeToByteArray()
            contents += PackageManifest.Content(
                PackageManifest.TYPE_LAYOUT, layout.first, "layouts/${layout.first}.json",
                sha(layoutBytes),
            )
            files += ZipSafeExtractor.ExtractedFile("layouts/${layout.first}.json", layoutBytes)
        }
        val manifestJson = PackageManifest.toJson(PackageManifest(3, contents))
        files += ZipSafeExtractor.ExtractedFile(
            JimeImporter.MANIFEST_PATH, manifestJson.encodeToByteArray(),
        )
        return files
    }

    private val plainSkin =
        """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""" +
            """"styles":{"keyBackground":"#FF112233"},"stylesDark":{"keyBackground":"#FF222222"}}"""

    private fun imported(outcome: JimeImporter.Outcome): JimeImporter.ImportReport {
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        return (outcome as JimeImporter.Outcome.Imported).report
    }

    @Test
    fun validLayoutImportsAndIsIndexed() = runBlocking {
        val f = fixture()
        val report = imported(
            f.importer.importFiles(
                packageFiles(plainSkin, "lay-1" to layoutJson()),
                ctx, JimeImporter.ConflictMode.FAIL,
            ),
        )
        assertEquals(listOf("lay-1"), report.importedLayoutIds)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, index.layouts.size)
        assertTrue(File(f.layout.layoutGenDir("lay-1", 1), "layout.json").isFile)
    }

    @Test
    fun invalidLayoutRejectsWholePackageWithFieldPaths() = runBlocking {
        val f = fixture()
        val badWeight = layoutJson(
            keyNode = """{"stableId":"1","label":"q","widthWeight":0.0,""" +
                """"tap":{"type":"text","value":"q"}}""",
        )
        val outcome = f.importer.importFiles(
            packageFiles(plainSkin, "lay-1" to badWeight),
            ctx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue(outcome is JimeImporter.Outcome.Rejected)
        val rejected = outcome as JimeImporter.Outcome.Rejected
        assertEquals("layout_invalid:lay-1", rejected.reason)
        // 逐项列出：报告 issues 含布局字段路径
        assertTrue(
            rejected.issues.any { it.path.contains("rows[0].keys[0].widthWeight") },
        )
        // 整包拒绝：库里什么都没有（索引根本未落盘 → Missing，或空索引）
        when (val read = f.store.readIndex()) {
            is SkinLibraryStore.ReadResult.Ok -> assertTrue(
                read.value.skins.isEmpty() && read.value.layouts.isEmpty(),
            )
            is SkinLibraryStore.ReadResult.Missing -> Unit
            else -> throw AssertionError("unexpected read result: $read")
        }
    }

    @Test
    fun fieldFallbackLayoutImportsWithIssues() = runBlocking {
        val f = fixture()
        // meta.name 缺失 → 逐字段回退（名回退 id），不拦包
        val noName = """{"formatVersion":3,"meta":{"id":"lay-1"},""" +
            """"rows":[{"keys":[{"stableId":"1","label":"q","tap":{"type":"text","value":"q"}}]}]}"""
        val report = imported(
            f.importer.importFiles(
                packageFiles(plainSkin, "lay-1" to noName),
                ctx, JimeImporter.ConflictMode.FAIL,
            ),
        )
        assertEquals(listOf("lay-1"), report.importedLayoutIds)
        assertTrue(report.issues.any { it.path == "layouts.lay-1.meta.name" })
    }

    @Test
    fun orphanLayoutImportsFine() = runBlocking {
        // 皮肤不引用该布局（独立布局条目）：导入照常，运行时不生效
        val f = fixture()
        val report = imported(
            f.importer.importFiles(
                packageFiles(plainSkin, "lay-orphan" to layoutJson("lay-orphan")),
                ctx, JimeImporter.ConflictMode.FAIL,
            ),
        )
        assertEquals(listOf("lay-orphan"), report.importedLayoutIds)
    }

    @Test
    fun copyImportRewritesLayoutIdKeyPrefixesAndMeta() = runBlocking {
        var n = 0
        val f = fixture(newId = { "copy-${++n}" })
        val skinWithLayout = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""" +
            """"styles":{"keyBackground":"#FF112233"},"stylesDark":{"keyBackground":"#FF222222"},"layoutId":"lay-1",""" +
            """"keyMappings":{"lay-1:K-1":{"label":"Q!","tap":{"type":"command","name":"delete"}}}}"""
        val files = packageFiles(skinWithLayout, "lay-1" to layoutJson())
        imported(f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL))
        // 同 id 异内容再导入 → 副本模式
        val changedSkin = skinWithLayout.replace("\"name\":\"A\"", "\"name\":\"A2\"")
        val changedLayout = layoutJson().replace("\"name\":\"L\"", "\"name\":\"L2\"")
        val copied = imported(
            f.importer.importFiles(
                packageFiles(changedSkin, "lay-1" to changedLayout),
                ctx, JimeImporter.ConflictMode.COPY,
            ),
        )
        assertEquals(
            mapOf("skin-a" to "copy-1", "lay-1" to "copy-2"), copied.copies,
        )
        // 副本皮肤树：layoutId 改写、keyMappings 键前缀改写、meta.id 改写
        val copySkinJson = File(f.layout.skinGenDir("copy-1", 1), "skin.json").readText()
        assertTrue(copySkinJson.contains("\"layoutId\":\"copy-2\""))
        assertTrue(copySkinJson.contains("\"copy-2:K-1\""))
        assertFalse(copySkinJson.contains("\"lay-1"))
        // 副本布局文件：meta.id 改写为新 id（铁律 3），索引 hash 与文件一致
        val copyLayoutFile = File(f.layout.layoutGenDir("copy-2", 1), "layout.json")
        assertTrue(copyLayoutFile.readText().contains("\"id\":\"copy-2\""))
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        val copyEntry = index.layouts.single { it.id == "copy-2" }
        assertEquals(sha(copyLayoutFile.readBytes()), copyEntry.hash)
    }

    @Test
    fun updateModeNewGenerationWithLayoutChange() = runBlocking {
        val f = fixture()
        val files = packageFiles(plainSkin, "lay-1" to layoutJson())
        imported(f.importer.importFiles(files, ctx, JimeImporter.ConflictMode.FAIL))
        val changed = packageFiles(plainSkin, "lay-1" to layoutJson().replace("L", "L2"))
        val report = imported(
            f.importer.importFiles(changed, ctx, JimeImporter.ConflictMode.UPDATE),
        )
        assertEquals(listOf("lay-1"), report.importedLayoutIds)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(2, index.layouts.single().generation)
    }
}
