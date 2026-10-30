package com.jobeen.ime.input.keyboard.key

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import splitties.dimensions.dp

class KeyboardPopup(private val context: Context) {

    private var popupWindow: PopupWindow? = null
    private var container: LinearLayout? = null
    private var containerBg: GradientDrawable? = null
    private var windowBg: GradientDrawable? = null
    private var itemViews: List<TextView> = emptyList()
    var selectedIndex = 0
        private set
    private var keyPressedColor: Int = 0

    // 选中项背景复用一份：旧实现每次滑动换选都新建 GradientDrawable；
    // 颜色在每次应用时按当前 keyPressedColor 重设，换色自动跟随
    private val selectedItemBg = GradientDrawable()

    // 视图层级复用：旧实现每次 show 都重建 LinearLayout + 全部 TextView +
    // PopupWindow（长按弹层是用户高频操作）。现在容器/窗口/条目视图各建
    // 一次：条目数变化才增删视图，颜色/文案变化只更新属性。

    fun show(
        anchor: View,
        items: List<KeyboardAction>,
        textColor: Int,
        bgColor: Int,
        keyBgColor: Int,
        keyPressedColor: Int,
    ) {
        popupWindow?.dismiss()

        this.keyPressedColor = keyPressedColor
        selectedIndex = 0

        val itemWidth = context.dp(42)
        val itemHeight = context.dp(36)
        val padding = context.dp(6)
        val gap = context.dp(4)
        val popupRadius = context.dp(8f)

        val cont = container ?: LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
        }.also { container = it }
        val cbg = containerBg ?: GradientDrawable().also { containerBg = it }
        cbg.setColor(bgColor)
        cbg.cornerRadius = popupRadius
        if (cont.background !== cbg) cont.background = cbg

