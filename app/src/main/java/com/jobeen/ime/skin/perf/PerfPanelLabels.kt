// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.perf

import android.content.Context
import com.jobeen.ime.R

/**
 * 性能面板事件标签的唯一来源（WO33 第 4(a) 项）：
 *
 * - 运行时：[resolve] 从 strings.xml（`perf_event_*`）取标签，
 *   [aggregateTemplate] 从 `perf_aggregate_format` 取聚合文案模板；
 *   [PerformancePanelView.refresh] 只走这条路，不再读硬编码中文。
 * - 纯函数缺省值：[DEFAULT_LABELS]／[DEFAULT_AGGREGATE_TEMPLATE] 是
 *   与 strings.xml 同值的 canonical 文案，供无 Context 的纯函数
 *   （[PerformancePanelView.rowsFor]／`formatAggregate`）与单测使用；
 *   两处同值由 [PerfPanelLabelsTest] 逐项比对 strings.xml 源码钉住，
 *   改串时两边必须一起改。
 */
object PerfPanelLabels {

    /** 事件 → strings.xml 串 ID（10 项全覆盖，见 [PerfMonitor.Event]）。 */
    fun labelResId(event: PerfMonitor.Event): Int = when (event) {
        PerfMonitor.Event.COLD_START -> R.string.perf_event_cold_start
        PerfMonitor.Event.KEY_TO_COMMIT -> R.string.perf_event_key_to_commit
        PerfMonitor.Event.SKIN_APPLY -> R.string.perf_event_skin_apply
        PerfMonitor.Event.SLOW_FRAME -> R.string.perf_event_slow_frame
        PerfMonitor.Event.MEMORY -> R.string.perf_event_memory
        PerfMonitor.Event.STARTUP_STEP -> R.string.perf_event_startup_step
        PerfMonitor.Event.KEY_DISPATCH -> R.string.perf_event_key_dispatch
        PerfMonitor.Event.KEY_ENGINE -> R.string.perf_event_key_engine
        PerfMonitor.Event.KEY_COMMIT -> R.string.perf_event_key_commit
        PerfMonitor.Event.MAIN_LAG -> R.string.perf_event_main_lag
    }

    /** 运行时从资源解析全部事件标签（面板显示与报告文本共用同一份）。 */
    fun resolve(context: Context): Map<PerfMonitor.Event, String> =
        PerfMonitor.Event.entries.associateWith { context.getString(labelResId(it)) }

    /** 聚合文案模板（与 `perf_aggregate_format` 同值）：%1$count %2$avg %3$unit %4$max。 */
    const val DEFAULT_AGGREGATE_TEMPLATE = "%1\$d 次，均值 %2\$.1f %3\$s，最大 %4\$d %3\$s"

    /** 运行时从资源取聚合文案模板。 */
    fun aggregateTemplate(context: Context): String =
        context.getString(R.string.perf_aggregate_format)

    /**
     * 与 strings.xml `perf_event_*` 同值的 canonical 标签（纯函数缺省
     * 值与单测期望的唯一来源；单测不再硬编码中文，改串不改测试）。
     */
    val DEFAULT_LABELS: Map<PerfMonitor.Event, String> = mapOf(
        PerfMonitor.Event.COLD_START to "冷启动",
        PerfMonitor.Event.KEY_TO_COMMIT to "按键提交",
        PerfMonitor.Event.SKIN_APPLY to "皮肤应用",
        PerfMonitor.Event.SLOW_FRAME to "掉帧",
        PerfMonitor.Event.MEMORY to "内存",
        PerfMonitor.Event.STARTUP_STEP to "启动步骤",
        PerfMonitor.Event.KEY_DISPATCH to "按键分发",
        PerfMonitor.Event.KEY_ENGINE to "引擎处理",
        PerfMonitor.Event.KEY_COMMIT to "提交上屏",
        PerfMonitor.Event.MAIN_LAG to "主线程卡顿",
    )
}
