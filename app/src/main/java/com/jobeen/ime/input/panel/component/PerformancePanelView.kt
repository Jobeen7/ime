// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.perf.PerfMonitor

/**
 * 键盘性能面板（P2-10，View 体系）：菜单第 2 页右下角入口。
 *
 * - 数据只复用 [PerfMonitor.aggregates]（开发页 Compose 不能直接
 *   复用，只复用数据）；每次打开时重读（show() 内刷新）。
 * - 基础 5 项（冷启动／按键提交／皮肤应用／掉帧／内存）固定顺序
 *   常驻累计；P4 分段探针（STARTUP_STEP／KEY_DISPATCH／KEY_ENGINE／
 *   KEY_COMMIT／MAIN_LAG）仅诊断模式开时才有，面板只显示已有项。
 * - 内存单位 KB，其余单位 ms；只记元数据与耗时（与 PerfMonitor
 *   同口径，绝不记输入内容、候选词、剪贴板）。
 */
@SuppressLint("ViewConstructor")
class PerformancePanelView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : ComponentView(context, colors) {

    private val rowsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    init {
        addView(
            ScrollView(context).apply {
                addView(
                    rowsContainer,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        refresh()
    }

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        super.refreshTheme(newColors)
        refresh()
    }

    override fun show() {
        refresh()
        super.show()
    }

    /** 重读聚合数据并重建行（空数据时显示一行“暂无数据”）。 */
    fun refresh() {
        rowsContainer.removeAllViews()
        val rows = rowsFor(PerfMonitor.aggregates())
        if (rows.isEmpty()) {
            rowsContainer.addView(rowTextView("暂无数据（冷启动／按键提交／皮肤应用／掉帧／内存五项随使用累计）"))
            return
        }
        for ((label, value) in rows) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val vPad = (6 * context.resources.displayMetrics.density).toInt()
                setPadding(0, vPad, 0, vPad)
            }
            row.addView(
                rowTextView(label, 15f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(rowTextView(value, 13f))
            rowsContainer.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun rowTextView(text: String, sizeSp: Float = 14f): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(sizeSp)
            setTextColor(colors.panel.candidateText)
        }

    companion object {
        /** 基础 5 项固定顺序（与开发页文案一致）。 */
        val BASE_ORDER = listOf(
            PerfMonitor.Event.COLD_START,
            PerfMonitor.Event.KEY_TO_COMMIT,
            PerfMonitor.Event.SKIN_APPLY,
            PerfMonitor.Event.SLOW_FRAME,
            PerfMonitor.Event.MEMORY,
        )

        /** P4 分段探针顺序（仅已有项显示）。 */
        val PROBE_ORDER = listOf(
            PerfMonitor.Event.STARTUP_STEP,
            PerfMonitor.Event.KEY_DISPATCH,
            PerfMonitor.Event.KEY_ENGINE,
            PerfMonitor.Event.KEY_COMMIT,
            PerfMonitor.Event.MAIN_LAG,
        )

        private fun labelFor(event: PerfMonitor.Event): String = when (event) {
            PerfMonitor.Event.COLD_START -> "冷启动"
            PerfMonitor.Event.KEY_TO_COMMIT -> "按键提交"
            PerfMonitor.Event.SKIN_APPLY -> "皮肤应用"
            PerfMonitor.Event.SLOW_FRAME -> "掉帧"
            PerfMonitor.Event.MEMORY -> "内存"
            PerfMonitor.Event.STARTUP_STEP -> "启动步骤"
            PerfMonitor.Event.KEY_DISPATCH -> "按键分发"
            PerfMonitor.Event.KEY_ENGINE -> "引擎处理"
            PerfMonitor.Event.KEY_COMMIT -> "提交上屏"
            PerfMonitor.Event.MAIN_LAG -> "主线程卡顿"
        }

        /** 单项文案（纯函数，可单测）：内存 KB，其余 ms；均值 1 位小数。 */
        fun formatAggregate(event: PerfMonitor.Event, agg: PerfMonitor.Aggregate): String {
            val unit = if (event == PerfMonitor.Event.MEMORY) "KB" else "ms"
            return "${agg.count} 次，均值 ${"%.1f".format(agg.avg)} $unit，最大 ${agg.max} $unit"
        }

        /**
         * 行顺序（纯函数，可单测）：基础 5 项固定顺序在前（缺失项
         * 跳过），探针项在后（仅已有项显示）；空聚合返回空列表
         * （调用方显示“暂无数据”）。
         */
        fun rowsFor(
            aggregates: Map<PerfMonitor.Event, PerfMonitor.Aggregate>,
        ): List<Pair<String, String>> {
            val rows = ArrayList<Pair<String, String>>()
            for (event in BASE_ORDER + PROBE_ORDER) {
                val agg = aggregates[event] ?: continue
                rows += labelFor(event) to formatAggregate(event, agg)
            }
            return rows
        }
    }
}
