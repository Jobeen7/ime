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
        assertNull(SymbolItemParser.parseItem(element("""{"label": "X"}""")))
        assertNull(SymbolItemParser.parseItem(element("""42""")))
    }

    @Test
    fun actionItemWithoutStyleNameParsesWithEmptyStyle() {
        val item = SymbolItemParser.parseItem(
            element("""{"label": "X", "action": "resume"}"""),
        ) as SymbolItem.LabelActionStyle
        assertEquals("", item.styleName)
        assertEquals(KeyboardAction.ResumeAction, SymbolItemActionResolver.resolve(item))
        val emptyNamed = SymbolItemParser.parseItem(
            element("""{"label": "X", "action": "resume", "styleName": ""}"""),
        ) as SymbolItem.LabelActionStyle
        assertEquals("", emptyNamed.styleName)
    }

    @Test
    fun actionAlwaysComesFromDataSourceNeverFromStyle() {
        // 样式名未知、缺省皆不影响分发：动作只由数据项的 action 定。
        val unknownStyle = SymbolItem.LabelActionStyle(
            label = "返回", action = "resume", styleName = "nonexistent_style",
        )
        assertEquals(
            KeyboardAction.ResumeAction,
            SymbolItemActionResolver.resolve(unknownStyle),
        )
        val noStyle = SymbolItem.LabelActionStyle(
            label = "锁定", action = "symbolic_lock_toggle", styleName = "",
        )
        // 板锁已删（.22）：锁切换名按未知非 # 动作口径回落提交 label。
        assertEquals(
            KeyboardAction.CommitAction("锁定"),
            SymbolItemActionResolver.resolve(noStyle),
        )
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
