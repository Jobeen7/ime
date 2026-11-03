// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

/** 回车键精简决策表逐行覆盖（纯决策，不碰 Android 框架方法）。 */
class EnterDecisionTest {

    private fun field(
        isMultiLine: Boolean = false,
        noEnterAction: Boolean = false,
        action: EnterFieldAction = EnterFieldAction.UNSPECIFIED,
    ) = EnterField(isMultiLine, noEnterAction, action)

    @Test
    fun composingBeatsEverything() {
        // 组字中优先于动作、长按、专用换行键三者。
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(true, field(action = EnterFieldAction.SEARCH), false, false),
        )
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(true, field(action = EnterFieldAction.SEARCH), false, true),
        )
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(
                true, field(isMultiLine = true, action = EnterFieldAction.SEARCH), true, false,
            ),
        )
    }

    @Test
    fun singleLineDeclaredActionPerformsThatAction() {
        val declared = listOf(
            EnterFieldAction.SEARCH,
            EnterFieldAction.SEND,
            EnterFieldAction.GO,
            EnterFieldAction.NEXT,
            EnterFieldAction.PREVIOUS,
            EnterFieldAction.DONE,
        )
        for (action in declared) {
            assertEquals(
                EnterOutcome.PerformAction(action),
                EnterDecision.decide(false, field(action = action), false, false),
            )
            // 九宫格点按同口径（专用换行键不影响单行动作）。
            assertEquals(
                EnterOutcome.PerformAction(action),
                EnterDecision.decide(false, field(action = action), true, false),
            )
        }
    }

    @Test
    fun singleLineUndeclaredActionPerformsDone() {
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.DONE),
            EnterDecision.decide(false, field(), false, false),
        )
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.DONE),
            EnterDecision.decide(false, field(), true, false),
        )
    }

    @Test
    fun multiLineWithNoEnterActionPerformsActionOrDone() {
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.SEND),
            EnterDecision.decide(
                false,
                field(isMultiLine = true, noEnterAction = true, action = EnterFieldAction.SEND),
                false,
                false,
            ),
        )
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.DONE),
            EnterDecision.decide(
                false,
                field(isMultiLine = true, noEnterAction = true),
                true,
                false,
            ),
        )
    }

    @Test
    fun multiLineWithoutFlagInsertsNewlineOnlyWithoutDedicatedKey() {
        // 非九宫格（26 键等）：多行无标志点按换行。
        assertEquals(
            EnterOutcome.InsertNewline,
            EnterDecision.decide(false, field(isMultiLine = true), false, false),
        )
        // 九宫格对照：多行无标志点按无动作，换行走专用换行键。
        assertEquals(
            EnterOutcome.NoOp,
            EnterDecision.decide(false, field(isMultiLine = true), true, false),
        )
        // 九宫格多行无标志时即使声明了动作也不发动作（无标志即换行口径，
        // 九宫格上换行功能已去，落点为无动作）。
        assertEquals(
            EnterOutcome.NoOp,
            EnterDecision.decide(
                false,
                field(isMultiLine = true, action = EnterFieldAction.SEND),
                true,
                false,
            ),
        )
    }

    @Test
    fun longPressWithoutDedicatedKeyIsNewlineExceptComposing() {
        // 无专用换行键时长按等于插换行（手机上的 Shift+Enter 口子），
        // 覆盖单行动作与多行不要换行标志两种点按会发动作的情形。
        assertEquals(
            EnterOutcome.InsertNewline,
            EnterDecision.decide(
                false, field(action = EnterFieldAction.SEARCH), false, true,
            ),
        )
        assertEquals(
            EnterOutcome.InsertNewline,
            EnterDecision.decide(
                false,
                field(isMultiLine = true, noEnterAction = true, action = EnterFieldAction.SEND),
                false,
                true,
            ),
        )
    }

    @Test
    fun longPressWithDedicatedKeyEqualsTap() {
        // 九宫格长按同点按：单行发动作、多行无标志无动作。
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.SEARCH),
            EnterDecision.decide(
                false, field(action = EnterFieldAction.SEARCH), true, true,
            ),
        )
        assertEquals(
            EnterOutcome.NoOp,
            EnterDecision.decide(false, field(isMultiLine = true), true, true),
        )
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.DONE),
            EnterDecision.decide(false, field(), true, true),
        )
    }
}