        // 条目视图按数量增删，文案/颜色逐个刷新
        if (itemViews.size != items.size) {
            cont.removeAllViews()
            val itemList = ArrayList<TextView>(items.size)
            items.forEachIndexed { index, _ ->
                val keyView = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                    setIncludeFontPadding(false)
                }
                itemList.add(keyView)
                cont.addView(
                    keyView,
                    LinearLayout.LayoutParams(itemWidth, itemHeight).apply {
                        if (index > 0) marginStart = gap
                    }
                )
            }
            itemViews = itemList
        }
        items.forEachIndexed { i, action ->
            itemViews[i].text = actionLabel(action)
            itemViews[i].setTextColor(textColor)
        }

        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        cont.measure(measureSpec, measureSpec)
        val totalWidth = cont.measuredWidth
        val totalHeight = cont.measuredHeight

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val anchorCenterX = loc[0] + anchor.width / 2
        val anchorTop = loc[1]

        // 气泡溢出检测（默认开）：窗口尺寸已知时走调位解析
        // （上方不够翻到下方、下方不够贴底边、横向夹内）；
        // 关闭或窗口尺寸未知时保持旧行为（只贴边夹取）。
        val overflowGap = context.dp(6)
        val rootView = anchor.rootView
        val checkEnabled = SkinSnapshotProvider.peek()?.bubbleOverflowCheck ?: true
        val (popupX, popupY) = if (checkEnabled && rootView != null &&
            rootView.width > 0 && rootView.height > 0
        ) {
            val pos = PopupOverflow.resolve(
                anchorCenterX, anchorTop, anchor.height,
                totalWidth, totalHeight,
                rootView.width, rootView.height, overflowGap,
            )
            pos.x to pos.y
        } else {
            (anchorCenterX - totalWidth / 2).coerceAtLeast(0) to
                (anchorTop - totalHeight - overflowGap).coerceAtLeast(0)
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        val ratio = anchorCenterX.toFloat() / screenWidth
        selectedIndex = when {
            ratio < 1f / 3f -> 0
            ratio > 2f / 3f -> (itemCount - 1).coerceAtLeast(0)
            else -> (itemCount / 2).coerceAtLeast(0)
        }
        applyItemBackgrounds()

        val wbg = windowBg ?: GradientDrawable().also { windowBg = it }
        wbg.setColor(bgColor)
        wbg.cornerRadius = popupRadius
        val pw = popupWindow ?: PopupWindow(cont, totalWidth, totalHeight, false).apply {
            isOutsideTouchable = false
            isTouchable = false
            elevation = context.dp(8f)
        }.also { popupWindow = it }
        if (pw.contentView !== cont) pw.contentView = cont
        pw.setBackgroundDrawable(wbg)
        pw.width = totalWidth
        pw.height = totalHeight
        pw.showAtLocation(anchor, Gravity.TOP or Gravity.START, popupX, popupY)
    }

    fun dismiss() {
        popupWindow?.dismiss()
    }

    fun selectIndex(index: Int): Boolean {
        if (index !in itemViews.indices) return false
        if (index == selectedIndex) return true
        selectedIndex = index
        applyItemBackgrounds()
        return true
    }

    /**
     * 清除高亮（接线轮 R5/R6：出界取消时的视觉态——无选中项，
     * 抬手不提交；下次 show() 会按锚点重算初始选中）。
     */
    fun clearSelection() {
        selectedIndex = -1
        applyItemBackgrounds()
    }

    val itemCount: Int get() = itemViews.size

    val itemStep: Float get() = context.dp(42 + 4).toFloat()

    private fun applyItemBackgrounds() {
        // 同一份 Drawable 同一时刻只能挂在一个 View 上：先从旧选中项
        // 摘下再挂到新选中项，避免迭代顺序让它短暂挂在两个 View 上
        itemViews.forEach { view ->
            if (view.background === selectedItemBg) view.background = null
        }
        selectedItemBg.setColor(keyPressedColor)
        selectedItemBg.cornerRadius = context.dp(5f)
        itemViews.getOrNull(selectedIndex)?.background = selectedItemBg
    }

    fun isShowing(): Boolean = popupWindow?.isShowing == true

    /** 弹出项的显示文本（接线轮 R6：长按格解析的格内容；行格无空位）。 */
    fun itemLabel(action: KeyboardAction): String = actionLabel(action)

    private fun actionLabel(action: KeyboardAction): String = when (action) {
        is KeyboardAction.CommitAction -> action.text
        is KeyboardAction.KeySequenceAction -> action.sequence
        // 其余类型用硬编码名称：javaClass.simpleName 经 R8 混淆后会变成 "a" 这类无意义名字
        is KeyboardAction.KeyCodeAction -> "KeyCodeAction"
        is KeyboardAction.ClearAction -> "ClearAction"
        is KeyboardAction.SelectCandidatePinYin -> "SelectCandidatePinYin"
        is KeyboardAction.CapsAction -> "CapsAction"
        is KeyboardAction.LayoutSwitchAction -> "LayoutSwitchAction"
        is KeyboardAction.ResumeAction -> "ResumeAction"
        is KeyboardAction.BackspaceAction -> "BackspaceAction"
        is KeyboardAction.ReturnAction -> "ReturnAction"
        is KeyboardAction.SpaceAction -> "SpaceAction"
        is KeyboardAction.CursorMoveAction -> "CursorMoveAction"
        is KeyboardAction.LangSwitchAction -> "LangSwitchAction"
        is KeyboardAction.RotateSchema -> "RotateSchema"
        is KeyboardAction.SelectSchema -> "SelectSchema"
        is KeyboardAction.SwitchSchemaByValue -> "SwitchSchemaByValue"
        is KeyboardAction.ShowInputMethodPickerAction -> "ShowInputMethodPickerAction"
        is KeyboardAction.VoiceInputAction -> "VoiceInputAction"
        is KeyboardAction.StopVoiceInputAction -> "StopVoiceInputAction"
        is KeyboardAction.VoiceDragPosition -> "VoiceDragPosition"
        is KeyboardAction.VoiceDragUp -> "VoiceDragUp"
        is KeyboardAction.MultiReturnAction -> "MultiReturnAction"
        is KeyboardAction.UndoAction -> "UndoAction"
        is KeyboardAction.RedoAction -> "RedoAction"
    }
}
