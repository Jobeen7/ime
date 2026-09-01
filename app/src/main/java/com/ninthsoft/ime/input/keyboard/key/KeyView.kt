package com.ninthsoft.ime.input.keyboard.key

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.PunctuationMode.CharacterSet
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Border
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.widget.SidePanelRender
import com.ninthsoft.ime.input.keyboard.key.widget.SidePanelView
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.parentId
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.imageResource
import splitties.views.padding
import kotlin.math.min
import kotlin.math.roundToInt

abstract class KeyView(
    ctx: Context,
    protected var colors: KeyboardColors.ColorScheme,
    val def: KeyDef.Appearance,
) : CustomGestureView(ctx), IPunctuationModeKey {

    override fun updateMode(punctuationMode: PunctuationMode) = Unit

    var bordered: Boolean = true
    var borderStroke: Boolean = true
        set(value) {
            field = value
            setupBackgroundWithPress()
        }
    var radius = colors.cornerRadius.let { dp(it) }
    var hMargin: Int = colors.keyHMargin.let { dp(it).toInt() }
    var vMargin: Int = colors.keyVMargin.let { dp(it).toInt() }
    var onPressedChanged: ((KeyView) -> Unit)? = null
    private var wasPressed = false

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false
    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    @FloatRange(0.0, 1.0)
    var layoutMarginLeft = 0f

    @FloatRange(0.0, 1.0)
    var layoutMarginRight = 0f

    open val displayText: String? get() = null

    protected val appearanceView = constraintLayout {
        isDuplicateParentStateEnabled = true
    }

    init {
        isEnabled = true
        isHapticFeedbackEnabled = false
        if (def.viewId > 0) {
            id = def.viewId
        }
        visibility = def.visibility

        if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            setupBackgroundWithPress()
        } else {
            if (def.border != Border.Special) {
                setupBackgroundWithPress()
            }
        }
        add(appearanceView, lParams(matchParent, matchParent))
    }

    private var pressedLayerAlpha = 0
    private var bgAnimator: Animator? = null

    protected fun setupBackgroundWithPress() {
        val bkgColor = when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyBackground
            Variant.Alternative -> colors.specialKeyBackground
            Variant.Accent -> colors.accentKeyBackground
            Variant.None -> Color.TRANSPARENT
        }
        val pressedColor = when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyPressed
            Variant.Alternative -> colors.specialKeyPressed
            Variant.Accent -> colors.accentKeyPressed
            Variant.None -> Color.TRANSPARENT
        }
        val hasShape = (bordered && def.border != Border.Off) || def.border == Border.On
        val normalBg: Drawable = if (hasShape) {
            createShapeBkg(bkgColor)
        } else {
            Color.TRANSPARENT.toDrawable()
        }
        val pressedBg: Drawable = if (hasShape) {
            createShapeBkg(pressedColor)
        } else {
            InsetDrawable(
                pressedColor.toDrawable(),
                hMargin, vMargin, hMargin, vMargin,
            )
        }
        val layered = LayerDrawable(arrayOf(normalBg, pressedBg))
        pressedBg.alpha = 0
        pressedLayerAlpha = 0
        appearanceView.background = layered
    }

    private fun createShapeBkg(color: Int): Drawable {
        val borderOrShadowWidth = dp(1)
        var strokeColor = when (def.variant) {
            Variant.Alternative -> colors.specialKeyBorderStroke
            Variant.Accent -> colors.accentKeyBorderStroke
            else -> colors.keyBorderStroke
        }
        if (!borderStroke) {
            strokeColor = Color.TRANSPARENT
        }
        return borderedKeyBackgroundDrawable(
            color, strokeColor,
            radius, borderOrShadowWidth, hMargin, vMargin,
        )
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        appearanceView.alpha = if (enabled) 1f else 0.3f
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val bg = appearanceView.background
        if (bg !is LayerDrawable || bg.numberOfLayers < 2) return
        val pressed = isPressed
        if (pressed == wasPressed) return
        wasPressed = pressed
        if (pressed) {
            onPressedChanged?.invoke(this)
        }

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
        bgAnimator = if (pressed) {
            anim(pressedLayerAlpha, 255, 220).also { it.start() }
        } else {
            AnimatorSet().apply {
                playSequentially(anim(pressedLayerAlpha, 255, 70), anim(255, 0, 180))
                start()
            }
        }
    }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { appearanceView.getLocationInWindow(it) }
        cachedBounds.set(x, y, x + appearanceView.width, y + appearanceView.height)
        boundsValid = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        boundsValid = false
        if (layoutMarginLeft != 0f || layoutMarginRight != 0f) {
            val w = right - left
            val h = bottom - top
            val layoutWidth = (w * (1f - layoutMarginLeft - layoutMarginRight)).roundToInt()
            appearanceView.updateLayoutParams<LayoutParams> {
                leftMargin = (w * layoutMarginLeft).roundToInt()
                rightMargin = (w * layoutMarginRight).roundToInt()
            }
            appearanceView.measure(
                MeasureSpec.makeMeasureSpec(layoutWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (bordered) return
        if (def.viewId == button_space) {
            bgAnimator?.cancel()
            val bkgRadius = dp(3f)
            val minHeight = dp(26)
            val hInset = dp(10)
            val vInset = if (h < minHeight) 0 else min((h - minHeight) / 2, dp(16))
            val normalBg = insetRadiusDrawable(
                hInset, vInset, bkgRadius, colors.keyBackground,
            )
            val pressedBg = insetRadiusDrawable(
                hInset, vInset, bkgRadius, colors.specialKeyPressed,
            )
            val layered = LayerDrawable(arrayOf(normalBg, pressedBg))
            pressedBg.alpha = 0
            pressedLayerAlpha = 0
            appearanceView.background = layered
            appearanceView.padding = 0
        }
    }

    companion object {
        var button_space = R.id.button_space
        var button_return = R.id.button_return
        var button_backspace = R.id.button_backspace
        var button_peroid = R.id.button_period
        var button_caps = R.id.button_caps
        var button_lang = R.id.button_lang
    }
}

@SuppressLint("ViewConstructor")
class TextKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.Text,
) : KeyView(ctx, colors, def) {
    override val displayText: String? get() = mainText.text.toString()

    override fun updateMode(punctuationMode: PunctuationMode) {
        val text = mainText.text.toString()
        updateText(when (punctuationMode) {
            PunctuationMode.FullWidth -> CharacterSet.fullWidth(text)
            PunctuationMode.HalfWidth -> CharacterSet.halfWidth(text)
        })
    }

    val mainText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        includeFontPadding = false
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.textSize)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
        setTypeface(typeface, def.textStyle)
        setTextColor(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
                Variant.None -> colors.keyText
            },
        )
    }

    init {
        appearanceView.apply {
            add(mainText, lParams(matchParent, matchParent) {
                centerInParent()
            })
        }
    }

    fun updateText(text: CharSequence?) {
        mainText.text = text
    }
}

