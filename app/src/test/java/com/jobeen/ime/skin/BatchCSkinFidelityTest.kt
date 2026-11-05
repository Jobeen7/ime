// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.panel.component.PerformancePanelView
import com.jobeen.ime.input.panel.menu.KeyboardMenuEntries
import com.jobeen.ime.skin.layout.LayoutKeyDefBuilder
import com.jobeen.ime.skin.layout.LayoutModel
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.KeyStyleGeometryParser
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.SkinKeyMappingsParser
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.model.SoundBindings
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.notify.NotificationSource
import com.jobeen.ime.skin.notify.SkinNotification
import com.jobeen.ime.skin.perf.PerfMonitor
import com.jobeen.ime.skin.runtime.CustomLayoutRuntime
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.symbol.BarSide
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbolsParser
import com.jobeen.ime.skin.symbol.SymbolBarKey
import com.jobeen.ime.skin.symbol.SymbolBarSpec
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.skin.symbolbar.symbolBarConfigFromSkinSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C 组皮肤保真项（P0-1／P0-2／P1-4／P1-5／P1-6／P1-7／P1-8／
 * L1／L2／L3／L4／L5／L6／P2-10）目标单测。
 */
class BatchCSkinFidelityTest {

    // ---------- P0-1：通知 label ----------

    private val scheme = KeyboardColors.ColorScheme(
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

    private fun snapshot() = SkinSnapshot(
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
            "accent" to mapOf(
                KeyStyleRole.FACE to "#FF0000",
                KeyStyleRole.TEXT to "#00FF00",
            ),
        ),
    )

    private fun accentedLayout(): List<List<KeyDef>> {
        val layout = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
        layout[0][0].skinStyleRef = "accent"
        return layout
    }

    private fun displayText(def: KeyDef): String =
        (def.appearance as KeyDef.Appearance.Text).displayText

    @Test
    fun notificationLabel_overridesStaticTextAndExpandsPlaceholder() {
        val latched = SkinNotification(
            source = NotificationSource.RIME_OPTION, matchKey = "ascii_mode",
            matchValue = "true", targetStyle = "accent",
            backgroundStyle = null, foregroundStyle = null,
            action = null, swipeUpAction = null, swipeDownAction = null,
            label = "通知-\$returnKeyType",
        )
        val result = KeyOverrideApplier.apply(
            "Qwerty", accentedLayout(), snapshot(),
            mapOf("returnKeyType" to "换行"),
            scheme = scheme, notifications = mapOf("accent" to latched),
        )
        assertEquals("通知-换行", displayText(result[0][0]))
    }

    @Test
    fun notificationWithoutLabel_keepsFactoryText() {
        val latched = SkinNotification(
            source = NotificationSource.RIME_OPTION, matchKey = "ascii_mode",
            matchValue = "true", targetStyle = "accent",
            backgroundStyle = "#0000FF", foregroundStyle = null,
            action = null, swipeUpAction = null, swipeDownAction = null,
            label = null,
        )
        val result = KeyOverrideApplier.apply(
            "Qwerty", accentedLayout(), snapshot(), emptyMap(),
            scheme = scheme, notifications = mapOf("accent" to latched),
        )
        assertEquals(displayText(accentedLayout()[0][0]), displayText(result[0][0]))
    }

    // ---------- P0-2 / P1-4 / P2-10（动作分发） ----------

