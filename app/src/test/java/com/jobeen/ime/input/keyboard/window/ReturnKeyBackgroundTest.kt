package com.jobeen.ime.input.keyboard.window

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回车键底色分色（对端模型）：动作型走主题 accent，其余走默认灰；
 * 默认灰取对端浅色 systemButton 实测 #B7BCC4。
 * 视图刷新本身依赖 Android View，走真机验收。
 */
class ReturnKeyBackgroundTest {

    @Test
    fun actionTypesUseAccentBackground() {
        assertTrue(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_GO))
        assertTrue(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_SEARCH))
        assertTrue(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_SEND))
        assertTrue(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_NEXT))
        assertTrue(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_DONE))
    }

    @Test
    fun plainAndPreviousUseDefaultGray() {
        assertFalse(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertFalse(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_NONE))
        assertFalse(ReturnKeyTypeText.isAccentBackground(EditorInfo.IME_ACTION_PREVIOUS))
    }

    @Test
    fun defaultGrayMatchesSampledPeerValue() {
        assertEquals(0xFFB7BCC4.toInt(), ReturnKeyTypeText.defaultGray)
    }
}
