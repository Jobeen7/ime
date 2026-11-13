// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预编辑光标映射（WO38 E1-10/2）：纯函数分区——
 * 串内值一字不动（与旧内联直通逐值一致）／越界收敛。
 * 另经转换器分支验证：CompositionMessage 的 cursorPos 走本函数。
 */
class Wo38PreeditCursorTest {

    @Test
    fun inRange_identity_startMiddleEnd() {
        assertEquals(0, mapPreeditCursor("nihao", 0))
        assertEquals(3, mapPreeditCursor("nihao", 3))
        assertEquals(5, mapPreeditCursor("nihao", 5))
    }

    @Test
    fun emptyPreedit_alwaysZero() {
        assertEquals(0, mapPreeditCursor("", 0))
        assertEquals(0, mapPreeditCursor("", 4))
        assertEquals(0, mapPreeditCursor("", -2))
    }

    @Test
    fun negative_clampedToZero() {
        assertEquals(0, mapPreeditCursor("nihao", -1))
        assertEquals(0, mapPreeditCursor("nihao", Int.MIN_VALUE))
    }

    @Test
    fun overlong_clampedToLength() {
        assertEquals(5, mapPreeditCursor("nihao", 6))
        assertEquals(5, mapPreeditCursor("nihao", Int.MAX_VALUE))
    }

    @Test
    fun converter_compositionMessage_routesCursorThroughMapping() {
        val message = RimeMessage.CompositionMessage(
            CompositionProto(
                length = 5,
                cursorPos = 99,
                selStart = 5,
                selEnd = 5,
                preedit = "nihao",
            ),
        )
        val converted = EngineMessageConverter.convert(message)
        val composition = converted as com.jobeen.ime.engine.data.EngineMessage.Composition
        assertEquals("nihao", composition.preedit)
        assertEquals(5, composition.cursorPos)
    }

    @Test
    fun converter_compositionMessage_inRangeCursorUnchanged() {
        val message = RimeMessage.CompositionMessage(
            CompositionProto(
                length = 5,
                cursorPos = 2,
                selStart = 2,
                selEnd = 2,
                preedit = "nihao",
            ),
        )
        val converted = EngineMessageConverter.convert(message)
        val composition = converted as com.jobeen.ime.engine.data.EngineMessage.Composition
        assertEquals(2, composition.cursorPos)
    }
}