@SuppressLint("ViewConstructor")
class AltTextKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.AltText,
) : KeyView(ctx, colors, def) {

    override val displayText: String? get() = mainText.text.toString()

    override fun updateMode(punctuationMode: PunctuationMode) {
        val text = mainText.text.toString()
        updateText(when (punctuationMode) {
            PunctuationMode.FullWidth -> CharacterSet.fullWidth(text)
            PunctuationMode.HalfWidth -> CharacterSet.halfWidth(text)
        })
        val altStr = altText.text.toString()
        updateAltText(when (punctuationMode) {
            PunctuationMode.FullWidth -> CharacterSet.fullWidth(altStr)
            PunctuationMode.HalfWidth -> CharacterSet.halfWidth(altStr)
        })
    }

    val mainText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        includeFontPadding = false
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.textSize)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
        setTypeface(typeface, def.textStyle)
        setTextColor(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
                Variant.None -> colors.keyText
            },
        )
    }

    val altText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.altTextSize)
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText
        setTextColor(colors.altText)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
    }

    init {
        appearanceView.apply {
            add(mainText, lParams(wrapContent, wrapContent))
            add(altText, lParams(wrapContent, wrapContent))
        }
        applyLayout()

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMainTextPosition()
            updateAltTextPosition()
        }
    }

    private fun applyLayout() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            startToStart = parentId
            endToEnd = parentId
            topToTop = parentId
            bottomToBottom = parentId
            verticalBias = 0.25f
        }
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            startToStart = parentId
            endToEnd = parentId
            topToTop = parentId
            bottomToBottom = parentId
            verticalBias = 0.8f
        }

        val altDef = def as KeyDef.Appearance.AltText
        mainText.translationY = dp(altDef.mainTextTranslationY).toFloat()
        altText.translationY = dp(altDef.altTextTranslationY).toFloat()
    }

    fun updateText(text: CharSequence?) {
        mainText.text = text
        mainText.doOnPreDraw {
            updateMainTextPosition()
        }
    }

    fun updateAltText(text: CharSequence?) {
        altText.text = text
        altText.doOnPreDraw {
            updateAltTextPosition()
        }
    }

    private fun updateMainTextPosition() {
        val text = mainText.text?.toString() ?: return
        if (text.isEmpty()) {
            mainText.translationX = 0f
            return
        }
        val rect = Rect()
        mainText.paint.getTextBounds(text, 0, text.length, rect)
        val viewCenter = mainText.width / 2f
        val glyphCenter = (rect.left + rect.right) / 2f
        mainText.translationX = viewCenter - glyphCenter
    }

    private fun updateAltTextPosition() {
        val text = altText.text?.toString() ?: return
        if (text.isEmpty()) {
            altText.translationX = 0f
            return
        }
        val rect = Rect()
        altText.paint.getTextBounds(text, 0, text.length, rect)
        val viewCenter = altText.width / 2f
        val glyphCenter = (rect.left + rect.right) / 2f
        altText.translationX = viewCenter - glyphCenter
    }
}

