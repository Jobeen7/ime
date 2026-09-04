package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.inputmethod.EditorInfo
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.keyboard.key.AltTextKeyView
import com.ninthsoft.ime.input.keyboard.key.CustomGestureView
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.ImageTextKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyPreviewPopup
import com.ninthsoft.ime.input.keyboard.key.KeyboardPopup
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardRippleView
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import kotlin.math.roundToInt
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.imageResource
import timber.log.Timber

abstract class BaseKeyboard(
    context: Context,
    protected val colors: KeyboardColors.ColorScheme,
    private val keyLayout: List<List<KeyDef>>,
) : ConstraintLayout(context), IKeyboard {
    override var keyActionListener: KeyActionListener? = null
    var expandKeypressArea = false
    private val previewPopup = KeyPreviewPopup(context)
    private val keyboardPopup = KeyboardPopup(context)
    protected val keyRows: List<ConstraintLayout>
    val rippleView: KeyboardRippleView

    private var spanPanelViews: List<KeyView> = emptyList()
    private var spaceKeyView: TextKeyView? = null
    private var returnKeyView: ImageKeyView? = null
    private var returnKeyIcon: Int = 0

    override fun updateSpaceKeyText(text: String) {
        spaceKeyView?.updateText(text)
    }

    protected open fun updateSidePanel(items: List<KeyDef>) {
        (spanPanelViews.firstOrNull() as? SidePanelKeyView)?.updateItems(items)
    }

    protected open fun resetSidePanelPosition() {
        (spanPanelViews.firstOrNull() as? SidePanelKeyView)?.resetPosition()
    }

    fun setSidePanelItemListener(listener: (KeyboardAction) -> Unit) {
        (spanPanelViews.firstOrNull() as? SidePanelKeyView)?.setOnItemActionListener(listener)
    }

    init {
        data class SpanDef(val def: KeyDef, val startRow: Int, val endRow: Int)

        val spanDefs = mutableListOf<SpanDef>()
        for (ri in keyLayout.indices) {
            for (def in keyLayout[ri]) {
                if (def.appearance is KeyDef.Appearance.SidePannel && def.appearance.rowSpan > 1) {
                    spanDefs.add(
                        SpanDef(
                            def,
                            ri,
                            (ri + def.appearance.rowSpan - 1).coerceAtMost(keyLayout.size - 1)
                        )
                    )
                }
            }
        }
        val spanViews = spanDefs.map { createKeyView(it.def).also { v -> v.id = generateViewId() } }
        spanPanelViews = spanViews

        keyRows = keyLayout.mapIndexed { rowIndex, row ->
            val parts =
                row.filterNot { it.appearance is KeyDef.Appearance.SidePannel && it.appearance.rowSpan > 1 }
            val keyViews = parts.map(::createKeyView)
            val sidePanelAtRow = spanDefs.indexOfFirst { rowIndex in it.startRow..it.endRow }
            val spanScale = if (sidePanelAtRow >= 0) {
                1f / (1f - spanDefs[sidePanelAtRow].def.appearance.percentWidth)
            } else 1f

            constraintLayout {
                var totalWidth = 0f
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        matchConstraintPercentWidth =
                            parts[index].appearance.percentWidth * spanScale
                    })
                    (parts[index].appearance.percentWidth * spanScale).let {
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    val firstScaledPercent = parts.first().appearance.percentWidth * spanScale
                    val lastScaledPercent = parts.last().appearance.percentWidth * spanScale
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (firstScaledPercent + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (lastScaledPercent + free)
                    }
                }
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                val sidePanelAtRow = spanDefs.indexOfFirst { index in it.startRow..it.endRow }
                if (sidePanelAtRow >= 0) {
                    leftToRightOf(spanViews[sidePanelAtRow])
                } else {
                    leftOfParent()
                }
                rightOfParent()
            })
        }

        for (i in spanDefs.indices) {
            val sd = spanDefs[i]
            add(spanViews[i], lParams {
                topToTop = keyRows[sd.startRow].id
                bottomToBottom = keyRows[sd.endRow].id
                leftOfParent()
                matchConstraintPercentWidth = sd.def.appearance.percentWidth
            })
        }

        rippleView = KeyboardRippleView(context).apply {
            id = generateViewId()
            isClickable = false
            isEnabled = false
            isFocusable = false
        }
        add(rippleView, lParams {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    protected fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.SidePannel -> SidePanelKeyView(context, colors, def.appearance)
        }.apply {
            if (def.appearance.viewId == KeyView.button_return && this is ImageKeyView) {
                returnKeyView = this
            }
            if (def.appearance.viewId == KeyView.button_space && this is TextKeyView) {
                spaceKeyView = this
            }
            borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
            onPressedChanged = { key ->
                if (key.isPressed) {
                    val isSwitchAction = def.behaviors.any { b ->
                        b is KeyDef.Behavior.Press && (b.action is KeyboardAction.LayoutSwitchAction || b.action is KeyboardAction.RotateSchema)
                    }
                    if (!isSwitchAction) {
                        val keyLoc = IntArray(2)
                        val boardLoc = IntArray(2)
                        key.getLocationOnScreen(keyLoc)
                        this@BaseKeyboard.getLocationOnScreen(boardLoc)
                        val cx = keyLoc[0] + key.width / 2f - boardLoc[0]
                        val cy = keyLoc[1] + key.height / 2f - boardLoc[1]
                        rippleView.startRipple(cx, cy, key)
                    }
                }
            }
            if (this is SidePanelKeyView) {
                onRippleRequest = { screenX, screenY ->
                    val boardLoc = IntArray(2)
                    this@BaseKeyboard.getLocationOnScreen(boardLoc)
                    rippleView.startRipple(screenX - boardLoc[0], screenY - boardLoc[1], this)
                }
            }
            def.popups?.forEach { popup ->
                when (popup) {
                    is KeyDef.Popup.Preview -> {
                        setOnLongClickListener {
                            showPreview(it as KeyView)
                            return@setOnLongClickListener true
                        }
                        onTouchUpListener = { previewPopup.dismiss() }
                    }

                    is KeyDef.Popup.Keyboard -> {
                        var selectedIndex = 0
                        var startIndex = 0
                        var originX = 0f
                        var initialMove = true
                        setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (keyboardPopup.isShowing()) {
                                        if (initialMove) {
                                            originX = event.x
                                            startIndex = keyboardPopup.selectedIndex
                                            initialMove = false
                                        }
                                        val dx = event.x - originX
                                        val step = keyboardPopup.itemStep
                                        val idx = (startIndex + dx / step).roundToInt()
                                            .coerceIn(0, keyboardPopup.itemCount - 1)
                                        if (idx != selectedIndex) {
                                            selectedIndex = idx
                                            keyboardPopup.selectIndex(idx)
                                        }
                                        return@setOnTouchListener true
                                    }
                                }

                                else -> {}
                            }
                            false
                        }
                        setOnLongClickListener {
                            showKeyboardPopup(it as KeyView, popup.keys)
                            selectedIndex = keyboardPopup.selectedIndex
                            startIndex = selectedIndex
                            initialMove = true
                            return@setOnLongClickListener true
                        }
                        onTouchUpListener = {
                            if (keyboardPopup.isShowing()) {
                                val item = popup.keys.getOrNull(selectedIndex)
                                if (item != null) {
                                    onAction(item)
                                }
                                keyboardPopup.dismiss()
                            }
                        }
                    }

                    else -> {}
                }
            }
            def.behaviors.forEach { behavior ->
                when (behavior) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener({
                            onAction(behavior.action)
                        })
                    }

                    is KeyDef.Behavior.LongPress -> {
                        longPressEnabled = true
                        setOnLongClickListener {
                            onAction(behavior.action)
                            return@setOnLongClickListener true
                        }
                        if (behavior.action is KeyboardAction.VoiceInputAction) {
                            onTouchMoveListener = { rawX, rawY ->
                                onAction(KeyboardAction.VoiceDragPosition(rawX, rawY))
                            }
                            onTouchUpListener = {
                                onAction(KeyboardAction.VoiceDragUp)
                            }
                        }
                    }

                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { onAction(behavior.action) }
                    }

                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = dp(800f)
                        swipeThresholdY = dp(36f)
                        onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                            when (event.type) {
                                CustomGestureView.GestureType.Up -> {
                                    if (!event.consumed && event.totalY < 0) {
                                        onAction(behavior.action)
                                        true
                                    } else false
                                }

                                else -> false
                            }
                        }
                    }

                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { onAction(behavior.action) }
                    }
                }
            }
        }
    }

    private fun showPreview(view: KeyView) {
        val text = view.displayText ?: return
        previewPopup.show(
            anchor = view,
            text = text,
            textColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyText
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
                KeyDef.Appearance.Variant.None -> colors.keyText
            },
            bgColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyBackground
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyBackground
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyBackground
                KeyDef.Appearance.Variant.None -> Color.TRANSPARENT
            },
        )
    }

    private fun showKeyboardPopup(view: KeyView, keys: List<KeyboardAction>) {
        keyboardPopup.show(
            anchor = view,
            items = keys,
            textColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyText
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
                KeyDef.Appearance.Variant.None -> colors.keyText
            },
            bgColor = colors.keyBackground,
            keyBgColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyBackground
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyBackground
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyBackground
                KeyDef.Appearance.Variant.None -> Color.TRANSPARENT
            },
            keyPressedColor = colors.accentKeyBackground,
        )
    }

    protected open fun onAction(action: KeyboardAction) {
        val transformed = if (action is KeyboardAction.ReturnAction) {
            if (action.force) {
                action
            } else {
                when (returnKeyIcon) {
                    R.drawable.ic_keyboard_send -> KeyboardAction.MultiReturnAction("SEND")
                    R.drawable.ic_keyboard_search -> KeyboardAction.MultiReturnAction("SEARCH")
                    R.drawable.ic_keyboard_go -> KeyboardAction.MultiReturnAction("GO")
                    R.drawable.ic_keyboard_arrow_right -> KeyboardAction.MultiReturnAction("NEXT")
                    R.drawable.ic_keyboard_done -> KeyboardAction.MultiReturnAction("DONE")
                    R.drawable.ic_keyboard_arrow_left -> KeyboardAction.MultiReturnAction("PREVIOUS")
                    else -> action
                }
            }
        } else {
            action
        }
        keyActionListener?.onKeyAction(transformed)
    }

    override fun onAttach() = Timber.d("Keyboard onAttach")

    override fun onDetach() {
        Timber.d("Keyboard onDetach")
        rippleView.cancelRipple()
        resetSidePanelPosition()
        previewPopup.dismiss()
        keyboardPopup.dismiss()
    }

    abstract override fun name(): String

    override fun setRippleEnabled(enabled: Boolean) {
        rippleView.rippleEnabled = enabled
        rippleView.visibility = if (enabled) VISIBLE else INVISIBLE
    }


    override fun updateEditorInfo(info: EditorInfo, empty: Boolean, isComposing: Boolean) {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val icon = if (empty || isComposing) {
            R.drawable.ic_keyboard_return
        } else {
            when (action) {
                EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_keyboard_search
                EditorInfo.IME_ACTION_SEND -> R.drawable.ic_keyboard_send
                EditorInfo.IME_ACTION_GO -> R.drawable.ic_keyboard_go
                EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_keyboard_arrow_left
                EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_keyboard_arrow_right
                EditorInfo.IME_ACTION_DONE -> R.drawable.ic_keyboard_done
                else -> R.drawable.ic_keyboard_return
            }
        }
        if (icon != returnKeyIcon) {
            returnKeyIcon = icon
            returnKeyView?.img?.imageResource = returnKeyIcon
        }
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        keyRows.forEach { row ->
            for (index in 0 until row.childCount) {
                (row.getChildAt(index) as? KeyView)?.updateMode(mode)
            }
        }
        spanPanelViews.forEach { it.updateMode(mode) }
    }
}
