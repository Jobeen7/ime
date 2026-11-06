// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.notify

import com.jobeen.ime.skin.model.OptionStateMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 板锁条件键已删（.22）：解析不再接受锁键、映射不再喂锁键。
 * 整数条件与布尔现值的类型失配口径仍在（与键名无关）。
 */
class BoardLockConditionTest {

    private fun conditionArray(json: String): JsonArray =
        Json.parseToJsonElement(json) as JsonArray

    @Test
    fun parserRejectsLockKeys() {
        val (parsed, dropped) = ConditionStyleParser.parse(
            conditionArray(
                """[
                    {"conditionKey": "symbolicKeyboardLockState", "conditionValue": true, "styleName": "locked"},
                    {"conditionKey": "emojiKeyboardLockState", "conditionValue": false, "styleName": "unlocked"}
                ]""",
            ),
        )
        assertEquals(2, dropped)
        assertEquals(0, parsed.size)
    }

    @Test
    fun parserRejectsDollarPrefixedLockKey() {
        val (parsed, dropped) = ConditionStyleParser.parse(
            conditionArray(
                """[{"conditionKey": "${'$'}symbolicKeyboardLockState", "conditionValue": true, "styleName": "locked"}]""",
            ),
        )
        assertEquals(1, dropped)
        assertEquals(0, parsed.size)
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
    fun mapperNoLongerFeedsLockKeys() {
        val current = OptionStateMapper.mapConditionCurrent(
            conditionOptionNames = emptyList(),
            values = emptyMap(),
            symbolKeyboardActive = true,
        )
        assertEquals(
            mapOf(
                "symbolLock" to ConditionCurrent.Bool(true),
            ),
            current,
        )
    }

    @Test
    fun intConditionOnBoolCurrentNeverMatches() {
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
