package com.ninthsoft.ime.input.panel.component

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.key.CustomGestureView
import com.ninthsoft.ime.input.keyboard.key.borderedKeyBackgroundDrawable
import com.ninthsoft.ime.input.keyboard.key.flatKeyBackgroundDrawable
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams as frameLParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageResource
import splitties.views.textResource

@SuppressLint("ViewConstructor")
class TextEditView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    private val buttonBordered: Boolean = false,
    private val buttonRadius: Float = colors.cornerRadius,
) : ComponentView(context, colors) {

    sealed class Action {
        data class MoveLeft(val shift: Boolean) : Action()
        data class MoveRight(val shift: Boolean) : Action()
        data class MoveUp(val shift: Boolean) : Action()
        data class MoveDown(val shift: Boolean) : Action()
        data class MoveHome(val shift: Boolean) : Action()
        data class MoveEnd(val shift: Boolean) : Action()
        data object SelectToggle : Action()
        data object CancelSelection : Action()
        data object SelectAll : Action()
        data object Cut : Action()
        data object Copy : Action()
        data object Paste : Action()
        data object Backspace : Action()
    }

    var onAction: ((Action) -> Unit)? = null

    private var hasSelection = false
    private var userSelection = false
    private var hasContent = false

    private fun TextEditingButton.onClickRepeating(block: () -> Unit) {
        setOnClickListener { block() }
        repeatEnabled = true
        onRepeatListener = { block() }
    }

    private fun moveDirection(block: (Boolean) -> Action) = block(userSelection || hasSelection)

    private val leftButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, false).apply {
            setIcon(R.drawable.ic_keyboard_arrow_left)
            contentDescription = context.getString(R.string.move_cursor_left)
        }
    private val upButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, false).apply {
            setIcon(R.drawable.ic_keyboard_arrow_up)
            contentDescription = context.getString(R.string.move_cursor_up)
        }
    private val downButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, false).apply {
            setIcon(R.drawable.ic_keyboard_arrow_down)
            contentDescription = context.getString(R.string.move_cursor_down)
        }
    private val rightButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, false).apply {
            setIcon(R.drawable.ic_keyboard_arrow_right)
            contentDescription = context.getString(R.string.move_cursor_right)
        }
    private val selectButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, false).apply {
            setText(R.string.select)
            enableActivatedState()
            contentDescription = context.getString(R.string.select)
        }
    private val homeButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, false).apply {
            setIcon(R.drawable.ic_keyboard_first_page)
            contentDescription = context.getString(R.string.move_cursor_to_start)
        }
    private val endButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, false).apply {
            setIcon(R.drawable.ic_keyboard_last_page)
            contentDescription = context.getString(R.string.move_cursor_to_end)
        }
    private val selectAllButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, true).apply {
            setText(R.string.select_all)
        }
    private val cutButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, true).apply {
            setText(R.string.cut)
            visibility = View.GONE
        }
    private val copyButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, true).apply {
            setText(R.string.copy)
        }
    private val pasteButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, true).apply {
            setText(R.string.paste)
        }
    private val backspaceButton =
        TextEditingButton(context, colors, buttonBordered, buttonRadius, true).apply {
            setIcon(R.drawable.ic_keyboard_backspace)
            contentDescription = context.getString(R.string.backspace)
        }
    val root: View = constraintLayout {
        add(leftButton, lParams {
            topOfParent()
            leftOfParent()
            above(homeButton)
            rightToLeftOf(selectButton)
        })
        add(upButton, lParams {
            topOfParent()
            leftToRightOf(leftButton)
            above(selectButton)
            rightToLeftOf(rightButton)
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(selectButton, lParams {
            below(upButton)
            leftToRightOf(leftButton)
            above(downButton)
            rightToLeftOf(rightButton)
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(downButton, lParams {
            below(selectButton)
            leftToRightOf(leftButton)
            above(homeButton)
            rightToLeftOf(rightButton)
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(rightButton, lParams {
            topOfParent()
            leftToRightOf(selectButton)
            above(endButton)
            rightToLeftOf(selectAllButton)
        })
        add(homeButton, lParams {
            below(downButton)
            leftOfParent()
            bottomOfParent()
            rightToLeftOf(endButton)
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(endButton, lParams {
            below(downButton)
            leftToRightOf(homeButton)
            bottomOfParent()
            rightToLeftOf(backspaceButton)
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
            matchConstraintDefaultHeight = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(selectAllButton, lParams {
            topOfParent()
            leftToRightOf(rightButton)
            rightOfParent()
            above(cutButton)
            matchConstraintPercentWidth = 0.3f
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(cutButton, lParams {
            below(selectAllButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(copyButton)
            matchConstraintPercentWidth = 0.3f
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(copyButton, lParams {
            below(cutButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(pasteButton)
            matchConstraintPercentWidth = 0.3f
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(pasteButton, lParams {
            below(copyButton)
            leftToRightOf(rightButton)
            rightOfParent()
            above(backspaceButton)
            matchConstraintPercentWidth = 0.3f
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
        add(backspaceButton, lParams {
            below(pasteButton)
            leftToRightOf(rightButton)
            rightOfParent()
            bottomOfParent()
            matchConstraintPercentWidth = 0.3f
            matchConstraintDefaultWidth = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT_SPREAD
        })
    }

    init {
        addView(root, LayoutParams(matchParent, matchParent))
        wireListeners()
        updateSelection(hasSelection, userSelection)
    }

    private fun wireListeners() {
        leftButton.onClickRepeating { onAction?.invoke(moveDirection { Action.MoveLeft(it) }) }
        upButton.onClickRepeating { onAction?.invoke(moveDirection { Action.MoveUp(it) }) }
        downButton.onClickRepeating { onAction?.invoke(moveDirection { Action.MoveDown(it) }) }
        rightButton.onClickRepeating { onAction?.invoke(moveDirection { Action.MoveRight(it) }) }
        backspaceButton.onClickRepeating {
            userSelection = false
            updateSelection(hasSelection, userSelection)
            onAction?.invoke(Action.Backspace)
        }

        homeButton.setOnClickListener { onAction?.invoke(moveDirection { Action.MoveHome(it) }) }
        endButton.setOnClickListener { onAction?.invoke(moveDirection { Action.MoveEnd(it) }) }
        selectButton.setOnClickListener {
            if (hasSelection) {
                userSelection = false
                updateSelection(hasSelection, userSelection)
                onAction?.invoke(Action.CancelSelection)
            } else if (!userSelection && !hasContent) {
                return@setOnClickListener
            } else {
                userSelection = !userSelection
                updateSelection(false, userSelection)
                onAction?.invoke(Action.SelectToggle)
            }
        }
        selectAllButton.setOnClickListener {
            userSelection = true
            updateSelection(false, userSelection)
            onAction?.invoke(Action.SelectAll)
        }
        cutButton.setOnClickListener {
            userSelection = false
            updateSelection(hasSelection, userSelection)
            onAction?.invoke(Action.Cut)
        }
        copyButton.setOnClickListener {
            userSelection = false
            updateSelection(hasSelection, userSelection)
            onAction?.invoke(Action.Copy)
        }
        pasteButton.setOnClickListener {
            userSelection = false
            updateSelection(hasSelection, userSelection)
            onAction?.invoke(Action.Paste)
        }
    }

    fun updateSelection(hasSelection: Boolean, userSelection: Boolean) {
        this.hasSelection = hasSelection
        this.userSelection = userSelection
        selectButton.isActivated = hasSelection || userSelection
        if (hasSelection) {
            selectAllButton.visibility = View.GONE
            cutButton.visibility = View.VISIBLE
        } else {
            selectAllButton.visibility = View.VISIBLE
            cutButton.visibility = View.GONE
        }
    }

    fun setSelection(start: Int, end: Int) {
        updateSelection(start != end, userSelection)
    }

    fun onInputChanged(text: String) {
        hasContent = text.isNotEmpty()
    }

    override fun show() {
        if (visibility != VISIBLE) {
            hasSelection = false
            userSelection = false
            updateSelection(false, false)
            super.show()
        }
    }

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        super.refreshTheme(newColors)
        val buttons = listOf(
            leftButton, upButton, downButton, rightButton,
            selectButton, homeButton, endButton,
            selectAllButton, cutButton, copyButton, pasteButton, backspaceButton,
        )
        buttons.forEach { it.refreshColors(newColors) }
    }
}

@SuppressLint("ViewConstructor")
class TextEditingButton(
    context: Context,
    private var colors: KeyboardColors.ColorScheme,
    private val bordered: Boolean,
    private val radius: Float,
    private val altStyle: Boolean = false,
) : CustomGestureView(context) {

    private val strokeWidth = dp(1)
    private val hInset = dp(3)
    private val vInset = dp(4)

    private val bgActive: Int get() = colors.accentKeyBackground
    private val bgNormal: Int get() = if (altStyle) colors.specialKeyBackground else colors.keyBackground
    private val textActive: Int get() = colors.accentKeyText
    private val textNormal: Int get() = if (altStyle) colors.specialKeyText else colors.keyText
    private val strokeNormal: Int get() = if (altStyle) colors.specialKeyBorderStroke else colors.keyBorderStroke
    private val strokeActive: Int get() = colors.accentKeyBorderStroke
    private val pressedColor: Int get() = if (altStyle) colors.specialKeyPressed else colors.keyPressed
    private val pressedActive: Int get() = colors.accentKeyPressed

    private var hasActivatedState = false
    private var pressedLayerAlpha = 0
    private var wasPressed = false
    private var bgAnimator: Animator? = null

    val textView: TextView = textView {
        isClickable = false
        isFocusable = false
        background = null
        setTextColor(textNormal)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
    }

    val imageView: ImageView = imageView {
        isClickable = false
        isFocusable = false
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = ColorStateList.valueOf(textNormal)
    }

    init {
        isHapticFeedbackEnabled = false
        isSoundEffectsEnabled = false
        buildBackground()
    }

    fun setText(resId: Int) {
        textView.textResource = resId
        removeView(imageView)
        add(textView, frameLParams(wrapContent, wrapContent) {
            gravity = Gravity.CENTER
        })
    }

    fun setIcon(resId: Int) {
        imageView.imageResource = resId
        removeView(textView)
        add(imageView, frameLParams(dp(26), dp(26)) {
            gravity = Gravity.CENTER
        })
    }

    fun enableActivatedState() {
        hasActivatedState = true
        applyTextColors()
        buildBackground()
    }

    private fun applyTextColors() {
        if (hasActivatedState) {
            textView.setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_activated),
                        intArrayOf(),
                    ),
                    intArrayOf(textActive, textNormal),
                ),
            )
            imageView.imageTintList = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_activated),
                    intArrayOf(),
                ),
                intArrayOf(textActive, textNormal),
            )
        } else {
            textView.setTextColor(textNormal)
            imageView.imageTintList = ColorStateList.valueOf(textNormal)
        }
    }

    fun refreshColors(newColors: KeyboardColors.ColorScheme) {
        colors = newColors
        applyTextColors()
        buildBackground()
    }

    private fun buildBackground() {
        val activated = hasActivatedState && isActivated
        val bgColor = if (activated) bgActive else bgNormal
        var strokeColor = if (activated) strokeActive else strokeNormal
        val pColor = if (activated) pressedActive else pressedColor

        if (!bordered) {
            strokeColor = Color.TRANSPARENT
        }

        val normalBg =
            borderedKeyBackgroundDrawable(bgColor, strokeColor, radius, strokeWidth, hInset, vInset)
        val pressedBg = if (bordered) {
            borderedKeyBackgroundDrawable(pColor, strokeColor, radius, strokeWidth, hInset, vInset)
        } else {
            flatKeyBackgroundDrawable(pColor, strokeColor, radius, strokeWidth, hInset, vInset)
        }
        val layered = LayerDrawable(arrayOf(normalBg, pressedBg))
        pressedBg.alpha = pressedLayerAlpha
        background = layered
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (hasActivatedState) {
            buildBackground()
        }
        val bg = background
        if (bg !is LayerDrawable || bg.numberOfLayers < 2) return

        val pressedChanged = isPressed != wasPressed
        wasPressed = isPressed
        if (!pressedChanged) return

        bgAnimator?.cancel()
        val pressedDrawable = bg.getDrawable(1) ?: return
        fun anim(from: Int, to: Int, durationMs: Long) = ValueAnimator.ofInt(from, to).apply {
            duration = durationMs
            addUpdateListener {
                val alpha = animatedValue as Int
                pressedLayerAlpha = alpha
                pressedDrawable.alpha = alpha
            }
        }
        bgAnimator = if (isPressed) {
            anim(pressedLayerAlpha, 255, 220).also { it.start() }
        } else {
            AnimatorSet().apply {
                playSequentially(anim(pressedLayerAlpha, 255, 70), anim(255, 0, 180))
                start()
            }
        }
    }
}