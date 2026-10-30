package com.jobeen.ime.skin.diag

import android.content.ContextWrapper
import android.util.Log
import com.jobeen.ime.base.log.AppLogBuffer
import com.jobeen.ime.base.log.DiagExport
import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.KeyOverride
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.ZipSafeExtractor
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.store.ApplyTransaction
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * 皮肤错误日志端到端 zip 可见性：逐跳证明（此前只有代码链论证）。
 *
 * 链：调用点 DiagLog.w/e → DiagLogCore 镜像（WARN/ERROR 同步、
 * runCatching 隔离）→ 生产镜像 Timber.tag("SkinDiag") → AppLogBuffer
 * 1000 条环形缓冲 → AboutScreen 导出 DiagExport.buildDiagnosticText
 * → zip 内 diagnostic.txt。
 *
 * 单测无 Android 进程，故链按跳组合证明，不虚构一测到底：
 * (1) 真实调用点（导入/应用事务/键样式装配）+ 可注入核心镜像，
 *     证明调用点确实发出条目；(2) 生产镜像体本身（与 init 注入
 *     同一份 mirrorToTimber）+ 捕获 Timber 树，证明 tag/级别/
 *     净化；(3) 真实 AppLogBuffer + 真实 DiagExport/zip，证明
 *     缓冲与导出。需要 Android Context 的环节（DiagLog.init、
 *     SkinSnapshotFactory.buildFromTree、设置页分享拉起）不在此测，
 *     见报告中的未覆盖项——不冒充已测。
 */
class SkinDiagExportChainTest {

    private data class Mirrored(
        val level: DiagLevel,
        val tag: String,
        val event: String,
        val context: Map<String, Any?>,
        val throwable: Throwable?,
    )

    private data class Captured(
        val priority: Int,
        val tag: String?,
        val message: String,
        val throwable: Throwable?,
    )

