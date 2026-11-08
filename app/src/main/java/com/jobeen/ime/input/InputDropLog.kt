// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

/**
 * 输入丢字日志（施工单 10 H2-16，CyIME J4 `InputDropLog` 对口）：
 * 键盘无声拒掉输入（无浮层、无 toast 解释）时记一行；窗口内多余
 * 的拒掉只计数（more=N），免部署期连续打字刷屏。
 *
 * 只记原因与门状态，绝不记按键与任何文本——「进聊天页打不出字」
 * 这类投诉事后可凭日志追溯。纯函数类（时钟与写盘均可注入），
 * 可主机单测；落盘走调用方的 DiagLog（WARN 级，同时进诊断导出）。
 */
internal class InputDropLog(
    private val uptimeMillis: () -> Long,
    private val write: (String) -> Unit,
    private val windowMs: Long = 2_000L,
) {
    private var lastAt = 0L
    private var logged = false
    private var suppressed = 0

    @Synchronized fun drop(reason: String, state: () -> String) {
        val now = uptimeMillis()
        if (logged && now - lastAt < windowMs) {
            suppressed++
            return
        }
        val extra = if (suppressed > 0) " more=$suppressed" else ""
        logged = true
        lastAt = now
        suppressed = 0
        write("input_dropped reason=$reason$extra ${state()}")
    }
}
