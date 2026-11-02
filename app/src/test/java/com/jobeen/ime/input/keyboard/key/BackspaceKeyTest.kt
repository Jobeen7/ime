package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退格键出厂行为（用户决定）：只有点按＋按住重复删字，
 * 无上下滑撤销／重做绑定（从来不是他的设定，已去掉）。
 */
class BackspaceKeyTest {

    @Test
    fun backspaceHasOnlyPressAndRepeatDelete() {
        val behaviors = backspaceKey().behaviors
        assertEquals(
            listOf(KeyboardAction.BackspaceAction),
            behaviors.filterIsInstance<KeyDef.Behavior.Press>().map { it.action },
        )
        assertEquals(
            listOf(KeyboardAction.BackspaceAction),
            behaviors.filterIsInstance<KeyDef.Behavior.Repeat>().map { it.action },
        )
        assertTrue(behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().isEmpty())
    }
}
