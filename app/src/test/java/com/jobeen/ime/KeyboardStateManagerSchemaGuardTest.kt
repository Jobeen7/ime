// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 启动时序竞态回归：factory 未注入（键盘窗口未建立）时，引擎 Schema 消息到达不得崩溃。 */
class KeyboardStateManagerSchemaGuardTest {

    private fun reset() {
        KeyboardStateManager.onDestroy()
    }

    @Test
    fun schemaMessage_beforeAttach_doesNotThrow_andRecordsSchema() {
        reset()
        val schema = EngineMessage.Schema(id = "test_schema", name = "test", layout = "Qwerty")
        KeyboardStateManager.handleEngineMessage(schema)
        assertEquals("test_schema", KeyboardStateManager.getCurrentSchema()?.id)
        // 未建键盘：不得创建键盘实例（create 未被调用）。
        assertNull(KeyboardStateManager.get("Qwerty"))
    }

    @Test
    fun repeatedSchemaMessages_beforeAttach_doesNotThrow() {
        reset()
        repeat(3) {
            KeyboardStateManager.handleEngineMessage(
                EngineMessage.Schema(id = "s$it", name = "t$it", layout = "Qwerty")
            )
        }
        assertEquals("s2", KeyboardStateManager.getCurrentSchema()?.id)
        assertNull(KeyboardStateManager.get("Qwerty"))
    }
}