@SuppressLint("ViewConstructor")
class SidePanelKeyView(
    ctx: Context, colors: KeyboardColors.ColorScheme, def: KeyDef.Appearance.SidePannel,
    keepSelection: Boolean = false,
) : SidePanelView(ctx, colors, def, keepSelection) {
    override val render: SidePanelRender = object : SidePanelRender(
        colors,
        resources.displayMetrics.density,
        visibleItemCount = def.visableRow,
        appearance = def,
    ) {
        override fun cornerRadius(): Float = dp(theme.cornerRadius)

        override fun toItem(itemDef: KeyDef): Item? {
            val itemAppearance = itemDef.appearance as? KeyDef.Appearance.Text ?: return null
            val action = itemDef.behaviors.firstNotNullOfOrNull { behavior ->
                (behavior as? KeyDef.Behavior.Press)?.action
            }
            val textColor = when (itemAppearance.variant) {
                Variant.Normal -> theme.keyText
                Variant.AltForeground, Variant.Alternative -> theme.specialKeyText
                Variant.Accent -> theme.accentKeyText
                Variant.None -> colors.keyText
            }
            return Item(
                label = itemAppearance.displayText,
                textSize = itemAppearance.textSize,
                textStyle = itemAppearance.textStyle,
                textColor = textColor,
                action = action,
            )
        }
    }
}

@SuppressLint("ViewConstructor")
class ImageKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.Image,
) : KeyView(ctx, colors, def) {
    val img = imageView {
        isClickable = false
        isFocusable = false
        imageTintList = ColorStateList.valueOf(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
                Variant.None -> colors.keyText
            },
        )
        imageResource = def.src
    }

    init {
        appearanceView.apply {
            add(img, lParams(wrapContent, wrapContent) {
                centerInParent()
            })
        }
    }
}

@SuppressLint("ViewConstructor")
class ImageTextKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.ImageText,
) : KeyView(ctx, colors, def) {

    override val displayText: String? get() = mainText.text.toString()

    override fun updateMode(punctuationMode: PunctuationMode) {
        val text = mainText.text.toString()
        updateText(when (punctuationMode) {
            PunctuationMode.FullWidth -> CharacterSet.fullWidth(text)
            PunctuationMode.HalfWidth -> CharacterSet.halfWidth(text)
        })
    }

    val mainText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        includeFontPadding = false
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.textSize)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
        setTypeface(typeface, def.textStyle)
        setTextColor(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
                Variant.None -> colors.keyText
            },
        )
    }

    val img = imageView {
        isClickable = false
        isFocusable = false
        imageTintList = ColorStateList.valueOf(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
                Variant.None -> colors.keyText
            },
        )
        imageResource = def.src
    }

    init {
        appearanceView.apply {
            id = if (def.viewId > 0) def.viewId else generateViewId()
            // 使用 wrapContent，并加上适当的上下间距
            add(img, lParams(dp(16), dp(16)))
            add(mainText, lParams(wrapContent, wrapContent))
        }

        // 让图片偏上，但不贴顶（通过 verticalBias = 0.35 并在上方留出空间）
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            startToStart = parentId
            endToEnd = parentId
            topToTop = parentId
            bottomToBottom = parentId
            verticalBias = 0.3f // 稍微靠上
        }

        // 让文字偏下，但不贴底
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            startToStart = parentId
            endToEnd = parentId
            topToTop = parentId
            bottomToBottom = parentId
            verticalBias = 0.75f // 稍微靠下，与图标拉开距离
        }

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTextPosition()
        }
    }

    fun updateText(text: CharSequence?) {
        mainText.text = text
        mainText.doOnPreDraw {
            updateTextPosition()
        }
    }

    private fun updateTextPosition() {
        val text = mainText.text?.toString() ?: return
        if (text.isEmpty()) {
            mainText.translationX = 0f
            return
        }
        val rect = Rect()
        mainText.paint.getTextBounds(
            text, 0, text.length, rect
        )
        val viewCenter = mainText.width / 2f
        val glyphCenter = (rect.left + rect.right) / 2f
        mainText.translationX = viewCenter - glyphCenter
    }
}