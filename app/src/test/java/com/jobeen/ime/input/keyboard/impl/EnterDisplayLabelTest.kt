package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.input.EnterFieldAction
import com.jobeen.ime.input.EnterOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回车显示文案（W19-2a）：组字中（上屏候选）显示“确认”；
 * 单行动作按编辑框动作；其余（插换行／无动作）显示“换行”。
 * 只改显示，行为（EnterDecision）不动。
 */
class EnterDisplayLabelTest {

    @Test
    fun composingShowsConfirm() {
        assertEquals("确认", enterDisplayLabel(EnterOutcome.CommitCandidate))
    }

    @Test
    fun insertNewlineAndNoOpShowNewline() {
        assertEquals("换行", enterDisplayLabel(EnterOutcome.InsertNewline))
        assertEquals("换行", enterDisplayLabel(EnterOutcome.NoOp))
    }

    @Test
    fun performActionShowsActionLabel() {
        assertEquals("搜索", enterDisplayLabel(EnterOutcome.PerformAction(EnterFieldAction.SEARCH)))
        assertEquals("发送", enterDisplayLabel(EnterOutcome.PerformAction(EnterFieldAction.SEND)))
        assertEquals("前往", enterDisplayLabel(EnterOutcome.PerformAction(EnterFieldAction.GO)))
        assertEquals("前往", enterDisplayLabel(EnterOutcome.PerformAction(EnterFieldAction.NEXT)))
        assertEquals("上一步", enterDisplayLabel(EnterOutcome.PerformAction(EnterFieldAction.PREVIOUS)))
        assertEquals("完成", enterDisplayLabel(EnterOutcome.PerformAction(EnterFieldAction.DONE)))
        assertEquals("换行", enterDisplayLabel(EnterOutcome.PerformAction(EnterFieldAction.UNSPECIFIED)))
    }
}
