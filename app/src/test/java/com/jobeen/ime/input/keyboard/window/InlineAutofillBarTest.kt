package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联自动填充状态机与分区（纯函数）：条区覆盖层本身是 ViewGroup，
 * 在 JVM 存根上无法实例化，故只测决策与分区；挂载／度量／布局走
 * assembleDebug 编译证明＋真机验证。
 */
class InlineAutofillBarTest {

    @Test
    fun show_requiresSuggestionsAndIdleAndActiveField() {
        assertTrue(inlineAutofillShouldShow(true, false, true))
        assertFalse(inlineAutofillShouldShow(false, false, true))
        assertFalse(inlineAutofillShouldShow(true, true, true))
        assertFalse(inlineAutofillShouldShow(true, false, false))
        assertFalse(inlineAutofillShouldShow(false, true, false))
    }


}
