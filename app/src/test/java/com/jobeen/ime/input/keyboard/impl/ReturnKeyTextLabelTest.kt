// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import android.view.inputmethod.EditorInfo
import com.jobeen.ime.R
import com.jobeen.ime.input.EnterDecision
import com.jobeen.ime.input.EnterField
import com.jobeen.ime.input.EnterFieldAction
import com.jobeen.ime.input.EnterOutcome
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.RETURN_KEY_TEXT_LABEL_SIZE_DP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回车键组字中改文字键（WO35 I-L3 渲染，Jobeen 2026-11-13 定）：
 * 组字中只上屏（无动作）时默认图标回车键变文字键显示“确认”；
 * 组字中一按完成有动作时（WO37 CommitAndAct）显示动作图标
 * （纸飞机等不动）；非组字态一律图标。显示跟行为走。
 *
 * 覆盖分工：视图层切换（ImageKeyView.showTextLabel/showIcon）无
 * Robolectric、测不了实例化 View，JVM 只钉纯决策＋放得下预算＋
 * 布局定义；皮肤文字回车键仍走 enterTextSetters（本批未动该路，
 * 映射由 ReturnKeyDisplayTest／ReturnKeyConfirmLabelTest 钉住）；
 * 真机验收组字中键面与切回图标。
 */
class ReturnKeyTextLabelTest {

    // 组字中 → 文字键“确认”（T9：有专用换行键）。
    @Test
    fun composingWithDedicatedNewlineKeyShowsTextLabel() {
        val outcome = EnterDecision.decide(
            composing = true,
            field = EnterField(isMultiLine = true, noEnterAction = false, action = EnterFieldAction.UNSPECIFIED),
            hasDedicatedNewlineKey = true,
            isLongPress = false,
        )
        assertEquals(EnterOutcome.CommitCandidate, outcome)
        assertTrue(returnKeyShowsTextLabel(outcome))
        assertEquals(
            R.drawable.ic_keyboard_return to "确认",
            returnKeyDisplay(outcome, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }

    // 组字中＋SEND（Qwerty：无专用换行键）：一按完成，显示纸飞机
    // 图标、不变文字键（WO37，显示跟行为走）。
    @Test
    fun composingWithSendShowsIconNotTextLabel() {
        val outcome = EnterDecision.decide(
            composing = true,
            field = EnterField(isMultiLine = false, noEnterAction = false, action = EnterFieldAction.SEND),
            hasDedicatedNewlineKey = false,
            isLongPress = false,
        )
        assertEquals(EnterOutcome.CommitAndAct(EnterFieldAction.SEND), outcome)
        assertFalse(returnKeyShowsTextLabel(outcome))
        assertEquals(
            R.drawable.ic_keyboard_send to "发送",
            returnKeyDisplay(outcome, EditorInfo.IME_ACTION_SEND),
        )
    }

    // 组字中无动作（Qwerty 多行未声明动作）：只上屏，仍显示“确认”。
    @Test
    fun composingWithoutActionShowsTextLabel() {
        val outcome = EnterDecision.decide(
            composing = true,
            field = EnterField(isMultiLine = true, noEnterAction = false, action = EnterFieldAction.UNSPECIFIED),
            hasDedicatedNewlineKey = false,
            isLongPress = false,
        )
        assertEquals(EnterOutcome.CommitCandidate, outcome)
        assertTrue(returnKeyShowsTextLabel(outcome))
        assertEquals(
            R.drawable.ic_keyboard_return to "确认",
            returnKeyDisplay(outcome, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }

    // 组字中＋SEND（T9 多行）：一按完成，显示纸飞机图标（WO37）。
    @Test
    fun composingT9WithSendShowsPaperPlaneNotTextLabel() {
        val outcome = EnterDecision.decide(
            composing = true,
            field = EnterField(isMultiLine = true, noEnterAction = false, action = EnterFieldAction.SEND),
            hasDedicatedNewlineKey = true,
            isLongPress = false,
        )
        assertEquals(EnterOutcome.CommitAndAct(EnterFieldAction.SEND), outcome)
        assertFalse(returnKeyShowsTextLabel(outcome))
        assertEquals(
            R.drawable.ic_keyboard_send to "发送",
            returnKeyDisplay(outcome, EditorInfo.IME_ACTION_SEND),
        )
    }

    // SEND → 纸飞机图标（文字键不出现）。
    @Test
    fun sendShowsIconNotTextLabel() {
        val outcome = EnterOutcome.PerformAction(EnterFieldAction.SEND)
        assertFalse(returnKeyShowsTextLabel(outcome))
        assertEquals(
            R.drawable.ic_keyboard_send to "发送",
            returnKeyDisplay(outcome, EditorInfo.IME_ACTION_SEND),
        )
    }

    // SEARCH → 搜索图标；DONE → 完成图标。
    @Test
    fun searchAndDoneShowIcons() {
        assertFalse(returnKeyShowsTextLabel(EnterOutcome.PerformAction(EnterFieldAction.SEARCH)))
        assertEquals(
            R.drawable.ic_keyboard_search to "搜索",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.SEARCH), EditorInfo.IME_ACTION_SEARCH),
        )
        assertFalse(returnKeyShowsTextLabel(EnterOutcome.PerformAction(EnterFieldAction.DONE)))
        assertEquals(
            R.drawable.ic_keyboard_done to "完成",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.DONE), EditorInfo.IME_ACTION_DONE),
        )
    }