    private class CapturingTree(val captured: MutableList<Captured>) : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            captured += Captured(priority, tag, message, t)
        }
    }

    private class FakeContext : ContextWrapper(null)

    private val cores = mutableListOf<DiagLogCore>()

    @After
    fun tearDown() {
        cores.forEach { runCatching { it.close() } }
        cores.clear()
        DiagLog.setCoreForTest(null)
        Timber.uprootAll()
        AppLogBuffer.clear()
        setBufferInstalled(false)
    }

    // ---- 通用缝隙 ----

    private fun setBufferInstalled(value: Boolean) {
        val field = AppLogBuffer::class.java.getDeclaredField("installed")
        field.isAccessible = true
        field.set(AppLogBuffer, value)
    }

    /** 带捕获镜像的核心并注入 DiagLog 单例；调用方用 mirrored 断言。 */
    private fun captureDiagLog(): MutableList<Mirrored> {
        val mirrored = mutableListOf<Mirrored>()
        val dir = Files.createTempDirectory("diag-chain").toFile()
        val core = DiagLogCore(
            rootDir = dir,
            mirror = DiagLogCore.Mirror { level, tag, event, context, throwable ->
                mirrored += Mirrored(level, tag, event, context, throwable)
            },
        )
        cores += core
        DiagLog.setCoreForTest(core)
        return mirrored
    }

    private fun installBuffer() {
        setBufferInstalled(false)
        AppLogBuffer.clear()
        AppLogBuffer.install(FakeContext(), enableLogging = true)
    }

    private fun mirroredEvent(mirrored: List<Mirrored>, event: String): Mirrored =
        mirrored.firstOrNull { it.event == event }
            ?: throw AssertionError("missing mirrored event $event, got ${mirrored.map { it.event }}")

    // ---- 跳 1：核心镜像触发与隔离 ----

    @Test
    fun mirrorThrowDoesNotBreakFileLog() {
        val dir = Files.createTempDirectory("diag-chain").toFile()
        val core = DiagLogCore(
            dir,
            mirror = DiagLogCore.Mirror { _, _, _, _, _ -> throw RuntimeException("mirror boom") },
        )
        cores += core
        // 镜像抛错不得外泄、不得丢文件行（runCatching 隔离）。
        core.w("store", "import_rejected", mapOf("reason" to "x"))
        core.flush()
        val text = dir.listFiles { f -> DiagLogCore.isLogFileName(f.name) }
            ?.sortedBy { it.name }?.joinToString("\n") { it.readText() } ?: ""
        assertTrue(text.contains("WARN [store] import_rejected reason=x"))
    }

    // ---- 跳 2：生产镜像体 → Timber ----

    @Test
    fun productionMirrorEmitsSkinDiagWithWarnErrorMapping() {
        val captured = mutableListOf<Captured>()
        Timber.plant(CapturingTree(captured))
        val failure = IllegalStateException("boom")
        DiagLog.mirrorToTimber(
            DiagLevel.WARN, "store", "import_rejected",
            mapOf("reason" to "manifest_count:0"), null,
        )
        DiagLog.mirrorToTimber(
            DiagLevel.ERROR, "store", "import_failed",
            mapOf("stage" to "build"), failure,
        )
        DiagLog.mirrorToTimber(DiagLevel.INFO, "store", "import_done", emptyMap(), null)
        DiagLog.mirrorToTimber(DiagLevel.DEBUG, "store", "detail", emptyMap(), null)

        assertEquals(2, captured.size)
        assertEquals(Log.WARN, captured[0].priority)
        assertEquals("SkinDiag", captured[0].tag)
        assertEquals("[store] import_rejected reason=manifest_count:0", captured[0].message)
        assertEquals(Log.ERROR, captured[1].priority)
        assertEquals("SkinDiag", captured[1].tag)
        assertTrue(captured[1].message.contains("[store] import_failed stage=build"))
        assertEquals(failure, captured[1].throwable)
    }

    @Test
    fun productionMirrorSanitizesTruncatesAndKeepsPercentVerbatim() {
        val captured = mutableListOf<Captured>()
        Timber.plant(CapturingTree(captured))
        DiagLog.mirrorToTimber(
            DiagLevel.WARN, "t".repeat(40), "e".repeat(70),
            mapOf("note" to "a\nb%s%d" + "x".repeat(300)), null,
        )
        assertEquals(1, captured.size)
        val msg = captured[0].message
        // 单行（控制字符已换空格）、tag 32+…、事件 64+…、值 256+…
        assertTrue(!msg.contains("\n"))
        assertTrue(msg.startsWith("[" + "t".repeat(32) + "…] " + "e".repeat(64) + "…"))
        assertTrue(msg.contains("note=a b%s%d" + "x".repeat(256 - 7) + "…"))
    }

    // ---- 跳 3+4：缓冲 → 导出文本 → zip ----

    @Test
    fun skinDiagEndToEnd_bufferToZipContainsSkinDiagEntries() {
        installBuffer()
        // 走生产镜像体进真实缓冲（throwable 为空，避免单测桩 android.util.Log）。
        DiagLog.mirrorToTimber(
            DiagLevel.WARN, "store", "import_rejected",
            mapOf("reason" to "manifest_count:0", "skinId" to "skin-a"), null,
        )
        DiagLog.mirrorToTimber(
            DiagLevel.ERROR, "store", "import_failed",
            mapOf("stage" to "build", "error" to "IllegalStateException"), null,
        )
        val entries = AppLogBuffer.entries.value
        assertTrue(entries.any { it.priority == Log.WARN && it.tag == "SkinDiag" &&
            it.message.contains("[store] import_rejected") })
        assertTrue(entries.any { it.priority == Log.ERROR && it.tag == "SkinDiag" &&
            it.message.contains("[store] import_failed") })

        val txt = DiagExport.buildDiagnosticText(
            "1.1.16", "com.jobeen.ime.debug", "15", 35, "Samsung", "SM-S9280",
            "20261027-220000", entries,
        )
        val cache = Files.createTempDirectory("diag-chain").toFile()
        val zip = DiagExport.diagZipFile(cache, "20261027-220000")
        DiagExport.writeDiagZip(zip, txt)
        ZipInputStream(zip.inputStream()).use { zis ->
            assertEquals(DiagExport.ZIP_ENTRY_NAME, zis.nextEntry?.name)
            val content = zis.readBytes().toString(Charsets.UTF_8)
            assertTrue(content.contains("W/SkinDiag [store] import_rejected reason=manifest_count:0"))
            assertTrue(content.contains("E/SkinDiag [store] import_failed stage=build"))
        }
    }

    @Test
    fun bufferDedupsAdjacentDuplicatesAndCapsAt1000() {
        installBuffer()
        Timber.tag("SkinDiag").w("dup")
        Timber.tag("SkinDiag").w("dup")
        assertEquals(1, AppLogBuffer.entries.value.size)
        repeat(1002) { i -> Timber.tag("SkinDiag").w("entry-$i") }
        val entries = AppLogBuffer.entries.value
        assertEquals(1000, entries.size)
        // 环形逐出最旧：entry-0/entry-1 已出，entry-2 仍在首位附近
        assertTrue(entries.none { it.message == "entry-0" || it.message == "entry-1" })
        assertTrue(entries.any { it.message == "entry-2" })
        assertTrue(entries.any { it.message == "entry-1001" })
        assertTrue(entries.all { it.tag == "SkinDiag" })
    }

    // ---- 代表性路径：真实调用点 → 镜像 ----

    private class FakeImageProbe : ImageInfoProbe {
        override fun bounds(bytes: ByteArray): ImageSize? =
            if (bytes.isNotEmpty() && bytes[0] == 'I'.code.toByte()) ImageSize(100, 50) else null
    }

    private class FakeAudioProbe : AudioInfoProbe {
        override fun probe(bytes: ByteArray): AudioInfo? =
            if (bytes.isNotEmpty() && bytes[0] == 'A'.code.toByte()) {
                AudioInfo(durationMs = 1000, sampleRate = 44100, channels = 2)
            } else null
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

    private fun importerFixture(): JimeImporter {
        val root = Files.createTempDirectory("jime-import").toFile()
        val layout = SkinLibraryLayout(root).also { it.ensureDirs() }
        val store = SkinLibraryStore(layout)
        return JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = FakeImageProbe(),
            audioProbe = FakeAudioProbe(),
            backgroundPreparer = FakePreparer(),
            defaultScheme = KeyboardColors.ColorScheme(
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
            ),
        )
    }

    private val importerCtx = JimeImporter.Context(
        currentVersionCode = 157,
        fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
        tier = MemoryTier.NORMAL,
        targetWidthPx = 100,
        targetHeightPx = 50,
    )

    private fun sha(bytes: ByteArray): String = DigestUtil.sha256Hex(bytes)

    private fun packageFiles(
        skinJson: String,
        layoutId: String? = null,
        layoutJson: String? = null,
    ): List<ZipSafeExtractor.ExtractedFile> {
        val skinBytes = skinJson.encodeToByteArray()
        val contents = mutableListOf(
            PackageManifest.Content(PackageManifest.TYPE_SKIN, "skin-a", "skin.json", sha(skinBytes)),
        )
        val files = mutableListOf(ZipSafeExtractor.ExtractedFile("skin.json", skinBytes))
        if (layoutId != null && layoutJson != null) {
            val layoutBytes = layoutJson.encodeToByteArray()
            contents += PackageManifest.Content(
                PackageManifest.TYPE_LAYOUT, layoutId, "layouts/$layoutId.json", sha(layoutBytes),
            )
            files += ZipSafeExtractor.ExtractedFile("layouts/$layoutId.json", layoutBytes)
        }
        files += ZipSafeExtractor.ExtractedFile(
            JimeImporter.MANIFEST_PATH,
            PackageManifest.toJson(PackageManifest(3, contents)).encodeToByteArray(),
        )
        return files
    }

    private val plainSkin = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""" +
        """"styles":{"keyBackground":"#FF112233"},"stylesDark":{"keyBackground":"#FF222222"}}"""

    @Test
    fun importRejected_manifestMissing() = runBlocking {
        val mirrored = captureDiagLog()
        val outcome = importerFixture().importFiles(
            emptyList(), importerCtx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue(outcome is JimeImporter.Outcome.Rejected)
        val entry = mirroredEvent(mirrored, "import_rejected")
        assertEquals(DiagLevel.WARN, entry.level)
        assertEquals("store", entry.tag)
        assertEquals("manifest_count:0", entry.context["reason"])
    }

    @Test
    fun importRejected_skinUnparseable() = runBlocking {
        val mirrored = captureDiagLog()
        val outcome = importerFixture().importFiles(
            packageFiles("not-json{"), importerCtx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue(outcome is JimeImporter.Outcome.Rejected)
        val entry = mirroredEvent(mirrored, "import_rejected")
        assertEquals("skin_unparseable", entry.context["reason"])
        assertEquals("skin-a", entry.context["skinId"])
    }

    @Test
    fun importRejected_layoutInvalid() = runBlocking {
        val mirrored = captureDiagLog()
        val badWeight = """{"formatVersion":3,"meta":{"id":"lay-1","name":"L"},""" +
            """"rows":[{"keys":[{"stableId":"1","label":"q","widthWeight":0.0,""" +
            """"tap":{"type":"text","value":"q"}}]}]}"""
        val outcome = importerFixture().importFiles(
            packageFiles(plainSkin, "lay-1", badWeight),
            importerCtx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue(outcome is JimeImporter.Outcome.Rejected)
        val entry = mirroredEvent(mirrored, "layout_validation_rejected")
        assertEquals("lay-1", entry.context["layoutId"])
        assertEquals("import", entry.context["source"])
    }

    @Test
    fun importValidationIssuesSummary() = runBlocking {
        val mirrored = captureDiagLog()
        // 注意：全无效配色仍按既有硬化口径整包拒绝（no_color_scheme），
        // 故此处用一好一坏两色：导入成功 + 坏色记 issue，摘要事件才成立。
        val badColorSkin = """{"formatVersion":3,"meta":{"id":"skin-a","name":"A"},""" +
            """"styles":{"keyBackground":"#notacolor","keyText":"#FF141414"},"stylesDark":{"keyBackground":"#FF222222"}}"""
        val outcome = importerFixture().importFiles(
            packageFiles(badColorSkin), importerCtx, JimeImporter.ConflictMode.FAIL,
        )
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        val entry = mirroredEvent(mirrored, "skin_validation_issues")
        assertEquals("skin-a", entry.context["skinId"])
        assertTrue((entry.context["firstPaths"] as String).contains("styles.keyBackground"))
    }

    @Test
    fun importFailed_transactionBuildThrows() = runBlocking {
        val mirrored = captureDiagLog()
        val root = Files.createTempDirectory("jime-import-fail").toFile()
        val layout = SkinLibraryLayout(root).also { it.ensureDirs() }
        val tx = ImportTransaction(layout, SkinLibraryStore(layout))
        val result = tx.importPackage { throw IllegalStateException("boom") }
        assertTrue(result is ImportTransaction.Result.Failed)
        val entry = mirroredEvent(mirrored, "import_failed")
        assertEquals(DiagLevel.ERROR, entry.level)
        assertEquals("build", entry.context["stage"])
        assertEquals("IllegalStateException", entry.context["error"])
        assertTrue(entry.throwable is IllegalStateException)
    }

    private class FakePorts : ApplyTransaction.Ports {
        var buildResult = true
        var switchBehavior: (SkinGenRef?) -> Boolean = { true }
        var firstRenderResult = true
        override fun buildSnapshot(ref: SkinGenRef): Boolean = buildResult
        override fun switchRuntime(ref: SkinGenRef?): Boolean = switchBehavior(ref)
        override suspend fun awaitFirstRender(): Boolean = firstRenderResult
    }

    private fun applyFixture(): Triple<SkinLibraryLayout, SkinLibraryStore, FakePorts> {
        val root = Files.createTempDirectory("skin-apply").toFile()
        val layout = SkinLibraryLayout(root).also { it.ensureDirs() }
        return Triple(layout, SkinLibraryStore(layout), FakePorts())
    }

    private fun skinGen(layout: SkinLibraryLayout, id: String, gen: Int): SkinGenRef {
        val dir = layout.skinGenDir(id, gen).also { it.mkdirs() }
        val skinJson = """{"meta":{"id":"$id","name":"S$gen","version":"1.0"},"styles":{}}"""
        File(dir, "skin.json").writeText(skinJson)
        val hash = DigestUtil.sha256Hex(skinJson.toByteArray())
        File(dir, "manifest.json").writeText(
            """{"version":1,"contents":[{"type":"skin","id":"$id","path":"skin.json","sha256":"$hash"}]}""",
        )
        return SkinGenRef(id, hash, gen)
    }

    @Test
    fun applyBuildFailed() = runBlocking {
        val mirrored = captureDiagLog()
        val (layout, store, ports) = applyFixture()
        ports.buildResult = false
        val tx = ApplyTransaction(layout, store, ports) { 7_000L }
        val ref = skinGen(layout, "s1", 1)
        assertTrue(tx.apply(ref) is ApplyTransaction.Result.BuildFailed)
        val entry = mirroredEvent(mirrored, "apply_build_failed")
        assertEquals("s1", entry.context["skinId"])
        assertEquals(1, entry.context["gen"])
    }

    @Test
    fun applySwitchFailedRollsBack() = runBlocking {
        val mirrored = captureDiagLog()
        val (layout, store, ports) = applyFixture()
        ports.switchBehavior = { false }
        val tx = ApplyTransaction(layout, store, ports) { 7_000L }
        val ref = skinGen(layout, "s1", 1)
        assertTrue(tx.apply(ref) is ApplyTransaction.Result.SwitchFailedRolledBack)
        val entry = mirroredEvent(mirrored, "apply_rolled_back")
        assertEquals("switch_failed", entry.context["reason"])
        assertEquals("s1", entry.context["failedSkinId"])
    }

    @Test
    fun rollbackUnavailableNoLastGood() = runBlocking {
        val mirrored = captureDiagLog()
        val (layout, store, ports) = applyFixture()
        val tx = ApplyTransaction(layout, store, ports) { 7_000L }
        assertTrue(tx.rollbackToLastGood() is ApplyTransaction.Result.RollbackUnavailable)
        val entry = mirroredEvent(mirrored, "rollback_unavailable")
        assertEquals("no_last_good", entry.context["reason"])
    }

    @Test
    fun renderFallback_missingKeyStyle() {
        val mirrored = captureDiagLog()
        val scheme = KeyboardColors.ColorScheme(
            keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
            specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
            accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
            keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
            background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
                candidateBackground = 24, candidateText = 25, candidateIndex = 26,
                candidateDivider = 27, toolbarPressed = 28,
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 30, textColor = 31, secondaryTextColor = 32,
            ),
            toastBackground = 40, toastText = 41,
        )
        val snapshot = SkinSnapshot(
            ref = SkinGenRef("skin-1", "h".repeat(64), 1),
            name = "test",
            colorScheme = scheme,
            geometryPresence = emptySet(),
            effectiveGeometry = emptyMap(),
            background = null,
            degradations = emptySet(),
            layoutId = null,
            keyOverrides = emptyMap(),
            soundBindings = SoundBindings.EMPTY,
            keyStyles = mapOf(
                "gold" to mapOf(KeyStyleRole.FACE to "accentKeyBackground"),
            ),
        )
        val layout = QwertyKeyboard.buildLayout(Variant.Accent)
        val target = layout.flatten().firstOrNull { it.id != null }
            ?: throw AssertionError("no key with id")
        target.skinStyleRef = "gone"
        KeyOverrideApplier.apply("Qwerty", layout, snapshot, emptyMap())
        val entry = mirroredEvent(mirrored, "key_style_fallback")
        assertEquals(target.id, entry.context["keyId"])
        assertEquals("gone", entry.context["style"])
        assertTrue((entry.context["warnings"] as String).contains("style_missing:gone"))
    }
}
