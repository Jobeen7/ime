package com.jobeen.ime.skin.batch5

import com.jobeen.ime.skin.image.DecodeGate
import com.jobeen.ime.skin.image.ImageDecodePolicy
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinToolbarParser
import com.jobeen.ime.skin.runtime.FingerprintCache
import com.jobeen.ime.skin.runtime.NightModePolicy
import com.jobeen.ime.skin.runtime.SkinGeneration
import com.jobeen.ime.skin.store.SkinAssetPruner
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinOverlayMerge
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第 5 批定向单测：纯 JVM 护栏（§2 图片／§3 切换／§4 组开关／§6 三态与宽度）。§1 可移植归档随皮肤导入删除而删除；§5 节流门（FabBackdropRefreshGate）随玻璃基础设施删除而删除。 */
class Batch5SkinGuardsTest {

    @Test
    fun assetOwnership_insideVsEscape() {
        val root = Files.createTempDirectory("batch5-assets").toFile()
        val inside = File(root, "a.png")
        val escape = File(root, "../escape.png")
        assertTrue(SkinAssetPruner.isOwnedAssetFile(root, inside))
        assertFalse(SkinAssetPruner.isOwnedAssetFile(root, escape))
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
    fun imagePolicy_decodeCaps() {
        // 施工单 5 第 7 条（用户决定改口径）：24MP 硬上限拒收已废止，
        // 超限图降采样到上限内使用；解码上限与导入校验口径同值。
        assertEquals(8192, ImageDecodePolicy.MAX_DECODE_SIDE)
        assertEquals(64L * 1024 * 1024, ImageDecodePolicy.MAX_DECODE_PIXELS)
        assertEquals(2, ImageDecodePolicy.DECODE_PARALLELISM)
        val root = Files.createTempDirectory("batch5-root").toFile()
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
    fun fingerprintCache_evictsOldestAndGenerationBumps() {
        val cache = FingerprintCache<String>()
        for (i in 0 until 9) cache.put("fp$i", "v$i")
        assertEquals(8, cache.size())
        assertNull(cache.get("fp0"))
        assertEquals("v8", cache.get("fp8"))
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


    // ---- §6 三态／数值／宽度／图标槽／覆盖层语义 ----




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
