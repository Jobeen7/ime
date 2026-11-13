// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // 组字中一按完成（WO37）：有动作则上屏＋执行动作，点按长按
        // 一样、字段与专用键都不影响“有动作就执行”这一半。
        assertEquals(
            EnterOutcome.CommitAndAct(EnterFieldAction.SEARCH),
            EnterDecision.decide(true, field(action = EnterFieldAction.SEARCH), false, false),
        )
        assertEquals(
            EnterOutcome.CommitAndAct(EnterFieldAction.SEARCH),
            EnterDecision.decide(true, field(action = EnterFieldAction.SEARCH), false, true),
        )
        assertEquals(
            EnterOutcome.CommitAndAct(EnterFieldAction.SEARCH),
            EnterDecision.decide(
                true, field(isMultiLine = true, action = EnterFieldAction.SEARCH), true, false,
            ),
        )
    }

    @Test
    fun composingWithoutActionOnlyCommitsCandidate() {
        // 组字中无动作时只上屏候选（WO37）：多行未声明动作、带不要
        // 换行标志时都没有动作可执行，长按不改变它；单行未声明动作
        // 时动作是 DONE（非组字分支同一口径），故上屏＋执行 DONE。
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(
                true,
                field(isMultiLine = true, action = EnterFieldAction.UNSPECIFIED),
                false,
                false,
            ),
        )
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(
                true,
                field(isMultiLine = true, action = EnterFieldAction.UNSPECIFIED),
                true,
                true,
            ),
        )
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(
                true,
                field(isMultiLine = true, noEnterAction = true, action = EnterFieldAction.SEND),
                false,
                false,
            ),
        )
        assertEquals(
            EnterOutcome.CommitCandidate,
            EnterDecision.decide(
                true,
                field(isMultiLine = true, noEnterAction = true, action = EnterFieldAction.SEND),
                true,
                true,
            ),
        )
        assertEquals(
            EnterOutcome.CommitAndAct(EnterFieldAction.DONE),
            EnterDecision.decide(
                true,
                field(action = EnterFieldAction.UNSPECIFIED),
                false,
                false,
            ),
        )
    }

    @Test
    fun composingActionMatrix() {
        // 组字中一按完成（WO37）全矩阵：动作口径与非组字分支同一来源
        // ——单行恒有动作（未声明→DONE）；多行仅未带标志且声明了
        // 动作时有动作；其余只上屏。
        val actions = listOf(
            EnterFieldAction.SEARCH,
            EnterFieldAction.SEND,
            EnterFieldAction.GO,
            EnterFieldAction.NEXT,
            EnterFieldAction.PREVIOUS,
            EnterFieldAction.DONE,
            EnterFieldAction.UNSPECIFIED,
        )
        for (action in actions) {
            for (multiLine in listOf(false, true)) {
                for (noEnterAction in listOf(false, true)) {
                    for (dedicated in listOf(false, true)) {
                        val expectedAction: EnterFieldAction? =
                            if (!multiLine) {
                                if (action == EnterFieldAction.UNSPECIFIED) {
                                    EnterFieldAction.DONE
                                } else {
                                    action
                                }
                            } else if (!noEnterAction && action != EnterFieldAction.UNSPECIFIED) {
                                action
                            } else {
                                null
                            }
                        val expected: EnterOutcome =
                            expectedAction?.let { EnterOutcome.CommitAndAct(it) }
                                ?: EnterOutcome.CommitCandidate
                        assertEquals(
                            expected,
                            EnterDecision.decide(
                                true,
                                field(
                                    isMultiLine = multiLine,
                                    noEnterAction = noEnterAction,
                                    action = action,
                                ),
                                dedicated,
                                false,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun t9NeverInsertsNewline() {
        // 九宫格回车永不插换行（2026-11-13 用户定，只限九宫格）：
        // 决策表现有 hasDedicatedNewlineKey 分支已实现，本测试只钉
        // 住行为——组字中／单行／多行有动作／多行无动作／长按，九宫格
        // 一律走不到 InsertNewline 分支。26 键等无专用键键盘保留
        // 原样，对照见
        // multiLineWithoutFlagInsertsNewlineOnlyWithoutDedicatedKey
        // 与 longPressWithoutDedicatedKeyIsNewlineExceptComposing。
        val actions = listOf(
            EnterFieldAction.SEARCH,
            EnterFieldAction.SEND,
            EnterFieldAction.DONE,
            EnterFieldAction.UNSPECIFIED,
        )
        for (composing in listOf(false, true)) {
            for (multiLine in listOf(false, true)) {
                for (noEnterAction in listOf(false, true)) {
                    for (action in actions) {
                        for (longPress in listOf(false, true)) {
                            val outcome = EnterDecision.decide(
                                composing,
                                field(
                                    isMultiLine = multiLine,
                                    noEnterAction = noEnterAction,
                                    action = action,
                                ),
                                true,
                                longPress,
                            )
                            assertTrue(
                                "T9 must never InsertNewline: composing=$composing " +
                                    "multiLine=$multiLine noEnterAction=$noEnterAction " +
                                    "action=$action longPress=$longPress",
                                outcome != EnterOutcome.InsertNewline,
                            )
                        }
                    }
                }
            }
        }
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
    fun multiLineWithNoEnterActionNeverPerformsAction() {
        // 2026-11-05 修正（审计 #1 项 1）：IME_FLAG_NO_ENTER_ACTION
        // 的平台含义是“回车不得被 action 取代”，多行字段按回车恒
        // 插换行（九宫格：换行走专用换行键，本键无动作），绝不因
        // action=SEND/DONE 走 performEditorAction。旧单测把反向
        // 语义断言为 SEND/DONE，本测试按平台语义改写。
        for (action in listOf(
            EnterFieldAction.SEND, EnterFieldAction.DONE, EnterFieldAction.UNSPECIFIED,
        )) {
            assertEquals(
                EnterOutcome.InsertNewline,
                EnterDecision.decide(
                    false,
                    field(isMultiLine = true, noEnterAction = true, action = action),
                    false,
                    false,
                ),
            )
            assertEquals(
                EnterOutcome.NoOp,
                EnterDecision.decide(
                    false,
                    field(isMultiLine = true, noEnterAction = true, action = action),
                    true,
                    false,
                ),
            )
        }
    }

    @Test
    fun decisionMatrixSingleMultiByActionFlagAndDedicatedKey() {
        // 回归矩阵：单行/多行 × SEND/DONE/UNSPECIFIED × 有无
        // NO_ENTER_ACTION × 有无专用换行键（点按、非组字）。
        // 单行恒发动作（标志不影响单行）；多行：未带标志且声明了动作时发该动作
        // （2026-11-11 修订，双开微信多行 SEND 回车发纸飞机），带标志或未声明
        // 动作时才落换行／无动作——无专用换行键插换行、有专用换行键无动作。
        val actions = listOf(
            EnterFieldAction.SEND, EnterFieldAction.DONE, EnterFieldAction.UNSPECIFIED,
        )
        for (action in actions) {
            val expectedSingle = EnterOutcome.PerformAction(
                if (action == EnterFieldAction.UNSPECIFIED) EnterFieldAction.DONE else action,
            )
            for (noEnterAction in listOf(false, true)) {
                for (dedicated in listOf(false, true)) {
                    assertEquals(
                        expectedSingle,
                        EnterDecision.decide(
                            false,
                            field(noEnterAction = noEnterAction, action = action),
                            dedicated,
                            false,
                        ),
                    )
                    val expectedMulti: EnterOutcome =
                        if (!noEnterAction && action != EnterFieldAction.UNSPECIFIED) {
                            EnterOutcome.PerformAction(action)
                        } else if (dedicated) {
                            EnterOutcome.NoOp
                        } else {
                            EnterOutcome.InsertNewline
                        }
                    assertEquals(
                        expectedMulti,
                        EnterDecision.decide(
                            false,
                            field(
                                isMultiLine = true,
                                noEnterAction = noEnterAction,
                                action = action,
                            ),
                            dedicated,
                            false,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun multiLineWithoutFlagInsertsNewlineOnlyWithoutDedicatedKey() {
        // 非九宫格（26 键等）：多行无标志且未声明动作时点按换行。
        assertEquals(
            EnterOutcome.InsertNewline,
            EnterDecision.decide(false, field(isMultiLine = true), false, false),
        )
        // 九宫格对照：多行无标志且未声明动作时点按无动作，换行走专用换行键。
        assertEquals(
            EnterOutcome.NoOp,
            EnterDecision.decide(false, field(isMultiLine = true), true, false),
        )
        // 九宫格多行无标志但声明了 SEND 时发该动作（2026-11-11 修订：
        // 双开微信文件传输助手报多行＋SEND，回车应为纸飞机、点按发送，
        // 与单行微信一致；换行仍走专用换行键）。
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.SEND),
            EnterDecision.decide(
                false,
                field(isMultiLine = true, action = EnterFieldAction.SEND),
                true,
                false,
            ),
        )
    }

    @Test
    fun multiLineSendMatchesSingleLineSend() {
        // 两微信一致：单行 SEND 与多行 SEND（均未带不要换行标志）
        // 回车都是纸飞机、点按都发送；组字中时一按完成：上屏候选＋发送。
        for (multiLine in listOf(false, true)) {
            assertEquals(
                EnterOutcome.PerformAction(EnterFieldAction.SEND),
                EnterDecision.decide(
                    false,
                    field(isMultiLine = multiLine, action = EnterFieldAction.SEND),
                    true,
                    false,
                ),
            )
        }
        assertEquals(
            EnterOutcome.CommitAndAct(EnterFieldAction.SEND),
            EnterDecision.decide(
                true,
                field(isMultiLine = true, action = EnterFieldAction.SEND),
                true,
                false,
            ),
        )
    }

    @Test
    fun longPressWithoutDedicatedKeyIsNewlineExceptComposing() {
        // 无专用换行键时长按等于插换行（手机上的 Shift+Enter 口子），
        // 覆盖单行动作与多行不要换行标志两种情形：后者点按已是
        // 换行（2026-11-05 修正），长按同样插换行。
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

    @Test
    fun longPressWithDedicatedKeyMultiLineSendPerformsAction() {
        // 九宫格长按同点按：多行＋声明动作（无标志）时发该动作。
        assertEquals(
            EnterOutcome.PerformAction(EnterFieldAction.SEND),
            EnterDecision.decide(
                false,
                field(isMultiLine = true, action = EnterFieldAction.SEND),
                true,
                true,
            ),
        )
    }
}
