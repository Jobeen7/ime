package com.jobeen.ime.input.keyboard.window

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * 内联自动填充条区是否显示（纯函数，供 JVM 测试）：
 * 收到非空建议、用户未组字、输入会话有效时才显示；任一条件不满足即隐藏，
 * 隐藏后恢复其下的正常条区（工具栏／候选），条区视图本身从不 GONE。
 */
internal fun inlineAutofillShouldShow(
    hasSuggestions: Boolean,
    isComposing: Boolean,
    fieldActive: Boolean,
): Boolean = hasSuggestions && !isComposing && fieldActive

/**
 * 按 pinned 标志把建议下标分区（纯函数，供 JVM 测试）：
 * pinned 进右固定槽，余下进横向滚动区；顺序与原列表一致。
 */
internal fun partitionInlineSuggestions(pinnedFlags: List<Boolean>): Pair<List<Int>, List<Int>> {
    val pinned = mutableListOf<Int>()
    val scrollable = mutableListOf<Int>()
    pinnedFlags.forEachIndexed { index, isPinned ->
        if (isPinned) pinned.add(index) else scrollable.add(index)
    }
    return pinned to scrollable
}

/**
 * 内联自动填充（Android 11+ IME Inline Autofill）条区覆盖层。
 *
 * 与 JIME 已有「内联建议」（PreeditPinner 幽灵文本）是两个功能：这里显示的是
 * 密码管理器给出的 InlineContentView 胶囊，点击由系统直接填入，绝不走
 * commitText、绝不进用户词库；胶囊由密码管理器进程渲染，输入法只拿到视图句柄。
 *
 * 布局：覆盖在条区矩形（与 panel.view 同 rect）上，父层按条区尺寸精确度量；
 * 本层默认 GONE，显示时才参与度量／布局。内容分两区：横向滚动的胶囊行、
 * 右固定槽（pinned 建议，如密码管理器归属 chip）；固定槽有内容时给滚动区
 * 留出等宽右 padding，避免胶囊滑到固定槽下面。
 */
internal class InlineAutofillBar(context: Context) : FrameLayout(context) {

    private val chipScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
    }
    private val chipRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }
    private val pinnedRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    init {
        visibility = View.GONE
        chipScroll.addView(
            chipRow,
            LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        addView(
            chipScroll,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        val pinnedParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        pinnedParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(pinnedRow, pinnedParams)
    }

    /** 替换全部胶囊（调用方已在主线程；空列表时调用方应直接 hide，不走这里）。 */
    fun setSuggestions(pinned: List<View>, scrollable: List<View>) {
        chipRow.removeAllViews()
        pinnedRow.removeAllViews()
        scrollable.forEach { chipRow.addView(it) }
        pinned.forEach { pinnedRow.addView(it) }
        pinnedRow.measure(
            MeasureSpec.UNSPECIFIED,
            MeasureSpec.UNSPECIFIED,
        )
        chipScroll.setPadding(0, 0, pinnedRow.measuredWidth, 0)
    }

    /** 清空胶囊并释放视图（InlineContentView 随 detach 由框架回收）。 */
    fun clear() {
        chipRow.removeAllViews()
        pinnedRow.removeAllViews()
        chipScroll.setPadding(0, 0, 0, 0)
    }
}
