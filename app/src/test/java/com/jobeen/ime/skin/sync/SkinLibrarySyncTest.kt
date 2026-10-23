package com.jobeen.ime.skin.sync

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 同步判定表与墓碑口径（详设 §4.3/§4.5），假传输 + 真实导入管线。 */
class SkinLibrarySyncTest {

    private class FakeTransport : SkinSyncTransport {
        val files = mutableMapOf<String, ByteArray>()
        var authFail = false

        override suspend fun get(path: String): ByteArray? {
            if (authFail) throw SkinSyncTransport.AuthException("HTTP 401")
            return files[path]
        }

        override suspend fun put(path: String, bytes: ByteArray) {
            if (authFail) throw SkinSyncTransport.AuthException("HTTP 401")
            files[path] = bytes
        }

        override suspend fun delete(path: String) {
            files.remove(path)
        }
    }

    private class FakePreparer : BackgroundPreparer {
        override fun prepare(request: BackgroundPreparer.Request): BackgroundPreparer.Result =
            BackgroundPreparer.Result.Done(request.preparedKey, 1, 1)
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
        val stateStore: SkinSyncStateStore,
        val transport: FakeTransport,
        val engine: SkinLibrarySync,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-sync").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val stateStore = SkinSyncStateStore(layout)
        val transport = FakeTransport()
        var copyN = 0
        val importer = JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = object : ImageInfoProbe {
                override fun bounds(bytes: ByteArray) = ImageSize(8, 8)
            },
            audioProbe = object : AudioInfoProbe {
                override fun probe(bytes: ByteArray) = null
            },
            backgroundPreparer = FakePreparer(),
            defaultScheme = defaultScheme,
            newId = { "copy-${++copyN}" },
        )
        val ctx = JimeImporter.Context(
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(8f, 2f, 2f, 4f),
            tier = MemoryTier.NORMAL,
            targetWidthPx = 64,
            targetHeightPx = 32,
        )
        val engine = SkinLibrarySync(
            layout = layout,
            store = store,
            stateStore = stateStore,
            transport = transport,
            importTransaction = ImportTransaction(layout, store),
            importerProvider = { importer to ctx },
            sleeper = {},
        )
        return Fixture(layout, store, stateStore, transport, engine)
    }

    private fun skinJson(id: String, color: String) =
        """{"formatVersion":3,"meta":{"id":"$id","name":"皮肤$id"},"styles":{"keyBackground":"$color"}}"""

    private suspend fun writeLocalSkin(f: Fixture, id: String, jsonText: String) {
        val bytes = jsonText.encodeToByteArray()
        val hash = DigestUtil.sha256Hex(bytes)
        val genDir = f.layout.skinGenDir(id, 1).apply { mkdirs() }
        File(genDir, "skin.json").writeBytes(bytes)
        val manifest = PackageManifest(
            3,
            listOf(PackageManifest.Content("skin", id, "skin.json", hash)),
        )
        File(genDir, "manifest.json").writeText(PackageManifest.toJson(manifest))
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        f.store.writeIndex(
            index.copy(
                skins = index.skins.filterNot { it.id == id } + SkinIndexEntry(
                    id, "皮肤$id", hash, 1, "skins/$id/gen-1", bytes.size.toLong(), 0L,
                ),
            ),
        )
    }

    private fun seedRemoteSkin(f: Fixture, id: String, jsonText: String) {
        val bytes = jsonText.encodeToByteArray()
        val hash = DigestUtil.sha256Hex(bytes)
        f.transport.files["skins/$id.json"] = bytes
        val index = RemoteSkinIndex(
            entries = listOf(
                RemoteSkinEntry(id, "skin", hash, bytes.size.toLong(), 0L),
            ),
        )
        f.transport.files["index.json"] =
            Json.encodeToString(index).encodeToByteArray()
    }

    private fun actions(report: SkinLibrarySync.Report) =
        report.results.associate { it.unitKey to it.action }

    @Test
    fun uploadNewLocalThenSkip() = runBlocking {
        val f = fixture()
        writeLocalSkin(f, "s1", skinJson("s1", "#FF111111"))
        val first = f.engine.sync()
        assertNull(first.abortedReason)
        assertEquals(
            SkinLibrarySync.Action.UPLOADED, actions(first)["skin:s1"],
        )
        assertNotNull(f.transport.files["skins/s1.json"])
        assertNotNull(f.transport.files["index.json"])
        assertEquals(
            DigestUtil.sha256Hex(skinJson("s1", "#FF111111").encodeToByteArray()),
            f.stateStore.read().units["skin:s1"],
        )
        val second = f.engine.sync()
        assertEquals(SkinLibrarySync.Action.SKIPPED, actions(second)["skin:s1"])
    }

    @Test
    fun downloadNewRemoteSkin() = runBlocking {
        val f = fixture()
        seedRemoteSkin(f, "s2", skinJson("s2", "#FF222222"))
        val report = f.engine.sync()
        assertEquals(SkinLibrarySync.Action.DOWNLOADED, actions(report)["skin:s2"])
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("s2"), index.skins.map { it.id })
    }

    @Test
    fun fastForwardRemoteUpdateImportsNewGeneration() = runBlocking {
        val f = fixture()
        val v1 = skinJson("s1", "#FF111111")
        writeLocalSkin(f, "s1", v1)
        f.engine.sync() // B := hash(v1)
        val v2 = skinJson("s1", "#FF999999")
        seedRemoteSkin(f, "s1", v2)
        val report = f.engine.sync()
        assertEquals(SkinLibrarySync.Action.DOWNLOADED, actions(report)["skin:s1"])
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        val entry = index.skins.single { it.id == "s1" }
        assertEquals(DigestUtil.sha256Hex(v2.encodeToByteArray()), entry.hash)
        assertEquals(2, entry.generation) // 同 id 新代次（§4.4.3 只入库）
    }

    @Test
    fun doubleChangeConflictDownloadsCopyAndUploadsLocal() = runBlocking {
        val f = fixture()
        writeLocalSkin(f, "s1", skinJson("s1", "#FF111111"))
        f.engine.sync()
        // 本地与远端各自改动
        writeLocalSkin(f, "s1", skinJson("s1", "#FF222222"))
        seedRemoteSkin(f, "s1", skinJson("s1", "#FF333333"))
        val report = f.engine.sync()
        val results = report.results.associateBy { it.unitKey }
        assertEquals(
            SkinLibrarySync.Action.CONFLICT_COPY, results["skin:s1"]?.action,
        )
        val copyId = results["skin:s1"]?.copyId
        assertNotNull(copyId)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.any { it.id == copyId })
        // 本地版已上传：远端文件为本地内容
        assertEquals(
            skinJson("s1", "#FF222222"),
            f.transport.files["skins/s1.json"]!!.decodeToString(),
        )
    }

    @Test
    fun tombstoneDeletesRemoteWhenUnchanged() = runBlocking {
        val f = fixture()
        val v1 = skinJson("s1", "#FF111111")
        writeLocalSkin(f, "s1", v1)
        f.engine.sync()
        // 本地删除：索引摘除 + 记墓碑
        f.store.writeIndex(LibraryIndex())
        f.stateStore.recordTombstone(
            "skin", "s1", DigestUtil.sha256Hex(v1.encodeToByteArray()),
        )
        val report = f.engine.sync()
        assertEquals(
            SkinLibrarySync.Action.DELETED_REMOTE, actions(report)["skin:s1"],
        )
        assertFalse(f.transport.files.containsKey("skins/s1.json"))
        assertTrue(f.stateStore.read().tombstones.isEmpty())
    }

    @Test
    fun tombstoneRemoteChangedRevivesAsCopy() = runBlocking {
        val f = fixture()
        val v1 = skinJson("s1", "#FF111111")
        writeLocalSkin(f, "s1", v1)
        f.engine.sync()
        f.store.writeIndex(LibraryIndex())
        f.stateStore.recordTombstone(
            "skin", "s1", DigestUtil.sha256Hex(v1.encodeToByteArray()),
        )
        // 远端在删除后更新
        seedRemoteSkin(f, "s1", skinJson("s1", "#FF444444"))
        val report = f.engine.sync()
        val result = report.results.single { it.unitKey == "skin:s1" }
        assertEquals(SkinLibrarySync.Action.CONFLICT_COPY, result.action)
        assertNotNull(result.copyId)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.any { it.id == result.copyId })
        assertTrue(f.stateStore.read().tombstones.isEmpty())
    }

    // ---- B3 §7.1：布局单元的结构校验与副本改写 ----

    private fun layoutJson(id: String, name: String) =
        """{"formatVersion":3,"meta":{"id":"$id","name":"$name"},""" +
            """"rows":[{"keys":[{"stableId":"1","label":"q",""" +
            """"tap":{"type":"text","value":"q"}}]}]}"""

    private suspend fun writeLocalLayout(f: Fixture, id: String, jsonText: String) {
        val bytes = jsonText.encodeToByteArray()
        val hash = DigestUtil.sha256Hex(bytes)
        val genDir = f.layout.layoutGenDir(id, 1).apply { mkdirs() }
        File(genDir, "layout.json").writeBytes(bytes)
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        f.store.writeIndex(
            index.copy(
                layouts = index.layouts.filterNot { it.id == id } +
                    com.jobeen.ime.skin.store.LayoutIndexEntry(
                        id, hash, 1, "layouts/$id/gen-1", bytes.size.toLong(), 0L,
                    ),
            ),
        )
    }

    private fun seedRemoteLayout(f: Fixture, id: String, jsonText: String) {
        val bytes = jsonText.encodeToByteArray()
        val hash = DigestUtil.sha256Hex(bytes)
        f.transport.files["layouts/$id.json"] = bytes
        val index = RemoteSkinIndex(
            entries = listOf(
                RemoteSkinEntry(id, "layout", hash, bytes.size.toLong(), 0L),
            ),
        )
        f.transport.files["index.json"] =
            Json.encodeToString(index).encodeToByteArray()
    }

    @Test
    fun downloadNewRemoteLayoutValidatesAndImports() = runBlocking {
        val f = fixture()
        seedRemoteLayout(f, "lay-1", layoutJson("lay-1", "远端布局"))
        val report = f.engine.sync()
        assertEquals(
            SkinLibrarySync.Action.DOWNLOADED, actions(report)["layout:lay-1"],
        )
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("lay-1"), index.layouts.map { it.id })
    }

    @Test
    fun remoteInvalidLayoutFailsUnitAndStaysOutOfLibrary() = runBlocking {
        val f = fixture()
        // 权重非法（整布局无效）：单元失败进失败清单，本地库不被毒化
        val bad = """{"formatVersion":3,"meta":{"id":"lay-bad","name":"坏"},""" +
            """"rows":[{"keys":[{"stableId":"1","label":"q","widthWeight":0.0,""" +
            """"tap":{"type":"text","value":"q"}}]}]}"""
        seedRemoteLayout(f, "lay-bad", bad)
        val report = f.engine.sync()
        val result = report.results.single { it.unitKey == "layout:lay-bad" }
        assertEquals(SkinLibrarySync.Action.FAILED, result.action)
        assertEquals("layout_invalid", result.detail)
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        assertTrue(index == null || index.layouts.isEmpty())
    }

    @Test
    fun layoutConflictCopyRewritesMetaIdAndHash() = runBlocking {
        val f = fixture()
        writeLocalLayout(f, "lay-1", layoutJson("lay-1", "本地 v1"))
        f.engine.sync() // base := hash(v1)，远端 = v1
        writeLocalLayout(f, "lay-1", layoutJson("lay-1", "本地 v2"))
        seedRemoteLayout(f, "lay-1", layoutJson("lay-1", "远端 v3"))
        val report = f.engine.sync()
        val result = report.results.single { it.unitKey == "layout:lay-1" }
        assertEquals(SkinLibrarySync.Action.CONFLICT_COPY, result.action)
        val copyId = result.copyId
        assertNotNull(copyId)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        val copyEntry = index.layouts.single { it.id == copyId }
        val copyFile = File(f.layout.fileFromRelative(copyEntry.dir), "layout.json")
        // 副本文件 meta.id 已改写为新 id，索引 hash 与改写后字节一致（铁律 3）
        assertTrue(copyFile.readText().contains("\"id\":\"$copyId\""))
        assertEquals(DigestUtil.sha256Hex(copyFile.readBytes()), copyEntry.hash)
    }

    @Test
    fun authFailureAbortsRound() = runBlocking {
        val f = fixture()
        writeLocalSkin(f, "s1", skinJson("s1", "#FF111111"))
        f.transport.authFail = true
        val report = f.engine.sync()
        assertNotNull(report.abortedReason)
    }

    @Test
    fun remoteHashMismatchFailsUnitWithoutImport() = runBlocking {
        val f = fixture()
        seedRemoteSkin(f, "s9", skinJson("s9", "#FF555555"))
        // 篡改远端文件字节（与索引 hash 不符）
        f.transport.files["skins/s9.json"] = skinJson("s9", "#FF666666").encodeToByteArray()
        val report = f.engine.sync()
        val result = report.results.single { it.unitKey == "skin:s9" }
        assertEquals(SkinLibrarySync.Action.FAILED, result.action)
        assertEquals("hash_mismatch", result.detail)
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        assertTrue(index == null || index.skins.isEmpty())
    }
}
