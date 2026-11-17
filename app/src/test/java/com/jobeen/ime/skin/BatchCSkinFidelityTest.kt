// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import com.jobeen.ime.skin.runtime.KeyActionMapping
import com.jobeen.ime.skin.symbol.BarSide
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbolsParser
import com.jobeen.ime.skin.symbol.SymbolBarKey
import com.jobeen.ime.skin.symbol.SymbolBarSpec
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.skin.symbolbar.symbolBarConfigFromSkinSpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C 组皮肤保真项（皮肤 v3 死壳删除后分流：只留测活代码的 12 项；
 * 通知 label／死解析器接线／前景层上限／几何边距／span 校验等
 * 均随已删的 KeyOverrideApplier／SkinValidator／LayoutValidator
 * 一并删除）。
 */
class BatchCSkinFidelityTest {

    // ---------- P0-2 / P1-4 / P2-10（动作分发） ----------

    @Test
    fun selectText_dispatchesToSelectAllAction() {
        assertEquals(
            KeyboardAction.SelectAllAction,
            KeyActionMapping.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_SELECT_TEXT),
            ),
        )
    }

    @Test
    fun showPasteboardView_dispatchesToShowPasteboardAction() {
        assertEquals(
            KeyboardAction.ShowPasteboardAction,
            KeyActionMapping.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_SHOW_PASTEBOARD_VIEW),
            ),
        )
    }

    @Test
    fun keyboardPerformance_dispatchesToShowPerformanceAction() {
        assertEquals(
            KeyboardAction.ShowPerformanceAction,
            KeyActionMapping.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_KEYBOARD_PERFORMANCE),
            ),
        )
    }

    @Test
    fun subCollectionPaging_dispatchesToPageActions() {
        assertEquals(
            KeyboardAction.SubCollectionPageUpAction,
            KeyActionMapping.toRuntime(
                SkinAction.Command(WhitelistAction.HASH_SUB_COLLECTION_PAGE_UP),
            ),
        )
        assertEquals(
            KeyboardAction.SubCollectionPageDownAction,
            KeyActionMapping.toRuntime(
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
}
