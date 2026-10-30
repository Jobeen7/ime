package com.jobeen.ime.input.keyboard.key

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.TimeInterpolator
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import com.jobeen.ime.R
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.PunctuationMode.CharacterSet
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.FontRole
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Border
import com.jobeen.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.jobeen.ime.input.keyboard.key.widget.SidePanelRender
import com.jobeen.ime.input.keyboard.key.widget.SidePanelView
import com.jobeen.ime.skin.icon.MaterialSymbols
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

// 对齐计算复用的边界矩形：调用都在主线程、bounds 取到后立即消费，
// 共享一份即可，不再每次按键文本定位新建 Rect
private val alignBoundsScratch = Rect()

private fun TextView.alignVisibleTextCenter() {
    val text = text?.toString().orEmpty()
    val container = parent as? android.view.View ?: return
    if (text.isEmpty() || width == 0 || container.width == 0) {
        translationX = 0f
        return
    }

    val bounds = alignBoundsScratch
    paint.getTextBounds(text, 0, text.length, bounds)
    val glyphCenter = left + (bounds.left + bounds.right) / 2f
    translationX = container.width / 2f - glyphCenter
}

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
    // G1 单键几何：附加内缩叠加于皮肤级键间距之上；可视高度乘子
    // 以对称竖向内缩实现（行高由网格固定，见装配器注释）。
    var hMargin: Int = (colors.keyHMargin + (def.skinHInsetDp ?: 0f)).let { dp(it).toInt() }
    var vMargin: Int = (colors.keyVMargin + (def.skinVInsetDp ?: 0f) +
        (def.skinHeightFactor?.let { (1f - it) * 12f } ?: 0f)).let { dp(it).toInt() }
    private var foregroundAttached = false
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

    /** 按压态独立图片层（重点候选 8-2）：构造期解码、初始隐藏，按压显隐。 */
    private var pressedFaceView: android.widget.ImageView? = null

    protected val appearanceView = constraintLayout {
        isDuplicateParentStateEnabled = true
        setClipChildren(false)
        setClipToPadding(false)
    }

    init {
        isEnabled = true
        isHapticFeedbackEnabled = false
        if (def.viewId > 0) {
            id = def.viewId
        }
        visibility = def.visibility

        // 等价化简：旧 if/else 两支殊途同归，只剩「Special 且无边框」一种不建背景
        if (def.border != Border.Special || bordered) {
            setupBackgroundWithPress()
        }
        add(appearanceView, lParams(matchParent, matchParent))
        // G1 单键图片键面：有接纳资产且位图可解时铺满键面（在子类
        // 文本/图标加入之前，最底层）；无图或解码失败时不加任何层，
        // 颜色键面即回退结果（失败日志由 SkinKeyImageStore 记一次）。
        val faceBitmap = def.skinImageAssetId?.let {
            com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(it)
        }
        if (faceBitmap != null) {
            val faceView = android.widget.ImageView(ctx).apply {
                isClickable = false
                isFocusable = false
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(faceBitmap)
            }
            appearanceView.apply {
                add(faceView, lParams(0, 0) {
                    startToStart = parentId
                    endToEnd = parentId
                    topToTop = parentId
                    bottomToBottom = parentId
                })
            }
        }
        // 按压态独立图片键面（重点候选 8-2）：与常态图片同层叠、同裁剪，
        // 压在常态层之上、初始隐藏；按压显、抬起隐（解码失败则无此层，
        // 按压回退常态图片／颜色键面）。
        val pressedFaceBitmap = def.skinPressedImageAssetId?.let {
            com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(it)
        }
        if (pressedFaceBitmap != null) {
            val pressedFaceView = android.widget.ImageView(ctx).apply {
                isClickable = false
                isFocusable = false
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(pressedFaceBitmap)
                visibility = android.view.View.GONE
            }
            this.pressedFaceView = pressedFaceView
            appearanceView.apply {
                add(pressedFaceView, lParams(0, 0) {
                    startToStart = parentId
                    endToEnd = parentId
                    topToTop = parentId
                    bottomToBottom = parentId
                })
            }
        }
    }

    /**
     * G1 前景多层叠加（由 BaseKeyboard 在视图建成后调用一次）：
     * 按装配顺序加入 appearanceView，后层压前层、压主文字之上；
     * image 层位图缺失时跳过该层（失败日志由 Store 记一次）。
     */
    fun attachForegroundLayers() {
        if (foregroundAttached) return
        foregroundAttached = true
        val layers = def.skinForegroundLayers ?: return
        for (layer in layers) {
            when (layer.type) {
                "text" -> {
                    val textView = TextView(context).apply {
                        isClickable = false
                        isFocusable = false
                        background = null
                        includeFontPadding = false
                        text = layer.text
                        setTextSize(
                            TypedValue.COMPLEX_UNIT_DIP,
                            layer.size ?: 11f,
                        )
                        gravity = android.view.Gravity.CENTER
                        setTextColor(layer.color ?: colors.altText)
                    }
                    appearanceView.apply {
                        add(textView, lParams(wrapContent, wrapContent) {
                            startToStart = parentId
                            endToEnd = parentId
                            topToTop = parentId
                            bottomToBottom = parentId
                            horizontalBias = layer.biasX
                            verticalBias = layer.biasY
                        })
                    }
                }
                "image" -> {
                    val bitmap = layer.imageAssetId?.let {
                        com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(it)
                    } ?: continue
                    val imageView = android.widget.ImageView(context).apply {
                        isClickable = false
                        isFocusable = false
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        setImageBitmap(bitmap)
                    }
                    appearanceView.apply {
                        add(imageView, lParams(dp(24), dp(24)) {
                            startToStart = parentId
                            endToEnd = parentId
                            topToTop = parentId
                            bottomToBottom = parentId
                            horizontalBias = layer.biasX
                            verticalBias = layer.biasY
                        })
                    }
                }
                else -> Unit
            }
        }
    }

    private var pressedLayerAlpha = 0
    private var bgAnimator: Animator? = null

    /** 按压态内容切换钩子（重点候选 8-2）：子类按需覆写（字形切换）；基类只切图片层。 */
    protected open fun onPressedContentChanged(pressed: Boolean) {}

    // 按压动画器池（见 drawableStateChanged）：三个动画器终身复用
    private var animTarget: Drawable? = null
    private val alphaUpdateListener = ValueAnimator.AnimatorUpdateListener { animator ->
        val alpha = animator.animatedValue as Int
        pressedLayerAlpha = alpha
        animTarget?.alpha = alpha
    }
    private val pressFadeAnim = ValueAnimator.ofInt(0, 255).apply {
        addUpdateListener(alphaUpdateListener)
    }
    private val releaseFadeAnim1 = ValueAnimator.ofInt(0, 255).apply {
        addUpdateListener(alphaUpdateListener)
    }
    private val releaseFadeAnim2 = ValueAnimator.ofInt(255, 0).apply {
        addUpdateListener(alphaUpdateListener)
    }
    private val releaseSet = AnimatorSet().apply {
        playSequentially(releaseFadeAnim1, releaseFadeAnim2)
    }

    protected fun setupBackgroundWithPress() {
        // 单键样式色优先（2026-10-27 并入项）：有解析色时覆盖变体取色，
        // 无样式时表达式与原逻辑逐字节一致。
        val bkgColor = def.skinFaceColor ?: when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyBackground
            Variant.Alternative -> colors.specialKeyBackground
            Variant.Accent -> colors.accentKeyBackground
            Variant.None -> Color.TRANSPARENT
        }
        val pressedColor = def.skinPressedColor ?: when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyPressed
            Variant.Alternative -> colors.specialKeyPressed
            Variant.Accent -> colors.accentKeyPressed
            Variant.None -> Color.TRANSPARENT
        }
        val hasShape = (bordered && def.border != Border.Off) || def.border == Border.On
        val normalBg: Drawable = if (hasShape) {
            // 拟态双阴影（2026-10-29 item-1）：快照 keyShadow 开启且
            // 装饰开关开时常态层换三层阴影形；装饰关 = 平面旧形。
            // 按压层保持平面（压在阴影之上显隐，阴影不重复绘制）。
            // 快照未就绪（peek 为 null）时走旧形。
            val shadow = com.jobeen.ime.skin.runtime.SkinSnapshotProvider.peek()?.keyShadow
            val decorationOn =
                com.jobeen.ime.data.manager.KeyboardManager.Keyboard.Decoration
                    .isEnabled(context)
            if (shadow?.enabled == true && decorationOn) {
                dualShadowKeyBackgroundDrawable(
                    bkgColor, strokeColorFor(), radius, strokeWidthFor(),
                    hMargin, vMargin,
                    shadow.lightColor, shadow.darkColor,
                    dp(shadow.offsetDp).roundToInt(),
                )
            } else {
                createShapeBkg(bkgColor)
            }
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

    private fun interpolatorFor(
        easing: com.jobeen.ime.skin.model.KeyAnimationEasing,
    ): TimeInterpolator = when (easing) {
        com.jobeen.ime.skin.model.KeyAnimationEasing.LINEAR -> LinearInterpolator()
        com.jobeen.ime.skin.model.KeyAnimationEasing.EASE_OUT -> DecelerateInterpolator()
        com.jobeen.ime.skin.model.KeyAnimationEasing.EASE_IN_OUT ->
            AccelerateDecelerateInterpolator()
        com.jobeen.ime.skin.model.KeyAnimationEasing.OVERSHOOT -> OvershootInterpolator(2f)
    }

    private fun strokeWidthFor(): Int = dp(colors.keyBorderWidthDp).roundToInt()

    private fun strokeColorFor(): Int {
        var strokeColor = def.skinBorderColor ?: when (def.variant) {
            Variant.Alternative -> colors.specialKeyBorderStroke
            Variant.Accent -> colors.accentKeyBorderStroke
            else -> colors.keyBorderStroke
        }
        if (!borderStroke) {
            strokeColor = Color.TRANSPARENT
        }
        return strokeColor
    }

    private fun createShapeBkg(color: Int): Drawable {
        // 边框宽来自配色（全局值恒生效，未设为 1dp，与旧硬编码一致）。
        return borderedKeyBackgroundDrawable(
            color, strokeColorFor(),
            radius, strokeWidthFor(), hMargin, vMargin,
        )
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        appearanceView.alpha = if (enabled) 1f else 0.3f
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val pressed = isPressed
        val changed = pressed != wasPressed
        if (changed) {
            wasPressed = pressed
            // 按压态内容（重点候选 8-2）：图片层显隐＋子类字形切换，
            // 置于背景层检查之前——无边框特殊键无按压背景层亦然。
            pressedFaceView?.visibility =
                if (pressed) android.view.View.VISIBLE else android.view.View.GONE
            onPressedContentChanged(pressed)
            // 按键动效（2026-10-29 item-1 最小版：按压缩放自动回弹）：
            // ViewPropertyAnimator 零分配复用，与按压层淡入淡出互不干扰；
            // 规格由装配期写在外观上（未配/降级关闭时为 null，不动效）。
            def.skinKeyAnimation?.let { spec ->
                val target = if (pressed) spec.pressedScale else 1f
                appearanceView.animate()
                    .scaleX(target)
                    .scaleY(target)
                    .setDuration(spec.durationMs.toLong())
                    .setInterpolator(interpolatorFor(spec.easing))
                    .start()
            }
        }
        val bg = appearanceView.background
        if (bg !is LayerDrawable || bg.numberOfLayers < 2) return
        if (!changed) return
        if (pressed) {
            onPressedChanged?.invoke(this)
        }

        bgAnimator?.cancel()

        val pressedDrawable = bg.getDrawable(1) ?: return
        // 动画器池化复用：旧实现每次按压新建 1 个、每次抬起新建 3 个
        // Animator（高频打字时持续分配）；三个动画器各建一次反复复用，
        // 监听统一走 animTarget 指向当前按压层
        animTarget = pressedDrawable
        bgAnimator = if (pressed) {
            pressFadeAnim.setIntValues(pressedLayerAlpha, 255)
            pressFadeAnim.duration = 220
            pressFadeAnim.also { it.start() }
        } else {
            releaseFadeAnim1.setIntValues(pressedLayerAlpha, 255)
            releaseFadeAnim1.duration = 70
            releaseFadeAnim2.setIntValues(255, 0)
            releaseFadeAnim2.duration = 180
            releaseSet.also { it.start() }
        }
    }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { appearanceView.getLocationInWindow(it) }
        cachedBounds.set(x, y, x + appearanceView.width, y + appearanceView.height)
        boundsValid = true
    }

    override fun onDetachedFromWindow() {
        // 本类自有的按压背景动画不随父类（CustomGestureView）的手势
        // runnable 收尾覆盖：分离时取消，避免动画继续改已脱窗按键的
        // 按压层透明度；重挂后下一次状态变化会经 drawableStateChanged
        // 重新启动动画器池，不影响复用
        bgAnimator?.cancel()
        bgAnimator = null
        // 按压手势被切断时（长按切键盘、窗口收起等）抬起信号不会再
        // 送达本 View，wasPressed 与按压层会锁死在按压态，重挂后无
        // 状态变化触发淡出（符号键残影）。这里强制复位到已抬起终态：
        // setPressed(false) 引发的状态变化会再启一个淡出动画，随即
        // 取消并把按压层直接归零，保证脱窗后无动画残留
        if (isPressed) {
            setPressed(false)
            bgAnimator?.cancel()
            bgAnimator = null
        }
        if (pressedLayerAlpha != 0) {
            pressedLayerAlpha = 0
            animTarget?.alpha = 0
            val bg = appearanceView.background
            if (bg is LayerDrawable && bg.numberOfLayers >= 2) {
                bg.getDrawable(1)?.alpha = 0
            }
        }
        // 按压态图片层同理复位（脱窗时按压信号不再送达，防残影）。
        pressedFaceView?.visibility = android.view.View.GONE
        wasPressed = false
        // 动效复位：脱窗时缩放动画不再送达抬起信号，强制回 1.0 防残影。
        appearanceView.animate().cancel()
        appearanceView.scaleX = 1f
        appearanceView.scaleY = 1f
        super.onDetachedFromWindow()
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
                hInset, vInset, bkgRadius, def.skinFaceColor ?: colors.keyBackground,
            )
            val pressedBg = insetRadiusDrawable(
                hInset, vInset, bkgRadius, def.skinPressedColor ?: colors.specialKeyPressed,
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

    val mainText = TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        includeFontPadding = false
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.skinTextSize ?: def.textSize)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
        setTypeface(GlobalFonts.typefaceFor(context, FontRole.KEY, def.textStyle) ?: typeface, def.textStyle)
        setTextColor(
            def.skinTextColor ?: when (def.variant) {
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
        // G1 内容中心偏置：有偏置时主文字改 wrapContent + 双向约束偏置
        // （无偏置时保持 matchParent 居中旧布局，一字不改）。
        val biasX = def.skinContentBiasX
        val biasY = def.skinContentBiasY
        if (biasX != null || biasY != null) {
            mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                height = ConstraintLayout.LayoutParams.WRAP_CONTENT
                startToStart = parentId
                endToEnd = parentId
                topToTop = parentId
                bottomToBottom = parentId
                horizontalBias = biasX ?: 0.5f
                verticalBias = biasY ?: 0.5f
            }
        }
        // 单键图标（参考项）：注册表内符号以字形代主显文本；
        // 未知名/字体失败时 apply 返回 false，原文本保持不变。
        if (MaterialSymbols.applyToTextView(mainText, ctx, def.skinIconSymbol)) {
            // 集成接线（图标染色角色）：符号字形着色用 keyStyles icon
            // 角色，无 icon 角色时保持既有文字色（既有现状）。
            def.skinIconTintColor?.let { mainText.setTextColor(it) }
        }
    }

    fun updateText(text: CharSequence?) {
        // 值未变不重设：setText 内容相同也会触发 requestLayout，全/半角
        // 切换时 updateMode 逐键调用，未变的键不应连带整排重排（L6）
        if (mainText.text.toString() == text?.toString()) return
        mainText.text = text
        // 单键图标（参考项）：大小写/全半角更新会重设文本，
        // 图标键须把字形贴回去；无图标时 apply 返回 false，无行为变化。
        MaterialSymbols.applyToTextView(mainText, context, def.skinIconSymbol)
    }

    // 按压态独立图标（重点候选 8-2）：常态与按压图标双配时按压换字形、
    // 抬起贴回常态字形；任一未配则不切换。注：按压中若 updateText 触发，
    // 会先贴回常态字形（既有行为），下一次状态变化时再按当前态贴一次，
    // 属短暂显示差异、无状态残留。
    override fun onPressedContentChanged(pressed: Boolean) {
        val normal = def.skinIconSymbol ?: return
        val pressedSymbol = def.skinPressedIconSymbol ?: return
        MaterialSymbols.applyToTextView(
            mainText, context, if (pressed) pressedSymbol else normal,
        )
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
        val topStr = topText.text.toString()
        updateTopText(when (punctuationMode) {
            PunctuationMode.FullWidth -> CharacterSet.fullWidth(topStr)
            PunctuationMode.HalfWidth -> CharacterSet.halfWidth(topStr)
        })
    }

    val mainText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        includeFontPadding = true
        maxLines = 1
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.skinTextSize ?: def.textSize)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
        setTypeface(GlobalFonts.typefaceFor(context, FontRole.KEY, def.textStyle) ?: typeface, def.textStyle)
        setTextColor(
            def.skinTextColor ?: when (def.variant) {
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
        includeFontPadding = true
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.skinAltTextSize ?: def.altTextSize)
        setTypeface(GlobalFonts.typefaceFor(context, FontRole.KEY, Typeface.BOLD) ?: typeface, Typeface.BOLD)
        text = def.altText
        setTextColor(def.skinAltTextColor ?: colors.altText)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
    }

    /**
     * 拟态顶部小字（2026-10-29 item-1）：与底部副文字同角色、
     * 同字号，位于主文字之上；无顶部小字时 GONE（不占布局）。
     */
    val topText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        includeFontPadding = true
        maxLines = 1
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.skinAltTextSize ?: def.altTextSize)
        setTypeface(GlobalFonts.typefaceFor(context, FontRole.KEY, Typeface.BOLD) ?: typeface, Typeface.BOLD)
        text = def.skinTopText ?: ""
        visibility = if (def.skinTopText.isNullOrBlank()) {
            android.view.View.GONE
        } else {
            android.view.View.VISIBLE
        }
        setTextColor(def.skinAltTextColor ?: colors.altText)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
    }

    init {
        // 隐藏按键提示（2026-10-29 批量第 4 项）：副文字不绘制但占位
        // （INVISIBLE，不扰动主文字布局；GONE 会让约束布局重排）
        if (KeyboardManager.Keyboard.Display.isHideHint(ctx)) {
            altText.visibility = android.view.View.INVISIBLE
        }
        appearanceView.apply {
            add(mainText, lParams(wrapContent, wrapContent))
            add(altText, lParams(wrapContent, wrapContent))
            add(topText, lParams(wrapContent, wrapContent))
        }
        applyLayout()
        // 单键图标（参考项）：主显文本换成符号字形，副显文本不变。
        // 隐藏按键符号时不换字形，主显保持出厂文字（图标键见 ImageKeyView）。
        if (!KeyboardManager.Keyboard.Display.isHideSymbol(ctx) &&
            MaterialSymbols.applyToTextView(mainText, ctx, def.skinIconSymbol)) {
            // 集成接线（图标染色角色）：符号字形着色用 keyStyles icon
            // 角色，无 icon 角色时保持既有文字色（既有现状）。
            def.skinIconTintColor?.let { mainText.setTextColor(it) }
        }

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMainTextPosition()
            updateAltTextPosition()
            updateTopTextPosition()
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
        topText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            startToStart = parentId
            endToEnd = parentId
            topToTop = parentId
            bottomToBottom = parentId
            verticalBias = 0.1f
        }

        val altDef = def as KeyDef.Appearance.AltText
        mainText.translationY = dp(altDef.mainTextTranslationY).toFloat()
        altText.translationY = dp(altDef.altTextTranslationY).toFloat()
        // G1 内容中心偏置：横向偏置作用于主副两行，纵向偏置只作用
        // 于主文字（副文字保持相对布局，不被顶出键面）。
        def.skinContentBiasX?.let { biasX ->
            mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                horizontalBias = biasX
            }
            altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                horizontalBias = biasX
            }
            topText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                horizontalBias = biasX
            }
        }
        def.skinContentBiasY?.let { biasY ->
            mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                verticalBias = biasY
            }
        }
    }

    fun updateText(text: CharSequence?) {
        // 同 TextKeyView：值未变不重设，避免全/半角切换整排重排（L6）
        if (mainText.text.toString() == text?.toString()) return
        mainText.text = text
        // 单键图标（参考项）：文本更新后把字形贴回去（同 TextKeyView）。
        MaterialSymbols.applyToTextView(mainText, context, def.skinIconSymbol)
        mainText.doOnPreDraw {
            updateMainTextPosition()
        }
    }

    // 按压态独立图标（重点候选 8-2，同 TextKeyView）：双配时切换字形。
    override fun onPressedContentChanged(pressed: Boolean) {
        val normal = def.skinIconSymbol ?: return
        val pressedSymbol = def.skinPressedIconSymbol ?: return
        MaterialSymbols.applyToTextView(
            mainText, context, if (pressed) pressedSymbol else normal,
        )
    }

    fun updateAltText(text: CharSequence?) {
        // 与 updateText 同口径：updateMode 总是成对调用两者，只守一处
        // 等于没守
        if (altText.text.toString() == text?.toString()) return
        altText.text = text
        altText.doOnPreDraw {
            updateAltTextPosition()
        }
    }

    fun updateTopText(text: CharSequence?) {
        if (topText.text.toString() == text?.toString()) return
        topText.text = text
        topText.doOnPreDraw {
            updateTopTextPosition()
        }
    }

    private fun updateMainTextPosition() {
        mainText.alignVisibleTextCenter()
    }

    private fun updateAltTextPosition() {
        altText.alignVisibleTextCenter()
    }

    private fun updateTopTextPosition() {
        topText.alignVisibleTextCenter()
    }
}

