// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import com.jobeen.ime.engine.data.EngineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 核-O1 规格测试：断言均从“引擎消息推进 currentSchema、回声不重复刷、
 * 非回声必刷”推导，不从实现反推。测的是纯决策
 * [resolveEngineSchemaTarget]；switchTo 本身的去重早退与空格键
 * 文案写入绑定 Android View，留待协调员全量构建/测试覆盖，不在此
 * 用 Robolectric 模拟。
 * - 已知表命中：取表内项（补回补取失败消息缺失的 layout 等字段）；
 * - 未知方案：原样用消息；
 * - layout 为空时不虚构布局：已有键盘则以当前键盘为目标（只刷文案），
 *   尚无键盘则退回默认布局。
 */
class SpaceLabelRefreshTest {

    private val known = listOf(
        EngineMessage.Schema("t9", "方案甲", "T9", "FullWidth", "PinYin"),
        EngineMessage.Schema("qwerty", "方案乙", "Qwerty", "HalfWidth", "PinYin"),
    )

    @Test
    fun knownHit_returnsDiskEntryAndItsLayout() {
        // 补取失败的原始消息只有 id/name：layout 为空时仍须按表内项切布局。
        val message = EngineMessage.Schema("t9", "方案甲", "", "", "")
        val (resolved, target) = resolveEngineSchemaTarget(known, message, "T9", "Qwerty")
        assertEquals(known[0], resolved)
        assertEquals("FullWidth", resolved.punctuation)
        assertEquals("T9", target)
    }

    @Test
    fun unknownId_returnsMessageVerbatimAndItsLayout() {
        val message = EngineMessage.Schema("fresh", "新方案", "T9", "FullWidth", "PinYin")
        val (resolved, target) = resolveEngineSchemaTarget(known, message, "Qwerty", "Qwerty")
        assertEquals(message, resolved)
        assertEquals("T9", target)
    }

    @Test
    fun unknownEmptyLayout_keepsCurrentKeyboardAsTarget() {
        // 未知方案且 layout 未知：目标取当前键盘名，只刷文案，不错切 Qwerty。
        // 解析后 id 已变，switchTo 去重键 (目标, 新 id) 与旧键不等，故必刷。
        val message = EngineMessage.Schema("fresh", "新方案", "", "", "")
        val (resolved, target) = resolveEngineSchemaTarget(known, message, "T9", "Qwerty")
        assertEquals(message, resolved)
        assertEquals("T9", target)
    }

    @Test
    fun unknownEmptyLayoutWithoutKeyboard_fallsBackToDefault() {
        val message = EngineMessage.Schema("fresh", "新方案", "", "", "")
        val (_, target) = resolveEngineSchemaTarget(known, message, null, "Qwerty")
        assertEquals("Qwerty", target)
    }

    @Test
    fun echoOfAppliedSchema_resolvesToSamePairAsApplied() {
        // 回声：消息与已应用的表内项同 id，解析结果与已应用的 (方案, 布局)
        // 对完全一致；switchTo 去重键 (布局, 方案 id) 与 lastSwitchKey
        // 相等而早退，不重复刷文案、不回调 onKeyboardChanged。
        val message = EngineMessage.Schema("t9", "方案甲", "T9", "FullWidth", "PinYin")
        val (resolved, target) = resolveEngineSchemaTarget(known, message, "T9", "Qwerty")
        assertEquals(known[0], resolved)
        assertEquals("T9", target)
    }
}
