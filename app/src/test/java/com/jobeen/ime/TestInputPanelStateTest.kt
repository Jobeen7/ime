// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.ui.screen.TestInputMode
import com.jobeen.ime.ui.screen.TestInputPanelState
import com.jobeen.ime.ui.screen.testInputImeHintVisible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试输入面板开合状态机：开合、输入类型、进子页收面板、非 Jime 提示可见条件。 */
class TestInputPanelStateTest {

    @Test
    fun openThenClose_tracksIsOpen() {
        val state = TestInputPanelState()
        assertFalse(state.isOpen)
        state.open()
        assertTrue(state.isOpen)
        state.close()
        assertFalse(state.isOpen)
    }

    @Test
    fun modeDefault_isText() {
        val state = TestInputPanelState()
        assertEquals(TestInputMode.TEXT, state.mode)
    }

    @Test
    fun modeAssignment_changesMode() {
        val state = TestInputPanelState()
        state.mode = TestInputMode.NUMBER
        assertEquals(TestInputMode.NUMBER, state.mode)
        state.mode = TestInputMode.PASSWORD
        assertEquals(TestInputMode.PASSWORD, state.mode)
    }

    @Test
    fun modePersistsAcrossClose() {
        val state = TestInputPanelState()
        state.mode = TestInputMode.NUMBER
        state.open()
        state.close()
        assertEquals(TestInputMode.NUMBER, state.mode)
    }

    @Test
    fun navigateAway_closesPanel() {
        val state = TestInputPanelState()
        state.open()
        state.onNavigateAway()
        assertFalse(state.isOpen)
    }

    @Test
    fun reopenAfterClose_works() {
        val state = TestInputPanelState()
        state.open()
        state.close()
        state.open()
        assertTrue(state.isOpen)
    }

    @Test
    fun imeHintVisible_onlyWhenOpenAndNotConfigured() {
        assertTrue(testInputImeHintVisible(panelOpen = true, isImeConfigured = false))
        assertFalse(testInputImeHintVisible(panelOpen = true, isImeConfigured = true))
        assertFalse(testInputImeHintVisible(panelOpen = false, isImeConfigured = false))
        assertFalse(testInputImeHintVisible(panelOpen = false, isImeConfigured = true))
    }
}
