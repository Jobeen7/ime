package com.jobeen.ime.input.keyboard.key

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import splitties.dimensions.dp

class KeyboardPopup(private val context: Context) {

    private var popupWindow: PopupWindow? = null
    private var itemViews: List<TextView> = emptyList()
    var selectedIndex = 0
        private set
    private var keyPressedColor: Int = 0

    fun show(
        anchor: View,
        items: List<KeyboardAction>,
        textColor: Int,
        bgColor: Int,
        keyBgColor: Int,
        keyPressedColor: Int,
    ) {
        dismiss()

        this.keyPressedColor = keyPressedColor
        selectedIndex = 0

        val itemWidth = context.dp(42)
        val itemHeight = context.dp(36)
        val padding = context.dp(6)
        val gap = context.dp(4)
        val popupRadius = context.dp(8f)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = popupRadius
            }
        }

        val itemList = mutableListOf<TextView>()

        items.forEach { action ->
            val label = actionLabel(action)
            val keyView = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(textColor)
                setIncludeFontPadding(false)
            }
            itemList.add(keyView)
            container.addView(
                keyView,
                LinearLayout.LayoutParams(itemWidth, itemHeight).apply {
                    if (container.childCount > 1) marginStart = gap
                }
            )
        }
        itemViews = itemList

        val measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        container.measure(measureSpec, measureSpec)
        val totalWidth = container.measuredWidth
        val totalHeight = container.measuredHeight

        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val anchorCenterX = loc[0] + anchor.width / 2
        val anchorTop = loc[1]

        val popupX = (anchorCenterX - totalWidth / 2).coerceAtLeast(0)
        val popupY = (anchorTop - totalHeight - context.dp(6)).coerceAtLeast(0)

        val screenWidth = context.resources.displayMetrics.widthPixels
        val ratio = anchorCenterX.toFloat() / screenWidth
        selectedIndex = when {
            ratio < 1f / 3f -> 0
            ratio > 2f / 3f -> (itemCount - 1).coerceAtLeast(0)
            else -> (itemCount / 2).coerceAtLeast(0)
        }
        applyItemBackgrounds()

        popupWindow = PopupWindow(
            container, totalWidth, totalHeight, false,
        ).apply {
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = popupRadius
            })
            isOutsideTouchable = false
            isTouchable = false
            elevation = context.dp(8f)
            showAtLocation(anchor, Gravity.TOP or Gravity.START, popupX, popupY)
        }
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
        itemViews = emptyList()
    }

    fun selectIndex(index: Int): Boolean {
        if (index !in itemViews.indices) return false
        if (index == selectedIndex) return true
        selectedIndex = index
        applyItemBackgrounds()
        return true
    }

    val itemCount: Int get() = itemViews.size

    val itemStep: Float get() = context.dp(42 + 4).toFloat()

    private fun applyItemBackgrounds() {
        val itemRadius = context.dp(5f)
        itemViews.forEachIndexed { i, view ->
            if (i == selectedIndex) {
                view.background = GradientDrawable().apply {
                    setColor(keyPressedColor)
                    cornerRadius = itemRadius
                }
            } else {
                view.background = null
            }
        }
    }

    fun isShowing(): Boolean = popupWindow != null

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
