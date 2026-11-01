// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.skin.model.KeyboardTypes

/**
 * 板提交后自动返回决策（审计 G，纯函数）：
 *
 * - 只有符号/表情两块板参与自动返回；主键盘与数字等类型
 *   的提交永不触发（调用方只在板内调用，类型再守一道）。
 * - 未锁定 → 提交后返回；锁定 → 常驻板内，直到显式返回键。
 * - 返回目标不在此决定：窗口层经
 *   KeyboardStateManager.returnFromBoardAfterCommit() 按
 *   类型模型走上一类型（对端 returnLastKeyboard 口径），
 *   上一类型缺失时退主类型，再缺失时退旧 resume 路径。
 */
object BoardAutoReturn {

    fun shouldReturnAfterCommit(boardType: String, locked: Boolean): Boolean {
        if (locked) return false
        return boardType == KeyboardTypes.SYMBOLIC || boardType == KeyboardTypes.EMOJIS
    }
}
