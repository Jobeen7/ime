// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.skin.diag.DiagLog

/**
 * 按键拒绝原因（WO38 E1-9）：只记原因名与纪元元数据，不含按键
 * 文本、字段内容与包名。现只有一个原因；以后新增原因只加枚举
 * 值，不改门控调用点。
 */
internal enum class InputKeyRejectReason {
    /** 捕获的会话纪元已不是当前纪元：按键属于已结束／已切换的旧会话。 */
    STALE_EPOCH,
}

/**
 * 门控判定结果（WO38 E1-9）：放行是单例、零分配；拒绝只在
 * 拒绝路径上分配一次（含日志上下文），热路径（放行）无 I/O、无分配。
 */
internal sealed interface InputKeyGateResult {
    data object Allow : InputKeyGateResult

    data class Deny(
        val reason: InputKeyRejectReason,
        val capturedEpoch: Long,
        val currentEpoch: Long,
    ) : InputKeyGateResult
}

/**
 * 引擎层统一会话门控（WO38 E1-9）：换编辑器失效旧按键＋拒绝记原因。
 *
 * 纪元来源——复用输入层已有的会话纪元，不另造代次（与
 * [ScriptRunSession] 同口径）：
 * [SherpaSpeechClient.currentEditorEpoch]，由 IMS 在真输入结束
 * （onFinishInput、onFinishInputView(true)）与字段身份变化
 * （onStartInput／onStartInputView 经 trackInputField 判变）时经
 * `invalidateSpeechAfterInputEnd → onInputViewFinished` 推进；
 * 同框收起再打开、同框 restarting 重开、字段不变的 restart 均不
 * 推进——与 ScriptRunSession“同框复检放行”的既定语义一致，门控
 * 不改变任何合法按键的行为。
 *
 * 用法——延迟分发两步走：启动／按下时 [capture] 记下当前纪元，
 * 分发时 [check] 复检；纪元已推进即拒绝并记原因（DiagLog，
 * 不弹用户提示）。[isCurrent] 是不记日志的纯复检，供调用方
 * 只需布尔值时走热路径。
 *
 * 覆盖面（诚实口径）：同步按键在主线程内按下即分发，分发时即
 * 当前会话，复检恒放行——门控的实际拦截面是跨会话延迟分发，
 * 本批接线点是 KeyActionListener 的 RunScriptAction 回切分发
 * （脚本结果在主线程分发前复检）。长按连发的过期键另有缺口，
 * 见下。
 *
 * 已知缺口（不在本批文件预算内，不硬造）：长按连发
 * （CustomGestureView 的 postDelayed repeatRunnable →
 * BaseKeyboard.onRepeatListener → onAction → onKeyAction）按下时
 * 无纪元戳，分发层无法区分“新会话里的新键”与“旧会话里按住未放
 * 的连发键”——补戳须碰 BaseKeyboard.kt（onRepeatListener 绑定处，
 * 约 672 行）与 CustomGestureView.kt（repeatRunnable），两文件
 * 均在本批禁碰之列。本门控的 [capture]/[check] 已是该接线要用的
 * 原语：连发启动时 capture、每次 fireRepeat 前 check，拒绝即停
 * 发并记原因；待有权改那两文件时直接接线，无需改本类。
 *
 * 线程：纪元是 AtomicLong，capture／check 任意线程可调；拒绝
 * 日志走 DiagLog 异步队列，不阻塞分发。
 */
internal class InputKeyGate(
    private val epochProvider: () -> Long = { SherpaSpeechClient.currentEditorEpoch() },
    private val onReject: (deny: InputKeyGateResult.Deny, actionLabel: String) -> Unit =
        { deny, actionLabel ->
            DiagLog.w(
                "keygate",
                "stale_key_rejected",
                mapOf(
                    "action" to actionLabel,
                    "reason" to deny.reason.name.lowercase(),
                    "capturedEpoch" to deny.capturedEpoch,
                    "currentEpoch" to deny.currentEpoch,
                ),
            )
        },
) {
    /** 记下当前会话纪元（按下／启动时调用）。 */
    fun capture(): Long = epochProvider()

    /** 纯复检：捕获的纪元是否仍是当前纪元（不记日志）。 */
    fun isCurrent(capturedEpoch: Long): Boolean = capturedEpoch == epochProvider()

    /**
     * 复检并在拒绝时记原因：放行返回 [InputKeyGateResult.Allow]
     * （单例）；拒绝返回 [InputKeyGateResult.Deny] 并经 [onReject]
     * 记一条原因日志（原因＋新旧纪元元数据，无用户提示）。
     */
    fun check(capturedEpoch: Long, actionLabel: String): InputKeyGateResult {
        val current = epochProvider()
        if (capturedEpoch == current) return InputKeyGateResult.Allow
        val deny = InputKeyGateResult.Deny(
            InputKeyRejectReason.STALE_EPOCH,
            capturedEpoch,
            current,
        )
        onReject(deny, actionLabel)
        return deny
    }
}