@SuppressLint("ViewConstructor")
class SidePanelKeyView(
    ctx: Context, colors: KeyboardColors.ColorScheme, def: KeyDef.Appearance.SidePannel,
    keepSelection: Boolean = false,
) : SidePanelView(ctx, colors, def, keepSelection) {
    override val render: SidePanelRender = object : SidePanelRender(
        ctx,
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
            // G1 图标染色角色优先于文字色（无 icon 角色时与旧口径一致）
            def.skinIconTintColor ?: def.skinTextColor ?: when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
                Variant.None -> colors.keyText
            },
        )
        imageResource = def.src
    }

    init {
        // 单键图标（参考项）：图片键无文本面，符号字形取
        // 代工厂图片居中显示；未知名/字体失败时沿用工厂图片。
        val iconView = def.skinIconSymbol?.let { name ->
            TextView(ctx).apply {
                isClickable = false
                isFocusable = false
                background = null
                gravity = android.view.Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24f)
                setTextColor(
                    // 集成接线：图片键符号字形同样走 icon 角色，无则回落文字色。
                    def.skinIconTintColor ?: def.skinTextColor ?: when (def.variant) {
                        Variant.Normal, Variant.AltForeground -> colors.keyText
                        Variant.Alternative -> colors.specialKeyText
                        Variant.Accent -> colors.accentKeyText
                        Variant.None -> colors.keyText
                    },
                )
            }.takeIf { MaterialSymbols.applyToTextView(it, ctx, name) }
        }
        appearanceView.apply {
            if (iconView != null) {
                add(iconView, lParams(wrapContent, wrapContent) {
                    centerInParent()
                })
                // 隐藏按键符号：符号字形不绘制（键面空白，仍可点按）
                if (KeyboardManager.Keyboard.Display.isHideSymbol(ctx)) {
                    iconView.visibility = android.view.View.INVISIBLE
                }
            } else {
                add(img, lParams(wrapContent, wrapContent) {
                    centerInParent()
                })
                // 隐藏按键符号：工厂图标同样不绘制（键面空白，仍可点按）
                if (KeyboardManager.Keyboard.Display.isHideSymbol(ctx)) {
                    img.visibility = android.view.View.INVISIBLE
                }
            }
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
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.skinTextSize ?: def.textSize)
        gravity = android.view.Gravity.CENTER
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
        setTypeface(GlobalFonts.typefaceFor(context, FontRole.KEY, def.textStyle) ?: typeface, def.textStyle)
        setTextColor(
            def.skinTextColor ?: when (def.variant) {
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
            // G1 图标染色角色优先于文字色（无 icon 角色时与旧口径一致）
            def.skinIconTintColor ?: def.skinTextColor ?: when (def.variant) {
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

        // G1 内容中心偏置：只作用于主文字（图标保持原布局）。
        def.skinContentBiasX?.let { biasX ->
            mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                horizontalBias = biasX
            }
        }
        def.skinContentBiasY?.let { biasY ->
            mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                verticalBias = biasY
            }
        }

        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateTextPosition()
        }

        // 单键图标（参考项）：主显文本换成符号字形，
        // 工厂图片保留（ImageText 的图文组合口径不变）。
        if (MaterialSymbols.applyToTextView(mainText, ctx, def.skinIconSymbol)) {
            // 集成接线（图标染色角色）：符号字形着色用 keyStyles icon
            // 角色，无 icon 角色时保持既有文字色（既有现状）。
            def.skinIconTintColor?.let { mainText.setTextColor(it) }
        }
    }

    fun updateText(text: CharSequence?) {
        // 同 TextKeyView：值未变不重设，避免全/半角切换整排重排（L6）
        if (mainText.text.toString() == text?.toString()) return
        mainText.text = text
        // 单键图标（参考项）：文本更新后把字形贴回去（同 TextKeyView）。
        MaterialSymbols.applyToTextView(mainText, context, def.skinIconSymbol)
        mainText.doOnPreDraw {
            updateTextPosition()
        }
    }

    // 按压态独立图标（重点候选 8-2，同 TextKeyView）：双配时切换字形。
    override fun onPressedContentChanged(pressed: Boolean) {
        val normal = def.skinIconSymbol ?: return
        val pressedSymbol = def.skinPressedIconSymbol ?: return
        MaterialSymbols.applyToTextView(
            mainText, context, if (pressed) pressedSymbol else normal,
        )
    }

    private fun updateTextPosition() {
        mainText.alignVisibleTextCenter()
    }
}
