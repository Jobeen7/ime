package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.layout.LayoutResolver
import com.jobeen.ime.skin.runtime.CustomLayoutRuntime
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一组：双色板、横屏专属布局绑定、快照取板（2026-10-27）。
 * 纯 JVM 单测：模式判断用纯函数口径，布局解析用 LayoutResolver，
 * 快照合成用 SkinSnapshotBuilder（与既有测试同口径默认方案）。
 */
class PaletteLayoutTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-1", "h".repeat(64), 1)

    private val defaultScheme = KeyboardColors.ColorScheme(
        keyBackground = 100, keyPressed = 101, keyBorderStroke = 102,
        specialKeyBackground = 200, specialKeyPressed = 201, specialKeyBorderStroke = 202,
        accentKeyBackground = 300, accentKeyPressed = 301, accentKeyBorderStroke = 302,
        keyText = 400, specialKeyText = 401, accentKeyText = 402, altText = 403,
        background = 500, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 600, toolbarText = 601, toolbarActived = 602, toolbarIcon = 603,
            candidateBackground = 604, candidateText = 605, candidateIndex = 606,
            candidateDivider = 607, toolbarPressed = 608,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 700, textColor = 701, secondaryTextColor = 702,
        ),
        toastBackground = 999, toastText = 998,
    )

    /** 夹具默认深色板：已有 stylesDark 时不动；否则在根对象末尾补一色深色板 */
    private fun withDefaultDark(json: String): String {
        if (json.contains("\"stylesDark\"")) return json
        val trimmed = json.trimEnd()
        require(trimmed.endsWith("}")) { "fixture JSON must end with }" }
        return trimmed.dropLast(1) + """, "stylesDark": {"keyBackground": "#FF222222"}}"""
    }

    private fun validate(json: String): SkinValidator.Result.Valid {
        // 2026-10-29 双模式硬要求：夹具默认补深色板（被测行为与深色板
        // 无关时用此默认；深色板自身行为由显式 stylesDark 用例覆盖）
        val tree = Json.parseToJsonElement(withDefaultDark(json)) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun build(validated: ValidatedSkin): SkinSnapshot = SkinSnapshotBuilder.build(
        validated = validated, ref = ref, defaultScheme = defaultScheme,
        tier = MemoryTier.NORMAL, assetSha256 = { null },
        targetWidthPx = 1080, targetHeightPx = 800,
    )

    // ---- 颜色模式纯函数 ----

    @Test
    fun colorModeMatrix() {
        assertTrue(SkinColorMode.isDark(SkinColorMode.Mode.SYSTEM, true))
        assertFalse(SkinColorMode.isDark(SkinColorMode.Mode.SYSTEM, false))
        assertTrue(SkinColorMode.isDark(SkinColorMode.Mode.DARK, false))
        assertFalse(SkinColorMode.isDark(SkinColorMode.Mode.LIGHT, true))
        assertEquals(SkinColorMode.Mode.SYSTEM, SkinColorMode.fromStored(null))
        assertEquals(SkinColorMode.Mode.DARK, SkinColorMode.fromStored("DARK"))
        // 库页口径小写与旧实现大写两种拼写都接受（存储契约）
        assertEquals(SkinColorMode.Mode.DARK, SkinColorMode.fromStored("dark"))
        assertEquals(SkinColorMode.Mode.LIGHT, SkinColorMode.fromStored("light"))
        assertEquals(SkinColorMode.Mode.SYSTEM, SkinColorMode.fromStored("system"))
        assertEquals("dark", SkinColorMode.stored(SkinColorMode.Mode.DARK))
        assertEquals("system", SkinColorMode.stored(SkinColorMode.Mode.SYSTEM))
        assertEquals("color_mode", SkinColorMode.KEY)
        assertEquals("skin_library", SkinColorMode.PREFS_NAME)
        // 未知存储值按 SYSTEM 处理（不抛、不返回 null）
        assertEquals(SkinColorMode.Mode.SYSTEM, SkinColorMode.fromStored("bogus"))
    }

    // ---- 双色板：显式深色 > 浅色回填 ----

    @Test
    fun darkPaletteExplicitOverridesLightFallback() {
        val valid = validate(
            """{
                "formatVersion": 3,
                "styles": {
                    "keyBackground": "#FF111111", "keyText": "#FFEEEEEE"
                },
                "stylesDark": { "keyBackground": "#FF222222" }
            }""",
        )
        val skin = valid.skin
        assertEquals(0xFF111111.toInt(), skin.styles.colors[StyleField.KEY_BACKGROUND])
        val dark = skin.darkStyles!!
        assertEquals(0xFF222222.toInt(), dark.colors[StyleField.KEY_BACKGROUND])
        // 深色未给出的叶子逐字段回填浅色板，不是默认方案
        assertEquals(0xFFEEEEEE.toInt(), dark.colors[StyleField.KEY_TEXT])
        val snapshot = build(skin)
        assertTrue(snapshot.hasDarkPalette)
        assertEquals(0xFF111111.toInt(), snapshot.effectiveColorScheme(false).keyBackground)
        assertEquals(0xFF222222.toInt(), snapshot.effectiveColorScheme(true).keyBackground)
        assertEquals(0xFFEEEEEE.toInt(), snapshot.effectiveColorScheme(true).keyText)
    }

    @Test
    fun darkDerivationExpandedThenExplicitOverrides() {
        val valid = validate(
            """{
                "formatVersion": 3,
                "styles": { "keyBackground": "#FF111111" },
                "derivationDark": {
                    "face": "#FF000000", "pressed": "#FF111111", "bed": "#FF222222",
                    "ink": "#FFFFFFFF", "accent": "#FFAA8800", "hint": "#FF888888"
                },
                "stylesDark": { "keyBackground": "#FF333333" }
            }""",
        )
        val dark = valid.skin.darkStyles!!
        // 显式深色 > 派生深色
        assertEquals(0xFF333333.toInt(), dark.colors[StyleField.KEY_BACKGROUND])
        // 派生深色生效（face 的派生 = keyPressed 仍取 pressed 基色）
        assertEquals(0xFF111111.toInt(), dark.colors[StyleField.KEY_PRESSED])
        assertEquals(0xFFFFFFFF.toInt(), dark.colors[StyleField.KEY_TEXT])
    }

    @Test
    fun lightOnlySkinRejectedMissingDarkMode() {
        // 2026-10-29 双模式硬要求（取代旧行为“单边皮肤无深色板、
        // 深色下沿用浅色板”）：浅色板齐全但深色段整体缺失即整包拒绝。
        val tree = Json.parseToJsonElement(
            """{
                "formatVersion": 3,
                "styles": { "keyBackground": "#FF111111" }
            }""",
        ) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Rejected)
        assertEquals(
            "skin_missing_dark_mode",
            (result as SkinValidator.Result.Rejected).reason,
        )
    }

    @Test
    fun darkOnlySkinRejected() {
        val tree = Json.parseToJsonElement(
            """{
                "formatVersion": 3,
                "stylesDark": { "keyBackground": "#FF222222" }
            }""",
        ) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Rejected)
        assertEquals("no_color_scheme", (result as SkinValidator.Result.Rejected).reason)
    }

    @Test
    fun darkSurfaceStyleFallsBackToLight() {
        val valid = validate(
            """{
                "formatVersion": 3,
                "styles": {
                    "keyBackground": "#FF111111", "surfaceStyle": "Flat"
                },
                "stylesDark": { "keyBackground": "#FF222222" }
            }""",
        )
        assertEquals(
            KeyboardColors.SurfaceStyle.Flat,
            valid.skin.darkStyles!!.surfaceStyle,
        )
        assertEquals(
            KeyboardColors.SurfaceStyle.Flat,
            build(valid.skin).darkColorScheme!!.surfaceStyle,
        )
    }

    // ---- 横屏绑定：形态校验 ----

    @Test
    fun landscapeBindingMorphology() {
        val valid = validate(
            """{
                "formatVersion": 3,
                "styles": { "keyBackground": "#FF111111" },
                "layoutId": "lay-portrait",
                "layoutIdLandscape": "lay-landscape"
            }""",
        )
        assertEquals("lay-portrait", valid.skin.layoutId)
        assertEquals("lay-landscape", valid.skin.layoutIdLandscape)

        val bad = validate(
            """{
                "formatVersion": 3,
                "styles": { "keyBackground": "#FF111111" },
                "layoutIdLandscape": 42
            }""",
        )
        assertNull(bad.skin.layoutIdLandscape)
        assertTrue(
            bad.issues.any {
                it.path == "layoutIdLandscape" && it.detail == "layout_id_not_string"
            },
        )
    }

    // ---- 横屏生效：绑定优先，缺失回退竖屏横屏变体 ----

    private fun layoutTree(id: String, label: String): JsonObject = buildJsonObject {
        put("formatVersion", 3)
        put("meta", buildJsonObject {
            put("id", id)
            put("name", id)
        })
        put("rows", buildJsonArray {
            add(buildJsonObject {
                put("keys", buildJsonArray {
                    add(buildJsonObject {
                        put("stableId", "1")
                        put("label", label)
                        put("widthWeight", 1.0)
                        put("landscapeWidthWeight", 3.0)
                        put("tap", buildJsonObject {
                            put("type", "text")
                            put("value", label)
                        })
                    })
                })
            })
        })
    }

    private fun resolved(id: String, label: String): ResolvedLayout {
        val outcome = LayoutResolver.resolve(layoutTree(id, label), id, 1, emptyMap())
        assertTrue(outcome is LayoutResolver.Outcome.Resolved)
        return (outcome as LayoutResolver.Outcome.Resolved).resolved
    }

    @Test
    fun landscapeBindingPreferredOverPortraitLandscapeVariant() {
        val portrait = resolved("lay-portrait", "p")
        val landscape = resolved("lay-landscape", "l")
        val base = validate(
            """{
                "formatVersion": 3,
                "styles": { "keyBackground": "#FF111111" },
                "layoutId": "lay-portrait",
                "layoutIdLandscape": "lay-landscape"
            }""",
        ).skin
        val snapshot = SkinSnapshotBuilder.build(
            validated = base, ref = ref, defaultScheme = defaultScheme,
            tier = MemoryTier.NORMAL, assetSha256 = { null },
            targetWidthPx = 1080, targetHeightPx = 800,
            resolvedLayout = portrait, layoutResolution = LayoutResolution.RESOLVED,
            resolvedLayoutLandscape = landscape,
            landscapeLayoutResolution = LayoutResolution.RESOLVED,
        )
        // 竖屏取竖屏结构
        assertEquals(
            "lay-portrait:K-1",
            CustomLayoutRuntime.effectiveLayout(snapshot, emptyMap())!![0][0].id,
        )
        // 横屏取横屏绑定布局（不是竖屏布局的横屏变体）
        assertEquals(
            "lay-landscape:K-1",
            CustomLayoutRuntime.effectiveLayout(
                snapshot, emptyMap(), landscape = true,
            )!![0][0].id,
        )
        assertEquals(LayoutResolution.RESOLVED, snapshot.landscapeLayoutResolution)
    }

    @Test
    fun landscapeMissingFallsBackToPortraitLandscapeVariant() {
        val portrait = resolved("lay-portrait", "p")
        val base = validate(
            """{
                "formatVersion": 3,
                "styles": { "keyBackground": "#FF111111" },
                "layoutId": "lay-portrait",
                "layoutIdLandscape": "lay-missing"
            }""",
        ).skin
        // 工厂层对缺失绑定返回 null + MISSING（此处模拟工厂结论）；
        // 运行期横屏取竖屏布局的横屏权重变体，恒等引用、永不空白。
        val snapshot = SkinSnapshotBuilder.build(
            validated = base, ref = ref, defaultScheme = defaultScheme,
            tier = MemoryTier.NORMAL, assetSha256 = { null },
            targetWidthPx = 1080, targetHeightPx = 800,
            resolvedLayout = portrait, layoutResolution = LayoutResolution.RESOLVED,
            resolvedLayoutLandscape = null,
            landscapeLayoutResolution = LayoutResolution.MISSING,
        )
        assertSame(
            portrait.baseLayoutLandscape,
            CustomLayoutRuntime.effectiveLayout(
                snapshot, emptyMap(), landscape = true,
            ),
        )
        // 横屏变体宽度确为 landscapeWidthWeight 的比例（单键行恒 1.0，
        // 故以双键布局验比例见 LayoutResolverTest；此处只验不为空）
        assertTrue(
            CustomLayoutRuntime.effectiveLayout(
                snapshot, emptyMap(), landscape = true,
            )!!.isNotEmpty(),
        )
    }

    // ---- 演示包原字节（与交付 .jime 同源的测试夹具） ----

    private fun demoText(name: String): String {
        val stream = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("g1-demo/$name")
        return stream.readBytes().decodeToString()
    }

    @Test
    fun demoPackageFixturesValidateAndSelect() {
        val tree = Json.parseToJsonElement(demoText("skin.json")) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        assertEquals("demo-dual-palette", skin.metaId)
        assertEquals("demo-portrait", skin.layoutId)
        assertEquals("demo-landscape", skin.layoutIdLandscape)
        assertTrue(skin.darkStyles != null)

        val portrait = resolvedFrom(demoText("layout-portrait.json"), "demo-portrait")
        val landscape = resolvedFrom(demoText("layout-landscape.json"), "demo-landscape")
        val snapshot = SkinSnapshotBuilder.build(
            validated = skin, ref = ref, defaultScheme = defaultScheme,
            tier = MemoryTier.NORMAL, assetSha256 = { null },
            targetWidthPx = 1080, targetHeightPx = 800,
            resolvedLayout = portrait, layoutResolution = LayoutResolution.RESOLVED,
            resolvedLayoutLandscape = landscape,
            landscapeLayoutResolution = LayoutResolution.RESOLVED,
        )
        // 演示包浅色板奶油底 + 深色板深 slate（与 .jime 内 skin.json 一致）
        assertEquals(0xFFFDF6EC.toInt(), snapshot.effectiveColorScheme(false).keyBackground)
        assertEquals(0xFF232B28.toInt(), snapshot.effectiveColorScheme(true).keyBackground)
        // 竖屏 3 行、横屏 2 行且键集不同：绑定切换可凭行数/键数判别
        val portraitKeys = CustomLayoutRuntime.effectiveLayout(snapshot, emptyMap())!!
        val landscapeKeys = CustomLayoutRuntime.effectiveLayout(
            snapshot, emptyMap(), landscape = true,
        )!!
        assertEquals(3, portraitKeys.size)
        assertEquals(2, landscapeKeys.size)
        assertEquals(5, landscapeKeys[0].size)
        assertEquals("demo-landscape:K-r", landscapeKeys[0][3].id)
    }

    private fun resolvedFrom(text: String, id: String): ResolvedLayout {
        val tree = Json.parseToJsonElement(text)
        val outcome = LayoutResolver.resolve(tree, id, 1, emptyMap())
        assertTrue(outcome is LayoutResolver.Outcome.Resolved)
        return (outcome as LayoutResolver.Outcome.Resolved).resolved
    }

    @Test
    fun noPortraitLayoutYieldsNullEffectiveLayout() {
        val base = validate(
            """{
                "formatVersion": 3,
                "styles": { "keyBackground": "#FF111111" }
            }""",
        ).skin
        val snapshot = build(base)
        assertNull(CustomLayoutRuntime.effectiveLayout(snapshot, emptyMap()))
        assertNull(
            CustomLayoutRuntime.effectiveLayout(snapshot, emptyMap(), landscape = true),
        )
    }
}
