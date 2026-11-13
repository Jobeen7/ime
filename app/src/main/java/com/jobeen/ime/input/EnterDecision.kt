// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

/**
 * 回车键精简决策（2026-11-03 用户定，九宫格去换行、26 键等保留原样；
 * 2026-11-13 用户定：九宫格回车永不插换行——决策表现有
 * hasDedicatedNewlineKey 分支已实现，九宫格走不到 InsertNewline
 * 分支，只加单测钉住，26 键等其他键盘保留原样；组字中一按完成，
 * 上屏候选＋执行动作）。
 *
 * 纯决策，不调 Android 框架方法、不读偏好、不读键盘状态：
 * 调用方在边界处把 EditorInfo 映射成 [EnterField]（见 EnterFieldBoundary），
 * 把组字中取自引擎 job 执行时点的原始输入是否非空。
 *
 * 决策表（逐行实现，不许增减分支）：
 * - 组字中 → 先按字段算非组字态的动作：有动作则上屏候选＋执行
 *   动作（CommitAndAct），无动作则只上屏候选（CommitCandidate），
 *   点按长按都一样。
 * - 单行＋声明了动作（SEARCH/SEND/GO/NEXT/PREVIOUS/DONE）→ 发该动作。
 * - 单行＋没声明动作（UNSPECIFIED，含 NONE）→ 发 DONE。
 * - 多行＋声明了动作且未带不要换行标志 → 发该动作（2026-11-11 修订：
 *   双开微信文件传输助手报多行＋SEND，回车原为无动作、点按不能发送，
 *   与单行微信的纸飞机不一致；用户要两微信回车一致为纸飞机、点按发送。
 *   专用换行键仍负责插换行，换行功能不受影响）。
 * - 多行＋带不要换行标志 → 同无标志：无专用换行键插换行，
 *   有专用换行键（九宫格）无动作。IME_FLAG_NO_ENTER_ACTION 的
 *   平台含义是“回车不得被 action 取代”（多行字段需要回车插
 *   换行），带该标志时声明了 SEND/DONE 也不发动作；显式发送走独立的发送键
 *   （MultiReturnAction 绑定），不走本决策。
 * - 多行＋没带标志＋没声明动作（UNSPECIFIED）→ 无专用换行键插换行，
 *   有专用换行键（九宫格）无动作（换行走专用换行键）。
 * - 长按：无专用换行键时等于插换行（手机上的 Shift+Enter 口子，
 *   组字中时按组字中分支走）；有专用换行键时同点按。
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

/** 回车键决策输出，五种之一。 */
sealed interface EnterOutcome {
    /** 组字中：走现有 Rime 回车路径上屏候选。 */
    data object CommitCandidate : EnterOutcome

    /** 组字中一按完成：先上屏候选、再执行编辑器动作（同一次按键内）。 */
    data class CommitAndAct(val action: EnterFieldAction) : EnterOutcome

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
            // 组字中一按完成（2026-11-13 用户定）：先按字段算非组字态
            // 的动作，有动作则上屏后执行，无动作则只上屏。点按长按都一样。
            val action = actionFor(field)
            return if (action != null) {
                EnterOutcome.CommitAndAct(action)
            } else {
                EnterOutcome.CommitCandidate
            }
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
        // 多行：声明了动作且未带不要换行标志时发该动作（2026-11-11 修订，
        // 见文件头决策表）；带不要换行标志或未声明动作时才落换行／无动作。
        if (!field.noEnterAction && field.action != EnterFieldAction.UNSPECIFIED) {
            return EnterOutcome.PerformAction(field.action)
        }
        if (!hasDedicatedNewlineKey) {
            return EnterOutcome.InsertNewline
        }
        return EnterOutcome.NoOp
    }

    /**
     * 非组字态的动作（有则发，无则空）：单行恒有动作（未声明→DONE）；
     * 多行仅在未带不要换行标志且声明了动作时有动作；其余一律无动作。
     * 组字中分支与非组字分支共用同一口径，显示与行为同一来源。
     */
    private fun actionFor(field: EnterField): EnterFieldAction? {
        if (!field.isMultiLine) {
            return if (field.action == EnterFieldAction.UNSPECIFIED) {
                EnterFieldAction.DONE
            } else {
                field.action
            }
        }
        return if (!field.noEnterAction && field.action != EnterFieldAction.UNSPECIFIED) {
            field.action
        } else {
            null
        }
    }
}

/**
 * 回车入队时的输入会话快照（纯数据，可单测；2026-11-05 审计 #1 项 4）：
 * 字段身份 InputFieldKey（分发时点由 KeyActionListener 侧的
 * EditorInfo 映射）＋输入框代次（引擎在 processEnter 入队时点
 * 取的 predictionFieldGen 现值；注入回车在 reduce 分发点取）。
 * 执行前（sendEnterJob）复检两者仍一致才执行——入队后换框
 * （代次推进或字段身份变）或输入结束（身份变空）时旧会话的
 * action 一律丢弃，绝不在新字段的 InputConnection 上执行。
 * 与按键脚本的 ScriptRunSession 同构、代次源不同：脚本用语音
 * 侧 EditorEpoch（只在真结束时推进），回车用引擎的 prediction-
 * FieldGen（onStartInputView 换框即推进，含 restarting 型逻辑换框）。
 */
internal data class EnterSession(
    val fieldKey: InputFieldKey?,
    val fieldGen: Long,
) {
    /** 执行时点复检：字段身份与代次两者都须与入队时一致。 */
    fun isCurrent(fieldKey: InputFieldKey?, fieldGen: Long): Boolean =
        this.fieldKey == fieldKey && this.fieldGen == fieldGen
}
