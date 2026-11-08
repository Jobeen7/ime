// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

/**
 * 符号组字顺序（G45，纯函数）：
 *
 * 符号提交（符号板／皮肤符号栏／横条／置顶条／字面提交）与组字
 * 的顺序规则——组字进行中时，先提交当前选中词、符号随后，两者
 * 由 native 合成一条 Commit 落屏（commitCurrentSelection），
 * 顺序有保证；无组字时符号直送。核实结论：该顺序自 2026-10-21
 *（0c2becc5）起已在 requestCommit／requestCommitPair 内实现，
 * E3 核实只读到 SymbolKeyboard.dispatchBoardCommit → CommitAction
 * 一层、未追踪到引擎，误判为“没有”；本项补的是判定信号的
 * 残余缺口（见下），不是从零新建顺序。
 *
 * 判定必须读本 job 执行时点的实时原始输入（getRawInput），不能
 * 读展示串缓存（compositionCached.preedit）：分发队列与作业队列
 * 是两条线，先行按键的响应可能尚未分发回写缓存，缓存会滞后于
 * 在途按键——按缓存判，符号可能超车待提交的组字先落屏（乱序），
 * 或缓存残留非空而组字已清时误走选中提交丢掉符号；与空格／退格／
 * 回车的同纪律（getRawInput，现读）。
 */
object SymbolComposeOrder {

    /**
     * @param rawInput 本 job 执行时点现读的原始输入（getRawInput）。
     * @return true：组字进行中，先提交当前选中词再补符号；
     *         false：无组字，符号直送。
     */
    fun shouldCommitSelectionFirst(rawInput: String): Boolean = rawInput.isNotEmpty()
}
