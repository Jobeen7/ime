package com.jobeen.ime.engine

/**
 * 组字清除后防撤销复活（2026-11-02 视频报：上滑清组字后下滑撤销把已清组字
 * 以已上屏文本复活）。
 *
 * 机制：复位清组字时记下被清段（首选引擎侧组字串，引擎侧无组字时以清前/清后
 * 光标前文本差分定段）；随后第一次走 Ctrl+Z 路径的撤销，若撤销纯增文本
 * （撤销前文本是撤销后文本的前缀）且增量以被清段结尾，则整个增量都是被清
 * 内容的复活，应删长度即增量长度。无论命中与否，第一次 Ctrl+Z 撤销即消费
 * （防滞留误删用户之后重打的同文）。
 *
 * 会话边界：提交、退格/前删、换输入框都会清守卫；连续两次清除之间无上述
 * 动作时守卫保持首次 armed 的段（第二次清除不覆盖——视频里两次上滑分别清
 * 组字与已上屏字，撤销时复活的是两段之和，增量以后清段结尾为判据）。
 * 纯函数，可单测；不触 Android 框架。
 */
class UndoClearGuard {
    private var suffix: String? = null

    fun hasGuard(): Boolean = !suffix.isNullOrEmpty()

    /** 首选 armed：仅当守卫空闲时记段（连续清除保持首次段）。 */
    fun arm(text: String?) {
        if (suffix == null && !text.isNullOrEmpty()) {
            suffix = text
        }
    }

    /** 差分 armed：清后是清前的前缀且确有删减时，删减段即被清段。 */
    fun armFromDiff(before: String?, after: String?) {
        if (suffix != null || before == null || after == null) return
        if (before.startsWith(after) && before.length > after.length) {
            suffix = before.substring(after.length)
        }
    }

    /**
     * Ctrl+Z 撤销后一次性消费：返回应删长度（UTF-16 单位，与
     * deleteSurroundingText 的计数口径一致），0 = 不删。
     */
    fun consume(beforeUndo: String?, afterUndo: String?): Int {
        val guarded = suffix
        suffix = null
        if (guarded.isNullOrEmpty() || beforeUndo == null || afterUndo == null) return 0
        if (!afterUndo.startsWith(beforeUndo)) return 0
        val added = afterUndo.substring(beforeUndo.length)
        return if (added.isNotEmpty() && added.endsWith(guarded)) added.length else 0
    }

    fun clear() {
        suffix = null
    }
}
