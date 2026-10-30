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
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 集成往返（八流合一）：一张含全部新字段的皮肤走真实导入管线
 * （importZip：清单 sha256 / 资产类别 / 校验）→ 导出（JimeExporter）
 * 后 skin.json 各节点与原包逐项相等。仅解析层通过的不算——本测
 * 覆盖：toolbar、image、subLabel、geometry、fontFamily、
 * imageAdjust、popupChars、icon、双色板、横屏绑定、swipeLeft /
 * swipeRight、单键几何覆写、多层前景、字号档位、派生公式、
 * icon 染色角色（keyStyles.icon）。动态文字只验树节点存活，
 * 生产供值未接线、仍静态回退（见集成报告 open 项）。
 */
class IntegrationRoundTripTest {

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

    private val skinJson = """
        {
          "formatVersion": 3,
          "meta": {"id": "integration-all-fields", "name": "Integration", "author": "", "version": "1.0"},
          "styles": {"keyBackground": "#FFFFFFFF", "keyText": "#FF141414"},
          "stylesDark": {"keyBackground": "#FF1A1A1A", "keyText": "#FFFFFFFF"},
          "derivation": {"face": "#FFFFFFFF", "pressed": "#FFEEEEEE", "bed": "#FFF5F5F5", "ink": "#FF141414", "accent": "#FF9C824A", "hint": "#FF999999"},
          "derivationFormulas": {"keyBorderStroke": "darken(face, 0.5)"},
          "textSizes": {"main": 20, "sub": 11},
          "keyStyles": {"warm": {"face": "#FFFFEEDD", "icon": "#FFCC6600"}},
          "geometry": {"keyRadiusDp": 14, "keyHGapDp": 6, "keyVGapDp": 6, "heightPercent": 30},
          "fontFamily": "serif",
          "layoutIdLandscape": "builtin-t9",
          "toolbar": {"buttons": [{"id": "emoji", "icon": "tb-emoji"}, {"id": "voice", "icon": "mic"}], "expandIcon": "expand_more", "collapseIcon": "tb-collapse"},
          "appearance": {"background": {"type": "asset", "asset": "bg_demo", "blurRadius": 0, "overlayAlpha": 0, "imageAdjust": {"brightnessPercent": 85, "blurRadiusDp": 6, "rotationDegrees": 0, "cropLeft": 0.08, "cropRight": 0.08}}},
          "keyMappings": {
            "KEY_Q": {
              "label": "Q", "subLabel": "q",
              "image": "img_key",
              "textSizes": {"main": 22},
              "geometry": {"widthFactor": 1.2, "contentCenterX": 0.4},
              "foregroundLayers": [{"type": "text", "text": "!", "color": "#FFCC6600"}, {"type": "image", "image": "img_badge"}],
              "swipeLeft": {"type": "text", "value": "<"},
              "swipeRight": {"type": "text", "value": ">"},
              "popupChars": ["，", "。"],
              "icon": "backspace",
              "style": "warm",
              "dynamicText": "candidateFirst",
              "tap": {"type": "text", "value": "q"}
            }
          }
        }
    """.trimIndent()

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun buildPackage(): ByteArray {
        val imgKey = "I".toByteArray() + ByteArray(99)
        val imgBadge = "I".toByteArray() + ByteArray(49)
        val bg = "I".toByteArray() + ByteArray(199)
        val tbEmoji = "I".toByteArray() + ByteArray(29)
        val tbCollapse = "I".toByteArray() + ByteArray(29)
        val skinBytes = skinJson.toByteArray()
        val manifest = """
            {"formatVersion": 3, "contents": [
              {"type": "skin", "id": "integration-all-fields", "path": "skin.json", "sha256": "${sha(skinBytes)}"},
              {"type": "asset", "id": "img_key", "path": "assets/img_key.png", "sha256": "${sha(imgKey)}"},
              {"type": "asset", "id": "img_badge", "path": "assets/img_badge.png", "sha256": "${sha(imgBadge)}"},
              {"type": "asset", "id": "bg_demo", "path": "assets/bg_demo.png", "sha256": "${sha(bg)}"},
              {"type": "asset", "id": "tb-emoji", "path": "assets/tb-emoji.png", "sha256": "${sha(tbEmoji)}"},
              {"type": "asset", "id": "tb-collapse", "path": "assets/tb-collapse.png", "sha256": "${sha(tbCollapse)}"}
            ]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            add("skin.json", skinBytes)
            add("manifest.json", manifest.toByteArray())
            add("assets/img_key.png", imgKey)
            add("assets/img_badge.png", imgBadge)
            add("assets/bg_demo.png", bg)
            add("assets/tb-emoji.png", tbEmoji)
            add("assets/tb-collapse.png", tbCollapse)
        }
        return out.toByteArray()
    }

    private fun skinOf(bytes: ByteArray): JsonObject =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var skin: String? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "skin.json") skin = zip.readBytes().decodeToString()
            }
            Json.parseToJsonElement(requireNotNull(skin)) as JsonObject
        }

    @Test
    fun allNewFieldsSurviveImportExportRoundtrip() = runBlocking {
        val root = Files.createTempDirectory("integration-roundtrip").toFile()
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
        val pkg = buildPackage()
        val pkgFile = File(root, "integration-all-fields.jime").apply { writeBytes(pkg) }
        val outcome = importer.importZip(pkgFile, ctx, JimeImporter.ConflictMode.FAIL)
        assertTrue("expected Imported, got $outcome", outcome is JimeImporter.Outcome.Imported)
        val report = (outcome as JimeImporter.Outcome.Imported).report
        assertEquals(listOf("integration-all-fields"), report.importedSkinIds)

        val exportedBytes = ByteArrayOutputStream()
        val exported = JimeExporter(layout, store).export("integration-all-fields", exportedBytes)
        assertTrue("expected Exported, got $exported", exported is JimeExporter.Result.Exported)
        val original = Json.parseToJsonElement(skinJson) as JsonObject
        val exportedSkin = skinOf(exportedBytes.toByteArray())
        for (key in listOf(
            "styles", "stylesDark", "derivation", "derivationFormulas", "textSizes",
            "keyStyles", "geometry", "fontFamily", "layoutIdLandscape", "toolbar",
            "appearance", "keyMappings",
        )) {
            assertEquals("field $key", original.getValue(key), exportedSkin.getValue(key))
        }
    }
}