    @Test
    fun selectText_dispatchesToSelectAllAction() {
        assertEquals(
            KeyboardAction.SelectAllAction,
            KeyOverrideApplier.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_SELECT_TEXT),
            ),
        )
    }

    @Test
    fun showPasteboardView_dispatchesToShowPasteboardAction() {
        assertEquals(
            KeyboardAction.ShowPasteboardAction,
            KeyOverrideApplier.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_SHOW_PASTEBOARD_VIEW),
            ),
        )
    }

    @Test
    fun keyboardPerformance_dispatchesToShowPerformanceAction() {
        assertEquals(
            KeyboardAction.ShowPerformanceAction,
            KeyOverrideApplier.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_KEYBOARD_PERFORMANCE),
            ),
        )
    }

    @Test
    fun subCollectionPaging_dispatchesToPageActions() {
        assertEquals(
            KeyboardAction.SubCollectionPageUpAction,
            KeyOverrideApplier.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_SUB_COLLECTION_PAGE_UP),
            ),
        )
        assertEquals(
            KeyboardAction.SubCollectionPageDownAction,
            KeyOverrideApplier.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_SUB_COLLECTION_PAGE_DOWN),
            ),
        )
    }

    // ---------- P1-5：二级集合翻页 ----------

    private fun boardState(): SubClassifiedBoardState {
        val (parsed, _) = SubClassifiedSymbolsParser.parse(
            Json.parseToJsonElement(
                """{"parents": [{"label": "数学", "subCategories": [
                    {"label": "运算", "symbols": [${
                    (1..30).joinToString(",") { "\"$it\"" }
                }]},
                    {"label": "比较", "symbols": ["<", ">"]}
                ]}]}""",
            ),
        )
        return SubClassifiedBoardState(parsed!!, pageSize = 25)
    }

    @Test
    fun subCollectionPaging_slicesAndStopsAtEnds() {
        val state = boardState()
        assertEquals(2, state.pageCount)
        assertEquals(25, state.visibleItems.size)
        assertTrue(state.nextPage())
        assertEquals(1, state.page)
        assertEquals(5, state.visibleItems.size)
        assertFalse(state.nextPage()) // 末页为空操作，不绕回
        assertEquals(1, state.page)
        assertTrue(state.prevPage())
        assertEquals(0, state.page)
        assertFalse(state.prevPage()) // 首页为空操作
        assertEquals(0, state.page)
    }

    @Test
    fun subCollectionPaging_switchResetsPage() {
        val state = boardState()
        assertTrue(state.nextPage())
        assertEquals(1, state.page)
        state.selectSub(1)
        assertEquals(0, state.page)
        assertEquals(2, state.visibleItems.size)
    }

    // ---------- P1-6：kaomoji ----------

    @Test
    fun kaomoji_isBuiltinAndFallsBackToSymbolic() {
        assertEquals("kaomoji", com.jobeen.ime.skin.model.KeyboardTypes.KAOMOJI)
        assertTrue(com.jobeen.ime.skin.model.KeyboardTypes.BUILTIN.contains("kaomoji"))
        assertEquals(
            SymbolKeyboard.NAME,
            CustomLayoutRuntime.builtinKeyboardName("kaomoji", "Qwerty"),
        )
    }

    // ---------- P1-7：死解析器接线 ----------

    private val fallbacks = GeometryFallbacks(12f, 6f, 5f, 10f)

    private fun validatedSkin(json: String): com.jobeen.ime.skin.model.ValidatedSkin {
        val trimmed = json.trimEnd()
        val withLight = if (json.contains("\"styles\"")) json
        else trimmed.dropLast(1) + """, "styles": {"keyBackground": "#FFFFFFFF"}}"""
        val withDark = if (withLight.contains("\"stylesDark\"")) withLight
        else withLight.trimEnd().dropLast(1) +
            """, "stylesDark": {"keyBackground": "#FF222222"}}"""
        val tree = Json.parseToJsonElement(withDark) as JsonObject
        val result = SkinValidator.validate(tree, 143, fallbacks, assetKind = { null })
        assertTrue(result is SkinValidator.Result.Valid)
        return (result as SkinValidator.Result.Valid).skin
    }

    @Test
    fun deadParsers_wiredIntoSnapshot() {
        val skin = validatedSkin(
            """{"formatVersion": 3,
                "t9Symbols": {"dataSource": [{"label": "，", "value": "，"}]},
                "numericSymbols": {"dataSource": [{"label": "1", "value": "1"}]},
                "horizontalSymbols": {"dataSource": [{"label": "、", "value": "、"}], "maxColumns": 5},
                "t9HorizontalSymbols": {"dataSource": [{"label": "。", "value": "。"}]}
            }""",
        )
        assertTrue(skin.t9Symbols != null)
        assertTrue(skin.numericSymbols != null)
        assertTrue(skin.horizontalSymbols != null)
        assertTrue(skin.t9HorizontalSymbols != null)
        assertEquals(1, skin.t9Symbols!!.dataSource.size)
        assertEquals(9, skin.t9HorizontalSymbols!!.maxColumns)
    }

    // ---------- P1-8：皮肤 symbolBar ----------

    private fun barSpec(
        keys: List<SymbolBarKey>,
        close: BarSide = BarSide.LEFT,
    ) = SymbolBarSpec(keys = keys, closePosition = close, typeSwitchPosition = BarSide.RIGHT)

    @Test
    fun skinSymbolBar_padsToEightAndMapsCorners() {
        val spec = barSpec(
            listOf(
                SymbolBarKey("，", listOf("、", "。", "；", "：")),
                SymbolBarKey("！", emptyList()),
                SymbolBarKey("？", emptyList()),
            ),
            close = BarSide.RIGHT,
        )
        val config = symbolBarConfigFromSkinSpec(spec, listOf("标点", "数字"))
        assertEquals(8, config.panels.getValue("标点").keys.size)
        assertFalse(config.closeButtonOnLeft)
        val first = config.panels.getValue("标点").keys[0]
        assertEquals("，", first.center.text)
        assertEquals(
            SkinAction.Command(WhitelistAction.SYMBOL, "，"),
            first.center.action,
        )
        assertEquals("、", first.topLeft?.text)
        assertEquals("。", first.topRight?.text)
        assertEquals("；", first.bottomLeft?.text)
        assertEquals("：", first.bottomRight?.text)
        // 各类型共用同一面板（皮肤只有一套面板）。
        assertEquals(
            config.panels.getValue("标点").keys,
            config.panels.getValue("数字").keys,
        )
    }

    @Test
    fun skinSymbolBar_truncatesBeyondEight() {
        val spec = barSpec((1..10).map { SymbolBarKey("$it", emptyList()) })
        val config = symbolBarConfigFromSkinSpec(spec, listOf("数字"))
        assertEquals(8, config.panels.getValue("数字").keys.size)
        assertEquals("8", config.panels.getValue("数字").keys[7].center.text)
    }

    // ---------- L5：符号项动作 ----------

    @Test
    fun symbolItemShortcut_copyDoesNotCommitLabel() {
        val action = SymbolItemActionResolver.resolve(
            SymbolItem.LabelActionStyle(label = "复制", action = "#copy"),
        )
        assertEquals(
            KeyboardAction.ClipboardAction(KeyboardAction.ClipboardAction.Kind.COPY),
            action,
        )
        assertFalse(action is KeyboardAction.CommitAction)
    }

    @Test
    fun symbolItemShortcut_unknownHashIsNoOp() {
        assertEquals(
            KeyboardAction.NoOpAction,
            SymbolItemActionResolver.resolve(
                SymbolItem.LabelActionStyle(label = "未知", action = "#noSuchAction"),
            ),
        )
    }

    @Test
    fun symbolItemShortcut_unknownPlainKeepsCommitLabel() {
        assertEquals(
            KeyboardAction.CommitAction("点按"),
            SymbolItemActionResolver.resolve(
                SymbolItem.LabelActionStyle(label = "点按", action = "somePlainAction"),
            ),
        )
    }

    @Test
    fun symbolItemShortcut_selectTextAndNewline() {
        assertEquals(
            KeyboardAction.SelectAllAction,
            SymbolItemActionResolver.resolve(
                SymbolItem.LabelActionStyle(label = "全选", action = "#selectText"),
            ),
        )
        assertEquals(
            KeyboardAction.CommitAction("\n"),
            SymbolItemActionResolver.resolve(
                SymbolItem.LabelActionStyle(label = "换行", action = "#换行"),
            ),
        )
    }

    // ---------- L3：前景层上限可诊断 ----------

    private fun parseKeyEntry(entryJson: String): SkinKeyMappingsParser.ParseResult {
        val tree = Json.parseToJsonElement("""{"K1": $entryJson}""") as JsonObject
        return SkinKeyMappingsParser.parse(tree, { true }, { false })
    }

    @Test
    fun foregroundLayerExcess_isDiagnosableWarning() {
        val result = parseKeyEntry(
            """{"foregroundLayers": [
              {"type": "text", "text": "一"},
              {"type": "text", "text": "二"},
              {"type": "text", "text": "三"},
              {"type": "text", "text": "四"},
              {"type": "text", "text": "超限"}
            ]}""",
        )
        val override = result.overrides.getValue("K1")
        assertEquals(4, override.foregroundLayers!!.size)
        assertTrue(
            result.fieldWarnings.any { it.reason == "foreground_layer_excess" },
        )
    }

    // ---------- L4：四边 insets ----------

    @Test
    fun geometryInsets_fourSidesParsed() {
        val result = parseKeyEntry(
            """{"geometry": {
                "hInsetDp": 2, "vInsetDp": 4,
                "insets": {"left": 1, "right": 3, "top": 5, "bottom": 6}
            }}""",
        )
        val geometry = result.overrides.getValue("K1").geometry!!
        assertEquals(2f, geometry.hInsetDp)
        assertEquals(4f, geometry.vInsetDp)
        assertEquals(1f, geometry.leftInsetDp)
        assertEquals(3f, geometry.rightInsetDp)
        assertEquals(5f, geometry.topInsetDp)
        assertEquals(6f, geometry.bottomInsetDp)
    }

    @Test
    fun geometryInsets_absentKeepsSymmetricOnly() {
        val result = parseKeyEntry(
            """{"geometry": {"hInsetDp": 2, "vInsetDp": 4}}""",
        )
        val geometry = result.overrides.getValue("K1").geometry!!
        assertNull(geometry.leftInsetDp)
        assertNull(geometry.rightInsetDp)
        assertNull(geometry.topInsetDp)
        assertNull(geometry.bottomInsetDp)
    }

    @Test
    fun styleGeometryInsets_fourSidesParsed() {
        val (geometry, _) = KeyStyleGeometryParser.parseEntry(
            Json.parseToJsonElement(
                """{"geometry": {
                    "insets": {"left": 2, "right": 2, "top": 5, "bottom": 5}
                }}""",
            ) as JsonObject,
        )
        assertEquals(2f, geometry!!.leftInsetDp)
        assertEquals(2f, geometry.rightInsetDp)
        assertEquals(5f, geometry.topInsetDp)
        assertEquals(5f, geometry.bottomInsetDp)
    }

    // ---------- L1：跨行列模型 ----------

    private val layoutId = "lay-test"

    private fun textTap(value: String = "q"): JsonObject = buildJsonObject {
        put("type", "text")
        put("value", value)
    }

    private fun layoutKey(
        stableId: String,
        label: String = "q",
        weight: Double = 1.0,
        rowSpan: Int? = null,
        alignSpanEnd: Boolean? = null,
        spanCol: Int? = null,
        spanColEnd: Int? = null,
    ): JsonObject = buildJsonObject {
        put("stableId", stableId)
        put("label", label)
        put("widthWeight", weight)
        if (rowSpan != null) put("rowSpan", rowSpan)
        if (alignSpanEnd != null) put("alignSpanEnd", alignSpanEnd)
        if (spanCol != null) put("spanCol", spanCol)
        if (spanColEnd != null) put("spanColEnd", spanColEnd)
        put("tap", textTap(label))
    }

    private fun layoutRow(vararg keys: JsonElement): JsonObject = buildJsonObject {
        put("keys", JsonArray(keys.toList()))
    }

    private fun layoutTree(vararg rows: JsonObject): JsonObject = buildJsonObject {
        put("formatVersion", 3)
        put("meta", buildJsonObject {
            put("id", layoutId)
            put("name", "测试布局")
        })
        put("rows", JsonArray(rows.toList()))
    }

    private fun validLayout(tree: JsonElement?): LayoutModel {
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue("expected Valid, got $result", result is LayoutValidation.Valid)
        return (result as LayoutValidation.Valid).layout
    }

    private fun validResult(tree: JsonElement?): LayoutValidation.Valid {
        val result = LayoutValidator.validate(tree, layoutId)
        assertTrue("expected Valid, got $result", result is LayoutValidation.Valid)
        return result as LayoutValidation.Valid
    }

    @Test
    fun spanCol_parsedAndKept() {
        val model = validLayout(
            layoutTree(
                layoutRow(
                    layoutKey("a", rowSpan = 2, spanCol = 1, spanColEnd = 2),
                    layoutKey("b"), layoutKey("c"),
                ),
                layoutRow(layoutKey("d"), layoutKey("e"), layoutKey("f")),
            ),
        )
        assertEquals(1, model.rows[0][0].spanCol)
        assertEquals(2, model.rows[0][0].spanColEnd)
        assertEquals(2, model.rows[0][0].rowSpan)
    }

    @Test
    fun spanCol_invalidFallsBackToLegacy() {
        val result = validResult(
            layoutTree(
                layoutRow(
                    layoutKey("a", rowSpan = 2, spanCol = 2, spanColEnd = 2),
                    layoutKey("b"),
                ),
                layoutRow(layoutKey("c"), layoutKey("d")),
            ),
        )
        assertTrue(result.issues.any { it.reason == "span_col_invalid" })
        val model = result.layout
        assertNull(model.rows[0][0].spanCol)
        assertEquals(2, model.rows[0][0].rowSpan) // 布局保持有效
    }

    @Test
    fun spanConflict_differentColumnsNoConflict() {
        // 同侧（左）两 span，行区间重叠但列区间 [0,1) 与 [1,2) 不重叠 → 有效。
        val model = validLayout(
            layoutTree(
                layoutRow(
                    layoutKey("a", rowSpan = 2, spanCol = 0, spanColEnd = 1),
                    layoutKey("b"), layoutKey("c"),
                ),
                layoutRow(
                    layoutKey("d", rowSpan = 2, spanCol = 1, spanColEnd = 2),
                    layoutKey("e"), layoutKey("f"),
                ),
                layoutRow(layoutKey("g"), layoutKey("h"), layoutKey("i")),
            ),
        )
        assertEquals(2, model.rows[0][0].rowSpan)
        assertEquals(2, model.rows[1][0].rowSpan)
    }

    @Test
    fun spanConflict_sameColumnDowngradesLaterSpan() {
        // 同列同行重叠 → 后出现的降级为普通键，布局保持有效。
        val result = validResult(
            layoutTree(
                layoutRow(
                    layoutKey("a", rowSpan = 2, spanCol = 0, spanColEnd = 1),
                    layoutKey("b"), layoutKey("c"),
                ),
                layoutRow(
                    layoutKey("d", rowSpan = 2, spanCol = 0, spanColEnd = 1),
                    layoutKey("e"), layoutKey("f"),
                ),
                layoutRow(layoutKey("g"), layoutKey("h"), layoutKey("i")),
            ),
        )
        assertTrue(result.issues.any { it.reason == "span_conflict_downgraded" })
        assertEquals(2, result.layout.rows[0][0].rowSpan)
        assertEquals(1, result.layout.rows[1][0].rowSpan)
        assertNull(result.layout.rows[1][0].spanCol)
    }

    @Test
    fun spanConflict_legacySameSideStillDowngrades() {
        // 旧口径两左 span（无 spanCol）行重叠 → 降级，不再整布局无效。
        val result = validResult(
            layoutTree(
                layoutRow(
                    layoutKey("a", rowSpan = 2, alignSpanEnd = false),
                    layoutKey("b"), layoutKey("c"),
                ),
                layoutRow(
                    layoutKey("d", rowSpan = 2, alignSpanEnd = false),
                    layoutKey("e"), layoutKey("f"),
                ),
                layoutRow(layoutKey("g"), layoutKey("h"), layoutKey("i")),
            ),
        )
        assertTrue(result.issues.any { it.reason == "span_conflict_downgraded" })
        assertEquals(1, result.layout.rows[1][0].rowSpan)
    }

    @Test
    fun spanConflict_legacyRightEdgeDifferentRowWidthsStillDowngrades() {
        // 两右 span 起始行键数不同（[2,3) 与 [3,4) 错开），但几何上
        // 同占行右缘 → 仍判冲突降级。
        val result = validResult(
            layoutTree(
                layoutRow(
                    layoutKey("a", rowSpan = 2, alignSpanEnd = true),
                    layoutKey("b"),
                ),
                layoutRow(
                    layoutKey("c", rowSpan = 2, alignSpanEnd = true),
                    layoutKey("d"), layoutKey("e"),
                ),
                layoutRow(layoutKey("f"), layoutKey("g")),
            ),
        )
        assertTrue(result.issues.any { it.reason == "span_conflict_downgraded" })
        assertEquals(1, result.layout.rows[1][0].rowSpan)
    }

    @Test
    fun builderRowInvariant_inRowPlusCoveringSpanSumsToOne() {
        // 行和不变量重推：每行（行内键 percentWidth 和＋覆盖 span 宽）= 1.0。
        val model = validLayout(
            layoutTree(
                layoutRow(
                    layoutKey("a", weight = 1.0, rowSpan = 2, alignSpanEnd = false),
                    layoutKey("b", weight = 3.0),
                    layoutKey("c", weight = 3.0),
                    layoutKey("d", weight = 1.0, rowSpan = 2, alignSpanEnd = true),
                ),
                layoutRow(
                    layoutKey("e", weight = 3.0),
                    layoutKey("f", weight = 3.0),
                ),
            ),
        )
        val built = LayoutKeyDefBuilder.build(model, emptyMap())
        // 第 0 行：行内 b/c（3/8 + 3/8）+ span a/d（1/8 + 1/8）= 1.0；
        // 第 1 行：行内 e/f（3/8 + 3/8）+ 覆盖 span a/d（1/8 + 1/8）= 1.0
        // （空白键计入行内和）。
        for (rowIndex in built.indices) {
            val row = built[rowIndex]
            val inRowSum = row.filterNot { it.appearance.rowSpan > 1 }
                .sumOf { it.appearance.percentWidth.toDouble() }
            val coveringSum = model.rows.withIndex().sumOf { (startRow, startKeys) ->
                if (startRow >= rowIndex) 0.0 else startKeys.sumOf { key ->
                    if (key.rowSpan > 1 && rowIndex <= startRow + key.rowSpan - 1) {
                        key.widthWeight / 8.0
                    } else {
                        0.0
                    }
                }
            } + if (rowIndex == 0) {
                row.filter { it.appearance.rowSpan > 1 }
                    .sumOf { it.appearance.percentWidth.toDouble() }
            } else {
                0.0
            }
            assertEquals(1.0, inRowSum + coveringSum, 1e-4)
        }
    }

    @Test
    fun builderRowInvariant_downgradedLayoutStillSumsToOne() {
        // 降级布局：被降级行之外的覆盖行 authored 和低于 1.0，
        // 差额由空白键吸收，渲染行总和仍为 1.0。
        val result = validResult(
            layoutTree(
                layoutRow(
                    layoutKey("a", weight = 1.0, rowSpan = 2, spanCol = 0, spanColEnd = 1),
                    layoutKey("b", weight = 3.0),
                    layoutKey("c", weight = 3.0),
                ),
                layoutRow(
                    layoutKey("d", weight = 1.0, rowSpan = 2, spanCol = 0, spanColEnd = 1),
                    layoutKey("e", weight = 3.0),
                    layoutKey("f", weight = 3.0),
                ),
                layoutRow(
                    layoutKey("g", weight = 3.0),
                    layoutKey("h", weight = 3.0),
                ),
            ),
        )
        assertTrue(result.issues.any { it.reason == "span_conflict_downgraded" })
        val built = LayoutKeyDefBuilder.build(result.layout, emptyMap())
        for (row in built) {
            val total = row.sumOf { it.appearance.percentWidth.toDouble() }
            assertEquals(1.0, total, 1e-4)
        }
    }

    // ---------- P2-10：性能面板 ----------

    @Test
    fun performanceRows_baseOrderThenProbesOnlyIfPresent() {
        val rows = PerformancePanelView.rowsFor(
            mapOf(
                PerfMonitor.Event.MEMORY to PerfMonitor.Aggregate(2L, 100.0, 120L),
                PerfMonitor.Event.COLD_START to PerfMonitor.Aggregate(1L, 300.0, 300L),
                PerfMonitor.Event.KEY_ENGINE to PerfMonitor.Aggregate(5L, 2.0, 9L),
            ),
        )
        assertEquals(
            listOf("冷启动", "内存", "引擎处理"),
            rows.map { it.first },
        )
        assertEquals("1 次，均值 300.0 ms，最大 300 ms", rows[0].second)
        assertEquals("2 次，均值 100.0 KB，最大 120 KB", rows[1].second)
    }

    @Test
    fun performanceRows_emptyAggregates() {
        assertTrue(PerformancePanelView.rowsFor(emptyMap()).isEmpty())
    }

    @Test
    fun menuSecondPage_performanceReplacesAbout() {
        val page2 = KeyboardMenuEntries.PAGES[1]
        assertEquals(8, page2.size)
        assertEquals("performance", page2.last().id)
        assertTrue(page2.none { it.id == "about" })
        assertEquals(
            com.jobeen.ime.input.panel.PanelAction.Performance,
            page2.last().action,
        )
    }
}
