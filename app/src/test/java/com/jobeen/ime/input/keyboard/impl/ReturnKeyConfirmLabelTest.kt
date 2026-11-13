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
 * 是上屏候选，键面显示“确认”，只改显示、不改行为）。
 * EnterDecision 与按键行为一律不动；不恢复 useTextLabel（当前
 * T9/Qwerty 回车键均为 Image 键，改文字键会丢掉已定的纸飞机图标）。
 *
 * 既有测试冲突（按简报要求明说、不悄悄改）：
 * ReturnKeyDisplayTest.insertNewlineAndCommitCandidateKeepNewlineLabel
 * 钉死 CommitCandidate → “换行”，新行为下失败；须由协调员按
 * Jobeen 已定决定处理。
 *
 * 已知差距：默认回车键是 Image 键（textSetter 对它为 null），
 * “确认”文案只在皮肤定义的文字回车键上可见；要在默认图标键面上
 * 显示“确认”，须另定渲染方式（文字键／图标＋文字），且与主微信框
 * 的纸飞机图标冲突——需真机验收确认可见性口径。
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
