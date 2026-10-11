package com.jobeen.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent

@SuppressLint("ViewConstructor")
class ConfirmOverlay(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : ComponentView(context, colors) {

    private var onConfirm: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    private val pillR = dp(10f)
    private val textSize = 13f
    private val marginH = dp(12)
    private val marginV = dp(0)

    private val messageView = TextView(context).apply {
        setTextColor(colors.panel.toolbarText)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, this@ConfirmOverlay.textSize)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        isSingleLine = false
        maxLines = 4 // 限制最大行数，避免过长
    }

    private val confirmBtn = TextView(context).apply {
        text = context.getString(R.string.clipboard_confirm)
        setTextColor(colors.accentKeyText)
        textSize = this@ConfirmOverlay.textSize
        gravity = Gravity.CENTER
        background = PillBg(colors.accentKeyBackground, colors.accentKeyBorderStroke, pillR)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener {
            onConfirm?.invoke()
            dismiss()
        }
    }

    private val cancelBtn = TextView(context).apply {
        text = context.getString(R.string.clipboard_cancel)
        setTextColor(colors.panel.toolbarText)
        textSize = this@ConfirmOverlay.textSize
        gravity = Gravity.CENTER
        background = PillBg(colors.keyBackground, colors.keyBorderStroke, pillR)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener {
            onCancel?.invoke()
            dismiss()
        }
    }

    private val btnContainer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(confirmBtn, LinearLayout.LayoutParams(0, wrapContent, 1f))
        addView(cancelBtn, LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
            marginStart = dp(8)
        })
    }

    // 动作菜单模式的按钮列（纵向逐项排布），与确认模式共用同一张卡片
    private val actionsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }

    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = PillBg(colors.panel.background, colors.keyBorderStroke, pillR)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener { }

        // 限制卡片的最大宽度（例如屏幕宽度减去左右边距），防止文字过长时撑满全屏不换行
        // 这里可以通过布局参数或者在测量时限制，最稳妥的是给 messageView 设置 maxWidth
        addView(messageView, LinearLayout.LayoutParams(wrapContent, wrapContent).apply {
            // 如果需要限制文本的最大宽度，可以动态计算或给 card 设置固定的最大宽度
        })

        addView(btnContainer, LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = dp(4)
        })

        addView(actionsContainer, LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = dp(4)
        })
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        addView(card, LayoutParams(wrapContent, wrapContent))
        setOnClickListener { dismiss() }
    }

    /**
     * @param cardX  卡片左边相对此 Overlay 左边界的水平偏移（像素）。
     *               [Float.NaN] 表示默认放在右下角。
     * @param cardY  卡片顶边相对此 Overlay 上边界的垂直偏移（像素）。
     *               [Float.NaN] 表示默认放在右下角。
     */
    fun confirm(
        message: String,
        onConfirm: () -> Unit,
        onCancel: (() -> Unit)? = null,
        cardX: Float = Float.NaN,
        cardY: Float = Float.NaN,
        centerHorizontal: Boolean = false,
        centerVertical: Boolean = false,
    ) {
        this.onConfirm = onConfirm
        this.onCancel = onCancel
        messageView.text = message
        btnContainer.visibility = View.VISIBLE
        actionsContainer.visibility = View.GONE
        showCard(cardX, cardY, centerHorizontal, centerVertical)
    }

    /**
     * 动作菜单模式：标题下排布动作按钮，点击任一项执行并关闭。
     * 定位参数与 [confirm] 相同。
     *
     * 按钮两列排布（一行两项、奇数时末项整行）：单列纵排时 5 项
     * 菜单的总高会超过面板可用高度，纵向布局按上限截断后最后一项
     * 被挤出卡外不可见（剪贴板菜单的「删除」就这样消失过）——两列
     * 把高度压到一半以下，横竖屏与大字体下都不会再丢项。
     */
    fun actions(
        message: String,
        items: List<Pair<String, () -> Unit>>,
        cardX: Float = Float.NaN,
        cardY: Float = Float.NaN,
        centerHorizontal: Boolean = false,
        centerVertical: Boolean = false,
    ) {
        messageView.text = message
        btnContainer.visibility = View.GONE
        actionsContainer.visibility = View.VISIBLE
        // 两列排布需要确定总宽（权重子项在 wrapContent 下会撑满
        // 可用宽）：固定容器宽、卡片随之定宽，定位钳制照常生效
        actionsContainer.layoutParams =
            LinearLayout.LayoutParams(dp(204), wrapContent).apply {
                topMargin = dp(4)
            }
        actionsContainer.removeAllViews()
        actionButtons.clear()
        items.chunked(2).forEachIndexed { rowIndex, rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowItems.forEachIndexed { colIndex, (label, action) ->
                val btn = TextView(context).apply {
                    text = label
                    setTextColor(colors.panel.toolbarText)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, this@ConfirmOverlay.textSize)
                    gravity = Gravity.CENTER
                    background = PillBg(colors.keyBackground, colors.keyBorderStroke, pillR)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    setOnClickListener {
                        action()
                        dismiss()
                    }
                }
                row.addView(
                    btn,
                    LinearLayout.LayoutParams(0, wrapContent, 1f).apply {
                        if (colIndex > 0) marginStart = dp(6)
                    }
                )
                actionButtons += btn
            }
            actionsContainer.addView(
                row,
                LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                    if (rowIndex > 0) topMargin = dp(6)
                }
            )
        }
        showCard(cardX, cardY, centerHorizontal, centerVertical)
    }

    // 主题刷新时动作按钮也要跟着换色，集中登记
    private val actionButtons = mutableListOf<TextView>()

    private fun showCard(
        cardX: Float,
        cardY: Float,
        centerHorizontal: Boolean,
        centerVertical: Boolean,
    ) {
        bringToFront()
        card.alpha = 0f
        card.scaleX = 0f
        card.scaleY = 0f
        visibility = VISIBLE

        card.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                card.removeOnLayoutChangeListener(this)
                val maxWidth = (measuredWidth - marginH * 2).coerceAtLeast(dp(200))
                messageView.maxWidth = maxWidth

                card.pivotX = card.width / 2f
                card.pivotY = card.height / 2f

                val cx =
                    if (cardX.isNaN()) measuredWidth - card.measuredWidth - marginH else cardX.toInt()
                val cy =
                    if (cardY.isNaN()) measuredHeight - card.measuredHeight - marginV else cardY.toInt()
                val maxX = (measuredWidth - card.measuredWidth - marginH).coerceAtLeast(marginH)
                val maxY = (measuredHeight - card.measuredHeight - marginV).coerceAtLeast(marginV)
                val finalCx =
                    if (centerHorizontal) (measuredWidth - card.measuredWidth) / 2 else cx
                val finalCy =
                    if (centerVertical) (measuredHeight - card.measuredHeight) / 2 else cy
                card.translationX = finalCx.coerceIn(marginH, maxX).toFloat()
                card.translationY = finalCy.coerceIn(marginV, maxY).toFloat()

                card.scaleX = 0f
                card.scaleY = 0f
                card.alpha = 1f
                invalidate()
                card.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(OvershootInterpolator(2f))
                    .start()
            }
        })
    }

    fun dismiss() {
        if (visibility != VISIBLE) return
        card.animate().cancel()
        animate().cancel()
        visibility = GONE
        card.scaleX = 1f
        card.scaleY = 1f
        card.alpha = 1f
        card.translationX = 0f
        card.translationY = 0f
    }

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        // 不调用 super：父类会把整块背景设为 panel.background，导致刷新主题后罩住全屏。
        // ConfirmOverlay 仅居中显示小卡片，背景必须保持透明。
        colors = newColors
        messageView.setTextColor(newColors.panel.toolbarText)
        confirmBtn.setTextColor(newColors.accentKeyText)
        confirmBtn.background =
            PillBg(newColors.accentKeyBackground, newColors.accentKeyBorderStroke, pillR)
        cancelBtn.setTextColor(newColors.panel.toolbarText)
        cancelBtn.background =
            PillBg(newColors.keyBackground, newColors.keyBorderStroke, pillR)
        actionButtons.forEach { btn ->
            btn.setTextColor(newColors.panel.toolbarText)
            btn.background =
                PillBg(newColors.keyBackground, newColors.keyBorderStroke, pillR)
        }
        card.background =
            PillBg(newColors.panel.background, newColors.keyBorderStroke, pillR)
    }
}

private class PillBg(
    fillColor: Int,
    strokeColor: Int,
    radius: Float,
) : android.graphics.drawable.GradientDrawable() {
    init {
        setColor(fillColor)
        setStroke(1, strokeColor)
        cornerRadius = radius
    }
}
