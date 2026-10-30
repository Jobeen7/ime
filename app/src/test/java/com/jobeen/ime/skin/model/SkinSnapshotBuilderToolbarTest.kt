package com.jobeen.ime.skin.model

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.store.SkinGenRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 快照工具栏构成（2026-10-27）：校验结果 → 快照引用转换（id + sha + 扩展名，不解码）。 */
class SkinSnapshotBuilderToolbarTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val ref = SkinGenRef("skin-1", "h".repeat(64), 1)
    private val assetKinds = mapOf(
        "tb-emoji" to AssetKind.IMAGE,
        "tb-expand" to AssetKind.IMAGE,
        "tb-collapse" to AssetKind.IMAGE,
    )
    private val shas = mapOf(
        "tb-emoji" to "e".repeat(64),
        "tb-expand" to "a".repeat(64),
        "tb-collapse" to "c".repeat(64),
    )
    private val exts = mapOf(
        "tb-emoji" to "png",
        "tb-expand" to "webp",
        "tb-collapse" to "png",
    )

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

    private fun build(json: String, extraKinds: Map<String, AssetKind> = emptyMap()): SkinSnapshot {
        // 2026-10-29 双模式硬要求：夹具默认补深色板（被测行为与深色板
        // 无关时用此默认；深色板自身行为由显式 stylesDark 用例覆盖）
        val tree = Json.parseToJsonElement(withDefaultDark(json)) as JsonObject
        val kinds = assetKinds + extraKinds
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { kinds[it] })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        val validated = (result as SkinValidator.Result.Valid).skin
        return SkinSnapshotBuilder.build(
            validated = validated,
            ref = ref,
            defaultScheme = defaultScheme,
            tier = MemoryTier.NORMAL,
            assetSha256 = { shas[it] },
            targetWidthPx = 1000,
            targetHeightPx = 400,
            assetExt = { exts[it] },
        )
    }

    @Test
    fun snapshotCarriesToolbarRefsWithShaAndExt() {
        val snapshot = build(
            """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
             "toolbar": {"buttons": [{"id": "emoji", "icon": "tb-emoji"}, "copy"],
              "expandIcon": "tb-expand", "collapseIcon": "tb-collapse"}}
            """.trimIndent(),
        )
        val toolbar = snapshot.toolbar
        assertTrue(toolbar.present)
        assertEquals(
            listOf(
                SkinToolbarButtonSnapshot(
                    "emoji", SkinToolbarIconRef("tb-emoji", "e".repeat(64), "png"),
                ),
                SkinToolbarButtonSnapshot("copy", null),
            ),
            toolbar.buttons,
        )
        assertEquals(SkinToolbarIconRef("tb-expand", "a".repeat(64), "webp"), toolbar.expandIcon)
        assertEquals(SkinToolbarIconRef("tb-collapse", "c".repeat(64), "png"), toolbar.collapseIcon)
    }

    @Test
    fun iconWithoutShaBecomesNullForRendererFallback() {
        // 校验期资产类别为 IMAGE，但构建期清单查无 sha：引用置空，
        // 渲染期回退内置图标（工厂日志层记 toolbar_icon_unresolved）。
        val snapshot = build(
            """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
             "toolbar": {"buttons": [{"id": "emoji", "icon": "tb-ghost"}],
              "expandIcon": "tb-ghost"}}
            """.trimIndent(),
            extraKinds = mapOf("tb-ghost" to AssetKind.IMAGE),
        )
        assertNull(snapshot.toolbar.buttons!!.single().icon)
        assertNull(snapshot.toolbar.expandIcon)
    }

    @Test
    fun symbolNamesPassThroughToSnapshotUnchanged() {
        val snapshot = build(
            """
            {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
             "toolbar": {"buttons": [{"id": "emoji", "icon": "star"}],
              "expandIcon": "menu", "collapseIcon": "keyboard_hide"}}
            """.trimIndent(),
        )
        val toolbar = snapshot.toolbar
        assertTrue(toolbar.present)
        assertEquals(
            listOf(SkinToolbarButtonSnapshot("emoji", null, "star")),
            toolbar.buttons,
        )
        assertNull(toolbar.expandIcon)
        assertNull(toolbar.collapseIcon)
        assertEquals("menu", toolbar.expandSymbol)
        assertEquals("keyboard_hide", toolbar.collapseSymbol)
    }

    @Test
    fun noToolbarNodeYieldsEmptySnapshotToolbar() {
        val snapshot = build(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}}""",
        )
        assertEquals(SkinToolbarSnapshot(), snapshot.toolbar)
    }
}
