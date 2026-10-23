package com.jobeen.ime.engine

import java.text.BreakIterator

/**
 * 前删规划（纯函数，详设 §5.2）：字素簇边界唯一口径 =
 * BreakIterator（UAX #29 扩展字素簇），与 ImeInputConnection
 * 退格删簇同算法——禁止按码点直删（防劈开国旗、ZWJ 序列、
 * 组合附加符）。Android 运行时由 ICU 实现，语义以真机为准。
 */
object ForwardDeletePlanner {

    /** 后文探测长度（字符）：覆盖一簇绰绰有余，又不至于整段回读。 */
    const val PROBE_CHARS = 128

    /** 一个字素簇的双口径长度。 */
    data class Cluster(val codePoints: Int, val utf16Length: Int)

    /** [after] 首个字素簇的长度；空文本返回 null（第 (4) 态无可删）。 */
    fun firstCluster(after: CharSequence): Cluster? {
        if (after.isEmpty()) return null
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(after.toString())
        val end = iterator.following(0)
        val clusterEnd = if (end == BreakIterator.DONE) after.length else end
        return Cluster(
            codePoints = Character.codePointCount(after, 0, clusterEnd),
            utf16Length = clusterEnd,
        )
    }
}
