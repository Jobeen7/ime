// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import android.view.inputmethod.EditorInfo
import com.jobeen.ime.R
import com.jobeen.ime.input.EnterFieldAction
import com.jobeen.ime.input.EnterOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** 回车键显示映射（I-L2）：显示与行为同一来源，无动作不再谎称“换行”。 */
class ReturnKeyDisplayTest {

    @Test
    fun performActionMapsToActionIconAndLabel() {
        assertEquals(
            R.drawable.ic_keyboard_send to "发送",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.SEND), EditorInfo.IME_ACTION_SEND),
        )
        assertEquals(
            R.drawable.ic_keyboard_search to "搜索",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.SEARCH), EditorInfo.IME_ACTION_SEARCH),
        )
        assertEquals(
            R.drawable.ic_keyboard_go to "前往",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.GO), EditorInfo.IME_ACTION_GO),
        )
        assertEquals(
            R.drawable.ic_keyboard_done to "完成",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.DONE), EditorInfo.IME_ACTION_DONE),
        )
        assertEquals(
            R.drawable.ic_keyboard_arrow_right to "前往",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.NEXT), EditorInfo.IME_ACTION_NEXT),
        )
        assertEquals(
            R.drawable.ic_keyboard_arrow_left to "上一步",
            returnKeyDisplay(EnterOutcome.PerformAction(EnterFieldAction.PREVIOUS), EditorInfo.IME_ACTION_PREVIOUS),
        )
    }

    @Test
    fun noOpHasEmptyLabelNotNewline() {
        // 九宫格多行无动作：文案为空，不再谎称“换行”；图标沿用通用回车图标。
        assertEquals(
            R.drawable.ic_keyboard_return to "",
            returnKeyDisplay(EnterOutcome.NoOp, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }

    @Test
    fun insertNewlineAndCommitCandidateKeepNewlineLabel() {
        assertEquals(
            R.drawable.ic_keyboard_return to "换行",
            returnKeyDisplay(EnterOutcome.InsertNewline, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
        // 组字中点按上屏候选：沿旧口径显示换行图标／文案。
        assertEquals(
            R.drawable.ic_keyboard_return to "换行",
            returnKeyDisplay(EnterOutcome.CommitCandidate, EditorInfo.IME_ACTION_UNSPECIFIED),
        )
    }
}
