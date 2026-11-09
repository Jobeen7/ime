// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.input.InputFieldKey
import com.jobeen.ime.input.shouldConsumePendingFieldChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待投递字段变化消费门（W17-6）纯决策单测：onStartInputView 只在
 * 展示字段相对上次已展示字段真变时才消费 pending；跨会话残留的
 * pending 不在同框重启时误触发新窗口复位。均不依赖 Android 框架。
 */
class FieldChangePendingConsumeTest {

    private val fieldA = InputFieldKey(
        packageName = "com.example.app",
        fieldId = 1001,
        inputType = 0x00000001,
        imeOptions = 0x40000000,
    )
    private val fieldB = fieldA.copy(fieldId = 1002)

    @Test
    fun noPending_neverConsumes() {
        assertFalse(shouldConsumePendingFieldChange(false, null, fieldA))
        assertFalse(shouldConsumePendingFieldChange(false, fieldA, fieldA))
        assertFalse(shouldConsumePendingFieldChange(false, fieldA, fieldB))
    }

    @Test
    fun pending_firstShow_consumes() {
        // 首次展示：无上次已展示字段可比，按变化消费（保旧行为）
        assertTrue(shouldConsumePendingFieldChange(true, null, fieldA))
    }

    @Test
    fun pending_unknownShown_consumes() {
        // 展示字段未知：无法比对，按变化消费（保旧行为）
        assertTrue(shouldConsumePendingFieldChange(true, fieldA, null))
    }

    @Test
    fun pending_sameFieldShown_doesNotConsume() {
        // 残留 pending 的同框重启：展示字段与上次已展示字段相同，不消费
        assertFalse(shouldConsumePendingFieldChange(true, fieldA, fieldA))
    }

    @Test
    fun pending_differentFieldShown_consumes() {
        assertTrue(shouldConsumePendingFieldChange(true, fieldA, fieldB))
    }
}
