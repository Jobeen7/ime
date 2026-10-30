package com.jobeen.ime.skin.model

import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 皮肤工具栏构成（2026-10-27 工具栏进皮肤）：顶层 toolbar 节点逐项校验口径。 */
class SkinToolbarParserTest {

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)
    private val assetKinds = mapOf(
        "tb-emoji" to AssetKind.IMAGE,
        "tb-expand" to AssetKind.IMAGE,
        "tb-collapse" to AssetKind.IMAGE,
        "tb-voice" to AssetKind.IMAGE,
        // 演示包 skin.json 的全部图标资产（演示测试要求零工具栏 issue）。
        "tb-copy" to AssetKind.IMAGE,
        "tap-audio" to AssetKind.AUDIO,
    )

    private fun validate(json: String): SkinValidator.Result.Valid {
        val tree = Json.parseToJsonElement(json) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { assetKinds[it] })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        return result as SkinValidator.Result.Valid
    }

    private fun toolbar(json: String): Pair<SkinToolbarSpec, List<SkinIssue>> {
        val result = validate(json)
        return result.skin.toolbar to result.issues.filter { it.path.startsWith("toolbar") }
    }

    private fun base(toolbarJson: String): String = """
        {"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"}, "stylesDark": {"keyBackground": "#FF222222"}, "toolbar": $toolbarJson}
    """.trimIndent()

    @Test
    fun fullToolbarParsesButtonsAndEdgeIcons() {
        val (spec, issues) = toolbar(
            base(
                """
                {"buttons": [{"id": "emoji", "icon": "tb-emoji"}, "copy", {"id": "voice"}],
                 "expandIcon": "tb-expand", "collapseIcon": "tb-collapse"}
                """.trimIndent(),
            ),
        )
        assertTrue(spec.present)
        assertEquals(
            listOf(
                SkinToolbarButton("emoji", "tb-emoji"),
                SkinToolbarButton("copy", null),
                SkinToolbarButton("voice", null),
            ),
            spec.buttons,
        )
        assertEquals("tb-expand", spec.expandIconAssetId)
        assertEquals("tb-collapse", spec.collapseIconAssetId)
        assertTrue("toolbar issues: $issues", issues.isEmpty())
        assertTrue(spec.dropped.isEmpty())
    }

    @Test
    fun noToolbarNodeMeansNotDefined() {
        val result = validate(
            """{"formatVersion": 3, "styles": {"keyBackground": "#FFFFFFFF"},
               "stylesDark": {"keyBackground": "#FF222222"}}""",
        )
        assertFalse(result.skin.toolbar.present)
        assertNull(result.skin.toolbar.buttons)
        assertNull(result.skin.toolbar.expandIconAssetId)
        assertNull(result.skin.toolbar.collapseIconAssetId)
    }

    @Test
    fun buttonsAbsentMeansCenterNotDefinedButEdgesApply() {
        val (spec, _) = toolbar(base("""{"expandIcon": "tb-expand"}"""))
        assertTrue(spec.present)
        assertNull(spec.buttons)
        assertEquals("tb-expand", spec.expandIconAssetId)
        assertNull(spec.collapseIconAssetId)
    }

    @Test
    fun explicitEmptyButtonsStaysEmpty() {
        val (spec, _) = toolbar(base("""{"buttons": []}"""))
        assertEquals(emptyList<SkinToolbarButton>(), spec.buttons)
    }

    @Test
    fun unknownDuplicateAndOverLimitDroppedIndividually() {
        val (spec, issues) = toolbar(
            base(
                """
                {"buttons": ["emoji", "bogus", "emoji", "copy", "paste", "clipboard",
                 "symbol", "phrases", "voice", "cursor", "undo", "redo"]}
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(
                "emoji", "copy", "paste", "clipboard", "symbol", "phrases", "voice",
                "cursor", "undo",
            ),
            spec.buttons!!.map { it.id },
        )
        val reasons = spec.dropped.map { it.reason }.toSet()
        assertTrue("dropped: ${spec.dropped}", "toolbar_unknown_button" in reasons)
        assertTrue("dropped: ${spec.dropped}", "toolbar_duplicate_button" in reasons)
        assertTrue("dropped: ${spec.dropped}", "toolbar_over_limit" in reasons)
        assertTrue(issues.all { it.path.startsWith("toolbar.") })
    }

    @Test
    fun unknownIconStringPreservedAsSymbolNotDropped() {
        // 非资产字符串 = 内置图标名（编辑器写 Symbol 名的口径）：
        // 原样保留、不记缺失、钮保留；渲染期无注册表时回退内置图标。
        val (spec, issues) = toolbar(
            base("""{"buttons": [{"id": "emoji", "icon": "star"}]}"""),
        )
        assertEquals(listOf(SkinToolbarButton("emoji", null, "star")), spec.buttons)
        assertTrue("issues: $issues", issues.isEmpty())
        assertTrue(spec.dropped.isEmpty())
    }

    @Test
    fun wrongTypeIconAssetDroppedButButtonKept() {
        val (spec, issues) = toolbar(
            base("""{"buttons": [{"id": "copy", "icon": "tap-audio"}]}"""),
        )
        assertEquals(listOf(SkinToolbarButton("copy", null)), spec.buttons)
        assertTrue(issues.any { it.detail == "toolbar_icon_asset_wrong_type" })
        assertTrue(issues.all { it.kind == SkinIssue.Kind.RESOURCE_FALLBACK })
    }

    @Test
    fun edgeSymbolNamesPreserved() {
        val (spec, issues) = toolbar(
            base("""{"expandIcon": "menu", "collapseIcon": "keyboard_hide"}"""),
        )
        assertNull(spec.expandIconAssetId)
        assertNull(spec.collapseIconAssetId)
        assertEquals("menu", spec.expandIconSymbol)
        assertEquals("keyboard_hide", spec.collapseIconSymbol)
        assertTrue("issues: $issues", issues.isEmpty())
    }

    @Test
    fun edgeIconAliasesAndCanonicalPriority() {
        val (aliasSpec, _) = toolbar(
            base("""{"leftIcon": "tb-expand", "rightIcon": "tb-collapse"}"""),
        )
        assertEquals("tb-expand", aliasSpec.expandIconAssetId)
        assertEquals("tb-collapse", aliasSpec.collapseIconAssetId)
        val (canonSpec, _) = toolbar(
            base("""{"expandIcon": "tb-voice", "leftIcon": "tb-expand"}"""),
        )
        assertEquals("tb-voice", canonSpec.expandIconAssetId)
    }

    @Test
    fun edgeIconWrongTypeDropped() {
        val (spec, issues) = toolbar(base("""{"expandIcon": "tap-audio"}"""))
        assertNull(spec.expandIconAssetId)
        assertTrue(issues.any { it.detail == "toolbar_icon_asset_wrong_type" })
    }

    @Test
    fun malformedToolbarNodeAndButtonsFieldFallBackToAppConfig() {
        val (nodeSpec, nodeIssues) = toolbar(base(""""not-an-object""""))
        assertFalse(nodeSpec.present)
        assertNull(nodeSpec.buttons)
        assertTrue(nodeIssues.any { it.detail == "toolbar_not_object" })

        val (fieldSpec, fieldIssues) = toolbar(base("""{"buttons": "emoji"}"""))
        assertNull(fieldSpec.buttons)
        assertTrue(fieldIssues.any { it.detail == "buttons_not_array" })
    }

    @Test
    fun allButtonsDroppedFallsBackInsteadOfBlankingCenter() {
        val (spec, _) = toolbar(base("""{"buttons": ["bogus1", "bogus2"]}"""))
        // 全部丢弃 = 中央区未定义（走 App 配置），不是留空。
        assertNull(spec.buttons)
        assertEquals(2, spec.dropped.size)
    }

    @Test
    fun demoPackageSkinValidatesWithoutToolbarIssues() {
        // 随分流交付的演示包 skin.json（test resources 副本）：
        // 校验零工具栏问题，构成与演示 .jime 一致。
        val bytes = requireNotNull(javaClass.classLoader)
            .getResourceAsStream("toolbar-demo-skin.json")?.readBytes()
            ?: error("missing test resource toolbar-demo-skin.json")
        val tree = Json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        val result = SkinValidator.validate(tree, 157, fallbacks, assetKind = { assetKinds[it] })
        assertTrue("expected Valid, got $result", result is SkinValidator.Result.Valid)
        val skin = (result as SkinValidator.Result.Valid).skin
        assertTrue(result.issues.none { it.path.startsWith("toolbar") })
        assertEquals(
            listOf("emoji", "voice", "undo", "copy", "clipboard"),
            skin.toolbar.buttons!!.map { it.id },
        )
        assertEquals("tb-expand", skin.toolbar.expandIconAssetId)
        assertEquals("tb-collapse", skin.toolbar.collapseIconAssetId)
    }

    @Test
    fun maxButtonsMatchesAppConfigLimit() {
        assertEquals(ToolbarConfig.MAX_ITEMS, SkinToolbarParser.MAX_BUTTONS)
        assertEquals(9, SkinToolbarParser.MAX_BUTTONS)
    }

    @Test
    fun nineButtonsParseAndTenthDrops() {
        // 9 钮全留（池内 9 个不同 id）；第 10 钮逐项丢弃、不拦包。
        val (spec, _) = toolbar(
            base(
                """
                {"buttons": ["emoji", "symbol", "select_all", "copy", "paste",
                 "clipboard", "phrases", "voice", "cursor", "undo"]}
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(
                "emoji", "symbol", "select_all", "copy", "paste",
                "clipboard", "phrases", "voice", "cursor",
            ),
            spec.buttons!!.map { it.id },
        )
        assertEquals(1, spec.dropped.size)
        assertEquals("undo", spec.dropped.single().id)
        assertEquals("toolbar_over_limit", spec.dropped.single().reason)
    }
}
