package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具板出厂行（纯函数）：行宽和、按钮文案与点击动作的单测。 */
class ToolBoardsTest {

    private fun rowWidths(layout: List<List<KeyDef>>): List<Float> =
        layout.map { row ->
            row.sumOf {
                ((it.appearance as KeyDef.Appearance.Text).percentWidth ?: 0f).toDouble()
            }.toFloat()
        }

    private fun pressActions(layout: List<List<KeyDef>>): List<KeyboardAction> =
        layout.flatten().map { key ->
            key.behaviors.filterIsInstance<KeyDef.Behavior.Press>().single().action
        }

    private fun texts(layout: List<List<KeyDef>>): List<String> =
        layout.flatten().map {
            (it.appearance as KeyDef.Appearance.Text).displayText
        }

    @Test
    fun layoutSwitchBoard_rowsSumToOneAndSwitchLayouts() {
        val layout = LayoutSwitchKeyboard.buildLayout()
        assertEquals(listOf(1f), rowWidths(layout))
        assertEquals(listOf("9键", "15键", "26键"), texts(layout))
        assertEquals(
            listOf(
                KeyboardAction.LayoutSwitchAction("T9"),
                KeyboardAction.LayoutSwitchAction("T15"),
                KeyboardAction.LayoutSwitchAction("Qwerty"),
            ),
            pressActions(layout),
        )
        // 面板钮只有点击动作，无长按误触（如布局键的 "/" 长按）。
        assertTrue(layout.flatten().all { key ->
            key.behaviors.none { it is KeyDef.Behavior.LongPress }
        })
    }

    @Test
    fun returnPathBoard_rowsSumToOneAndReturnActions() {
        val layout = ReturnPathKeyboard.buildLayout()
        assertEquals(listOf(1f), rowWidths(layout))
        assertEquals(listOf("返回", "主键盘"), texts(layout))
        assertEquals(
            listOf(
                KeyboardAction.ReturnLastAction,
                KeyboardAction.ReturnPrimaryAction,
            ),
            pressActions(layout),
        )
    }

    @Test
    fun floatPanelBoard_rowsSumToOneAndToolActions() {
        val layout = FloatPanelKeyboard.buildLayout()
        assertEquals(listOf(1f), rowWidths(layout))
        assertEquals(listOf("设置", "方案", "符号", "表情"), texts(layout))
        assertEquals(
            listOf(
                KeyboardAction.SettingsAction,
                KeyboardAction.RotateSchema,
                KeyboardAction.LayoutSwitchAction("Symbol"),
                KeyboardAction.LayoutSwitchAction("Emoji"),
            ),
            pressActions(layout),
        )
    }
}
