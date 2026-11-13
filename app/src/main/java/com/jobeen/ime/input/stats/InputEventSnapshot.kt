// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.stats

/**
 * 输入事件快照（WO38 E1-10/6）：最小载体。
 *
 * 背景：此前没有“输入事件快照”这一载体（全仓 grep
 * InputEvent／inputEventSnapshot／EventSnapshot 零匹配）；
 * 统计侧只有周期聚合 [InputStats.snapshot]（按日／周／月／
 * 年聚合的 PeriodStats）。本类把一次输入事件与捕获时刻的
 * 统计摘要绑在一起：事件只给标识（eventId，由调用方按一次
 * 按键／提交递增——不记输入内容、候选词、剪贴板、词典，与
 * InputStats 的隐私口径一致），摘要即当时的 PeriodStats。
 *
 * 用途：诊断／埋点消费方凭 eventId 把“某次输入发生时统计是
 * 什么样”钉住——例如复现“某次提交后计数未涨”时，可比对事
 * 件前后两份快照的摘要差值，而不用在事后反推周期聚合。
 * 本批只定义载体＋捕获口径，不改任何现有记账调用（noteKeyPress
 * ／noteCommit 等签名与行为一字不动）；事件 id 的生产与投递
 * 是后续接线，不在本批内。
 */
data class InputEventSnapshot(
    /** 输入事件标识（调用方递增；0 保留给“未编号”事件）。 */
    val eventId: Long,
    /** 摘要所属周期（与 [InputStats.snapshot] 的 period 同口径）。 */
    val period: InputStats.Period,
    /** 摘要所属方案（null = 全部方案，同 snapshot 口径）。 */
    val scheme: String?,
    /** 捕获时刻的统计摘要（与 snapshot(period, scheme) 同口径）。 */
    val summary: InputStats.PeriodStats,
    /** 捕获时刻（调用方时钟，毫秒）。 */
    val capturedAtMs: Long,
)

/**
 * 以一次输入事件为锚捕获统计摘要：摘要即调用时刻的
 * [InputStats.snapshot]，不另算、不缓存——同一时刻两次捕获
 * 的摘要相等；事件之间摘要只随记账推进而变。
 */
fun captureInputEventSnapshot(
    eventId: Long,
    period: InputStats.Period,
    scheme: String? = null,
    nowMs: Long = System.currentTimeMillis(),
): InputEventSnapshot =
    InputEventSnapshot(
        eventId = eventId,
        period = period,
        scheme = scheme,
        summary = InputStats.snapshot(period, scheme),
        capturedAtMs = nowMs,
    )
