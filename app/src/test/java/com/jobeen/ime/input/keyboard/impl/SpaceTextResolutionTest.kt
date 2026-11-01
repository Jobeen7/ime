package com.jobeen.ime.input.keyboard.impl

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 空格文案决议（空格标跟皮肤走）：皮肤空格文案非空时用皮肤文案；
 * 皮肤未定义（空串/缺失）时回落方案名（现行行为）。
 * 视图刷新本身依赖 Android View，走真机验收。
 */
class SpaceTextResolutionTest {

    @Test
    fun skinTextWinsWhenNonEmpty() {
        assertEquals("空格", BaseKeyboard.resolveSpaceText("空格", "万象拼音"))
    }

    @Test
    fun emptySkinTextFallsBackToSchemeName() {
        assertEquals("万象拼音", BaseKeyboard.resolveSpaceText("", "万象拼音"))
    }

    @Test
    fun missingSkinTextFallsBackToSchemeName() {
        assertEquals("万象拼音", BaseKeyboard.resolveSpaceText(null, "万象拼音"))
    }
}
