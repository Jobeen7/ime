// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

/**
 * 输入被无声拒掉时的丢字日志（施工单 10 H2-16）。
 *
 * 键盘在不弹任何提示的情况下拒掉一次输入，就记一行，写明拒掉原因
 * 和当时的门状态；同一窗口期内再被拒掉的不再写行，只把次数攒起来，
 * 下次写行时以 more=N 带上，避免部署期连续打字把日志刷屏。
 *
 * 行里只有原因和门状态，不含按键、不含任何文本——事后凭它能追溯
 * 「进聊天页打不出字」这类投诉。时钟与写盘都由调用方注入，
 * 因此可主机单测；落盘走调用方的 DiagLog（WARN 级，随诊断导出）。
 */
internal class InputDropLog(
    private val uptimeMillis: () -> Long,
    private val write: (String) -> Unit,
    private val windowMs: Long = 2_000L,
) {
    /** 上一次真正写行的时间；还没写过行时为空。 */
    private var lastWrittenAt: Long? = null

    /** 上一次写行之后被压住的拒掉次数。 */
    private var heldBack = 0

    @Synchronized fun drop(reason: String, state: () -> String) {
        val now = uptimeMillis()
        val last = lastWrittenAt
        if (last != null && now - last < windowMs) {
            heldBack++
            return
        }
        val more = heldBack
        heldBack = 0
        lastWrittenAt = now
        val suffix = if (more > 0) " more=$more" else ""
        write("input_dropped reason=$reason$suffix ${state()}")
    }
}
