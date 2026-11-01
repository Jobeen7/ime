// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.notify

import com.jobeen.ime.skin.model.OptionStateMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 板锁条件键（审计 G）：解析接受、$ 前缀归一、映射喂值、门控匹配。 */
class BoardLockConditionTest {

    private fun conditionArray(json: String): JsonArray =
        Json.parseToJsonElement(json) as JsonArray

    @Test
    fun parserAcceptsBothLockKeys() {
        val (parsed, dropped) = ConditionStyleParser.parse(
            conditionArray(
                """[
                    {"conditionKey": "symbolicKeyboardLockState", "conditionValue": true, "styleName": "locked"},
                    {"conditionKey": "emojiKeyboardLockState", "conditionValue": false, "styleName": "unlocked"}
                ]""",
            ),
        )
        assertEquals(0, dropped)
        assertEquals(2, parsed.size)
        assertEquals("symbolicKeyboardLockState", parsed[0].conditionKey)
        assertEquals("emojiKeyboardLockState", parsed[1].conditionKey)
    }

    @Test
    fun parserNormalizesDollarPrefixedLockKey() {
        val (parsed, dropped) = ConditionStyleParser.parse(
            conditionArray(
                """[{"conditionKey": "${'$'}symbolicKeyboardLockState", "conditionValue": true, "styleName": "locked"}]""",
            ),
        )
        assertEquals(0, dropped)
        assertEquals("symbolicKeyboardLockState", parsed[0].conditionKey)
    }

    @Test
    fun parserStillRejectsUnknownKeys() {
        val (_, dropped) = ConditionStyleParser.parse(
            conditionArray(
                """[{"conditionKey": "somethingElse", "conditionValue": true, "styleName": "x"}]""",
            ),
        )
        assertEquals(1, dropped)
    }

    @Test
    fun mapperFeedsLockKeysAlongsideSymbolLock() {
        val current = OptionStateMapper.mapConditionCurrent(
            conditionOptionNames = emptyList(),
            values = emptyMap(),
            symbolKeyboardActive = true,
            symbolicLocked = true,
            emojiLocked = false,
        )
        assertEquals(
            mapOf(
                "symbolLock" to ConditionCurrent.Bool(true),
                "symbolicKeyboardLockState" to ConditionCurrent.Bool(true),
                "emojiKeyboardLockState" to ConditionCurrent.Bool(false),
            ),
            current,
        )
    }

    @Test
    fun intConditionOnLockKeyNeverMatchesBoolCurrent() {
        // Int 形态无生产者：布尔现值对 Int 条件恒不成立（类型失配口径）。
        val entry = ConditionStyle(
            "symbolicKeyboardLockState",
            ConditionValue.Ints(setOf(1)),
            listOf("locked"),
        )
        assertTrue(
            !ConditionEvaluator.matches(entry, ConditionCurrent.Bool(true)),
        )
        assertTrue(
            ConditionEvaluator.matches(
                entry.copy(conditionValue = ConditionValue.Bool(true)),
                ConditionCurrent.Bool(true),
            ),
        )
    }
}
