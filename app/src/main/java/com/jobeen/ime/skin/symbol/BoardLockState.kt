// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.notify.ConditionCurrent
import com.jobeen.ime.skin.notify.ConditionStyleParser

/**
 * 符号/表情板锁定态（审计 G，纯状态，可单测）：
 *
 * - 两块板各一把锁：symbolic（符号板）与 emojis（表情板），
 *   互不干扰；锁只由锁定切换动作翻转，进程级会话内有效。
 * - 锁的语义只管“点按提交后是否自动回主键盘”（见
 *   BoardAutoReturn）：锁定时板常驻，不锁定时提交即回。
 *   锁不被返回/恢复动作清除——回到主键盘后下次再进板，
 *   锁仍在，直到再次切换动作关掉（对端“sticky”口径）；
 *   服务销毁时 [reset] 清空（会话结束）。
 * - 条件现值：两把锁各喂一个布尔条件键
 *   （symbolicKeyboardLockState / emojiKeyboardLockState），
 *   供 conditionStyles 门控样式；与既有的 symbolLock（板是否
 *   为活动键盘）是两个不同的键，不得混用。
 * - 整数条件形态不在此生产：锁是布尔量，Int 数组条件对
 *   锁键恒不成立（ConditionEvaluator 的类型失配口径），
 *   解析期仍接受该形态，不以此为由剔除整项。
 */
class BoardLockState {
    var symbolicLocked: Boolean = false
        private set
    var emojiLocked: Boolean = false
        private set

    fun toggleSymbolic(): Boolean {
        symbolicLocked = !symbolicLocked
        return symbolicLocked
    }

    fun toggleEmoji(): Boolean {
        emojiLocked = !emojiLocked
        return emojiLocked
    }

    /** 某类型板是否锁定；非板类型（主键盘/数字等）恒为不锁定。 */
    fun isLocked(boardType: String): Boolean = when (boardType) {
        KeyboardTypes.SYMBOLIC -> symbolicLocked
        KeyboardTypes.EMOJIS -> emojiLocked
        else -> false
    }

    /** 两把锁的条件现值（调用方并入条件总表）。 */
    fun conditionCurrent(): Map<String, ConditionCurrent> = mapOf(
        ConditionStyleParser.SYMBOLIC_LOCK_KEY to ConditionCurrent.Bool(symbolicLocked),
        ConditionStyleParser.EMOJI_LOCK_KEY to ConditionCurrent.Bool(emojiLocked),
    )

    fun reset() {
        symbolicLocked = false
        emojiLocked = false
    }
}
