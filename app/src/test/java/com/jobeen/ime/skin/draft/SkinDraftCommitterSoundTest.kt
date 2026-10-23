package com.jobeen.ime.skin.draft

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.image.BackgroundPreparer
import com.jobeen.ime.skin.media.AudioInfo
import com.jobeen.ime.skin.media.AudioInfoProbe
import com.jobeen.ime.skin.media.ImageInfoProbe
import com.jobeen.ime.skin.media.ImageSize
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 草稿提交的音效组包（B2 §7.1-3）：草稿目录里背景图与导入音效
 * 同为 asset-<sha> 命名，提交时音效必须先被索引认领——绝不能
 * 被 B1 的单资产背景映射顺手映射给背景 id。
 */
class SkinDraftCommitterSoundTest {

    private class FakeImageProbe : ImageInfoProbe {
        override fun bounds(bytes: ByteArray): ImageSize? =
            if (bytes.isNotEmpty() && bytes[0] == 'I'.code.toByte()) {
                ImageSize(100, 50)
            } else {
                null
            }
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
        override fun prepare(
            request: BackgroundPreparer.Request,
        ): BackgroundPreparer.Result {
            val dir = File(request.genDir, "prepared").apply { mkdirs() }
            File(dir, "${request.preparedKey}.webp").writeBytes(byteArrayOf(1, 2, 3))
            return BackgroundPreparer.Result.Done(
                request.preparedKey, request.targetWidthPx, request.targetHeightPx,
            )
        }
    }

    private val scheme = KeyboardColors.ColorScheme(
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
        val draftStore: DraftStore,
        val committer: SkinDraftCommitter,
    )

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("jime-commit-snd").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val importer = JimeImporter(
            layout = layout,
            store = store,
            importTransaction = ImportTransaction(layout, store),
            imageProbe = FakeImageProbe(),
            audioProbe = FakeAudioProbe(),
            backgroundPreparer = FakePreparer(),
            defaultScheme = scheme,
        )
        val ctx = JimeImporter.Context(
            currentVersionCode = 143,
            fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f),
            tier = MemoryTier.NORMAL,
            targetWidthPx = 100,
            targetHeightPx = 50,
        )
        val draftStore = DraftStore(layout)
        return Fixture(
            layout,
            draftStore,
            SkinDraftCommitter(layout, store, draftStore) { importer to ctx },
        )
    }

    private fun treeWithSoundAndBackground() = buildJsonObject {
        put("formatVersion", 3)
        putJsonObject("meta") {
            put("id", "skin-snd")
            put("name", "S")
        }
        putJsonObject("styles") { put("keyBackground", "#FF112233") }
        putJsonObject("appearance") {
            putJsonObject("background") {
                put("type", "asset")
                put("asset", "bg_main")
                put("blurRadius", 0.0)
                put("overlayAlpha", 0.5)
            }
        }
        putJsonObject("audio") { put("tap", "snd_pop") }
        putJsonObject("keyMappings") {
            putJsonObject("KEY_Q") { put("sound", "snd_pop") }
        }
    }

    private fun manifestAssetShas(layout: SkinLibraryLayout): Map<String, String> {
        val manifestFile = layout.root.walkTopDown()
            .first { it.isFile && it.name == "manifest.json" }
        val root = Json.parseToJsonElement(manifestFile.readText()).jsonObject
        return root.getValue("contents").jsonArray
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.content == "asset" }
            .associate {
                it.getValue("id").jsonPrimitive.content to
                    it.getValue("sha256").jsonPrimitive.content
            }
    }

    @Test
    fun soundClaimedByIndexAndBackgroundKeepsItsOwnBytes() = runBlocking {
        val f = fixture()
        val bgBytes = "IMGDATA-background".encodeToByteArray()
        val soundBytes = "AUDIODATA-pop".encodeToByteArray()
        val bgSha = DigestUtil.sha256Hex(bgBytes)
        val soundSha = DigestUtil.sha256Hex(soundBytes)
        val draft = f.draftStore.create(
            "skin-snd", treeWithSoundAndBackground(), DraftStore.SOURCE_BLANK, 0,
        )
        f.draftStore.draftAssetFile("skin-snd", bgSha, "png").writeBytes(bgBytes)
        f.draftStore.draftAssetFile("skin-snd", soundSha, "mp3").writeBytes(soundBytes)
        f.draftStore.writeDraftSoundIndex("skin-snd", mapOf("snd_pop" to soundSha))

        val result = f.committer.commit(draft)
        assertTrue("期望 Committed，实际 $result", result is SkinDraftCommitter.Result.Committed)

        val assetShas = manifestAssetShas(f.layout)
        assertEquals(bgSha, assetShas["bg_main"])
        assertEquals(soundSha, assetShas["snd_pop"])
        assertArrayEquals(soundBytes, f.layout.assetFile(soundSha, "mp3").readBytes())
        assertArrayEquals(bgBytes, f.layout.assetFile(bgSha, "png").readBytes())
    }

    @Test
    fun missingSoundFileFailsWithNamedReason() = runBlocking {
        val f = fixture()
        val soundSha = DigestUtil.sha256Hex("AUDIODATA-pop".encodeToByteArray())
        val draft = f.draftStore.create(
            "skin-snd", treeWithSoundAndBackground(), DraftStore.SOURCE_BLANK, 0,
        )
        f.draftStore.writeDraftSoundIndex("skin-snd", mapOf("snd_pop" to soundSha))
        val result = f.committer.commit(draft)
        assertTrue(result is SkinDraftCommitter.Result.Failed)
        assertEquals(
            "draft_sound_missing:snd_pop",
            (result as SkinDraftCommitter.Result.Failed).reason,
        )
    }

    @Test
    fun soundHashMismatchFailsWithNamedReason() = runBlocking {
        val f = fixture()
        val claimedSha = DigestUtil.sha256Hex("AUDIODATA-pop".encodeToByteArray())
        val draft = f.draftStore.create(
            "skin-snd", treeWithSoundAndBackground(), DraftStore.SOURCE_BLANK, 0,
        )
        // 文件名挂认领 sha、内容却是别的字节：哈希复核必须拦下
        f.draftStore.draftAssetFile("skin-snd", claimedSha, "mp3")
            .writeBytes("AUDIODATA-other".encodeToByteArray())
        f.draftStore.writeDraftSoundIndex("skin-snd", mapOf("snd_pop" to claimedSha))
        val result = f.committer.commit(draft)
        assertTrue(result is SkinDraftCommitter.Result.Failed)
        assertEquals(
            "draft_sound_hash_mismatch:snd_pop",
            (result as SkinDraftCommitter.Result.Failed).reason,
        )
    }
}
