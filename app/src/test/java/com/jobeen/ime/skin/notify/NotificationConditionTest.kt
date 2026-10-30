// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.notify

import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态驱动通知＋条件样式（重点候选 8-1）：锁定语义、门控匹配、解析丢弃。 */
class NotificationConditionTest {

    private fun notificationArray(json: String): JsonArray =
        Json.parseToJsonElement(json) as JsonArray

    private fun conditionArray(json: String): JsonArray =
        Json.parseToJsonElement(json) as JsonArray

    @Test
    fun latch_mismatchNeverClears() {
        val (rules, dropped) = SkinNotificationParser.parse(
            notificationArray(
                """[
                    {"source": "rime_option", "matchKey": "ascii_mode", "matchValue": "true",
                     "targetStyle": "accent", "backgroundStyle": "#FF0000"},
                    {"source": "rime_option", "matchKey": "ascii_mode", "matchValue": "false",
                     "targetStyle": "accent", "backgroundStyle": "#0000FF"}
                ]""",
            ),
        )
        assertEquals(0, dropped)
        val state = NotificationState(rules)
        assertTrue(state.onEvent(NotificationEvent(NotificationSource.RIME_OPTION, "ascii_mode", "true")))
        assertEquals("#FF0000", state.latchedFor("accent")?.backgroundStyle)
        // 失配事件（另一选项变化）不清除锁定
        assertFalse(state.onEvent(NotificationEvent(NotificationSource.RIME_OPTION, "full_shape", "true")))
        assertEquals("#FF0000", state.latchedFor("accent")?.backgroundStyle)
        // 同目标的新命中替换旧锁定
        assertTrue(state.onEvent(NotificationEvent(NotificationSource.RIME_OPTION, "ascii_mode", "false")))
        assertEquals("#0000FF", state.latchedFor("accent")?.backgroundStyle)
        assertNull(state.latchedFor("other"))
    }

    @Test
    fun preedit_matchesWithoutKeyOrValue() {
        val (rules, dropped) = SkinNotificationParser.parse(
            notificationArray(
                """[{"source": "preedit", "targetStyle": "accent",
                    "action": {"type": "command", "action": "caps"}}]""",
            ),
        )
        assertEquals(0, dropped)
        val state = NotificationState(rules)
        assertTrue(state.onEvent(NotificationEvent(NotificationSource.PREEDIT, null, null)))
        assertEquals(
            SkinAction.Command(WhitelistAction.CAPS),
            state.latchedFor("accent")?.action,
        )
    }

    @Test
    fun parser_dropsBadEntriesAndBadActionFields() {
        val (rules, dropped) = SkinNotificationParser.parse(
            notificationArray(
                """[
                    {"source": "nope", "targetStyle": "a"},
                    {"source": "preedit", "matchKey": "x", "targetStyle": "b"},
                    {"source": "scheme", "targetStyle": ""},
                    {"source": "scheme", "targetStyle": "c",
                     "action": {"type": "command", "action": "voice_input"},
                     "swipeUpAction": {"type": "command", "action": "nope"},
                     "backgroundStyle": "#123456"}
                ]""",
            ),
        )
        assertEquals(3, dropped)
        assertEquals(1, rules.size)
        assertEquals("c", rules[0].targetStyle)
        // 非法动作字段只丢字段，样式与整项保留
        assertNull(rules[0].action)
        assertNull(rules[0].swipeUpAction)
        assertEquals("#123456", rules[0].backgroundStyle)
    }

    @Test
    fun parser_actionValueRules() {
        val (rules, _) = SkinNotificationParser.parse(
            notificationArray(
                """[
                    {"source": "scheme", "targetStyle": "a",
                     "action": {"type": "command", "action": "switch_schema", "value": "wanxiang"}},
                    {"source": "scheme", "targetStyle": "b",
                     "action": {"type": "command", "action": "switch_schema"}},
                    {"source": "scheme", "targetStyle": "c",
                     "action": {"type": "command", "action": "caps", "value": "x"}},
                    {"source": "scheme", "targetStyle": "d",
                     "action": {"type": "command", "action": "switch_layout", "value": "T9"}}
                ]""",
            ),
        )
        assertEquals(4, rules.size)
        assertEquals(
            SkinAction.Command(WhitelistAction.SWITCH_SCHEMA, "wanxiang"),
            rules[0].action,
        )
        assertNull(rules[1].action) // REQUIRED 动作缺 value → 字段丢弃
        assertNull(rules[2].action) // FORBIDDEN 动作带 value → 字段丢弃
        assertEquals(
            SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "T9"),
            rules[3].action,
        )
    }

    @Test
    fun conditionEvaluator_boolAndIntArray() {
        val (entries, dropped) = ConditionStyleParser.parse(
            conditionArray(
                """[
                    {"conditionKey": "rime${'$'}ascii_mode", "conditionValue": true,
                     "styleName": "accent"},
                    {"conditionKey": "symbolLock", "conditionValue": [1, 2],
                     "styleName": ["lock_a", "lock_b"]},
                    {"conditionKey": "rime${'$'}full_shape", "conditionValue": false,
                     "styleName": "half"}
                ]""",
            ),
        )
        assertEquals(0, dropped)
        val current = mapOf(
            "rime\$ascii_mode" to ConditionCurrent.Bool(true),
            "symbolLock" to ConditionCurrent.IntValue(2),
            "rime\$full_shape" to ConditionCurrent.Bool(true),
        )
        val active = ConditionEvaluator.activeStyleNames(entries, current)
        assertEquals(setOf("accent", "lock_a", "lock_b"), active)
        // 类型失配（布尔条件对整数现值）视为不成立
        assertFalse(
            ConditionEvaluator.matches(
                entries[0], ConditionCurrent.IntValue(1),
            ),
        )
        // 未知现值键 → 不成立
        assertFalse(
            ConditionEvaluator.matches(
                entries[0], null,
            ),
        )
    }

    @Test
    fun conditionParser_dropsBadEntries() {
        val (entries, dropped) = ConditionStyleParser.parse(
            conditionArray(
                """[
                    {"conditionKey": "unknown_key", "conditionValue": true, "styleName": "a"},
                    {"conditionKey": "rime${'$'}", "conditionValue": true, "styleName": "b"},
                    {"conditionKey": "rime${'$'}ascii_mode", "conditionValue": [], "styleName": "c"},
                    {"conditionKey": "rime${'$'}ascii_mode", "conditionValue": "yes", "styleName": "d"},
                    {"conditionKey": "rime${'$'}ascii_mode", "conditionValue": true, "styleName": ""},
                    {"conditionKey": "symbolLock", "conditionValue": [0], "styleName": "ok"}
                ]""",
            ),
        )
        assertEquals(5, dropped)
        assertEquals(1, entries.size)
        assertEquals("ok", entries[0].styleNames.single())
    }
}
