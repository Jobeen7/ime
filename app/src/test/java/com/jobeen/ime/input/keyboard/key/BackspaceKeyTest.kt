package com.jobeen.ime.input.keyboard.key

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 退格键出厂行为（用户规格，2026-11-02 定）：
 * 点按删一字、按住重复删字、上滑清空、下滑引擎撤销。
 * （.11 去掉滑动绑定的改法不对，已按此改回；测试随用户规格改，见报告。）
 */
class BackspaceKeyTest {

    @Test
    fun backspaceFactoryBehaviorsMatchUserSpec() {
        val behaviors = backspaceKey().behaviors
        assertEquals(
            listOf(KeyboardAction.BackspaceAction),
            behaviors.filterIsInstance<KeyDef.Behavior.Press>().map { it.action },
        )
        assertEquals(
            listOf(KeyboardAction.BackspaceAction),
            behaviors.filterIsInstance<KeyDef.Behavior.Repeat>().map { it.action },
        )
        val swipe = behaviors.filterIsInstance<KeyDef.Behavior.Swipe>().single()
        assertEquals(KeyboardAction.ClearAction, swipe.upAction)
        assertEquals(KeyboardAction.UndoAction, swipe.downAction)
    }
}
