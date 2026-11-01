package com.jobeen.ime.skin.model

import com.jobeen.ime.skin.model.StyleStatePriority.SwipeDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 样式状态优先级 §5 全序：前景（滑动＞锁定＞大写＞基础）与动作（组字＞大写＞普通）。 */
class StyleStatePriorityTest {

    @Test
    fun foregroundSwipeBeatsCapsAndBase() {
        assertEquals(
            0xFF111111.toInt(),
            StyleStatePriority.foregroundColor(
                swipeUpColor = 0xFF111111.toInt(), swipeDownColor = null,
                capsLockedColor = 0xFF222222.toInt(), uppercasedColor = 0xFF333333.toInt(),
                swipeDir = SwipeDir.UP, isCapsLocked = true, isUppercased = true,
            ),
        )
        assertEquals(
            0xFF444444.toInt(),
            StyleStatePriority.foregroundColor(
                swipeUpColor = null, swipeDownColor = 0xFF444444.toInt(),
                capsLockedColor = 0xFF222222.toInt(), uppercasedColor = 0xFF333333.toInt(),
                swipeDir = SwipeDir.DOWN, isCapsLocked = true, isUppercased = true,
            ),
        )
    }

    @Test
    fun foregroundCapsLockedBeatsUppercased() {
        assertEquals(
            0xFF222222.toInt(),
            StyleStatePriority.foregroundColor(
                swipeUpColor = null, swipeDownColor = null,
                capsLockedColor = 0xFF222222.toInt(), uppercasedColor = 0xFF333333.toInt(),
                swipeDir = SwipeDir.NONE, isCapsLocked = true, isUppercased = true,
            ),
        )
        // 锁定色缺失时退大写色（不跳回默认）。
        assertEquals(
            0xFF333333.toInt(),
            StyleStatePriority.foregroundColor(
                swipeUpColor = null, swipeDownColor = null,
                capsLockedColor = null, uppercasedColor = 0xFF333333.toInt(),
                swipeDir = SwipeDir.NONE, isCapsLocked = true, isUppercased = true,
            ),
        )
        // 非锁定大写态只取大写色。
        assertEquals(
            0xFF333333.toInt(),
            StyleStatePriority.foregroundColor(
                swipeUpColor = null, swipeDownColor = null,
                capsLockedColor = 0xFF222222.toInt(), uppercasedColor = 0xFF333333.toInt(),
                swipeDir = SwipeDir.NONE, isCapsLocked = false, isUppercased = true,
            ),
        )
        assertNull(
            StyleStatePriority.foregroundColor(
                null, null, null, null,
                SwipeDir.NONE, isCapsLocked = false, isUppercased = false,
            ),
        )
    }

    @Test
    fun actionPreeditBeatsUppercasedBeatsBase() {
        assertEquals(
            "preedit",
            StyleStatePriority.action("preedit", "upper", "base", true, true),
        )
        // 组字中无 preedit 动作时不跳过大写（两态独立）。
        assertEquals(
            "upper",
            StyleStatePriority.action(null, "upper", "base", true, true),
        )
        assertEquals(
            "upper",
            StyleStatePriority.action(null, "upper", "base", false, true),
        )
        assertEquals(
            "base",
            StyleStatePriority.action(null, null, "base", true, true),
        )
    }
}
