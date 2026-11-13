// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import android.view.inputmethod.EditorInfo
import com.jobeen.ime.R
import com.jobeen.ime.input.EnterFieldAction
import com.jobeen.ime.input.EnterOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回车键组字中显示“确认”（I-L3，Jobeen 已定决定：组字中点按回车
 * 是上屏候选，键面显示“确认”）。2026-11-13 用户定：组字中一律显示
 * “确认”，无论有无动作（CommitAndAct 行为仍是一按上屏＋执行动作，
 * 只改显示）。EnterDecision 与按键行为一律不动；不恢复 useTextLabel
 * （当前 T9/Qwerty 回车键均为 Image 键，改文字键会丢掉已定的纸飞机图标）。
 *
 * 既有测试冲突（按简报要求明说、不悄悄改）：
 * ReturnKeyDisplayTest.insertNewlineAndCommitCandidateKeepNewlineLabel
 * 钉死 CommitCandidate → “换行”，新行为下失败；须由协调员按
 * Jobeen 已定决定处理。
 *
 * WO35 渲染：默认图标回车键在组字态变文字键（ImageKeyView 文字标签，
 * 见 ReturnKeyTextLabelTest），映射本身仍是本文件所钉的“确认”；
 * 真机验收确认可见性口径（组字中键面是否显示“确认”、切回图标是否正常）。
 */
class ReturnKeyConfirmLabelTest {

    @Test
    fun commitCandidateShowsConfirm() {
        assertEquals(
            R.drawable.ic_keyboard_return to "确认",
            returnKeyDisplay(EnterOutcome.CommitCandidate, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }

    @Test
    fun commitAndActShowsConfirm() {
        // 组字中一按完成有动作时同样显示“确认”（2026-11-13 用户定）；
        // 行为仍是上屏＋执行动作，见 EnterDecisionTest。
        assertEquals(
            R.drawable.ic_keyboard_send to "确认",
            returnKeyDisplay(EnterOutcome.CommitAndAct(EnterFieldAction.SEND), EditorInfo.IME_ACTION_SEND),
        )
    }

    @Test
    fun insertNewlineStillShowsNewline() {
        assertEquals(
            R.drawable.ic_keyboard_return to "换行",
            returnKeyDisplay(EnterOutcome.InsertNewline, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }

    @Test
    fun noOpStillEmpty() {
        assertEquals(
            R.drawable.ic_keyboard_return to "",
            returnKeyDisplay(EnterOutcome.NoOp, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }

    @Test
    fun performActionUnchanged() {
        assertEquals(
            R.drawable.ic_keyboard_send to "发送",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.SEND), EditorInfo.IME_ACTION_SEND),
        )
    }
}
