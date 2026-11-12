package com.jobeen.ime.skin.batch5

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.image.DecodeGate
import com.jobeen.ime.skin.image.ImageDecodePolicy
import com.jobeen.ime.skin.model.IconSlotCatalog
import com.jobeen.ime.skin.model.KeyWidthAcceptance
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinGroupFollow
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinStyleStates
import com.jobeen.ime.skin.model.SkinToolbarParser
import com.jobeen.ime.skin.pkg.PortableSkinArchive
import com.jobeen.ime.skin.pkg.ZipSafeExtractor
import com.jobeen.ime.skin.runtime.FingerprintCache
import com.jobeen.ime.skin.runtime.NightModePolicy
import com.jobeen.ime.skin.runtime.SkinGeneration
import com.jobeen.ime.skin.runtime.SkinSwitchPolicy
import com.jobeen.ime.skin.store.SkinAssetPruner
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinOverlayMerge
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 5 批定向单测：纯 JVM 护栏（§1 归档／§2 图片／§3 切换／§4 组开关／§6 三态与宽度）。§5 节流门（FabBackdropRefreshGate）随玻璃基础设施删除而删除。 */
class Batch5SkinGuardsTest {

    private fun scheme(): KeyboardColors.ColorScheme = KeyboardColors.ColorScheme(
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

    private fun snapshot(): SkinSnapshot = SkinSnapshot(
        ref = SkinGenRef("skin-a", "hash-a", 1),
        name = "skin-a",
        colorScheme = scheme(),
        geometryPresence = emptySet(),
        effectiveGeometry = emptyMap(),
        background = null,
        degradations = emptySet(),
        layoutId = null,
    )

    // ---- §1 可移植归档 ----

    @Test
    fun portable_manifestSizeRoundTripAndMismatchRejected() {
        val manifest = PackageManifest(
            contents = listOf(
                PackageManifest.Content("skin", "skin-a", "skin.json", "sha-skin", 3L),
            ),
        )
        val parsed = PackageManifest.parse(PackageManifest.toJson(manifest))!!
        assertEquals(3L, parsed.contents.single().sizeBytes)
        val files = mapOf(
            "skin.json" to ZipSafeExtractor.ExtractedFile("skin.json", byteArrayOf(1, 2, 3)),
        )
        val tree = SkinJson.parseObject("{}")!!
        assertTrue(PortableSkinArchive.validate(manifest, files, tree).ok)
        val wrong = manifest.copy(
            contents = listOf(
                PackageManifest.Content("skin", "skin-a", "skin.json", "sha-skin", 99L),
            ),
        )
        val rejected = PortableSkinArchive.validate(wrong, files, tree)
        assertFalse(rejected.ok)
        assertEquals("portable_size_mismatch:skin.json", rejected.rejected!!.reason)
        // 旧清单无 size 字段：只验 SHA 口径，不拒。
        val legacy = manifest.copy(
            contents = listOf(
                PackageManifest.Content("skin", "skin-a", "skin.json", "sha-skin", null),
            ),
        )
        assertTrue(PortableSkinArchive.validate(legacy, files, tree).ok)
    }

    @Test
    fun portable_pagesAndKeyMappingsLimits() {
        val manyKeyboards = buildJsonObject {
            put("keyboards", buildJsonObject {
                for (i in 0 until 513) put("type$i", JsonPrimitive("lay-$i"))
            })
        }
        val manifest = PackageManifest(
            contents = listOf(PackageManifest.Content("skin", "s", "skin.json", "x", 2L)),
        )
        val files = mapOf(
            "skin.json" to ZipSafeExtractor.ExtractedFile("skin.json", byteArrayOf(1, 2)),
        )
        val result = PortableSkinArchive.validate(manifest, files, manyKeyboards)
        assertFalse(result.ok)
        assertTrue(result.rejected!!.reason.startsWith("portable_pages_exceeded"))
        // Jime 布局不引用布局，页图天然无环：恒 null，不是拒因。
        assertNull(PortableSkinArchive.findCycle(manyKeyboards))
    }

    @Test
    fun portable_modelRefsAndOwnership() {
        val tree = SkinJson.parseObject(
            """{"appearance":{"background":{"assetId":"bg-1"}},"toolbar":{"icon":"other"}}""",
        )!!
        val refs = PortableSkinArchive.modelReferencedAssetIds(tree, setOf("bg-1", "tap-1"))
        assertEquals(setOf("bg-1"), refs)
        val root = Files.createTempDirectory("batch5-assets").toFile()
        val inside = File(root, "a.png")
        val escape = File(root, "../escape.png")
        assertTrue(PortableSkinArchive.isOwnedAssetFile(root, inside))
        assertFalse(PortableSkinArchive.isOwnedAssetFile(root, escape))
    }

    @Test
    fun pruner_deletesOnlyUnreferencedNonVideoInsideRoot() {
        val root = Files.createTempDirectory("batch5-prune").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val genDir = File(layout.skinsDir, "s1/gen-1").apply { mkdirs() }
        val keptSha = "a".repeat(64)
        val manifest = PackageManifest(
            contents = listOf(
                PackageManifest.Content("asset", "bg", "assets/bg.png", keptSha),
            ),
        )
        File(genDir, "manifest.json").writeText(PackageManifest.toJson(manifest))
        val kept = File(layout.assetsDir, "$keptSha.png").apply { writeBytes(byteArrayOf(1)) }
        val orphan = File(layout.assetsDir, "${"b".repeat(64)}.png").apply {
            writeBytes(byteArrayOf(2))
        }
        val orphanVideo = File(layout.assetsDir, "${"c".repeat(64)}.mp4").apply {
            writeBytes(byteArrayOf(3))
        }
        assertEquals(1, SkinAssetPruner.pruneUnreferenced(layout))
        assertTrue(kept.isFile)
        assertFalse(orphan.isFile)
        // 视频由 VideoAssetPruner 专责，本清理默认排除视频扩展名。
        assertTrue(orphanVideo.isFile)
    }

    // ---- §2 图片护栏 ----

    @Test
    fun imagePolicy_decodeCapsFilenameAndRoot() {
        // 施工单 5 第 7 条（用户决定改口径）：24MP 硬上限拒收已废止，
        // 超限图降采样到上限内使用；解码上限与导入校验口径同值。
        assertEquals(8192, ImageDecodePolicy.MAX_DECODE_SIDE)
        assertEquals(64L * 1024 * 1024, ImageDecodePolicy.MAX_DECODE_PIXELS)
        assertTrue(ImageDecodePolicy.isValidAssetFileName("bg_main-1.png"))
        assertFalse(ImageDecodePolicy.isValidAssetFileName(".."))
        assertFalse(ImageDecodePolicy.isValidAssetFileName("a/b.png"))
        assertFalse(ImageDecodePolicy.isValidAssetFileName("a b.png"))
        assertEquals(
            ImageDecodePolicy.Placeholder.TEXT_FALLBACK,
            ImageDecodePolicy.placeholderFor(ImageDecodePolicy.Role.FOREGROUND_ICON),
        )
        assertEquals(
            ImageDecodePolicy.Placeholder.STATIC_BACKGROUND_FALLBACK,
            ImageDecodePolicy.placeholderFor(ImageDecodePolicy.Role.VIDEO),
        )
        assertEquals(2, ImageDecodePolicy.DECODE_PARALLELISM)
        val root = Files.createTempDirectory("batch5-root").toFile()
        assertTrue(ImageDecodePolicy.isInsideRoot(root, File(root, "sub/a.png")))
        assertFalse(ImageDecodePolicy.isInsideRoot(root, File(root, "../x.png")))
    }

    @Test
    fun decodeGate_singleFlightAndGenerationReset() {
        val gate = DecodeGate()
        val gen = gate.begin("k1")
        assertNotNull(gen)
        assertNull(gate.begin("k1"))
        gate.finish("k1", false, gen!!)
        assertTrue(gate.isFailed("k1"))
        assertNull(gate.begin("k1"))
        gate.nextGeneration()
        assertFalse(gate.isFailed("k1"))
        val gen2 = gate.begin("k1")
        assertNotNull(gen2)
        // 旧世代 finish 不得污染新世代失败集。
        gate.finish("k1", false, gen)
        assertFalse(gate.isFailed("k1"))
        gate.finish("k1", true, gen2!!)
        assertFalse(gate.isFailed("k1"))
    }

    // ---- §3 切换缓存 ----

    @Test
    fun switchPolicy_fingerprintCacheAndDecisions() {
        val a = listOf(
            SkinSwitchPolicy.FingerprintEntry("b.json", "sha-b"),
            SkinSwitchPolicy.FingerprintEntry("a.json", null),
        )
        val b = listOf(
            SkinSwitchPolicy.FingerprintEntry("a.json", null),
            SkinSwitchPolicy.FingerprintEntry("b.json", "sha-b"),
        )
        assertEquals(
            SkinSwitchPolicy.contentFingerprint(a),
            SkinSwitchPolicy.contentFingerprint(b),
        )
        val changed = listOf(
            SkinSwitchPolicy.FingerprintEntry("a.json", "sha-a"),
            SkinSwitchPolicy.FingerprintEntry("b.json", "sha-b"),
        )
        assertTrue(SkinSwitchPolicy.contentFingerprint(changed) != SkinSwitchPolicy.contentFingerprint(a))
        val cache = FingerprintCache<String>()
        for (i in 0 until 9) cache.put("fp$i", "v$i")
        assertEquals(8, cache.size())
        assertNull(cache.get("fp0"))
        assertEquals("v8", cache.get("fp8"))
        fun decide(
            structureEqual: Boolean,
            skinChanged: Boolean,
            colorOnly: Boolean,
            keyboardOnly: Boolean,
            layoutVersionChanged: Boolean,
            styleVersionChanged: Boolean,
            alreadyCurrent: Boolean = false,
        ) = SkinSwitchPolicy.decideViewCache(
            structureEqual, skinChanged, colorOnly, keyboardOnly,
            layoutVersionChanged, styleVersionChanged, alreadyCurrent,
        )
        assertEquals(
            SkinSwitchPolicy.ViewCacheDecision.NO_NOTIFY,
            decide(true, true, false, false, false, false),
        )
        assertEquals(
            SkinSwitchPolicy.ViewCacheDecision.REBUILD_ALL,
            decide(false, true, false, false, false, false),
        )
        assertEquals(
            SkinSwitchPolicy.ViewCacheDecision.REDRAW_IN_PLACE,
            decide(false, false, true, false, false, false),
        )
        assertEquals(
            SkinSwitchPolicy.ViewCacheDecision.REBUILD_CURRENT,
            decide(false, false, false, true, false, false),
        )
        assertEquals(
            SkinSwitchPolicy.ViewCacheDecision.REBUILD_CURRENT,
            decide(false, false, false, false, true, false),
        )
        assertEquals(
            SkinSwitchPolicy.ViewCacheDecision.NO_NOTIFY,
            decide(false, false, false, false, false, false, true),
        )
        val gen = SkinGeneration()
        val g0 = gen.current()
        val g1 = gen.bump()
        assertTrue(g1 != g0 && gen.isCurrent(g1) && !gen.isCurrent(g0))
    }

    @Test
    fun nightModePolicy_explicitWinsOverReported() {
        assertTrue(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_YES, false))
        assertFalse(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_NO, true))
        assertTrue(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_AUTO, true))
        assertFalse(NightModePolicy.resolve(null, false))
        assertTrue(NightModePolicy.resolve(NightModePolicy.MODE_NIGHT_UNSPECIFIED, true))
    }

    // ---- §4 组跟随／脱离 ----

    @Test
    fun groupFollow_styleAndAreaSwitch() {
        val base = snapshot()
        assertTrue(SkinGroupFollow.isFollowing(base, "pinyin", SkinGroupFollow.Group.STYLE))
        assertTrue(SkinGroupFollow.isFollowing(base, "pinyin", SkinGroupFollow.Group.AREA))
        val detached = SkinGroupFollow.detach(base, "pinyin", SkinGroupFollow.Group.STYLE)
        assertFalse(SkinGroupFollow.isFollowing(detached, "pinyin", SkinGroupFollow.Group.STYLE))
        assertEquals(base.colorScheme, detached.typeStyles.getValue("pinyin").colorScheme)
        // 已脱离再脱离幂等；跟随去掉差量。
        assertEquals(detached, SkinGroupFollow.detach(detached, "pinyin", SkinGroupFollow.Group.STYLE))
        val followed = SkinGroupFollow.follow(detached, "pinyin", SkinGroupFollow.Group.STYLE)
        assertTrue(SkinGroupFollow.isFollowing(followed, "pinyin", SkinGroupFollow.Group.STYLE))
        val areaDetached = SkinGroupFollow.detach(base, "pinyin", SkinGroupFollow.Group.AREA)
        assertEquals(base.toolbarHeight, areaDetached.typeAreas.getValue("pinyin").toolbarHeight)
        // 未覆盖组：无合并引擎可附着，显式记为未覆盖。
        assertTrue("toolbar" in SkinGroupFollow.UNCOVERED_GROUPS)
        assertTrue("bindings" in SkinGroupFollow.UNCOVERED_GROUPS)
        // 角色样式命名对照（口径不变，仅命名）。
        assertEquals("accent", SkinGroupFollow.styleRoleName(true, false, 5))
        assertEquals("normal", SkinGroupFollow.styleRoleName(false, true, 5))
        assertEquals("normal", SkinGroupFollow.styleRoleName(false, false, 1))
        assertEquals("function", SkinGroupFollow.styleRoleName(false, false, 3))
    }

    // ---- §6 三态／数值／宽度／图标槽／覆盖层语义 ----

    @Test
    fun styleStates_threeStateAndDerivation() {
        val normal = 0xFFFFFFFF.toInt()
        val dark = 0xFF101010.toInt()
        assertEquals(normal, SkinStyleStates.resolveThreeState(normal, null, null, SkinStyleStates.State.NORMAL))
        // 按下无显式值时自动推导（亮色加深），不是回落原值。
        val derivedLight = SkinStyleStates.resolveThreeState(normal, null, null, SkinStyleStates.State.PRESSED)
        assertTrue(derivedLight!! != normal)
        // 暗色提亮。
        val derivedDark = SkinStyleStates.resolveThreeState(dark, null, null, SkinStyleStates.State.PRESSED)
        assertTrue((derivedDark!! shr 16 and 0xFF) > 0x10)
        // 锁定继承链：锁定 → 按下 → 常态。
        assertEquals(0xFF112233.toInt(), SkinStyleStates.resolveThreeState(normal, 0xFF112233.toInt(), null, SkinStyleStates.State.LOCKED))
        assertNull(SkinStyleStates.resolveThreeState(null, null, null, SkinStyleStates.State.LOCKED))
        // alpha 下限 48：透明常态色的按下推导 alpha 不低于 48。
        val transparent = 0x00101010
        val derivedAlpha = SkinStyleStates.derivePressedColor(transparent)
        assertTrue((derivedAlpha ushr 24) >= 48)
        assertEquals(3.5f, SkinStyleStates.clampedFloat(Float.NaN, 0f..10f, 3.5f))
        assertEquals(3.5f, SkinStyleStates.clampedFloat(99f, 0f..10f, 3.5f))
        assertEquals(7, SkinStyleStates.clampedInt(null, 0..10, 7))
    }

    @Test
    fun keyWidths_factorRescaleKeepsRowSumOne() {
        val rows = listOf(
            listOf(
                KeyWidthAcceptance.RowKey("a", 0.25f),
                KeyWidthAcceptance.RowKey("b", 0.25f),
                KeyWidthAcceptance.RowKey("c", 0.5f),
            ),
        )
        val accepted = KeyWidthAcceptance.acceptFactors(rows, mapOf("a" to 2f))
            as KeyWidthAcceptance.Result.Accepted
        val row = accepted.rows.single()
        assertEquals(1.0, row.sumOf { it.toDouble() }, 1e-3)
        assertEquals(0.4, row[0].toDouble(), 1e-3)
        assertEquals(0.2, row[1].toDouble(), 1e-3)
        // 跨行键宽度恒为出厂值，不参与缩放。
        val spanRows = listOf(
            listOf(
                KeyWidthAcceptance.RowKey("a", 0.8f),
                KeyWidthAcceptance.RowKey("s", 0.2f, rowSpan = 2),
            ),
        )
        val spanAccepted = KeyWidthAcceptance.acceptFactors(spanRows, mapOf("s" to 3f))
            as KeyWidthAcceptance.Result.Accepted
        assertEquals(0.2f, spanAccepted.rows.single()[1])
        val rejected = KeyWidthAcceptance.acceptFactors(rows, mapOf("a" to 0f))
        assertTrue(rejected is KeyWidthAcceptance.Result.Rejected)
        val nanRejected = KeyWidthAcceptance.acceptFactors(rows, mapOf("a" to Float.NaN))
        assertTrue(nanRejected is KeyWidthAcceptance.Result.Rejected)
    }

    @Test
    fun iconSlots_groupedWithoutRenaming() {
        val grouped = IconSlotCatalog.grouped()
        assertEquals(setOf("keys", "toolbar", "system"), grouped.keys)
        assertTrue("key.icon" in grouped.getValue("keys"))
        assertTrue("key.pressedIcon" in grouped.getValue("keys"))
        assertTrue("toolbar.expandIcon" in grouped.getValue("toolbar"))
        assertTrue("toolbar.collapseIcon" in grouped.getValue("toolbar"))
        // 三组无交集；system 在 Jime 无皮肤可寻址槽，空表是审计结论。
        val all = grouped.values.flatten()
        assertEquals(all.size, all.toSet().size)
        assertTrue(grouped.getValue("system").isEmpty())
        assertTrue(IconSlotCatalog.isKnown("toolbar.buttons[].icon"))
        assertFalse(IconSlotCatalog.isKnown("system.fake"))
    }

    @Test
    fun overlayAndToolbar_unconfiguredVsConfiguredEmpty() {
        val base = SkinJson.parseObject("""{"a":1,"b":2}""")!!
        // null = 删除覆盖恢复默认（b 被删）；缺省 = 继承；有值 = 覆盖。
        val (merged, _) = SkinOverlayMerge.merge(
            base,
            buildJsonObject { put("b", JsonPrimitive(null as String?)) },
        )
        // JsonPrimitive(null) 在 kotlinx 中即 JsonNull；合并后 b 不在结果中。
        assertFalse(merged.containsKey("b"))
        assertEquals(1, (merged["a"] as JsonPrimitive).content.toInt())
        val (merged2, _) = SkinOverlayMerge.merge(
            base,
            buildJsonObject { put("a", JsonPrimitive("")) },
        )
        // 空串 = 显式解绑：a 被删。
        assertFalse(merged2.containsKey("a"))
        // 工具栏：buttons 缺失 = 未配置（null）；空数组 = 已配置空列表。
        val (missing, _) = SkinToolbarParser.parse(
            SkinJson.parseObject("""{"expandIcon":"x"}"""),
            { null }, { true },
        )
        assertNull(missing.buttons)
        val (emptyListSpec, _) = SkinToolbarParser.parse(
            SkinJson.parseObject("""{"buttons":[]}"""),
            { null }, { true },
        )
        assertNotNull(emptyListSpec.buttons)
        assertTrue(emptyListSpec.buttons!!.isEmpty())
    }
}
