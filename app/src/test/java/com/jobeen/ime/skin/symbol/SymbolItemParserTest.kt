// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.input.keyboard.key.KeyboardAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 符号数据源三形态（审计 J）：解析口径与点按动作分发。 */
class SymbolItemParserTest {

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun plainStringCommitsItself() {
        val item = SymbolItemParser.parseItem(element(""""★""""))
        assertEquals(SymbolItem.Plain("★"), item)
        assertEquals(
            KeyboardAction.CommitAction("★"),
            SymbolItemActionResolver.resolve(item!!),
        )
    }

    @Test
    fun labelValueDisplaysLabelCommitsValue() {
        val item = SymbolItemParser.parseItem(
            element("""{"label": "空格", "value": " "}"""),
        )
        assertEquals(SymbolItem.LabelValue("空格", " "), item)
        assertEquals("空格", item!!.displayText)
        assertEquals(
            KeyboardAction.CommitAction(" "),
            SymbolItemActionResolver.resolve(item),
        )
    }

    @Test
    fun labelActionStyleResolvesKnownActions() {
        val resume = SymbolItemParser.parseItem(
            element("""{"label": "返回", "action": "resume", "styleName": "accent"}"""),
        ) as SymbolItem.LabelActionStyle
        assertEquals(KeyboardAction.ResumeAction, SymbolItemActionResolver.resolve(resume))
        val lock = SymbolItemParser.parseItem(
            element("""{"label": "锁定", "action": "symbolic_lock_toggle", "styleName": "lock"}"""),
        ) as SymbolItem.LabelActionStyle
        assertEquals(
            KeyboardAction.ToggleSymbolicLockAction,
            SymbolItemActionResolver.resolve(lock),
        )
    }

    @Test
    fun unknownActionFallsBackToCommitLabel() {
        val item = SymbolItemParser.parseItem(
            element("""{"label": "X", "action": "bogus_action", "styleName": "s"}"""),
        ) as SymbolItem.LabelActionStyle
        assertEquals(
            KeyboardAction.CommitAction("X"),
            SymbolItemActionResolver.resolve(item),
        )
    }

    @Test
    fun invalidItemsAreDropped() {
        assertNull(SymbolItemParser.parseItem(element("\"\"")))
        assertNull(SymbolItemParser.parseItem(element("""{"value": "x"}""")))
        assertNull(SymbolItemParser.parseItem(element("""{"label": "X", "value": ""}""")))
        // action 项缺 styleName：整项丢弃（对端升级口径）。
        assertNull(
            SymbolItemParser.parseItem(element("""{"label": "X", "action": "resume"}""")),
        )
        assertNull(SymbolItemParser.parseItem(element("""42""")))
    }

    @Test
    fun dataSourceCountsDroppedItems() {
        val (items, dropped) = SymbolItemParser.parseDataSource(
            element("""["a", {"label": "b", "value": "B"}, {"label": "c"}, ""]"""),
        )
        assertEquals(2, items.size)
        assertEquals(2, dropped)
        assertTrue(items[1] is SymbolItem.LabelValue)
    }
}
