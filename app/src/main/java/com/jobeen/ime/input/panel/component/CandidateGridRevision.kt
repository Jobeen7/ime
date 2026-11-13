// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

/**
 * 候选网格的代次门（WO38 E1-10/1）：纯函数＋纯状态机，不含 Android
 * 依赖，可单测。由 [CandidateGridView.show] /
 * [CandidateGridView.updateCandidates] 在替换列表前调用。
 *
 * 背景：引擎→窗口层已有 revision 门（EngineMessage.Candidates
 * 的 revision ＋ shouldApplyCandidateFrame，窗口层 setCandidates
 * 凭它丢迟到旧帧），面板层亦有 candidateGeneration（KawaiiPanel
 * 的 I-M4，延迟重投只执行仍是最新代次的帧）。但展开网格还有两
 * 条旁路不受这两道门约束——
 * 1. 同态复用分支（KawaiiPanel state setter）：新批次直接调
 *    candidateGrid.updateCandidates(value.candidates)，不带代次；
 * 2. 展开重显（ComposingStateRender / PredictionStateRender
 *    .showExpand）：读 render 对象里原地更新的 var candidates
 *    调 candidateGrid.show(candidates)，同样不带代次。
 * 迟到的旧 render／旧 state 经这两条旁路仍能覆盖新表。本门钉在
 * 网格入口：代次只进不退，旧帧直接丢弃。
 *
 * 口径（与窗口层 shouldApplyCandidateFrame 的区别是等号）：
 * - revision <= 0：未版本化合成帧（清空、联想、拖拽重排等旧调
 *   用点）恒放行，不推进已应用代次——与窗口层同口径；
 * - revision == 已应用代次：放行——同一批次的追加页合并与重
 *   显是同一代次的合法续写，严格大于会把追加页误拦（追加页
 *   与首屏同源代次，见 EngineMessage.Candidates.revision 注释）；
 * - revision > 已应用代次：放行并推进；
 * - revision < 已应用代次：迟到旧帧，丢弃。
 * 重复同代次帧会重算一次布局（幂等内容，无可见副作用）；hide()
 * 不复位已应用代次——收起后迟到的旧展开重显仍被拦住。
 */
internal class CandidateGridRevisionGate {

    /** 已应用的最大代次（0 = 尚未应用任何版本化帧）。 */
    var lastAppliedRevision: Long = 0L
        private set

    /**
     * 帧是否可应用：可应用时更新已应用代次并返回 true；
     * 迟到旧帧不更新、不应用，返回 false。
     */
    fun tryApply(revision: Long): Boolean {
        if (revision <= 0L) return true
        if (revision < lastAppliedRevision) return false
        lastAppliedRevision = revision
        return true
    }
}

/**
 * 网格代次放行判定（纯函数，供单测直接验证口径）：
 * 与 [CandidateGridRevisionGate.tryApply] 同规则、无状态版。
 */
internal fun shouldApplyCandidateGridFrame(revision: Long, lastAppliedRevision: Long): Boolean =
    revision <= 0L || revision >= lastAppliedRevision
