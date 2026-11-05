// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回车入队会话快照纯门控（2026-11-05 审计 #1 项 4）：不碰 Android
 * 框架方法，不用 Robolectric。门控本身的判定在此断言；队列暂停＋
 * 切换编辑器后放行的端到端时序（RimeEngine 串行 job）需真机／
 * 插桩验证，不在此断言——接线点只有 sendEnterJob 入口一处复检。
 */
class EnterSessionTest {

    private val fieldA = InputFieldKey(
        packageName = "com.example.app",
        fieldId = 1001,
        inputType = 0x00000001,
        imeOptions = 0x40000000,
    )
    private val fieldB = fieldA.copy(fieldId = 1002)

    @Test
    fun sameFieldAndEpoch_isCurrent() {
        val captured = EnterSession(fieldA, 7L)
        assertTrue(captured.isCurrent(fieldA, 7L))
    }

    @Test
    fun fieldChanged_isStale() {
        val captured = EnterSession(fieldA, 7L)
        assertFalse(captured.isCurrent(fieldB, 7L))
    }

    @Test
    fun epochAdvanced_isStale() {
        val captured = EnterSession(fieldA, 7L)
        assertFalse(captured.isCurrent(fieldA, 8L))
    }

    @Test
    fun inputFinished_fieldBecameNull_isStale() {
        // 输入结束（onFinishInputView 清 editorInfo）：身份变空，
        // 代次纵未推进，旧会话回车也不得执行。
        val captured = EnterSession(fieldA, 7L)
        assertFalse(captured.isCurrent(null, 7L))
    }

    @Test
    fun nullField_sameEpoch_isCurrent() {
        // 入队时即无输入框（无字段信息）：执行时仍无框，放行——
        // 此时 PerformAction(DONE) 对空连接为空操作，与旧口径一致。
        val captured = EnterSession(null, 3L)
        assertTrue(captured.isCurrent(null, 3L))
    }

    @Test
    fun nullVsField_isStale() {
        val captured = EnterSession(null, 3L)
        assertFalse(captured.isCurrent(fieldA, 3L))
    }
}
