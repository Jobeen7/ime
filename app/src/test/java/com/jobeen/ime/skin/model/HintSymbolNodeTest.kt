// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 长按符号全节点解析：文本＋动作，非法动作整节点丢弃。 */
class HintSymbolNodeTest {

    private fun parse(json: String): HintSymbolNode? =
        HintSymbolNodeParser.parse(Json.parseToJsonElement(json))

    @Test
    fun parsesTextAndSymbolAction() {
        val node = parse(
            """{"text": "↑", "action": {"type": "symbol", "value": "↑"}}""",
        )!!
        assertEquals("↑", node.text)
        assertEquals("↑", node.displayText)
        assertEquals(
            SkinAction.Command(WhitelistAction.SYMBOL, "↑"),
            node.action,
        )
    }

    @Test
    fun missingTextFallsBackToSymbolLiteral() {
        val node = parse(
            """{"action": {"type": "symbol", "value": "、"}}""",
        )!!
        assertNull(node.text)
        assertEquals("、", node.displayText)
    }

    @Test
    fun nonLiteralActionDisplaysBlank() {
        val node = parse(
            """{"action": {"type": "command", "action": "#cut"}}""",
        )!!
        assertEquals("", node.displayText)
    }

    @Test
    fun missingOrInvalidActionDropsNode() {
        assertNull(parse("""{"text": "↑"}"""))
        assertNull(parse("""{"text": "↑", "action": {"type": "sequence"}}"""))
        assertNull(parse("""{"text": "↑", "action": {"type": "nope"}}"""))
        assertNull(parse(""""↑""""))
    }
}
