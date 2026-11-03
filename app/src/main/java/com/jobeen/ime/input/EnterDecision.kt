// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

/**
 * 回车键精简决策（2026-11-03 用户定，九宫格去换行、26 键等保留原样）。
 *
 * 纯决策，不调 Android 框架方法、不读偏好、不读键盘状态：
 * 调用方在边界处把 EditorInfo 映射成 [EnterField]（见 EnterFieldBoundary），
 * 把组字中取自引擎 job 执行时点的原始输入是否非空。
 *
 * 决策表（逐行实现，不许增减分支）：
 * - 组字中 → 上屏候选（走现有 Rime 回车路径），点按长按都一样。
 * - 单行＋声明了动作（SEARCH/SEND/GO/NEXT/PREVIOUS/DONE）→ 发该动作。
 * - 单行＋没声明动作（UNSPECIFIED，含 NONE）→ 发 DONE。
 * - 多行＋带不要换行标志 → 发声明的动作，没声明则 DONE。
 * - 多行＋没带标志＋无专用换行键 → 插换行。
 * - 多行＋没带标志＋有专用换行键（九宫格）→ 无动作（换行走专用换行键）。
 * - 长按：无专用换行键时等于插换行（手机上的 Shift+Enter 口子，
 *   组字中时仍先上屏候选）；有专用换行键时同点按。
 *
 * 刻意不在决策里的量：框空不空（空探针已退出回车决策，空框点按
 * 不再翻回换行）、回车键图标（图标不再决定行为，显示改由本决策
 * 反推）、长按 force 旁路（force 只作是否长按输入，不跳过变换）。
 */
enum class EnterFieldAction {
    SEARCH,
    SEND,
    GO,
    NEXT,
    PREVIOUS,
    DONE,
    /** 未声明动作；边界处把 IME_ACTION_UNSPECIFIED 与 IME_ACTION_NONE 都折到这里。 */
    UNSPECIFIED,
}

/** 决策用的字段属性（边界处由 EditorInfo 一次性映射，决策内不再碰 EditorInfo）。 */
data class EnterField(
    /** inputType 的 TYPE_TEXT_FLAG_MULTI_LINE。 */
    val isMultiLine: Boolean,
    /** imeOptions 的 IME_FLAG_NO_ENTER_ACTION。 */
    val noEnterAction: Boolean,
    /** imeOptions 的 IME_MASK_ACTION（已折成 [EnterFieldAction]）。 */
    val action: EnterFieldAction,
)

/** 回车键决策输出，四种之一。 */
sealed interface EnterOutcome {
    /** 组字中：走现有 Rime 回车路径上屏候选。 */
    data object CommitCandidate : EnterOutcome

    /** 发编辑器动作（边界处再映射回 EditorInfo 动作常量）。 */
    data class PerformAction(val action: EnterFieldAction) : EnterOutcome

    /** 插换行（走现有 emitCommit("\n") 的 FIFO 路径，不超车前面的上屏）。 */
    data object InsertNewline : EnterOutcome

    /** 无动作（九宫格多行无标志时：换行走专用换行键）。 */
    data object NoOp : EnterOutcome
}

object EnterDecision {

    fun decide(
        /** 执行时点串行读到的原始输入是否非空（组字中）。 */
        composing: Boolean,
        field: EnterField,
        /** 有无专用换行键：九宫格 true，其他键盘 false，以键盘身份判定。 */
        hasDedicatedNewlineKey: Boolean,
        /** 是否长按（ReturnAction 的 force，仅作本输入）。 */
        isLongPress: Boolean,
    ): EnterOutcome {
        if (composing) {
            return EnterOutcome.CommitCandidate
        }
        if (isLongPress && !hasDedicatedNewlineKey) {
            return EnterOutcome.InsertNewline
        }
        if (!field.isMultiLine) {
            return EnterOutcome.PerformAction(
                if (field.action == EnterFieldAction.UNSPECIFIED) {
                    EnterFieldAction.DONE
                } else {
                    field.action
                },
            )
        }
        if (field.noEnterAction) {
            return EnterOutcome.PerformAction(
                if (field.action == EnterFieldAction.UNSPECIFIED) {
                    EnterFieldAction.DONE
                } else {
                    field.action
                },
            )
        }
        if (!hasDedicatedNewlineKey) {
            return EnterOutcome.InsertNewline
        }
        return EnterOutcome.NoOp
    }
}
