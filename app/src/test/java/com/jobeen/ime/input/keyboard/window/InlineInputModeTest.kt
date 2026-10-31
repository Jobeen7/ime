package com.jobeen.ime.input.keyboard.window

import com.jobeen.ime.engine.data.EngineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内嵌输入模式组字串拼接（纯函数）：多段拼一串，空串直送即清组字区；
 * IC 调用与药丸显隐本身是 Android 视图／输入连接行为，走真机验证。
 */
class InlineInputModeTest {

    private fun item(text: String) = EngineMessage.DynamicPreedit.DynamicPreeditItem(
        text,
        EngineMessage.DynamicPreedit.DynamicPreeditType.Normal,
    )

    @Test
    fun joined_concatenatesSegmentsWithoutSeparator() {
        assertEquals(
            "nihao",
            inlinePreeditJoined(listOf(item("ni"), item("hao"))),
        )
    }

    @Test
    fun joined_emptyListIsEmpty() {
        assertEquals("", inlinePreeditJoined(emptyList()))
    }

    @Test
    fun joined_singleSegmentUnchanged() {
        assertEquals("ni", inlinePreeditJoined(listOf(item("ni"))))
    }
}
