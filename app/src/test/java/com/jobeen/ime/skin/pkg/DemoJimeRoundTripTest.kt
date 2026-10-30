package com.jobeen.ime.skin.pkg

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.layout.LayoutResolver
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.LayoutResolution
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.ResolvedLayout
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.CustomLayoutRuntime
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.store.SkinGenRef
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演示包往返证据（2026-10-27 第一组交付口径）：交付的
 * demo-dual-palette-landscape.jime 本体（测试资源，与交付
 * 文件同字节）经清单 sha256 校验 → 皮肤校验 → 两布局解析
 * → 快照装配后，新字段（stylesDark / layoutIdLandscape）
 * 与演示夹具逐项一致——新字段进包出包无损。
 * 清单结构与 Bin 导入管线同款（formatVersion 3 + contents
 * sha256），不依赖导入 UI。
 */
class DemoJimeRoundTripTest {

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

    private fun packageEntries(): Map<String, ByteArray> {
        val stream = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("g1-demo/demo-dual-palette-landscape.jime")
            ?: error("missing test resource g1-demo/demo-dual-palette-landscape.jime")
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun resolvedFrom(text: String, id: String): ResolvedLayout {
        val tree = Json.parseToJsonElement(text)
        val outcome = LayoutResolver.resolve(tree, id, 1, emptyMap())
        assertTrue(
            "layout $id must resolve, got $outcome",
            outcome is LayoutResolver.Outcome.Resolved,
        )
        return (outcome as LayoutResolver.Outcome.Resolved).resolved
    }

    @Test
    fun demoJimeRoundTripPreservesNewFields() {
        val entries = packageEntries()
        assertEquals(
            setOf(
                "manifest.json", "skin.json",
                "layouts/demo-portrait.json", "layouts/demo-landscape.json",
            ),
            entries.keys,
        )

        // 1. 清单 sha256 与包内字节逐项吻合
        val manifest = Json.decodeFromString<PackageManifest>(
            entries["manifest.json"]!!.decodeToString(),
        )
        assertEquals(3, manifest.formatVersion)
        assertEquals(3, manifest.contents.size)
        for (content in manifest.contents) {
            val bytes = requireNotNull(entries[content.path]) {
                "manifest references missing path ${content.path}"
            }
            assertEquals(
                "sha256 mismatch for ${content.path}",
                content.sha256,
                DigestUtil.sha256Hex(bytes),
            )
        }
        val skinContent = manifest.contents.single { it.type == "skin" }
        assertEquals("demo-dual-palette", skinContent.id)

        // 2. 皮肤校验：双色板 + 横屏绑定原样出包
        val tree = Json.parseToJsonElement(
            entries["skin.json"]!!.decodeToString(),
        ) as JsonObject
        val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue("skin must validate, got $result", result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        assertEquals("demo-dual-palette", skin.metaId)
        assertEquals("demo-portrait", skin.layoutId)
        assertEquals("demo-landscape", skin.layoutIdLandscape)
        assertTrue(skin.darkStyles != null)

        // 3. 两布局解析 + 快照装配：方向/色板选择与夹具一致
        val portrait = resolvedFrom(
            entries["layouts/demo-portrait.json"]!!.decodeToString(), "demo-portrait",
        )
        val landscape = resolvedFrom(
            entries["layouts/demo-landscape.json"]!!.decodeToString(), "demo-landscape",
        )
        val snapshot = SkinSnapshotBuilder.build(
            validated = skin, ref = SkinGenRef("demo-dual-palette", "h", 1),
            defaultScheme = defaultScheme, tier = MemoryTier.NORMAL,
            assetSha256 = { null }, targetWidthPx = 1080, targetHeightPx = 800,
            resolvedLayout = portrait, layoutResolution = LayoutResolution.RESOLVED,
            resolvedLayoutLandscape = landscape,
            landscapeLayoutResolution = LayoutResolution.RESOLVED,
        )
        assertTrue(snapshot.hasDarkPalette)
        assertEquals(
            0xFFFDF6EC.toInt(), snapshot.effectiveColorScheme(false).keyBackground,
        )
        assertEquals(
            0xFF232B28.toInt(), snapshot.effectiveColorScheme(true).keyBackground,
        )
        val portraitKeys = CustomLayoutRuntime.effectiveLayout(snapshot, emptyMap())!!
        val landscapeKeys = CustomLayoutRuntime.effectiveLayout(
            snapshot, emptyMap(), landscape = true,
        )!!
        assertEquals(3, portraitKeys.size)
        assertEquals(2, landscapeKeys.size)
        assertEquals(5, landscapeKeys[0].size)
        assertEquals("demo-landscape:K-r", landscapeKeys[0][3].id)

        // 4. 模式纯函数：跟随系统在夜间取深板、日间取浅板
        assertEquals(true, SkinColorMode.isDark(SkinColorMode.Mode.SYSTEM, true))
        assertEquals(false, SkinColorMode.isDark(SkinColorMode.Mode.SYSTEM, false))
        assertEquals(true, SkinColorMode.isDark(SkinColorMode.Mode.DARK, false))
        assertEquals(false, SkinColorMode.isDark(SkinColorMode.Mode.LIGHT, true))
    }
}
