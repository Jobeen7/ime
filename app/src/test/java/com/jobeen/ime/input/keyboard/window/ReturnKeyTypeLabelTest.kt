package com.jobeen.ime.input.keyboard.window

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回车键中文文案映射（自定义布局文字回车键跟 returnKeyType 用）：
 * 动作 → 文案；未指定／其他 → 换行。
 * 视图刷新本身依赖 Android View，走真机验收。
 */
class ReturnKeyTypeLabelTest {

    @Test
    fun labelsFollowImeActions() {
        assertEquals("前往", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_GO))
        assertEquals("发送", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_SEND))
        assertEquals("搜索", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_SEARCH))
        assertEquals("前往", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_NEXT))
        assertEquals("上一步", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_PREVIOUS))
        assertEquals("完成", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_DONE))
    }

    @Test
    fun unspecifiedFallsBackToNewline() {
        assertEquals("换行", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals("换行", ReturnKeyTypeText.labelFor(EditorInfo.IME_ACTION_NONE))
    }
}