    // 插换行／无动作 → 回车箭头（文字键不出现）。
    @Test
    fun insertNewlineAndNoOpShowReturnArrow() {
        assertFalse(returnKeyShowsTextLabel(EnterOutcome.InsertNewline))
        assertEquals(
            R.drawable.ic_keyboard_return to "换行",
            returnKeyDisplay(EnterOutcome.InsertNewline, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
        assertFalse(returnKeyShowsTextLabel(EnterOutcome.NoOp))
        assertEquals(
            R.drawable.ic_keyboard_return to "",
            returnKeyDisplay(EnterOutcome.NoOp, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }

    // 按键行为（WO37）：组字中一按完成——无动作只上屏候选
    // （T9 多行未声明动作），有动作上屏＋执行
    // （Qwerty 单行 SEND → CommitAndAct）。
    @Test
    fun pressBehaviorWhileComposing() {
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(true, EnterField(true, false, EnterFieldAction.UNSPECIFIED), true, false),
        )
        assertEquals(
            EnterOutcome.CommitAndAct(EnterFieldAction.SEND),
            EnterDecision.decide(true, EnterField(false, false, EnterFieldAction.SEND), false, false),
        )
    }

    // 布局定义不动：T9/Qwerty 回车键仍是图标键（切换只在视图层做，
    // 布局定义与键宽一字未改）。
    @Test
    fun returnKeyDefsStillImageKeys() {
        val t9 = T9Keyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
            .flatten().first { it.id == KeyIds.T9.T9_ENTER }
        assertEquals(0.18f, t9.appearance.percentWidth, 0.0001f)
        val t9Image = t9.appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_return, t9Image.src)
        assertEquals(KeyView.button_return, t9Image.viewId)

        val qwerty = QwertyKeyboard.buildLayout(KeyDef.Appearance.Variant.Accent)
            .flatten().first { it.id == KeyIds.Qwerty.KEY_ENTER }
        assertEquals(0.15f, qwerty.appearance.percentWidth, 0.0001f)
        val qwertyImage = qwerty.appearance as KeyDef.Appearance.Image
        assertEquals(R.drawable.ic_keyboard_return, qwertyImage.src)
        assertEquals(KeyView.button_return, qwertyImage.viewId)
    }

    // “确认”放得下：S24 Ultra 竖屏（键盘 376dp＝屏宽 384dp − 两侧内边距各 4dp），生产字号。
    @Test
    fun confirmFitsT9AndQwerty() {
        assertTrue(confirmLabelFits(0.18f, 376f, RETURN_KEY_TEXT_LABEL_SIZE_DP))
        assertTrue(confirmLabelFits(0.15f, 376f, RETURN_KEY_TEXT_LABEL_SIZE_DP))
    }

    // “确认”放得下：窄屏 360dp（键盘 352dp），生产字号。
    @Test
    fun confirmFitsNarrowScreen() {
        assertTrue(confirmLabelFits(0.18f, 352f, RETURN_KEY_TEXT_LABEL_SIZE_DP))
        assertTrue(confirmLabelFits(0.15f, 352f, RETURN_KEY_TEXT_LABEL_SIZE_DP))
    }

    // 放得下边界：Qwerty 在 376dp 键盘上容得下 24dp 字号、容不下 26dp；
    // T9 容得下 29dp、容不下 30dp（皮肤 G1 大字号的上限口径）。
    @Test
    fun confirmFitThreshold() {
        assertTrue(confirmLabelFits(0.15f, 376f, 24f))
        assertFalse(confirmLabelFits(0.15f, 376f, 26f))
        assertTrue(confirmLabelFits(0.18f, 376f, 29f))
        assertFalse(confirmLabelFits(0.18f, 376f, 30f))
    }
}
