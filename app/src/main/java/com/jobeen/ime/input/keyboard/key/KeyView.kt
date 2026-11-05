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
import com.jobeen.ime.skin.diag.DiagLog
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
import com.jobeen.ime.skin.image.toScaleType
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

    /**
     * 运行时底色覆盖（回车键按动作分色用）：非空时 setupBackgroundWithPress
     * 优先用它，不动 KeyDef 的皮肤面色（换回时不丢皮肤原色）。
     * 调用方改值后须调 [refreshBackground] 重建背景。
     */
    var backgroundOverride: Int? = null

    /** 按当前 backgroundOverride/def/变体重建键背景（运行时换色用）。 */
    fun refreshBackground() {
        setupBackgroundWithPress()
    }

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

    /** 可视区域高度占比（S7）：null = 全高；纵向对齐见 layoutVertical。 */
    var layoutHeightFraction: Float? = null
    var layoutVertical: com.jobeen.ime.skin.model.KeyBoundsVertical =
        com.jobeen.ime.skin.model.KeyBoundsVertical.CENTER

    open val displayText: String? get() = null

    /** 变换目标标签视图（工具项 14）：文本键子类覆写为 mainText，无标签键为 null。 */
    protected open val labelView: android.view.View? get() = null

    /** 常态图片键面（工具项 14 变换目标；无图片面时为 null）。 */
    private var faceView: android.view.View? = null

    /** 按压态独立图片层（重点候选 8-2）：构造期解码、初始隐藏，按压显隐。 */
    private var pressedFaceView: android.view.View? = null

    protected val appearanceView = constraintLayout {
        isDuplicateParentStateEnabled = true
        setClipChildren(false)
        setClipToPadding(false)
    }

    /** 审计 H 项 cartoon 播放器（帧视图 + 帧表 + 帧率 + 播放状态）。 */
    private class CartoonPlayer(
        val view: android.widget.ImageView,
        val frames: List<android.graphics.Bitmap>,
        val fps: Int,
    ) {
        var index: Int = 0
        var running: Boolean = false
        val step: Runnable = Runnable {
            if (!running) return@Runnable
            view.setImageBitmap(frames[index % frames.size])
            index++
            view.postDelayed(step, (1000L / fps).coerceAtLeast(16L))
        }

        fun start() {
            if (running) return
            running = true
            index = 0
            view.visibility = android.view.View.VISIBLE
            view.post(step)
        }

        fun stop() {
            running = false
            view.removeCallbacks(step)
            view.visibility = android.view.View.GONE
        }
    }

    private val cartoonPlayers = mutableListOf<CartoonPlayer>()
    private val physicsBitmaps =
        mutableListOf<Pair<com.jobeen.ime.skin.model.KeyAnimationDef.Physics,
            List<android.graphics.Bitmap>>>()
    private val physicsViews = mutableListOf<android.view.View>()
    private var transformAnimator: ValueAnimator? = null
    private var transformActive: Boolean = false
    private var transformBase: TransformBase? = null
    /** 当前变换的目标视图（工具项 14：startTransform 解析后记下，复原／脱窗同视图）。 */
    private var transformView: android.view.View? = null
    /** 已记过 WARN 的未知变换目标（每串一次，避免按压刷日志）。 */
    private val loggedUnknownTransformTargets = mutableSetOf<String>()
    private var transformCurrentFraction: Float = 0f
    /**
     * 审计 H 项 transform 代次：取消旧动画器会同步触发其结束
     * 回调，代次让过期回调直接返回，不与新动画器争抢。
     */
    private var transformGeneration: Int = 0

    /** 审计 H 项 transform 起点（启动时捕获的键面变换值）。 */
    private data class TransformBase(
        val scaleX: Float,
        val scaleY: Float,
        val transX: Float,
        val transY: Float,
        val alpha: Float,
        val rotation: Float,
    )

    init {
        isEnabled = true
        isHapticFeedbackEnabled = false
        if (def.viewId > 0) {
            id = def.viewId
        }
        visibility = def.visibility
        // 审计 A 项可视区域：宽度占比 + 横向对齐经既有
        // layoutMargin 机制在 onLayout 落到键面（左/右内缩和
        // = 1 - 宽度占比）；S7：高度占比 + 纵向对齐同样经
        // onLayout 落到键面（无高度占比时纵向对齐不改变全高键面）。
        def.skinVisibleBounds?.let { bounds ->
            val widthFraction = bounds.widthFraction ?: 1f
            val shrink = (1f - widthFraction).coerceIn(0f, 1f)
            layoutMarginLeft = when (bounds.horizontal) {
                com.jobeen.ime.skin.model.KeyBoundsHorizontal.LEFT -> 0f
                com.jobeen.ime.skin.model.KeyBoundsHorizontal.RIGHT -> shrink
                else -> shrink / 2f
            }
            layoutMarginRight = shrink - layoutMarginLeft
            layoutHeightFraction = bounds.heightFraction
            layoutVertical = bounds.vertical
        }

        // 等价化简：旧 if/else 两支殊途同归，只剩「Special 且无边框」一种不建背景
        if (def.border != Border.Special || bordered) {
            setupBackgroundWithPress()
        }
        add(appearanceView, lParams(matchParent, matchParent))
        // G1 单键图片键面：有接纳资产且位图可解时铺满键面（在子类
        // 文本/图标加入之前，最底层）；无图或解码失败时不加任何层，
        // 颜色键面即回退结果（失败日志由 SkinKeyImageStore 记一次）。
        // 精灵图键面（工具项 8）：sheet+子图经 drawable 路径裁剪
        // （帧矩形＋cap 边框），不再整图铺面；帧脏/解码失败时无面层。
        val faceSpriteDrawable = def.skinFaceSpriteRef?.let { ref ->
            spriteFaceDrawable(ref, def.skinFaceContentMode)
        }
        if (faceSpriteDrawable != null) {
            // 背景式面层：drawable 自带 contentMode 绘制，bounds 即键面，
            // 不再经 ImageView scaleType 二次缩放。
            val faceView = android.view.View(ctx).apply {
                isClickable = false
                isFocusable = false
                background = faceSpriteDrawable
            }
            this.faceView = faceView
            appearanceView.apply {
                add(faceView, lParams(0, 0) {
                    startToStart = parentId
                    endToEnd = parentId
                    topToTop = parentId
                    bottomToBottom = parentId
                })
            }
        } else {
            val faceBitmap = def.skinImageAssetId?.let {
                com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(it)
            }
            if (faceBitmap != null) {
                val faceView = android.widget.ImageView(ctx).apply {
                    isClickable = false
                    isFocusable = false
                    // 审计 D 项：覆写指定模式时用描述模式，否则旧口径 CENTER_CROP。
                    scaleType = def.skinFaceContentMode?.toScaleType()
                        ?: android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(faceBitmap)
                }
                this.faceView = faceView
                appearanceView.apply {
                    add(faceView, lParams(0, 0) {
                        startToStart = parentId
                        endToEnd = parentId
                        topToTop = parentId
                        bottomToBottom = parentId
                    })
                }
            }
        }
        // 按压态独立图片键面（重点候选 8-2）：与常态图片同层叠、同裁剪，
        // 压在常态层之上、初始隐藏；按压显、抬起隐（解码失败则无此层，
        // 按压回退常态图片／颜色键面）。
        // 按压态精灵图键面（工具项 8）：与常态精灵图面同口径。
        val pressedSpriteDrawable = def.skinPressedFaceSpriteRef?.let { ref ->
            spriteFaceDrawable(ref, def.skinPressedFaceContentMode)
        }
        if (pressedSpriteDrawable != null) {
            val pressedFaceView = android.view.View(ctx).apply {
                isClickable = false
                isFocusable = false
                background = pressedSpriteDrawable
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
        } else {
            val pressedFaceBitmap = def.skinPressedImageAssetId?.let {
                com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(it)
            }
            if (pressedFaceBitmap != null) {
                val pressedFaceView = android.widget.ImageView(ctx).apply {
                    isClickable = false
                    isFocusable = false
                    // 审计 D 项：覆写指定模式时用描述模式，否则旧口径 CENTER_CROP。
                    scaleType = def.skinPressedFaceContentMode?.toScaleType()
                        ?: android.widget.ImageView.ScaleType.CENTER_CROP
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
        // 审计 H 项动效层（构造期解码、初始隐藏；无样式动效时零附加层）：
        // cartoon 帧铺满键面（与常态图片同裁剪），physics 位图按定义
        // 备用（按压时生成粒子视图，构造期不加层）。
        val styleAnimations = def.skinAnimations
        if (!styleAnimations.isNullOrEmpty()) {
            for (animation in styleAnimations) {
                when (animation) {
                    is com.jobeen.ime.skin.model.KeyAnimationDef.Cartoon -> {
                        val frames = animation.images.mapNotNull {
                            com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(it)
                        }
                        if (frames.isEmpty()) continue
                        val frameView = android.widget.ImageView(ctx).apply {
                            isClickable = false
                            isFocusable = false
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            visibility = android.view.View.GONE
                            animation.targetScale?.let { scale ->
                                scaleX = scale
                                scaleY = scale
                            }
                        }
                        cartoonPlayers += CartoonPlayer(frameView, frames, animation.fps)
                        appearanceView.apply {
                            add(frameView, lParams(0, 0) {
                                startToStart = parentId
                                endToEnd = parentId
                                topToTop = parentId
                                bottomToBottom = parentId
                            })
                        }
                    }
                    is com.jobeen.ime.skin.model.KeyAnimationDef.Physics -> {
                        val bitmaps = animation.images.mapNotNull {
                            com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(it)
                        }
                        if (bitmaps.isNotEmpty()) {
                            physicsBitmaps += animation to bitmaps
                        }
                    }
                    else -> Unit
                }
            }
        }
    }



    /**
     * 前景层实时变量刷新器（审计 K 余项）：模板 + 文本写盘，
     * 由键盘层在候选/组字变化时与主显动态文字同批刷新；
     * 函数类型参数在最后。
     */
    data class ForegroundDynamic(
        val template: String,
        val setText: (String) -> Unit,
    )

    /** 含实时变量的前景文本层刷新器（attachForegroundLayers 建层时记下）。 */
    val foregroundDynamics = mutableListOf<ForegroundDynamic>()

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
                    // 含实时变量的层：记下模板与写盘，供键盘层原地刷新
                    // （初始文本已是构建期快照解析值，此处只记刷新器）。
                    layer.dynamicTemplate?.let { template ->
                        foregroundDynamics += ForegroundDynamic(template) { text ->
                            textView.text = text
                        }
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
                        // 审计 D 项：层指定模式时用描述模式，否则旧口径 FIT_CENTER。
                        scaleType = layer.contentMode?.toScaleType()
                            ?: android.widget.ImageView.ScaleType.FIT_CENTER
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

    /**
     * 精灵图面 drawable（工具项 8）：sheet 位图 + 描述 + 快照精灵
     * 图表经 drawableFor 裁剪（帧矩形＋cap 边框，缺帧按整图计）；
     * sheet 解码失败或帧脏数据时返回 null（渲染无面层，回退颜色键面）。
     */
    private fun spriteFaceDrawable(
        ref: com.jobeen.ime.skin.model.SkinImageRef,
        mode: com.jobeen.ime.skin.model.ImageContentMode?,
    ): Drawable? {
        val bitmap =
            com.jobeen.ime.skin.runtime.SkinKeyImageStore.resolve(ref.file) ?: return null
        val spriteMaps =
            com.jobeen.ime.skin.runtime.SkinSnapshotProvider.peek()?.spriteMaps ?: emptyMap()
        val descriptor = com.jobeen.ime.skin.model.SkinImageDescriptor.File(
            normal = ref,
            highlight = null,
            contentMode = mode
                ?: com.jobeen.ime.skin.model.ImageContentMode.SCALE_ASPECT_FILL,
            insets = null,
        )
        return com.jobeen.ime.skin.image.drawableFor(bitmap, descriptor, spriteMaps, false)
    }

    private var swipeIndicatorsAttached = false

    /**
     * 滑动方向指示（多主类型补充）：由 BaseKeyboard 在视图建成后
     * 调用一次（与 attachForegroundLayers 同批）。flag 为总闸，
     * 方向以调用方传入的合并后滑动动作为准——无动作的方向不置
     * 标，不凭空造标。顶／底边居中各一静态小 chevron（⌃／⌄，
     * 10sp，主文字色）；仅 appearanceView 内加子视图，不碰背景
     * drawable 数学；静态标记，按压不改态；重复调用只挂一次。
     */
    fun attachSwipeIndicators(hasUp: Boolean, hasDown: Boolean) {
        if (swipeIndicatorsAttached) return
        swipeIndicatorsAttached = true
        if (!def.skinShowSwipeIndicators) return
        if (!hasUp && !hasDown) return
        val color = def.skinTextColor ?: when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyText
            Variant.Alternative -> colors.specialKeyText
            Variant.Accent -> colors.accentKeyText
            Variant.None -> colors.keyText
        }
        fun chevron(text: String, top: Boolean) {
            val view = TextView(context).apply {
                isClickable = false
                isFocusable = false
                background = null
                includeFontPadding = false
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                gravity = android.view.Gravity.CENTER
                setTextColor(color)
            }
            appearanceView.apply {
                add(view, lParams(wrapContent, wrapContent) {
                    startToStart = parentId
                    endToEnd = parentId
                    if (top) topToTop = parentId else bottomToBottom = parentId
                })
            }
        }
        if (hasUp) chevron("⌃", true)
        if (hasDown) chevron("⌄", false)
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

    /** 上次已发面色诊断的签名（快照＋键＋来源＋取值）：变化才重发，压住按压态重建的重复行。 */
    private var lastKeyFaceSig: String? = null

    /**
     * 真机灰诊断行（2026-11-05，诊断-only）：在最终取色点记快照
     * id/代次、逐键样式解析结果与面色最终取值＋来源；签名变化
     * 才发一条（键盘重建／快照换代／覆盖变化），渲染结果不动。
     */
    private fun logKeyFaceOnce(variantFace: Int) {
        // 诊断模式关时直接返回：不 peek 快照、不拼签名，热路径零开销。
        if (!com.jobeen.ime.skin.diag.DiagPrefs.isDiagnosticMode(context)) return
        val snapshot = com.jobeen.ime.skin.runtime.SkinSnapshotProvider.peek()
        val info = KeyFaceDiag.resolve(
            keyLabel = keyFaceLabel(),
            skinId = snapshot?.ref?.skinId,
            generation = snapshot?.ref?.generation,
            keyStylesEmpty = snapshot?.keyStyles?.isEmpty() ?: true,
            overrideColor = backgroundOverride,
            skinFaceColor = def.skinFaceColor,
            variantDefaultColor = variantFace,
        )
        val sig = "${info.skinId}|${info.generation}|${info.keyLabel}|${info.source}|${info.faceHex}"
        if (sig == lastKeyFaceSig) return
        lastKeyFaceSig = sig
        KeyFaceDiag.log(info)
    }

    /** 诊断用键标识：主文字（字母键即字母本身），无文字键用 viewId；不记输入内容。 */
    private fun keyFaceLabel(): String {
        val text = when (val a = def) {
            is KeyDef.Appearance.Text -> a.displayText
            is KeyDef.Appearance.AltText -> a.displayText
            is KeyDef.Appearance.ImageText -> a.displayText
            else -> ""
        }
        return text.ifEmpty { "view:${def.viewId}" }
    }

    protected fun setupBackgroundWithPress() {
        // 单键样式色优先（2026-10-27 并入项）：有解析色时覆盖变体取色，
        // 无样式时表达式与原逻辑逐字节一致。运行时覆盖最优先。
        val variantFace = when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyBackground
            Variant.Alternative -> colors.specialKeyBackground
            Variant.Accent -> colors.accentKeyBackground
            Variant.None -> Color.TRANSPARENT
        }
        val bkgColor = backgroundOverride ?: def.skinFaceColor ?: variantFace
        // 真机灰诊断行（只记元数据，不改渲染）：见 logKeyFaceOnce。
        logKeyFaceOnce(variantFace)
        val pressedColor = def.skinPressedColor ?: when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyPressed
            Variant.Alternative -> colors.specialKeyPressed
            Variant.Accent -> colors.accentKeyPressed
            Variant.None -> Color.TRANSPARENT
        }
        val hasShape = (bordered && def.border != Border.Off) || def.border == Border.On
        // 审计 A 项：样式几何面（渐变/阴影/描边/圆角/下边缘任一
        // 指定）→ 样式键面；全无时与原逻辑逐字节一致（旧形）。
        // 样式圆角优先于全局圆角；样式描边宽优先于全局描边宽。
        val radiusPx = def.skinCornerRadiusDp?.let { dp(it) } ?: radius
        val continuous = def.skinCornerCurve ==
            com.jobeen.ime.skin.model.KeyCornerCurve.CONTINUOUS
        val styled = def.skinGradient != null || def.skinPressedGradient != null ||
            def.skinShadow != null || def.skinBorderWidthDp != null ||
            def.skinPressedBorderColor != null || def.skinLowerEdgeColor != null ||
            def.skinPressedLowerEdgeColor != null ||
            def.skinCornerRadiusDp != null || def.skinCornerCurve != null
        val strokeWidthPx = def.skinBorderWidthDp?.let { dp(it).roundToInt() }
            ?: strokeWidthFor()
        val normalBg: Drawable = if (hasShape) {
            if (styled) {
                styledFaceDrawable(
                    fillColor = bkgColor,
                    gradient = def.skinGradient,
                    strokeColor = strokeColorFor(pressed = false),
                    strokeWidthPx = strokeWidthPx,
                    shadowLayer = def.skinShadow?.normal,
                    lowerEdgeColor = def.skinLowerEdgeColor,
                    radiusPx = radiusPx,
                    continuous = continuous,
                )
            } else {
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
            }
        } else {
            Color.TRANSPARENT.toDrawable()
        }
        val pressedBg: Drawable = if (hasShape) {
            if (styled) {
                styledFaceDrawable(
                    fillColor = pressedColor,
                    gradient = def.skinPressedGradient,
                    strokeColor = strokeColorFor(pressed = true),
                    strokeWidthPx = strokeWidthPx,
                    shadowLayer = def.skinShadow?.pressed ?: def.skinShadow?.normal,
                    lowerEdgeColor = def.skinPressedLowerEdgeColor
                        ?: def.skinLowerEdgeColor,
                    radiusPx = radiusPx,
                    continuous = continuous,
                )
            } else {
                createShapeBkg(pressedColor)
            }
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
        // 样式阴影用模糊画笔，须软件层（只在有阴影时设置；
        // 无阴影键不碰图层类型，保持既有渲染路径）。
        if (styled && def.skinShadow != null) {
            appearanceView.setLayerType(
                android.view.View.LAYER_TYPE_SOFTWARE, null,
            )
        }
    }

    /**
     * 审计 A 项样式键面（带键间距内缩的 LayerDrawable 单层）：
     * 填充取渐变（有值时优先于纯色）、描边取按压态色（未指定
     * 时回落常态描边）、阴影取生效色与 dp→px 换算、下边缘条
     * 固定 1dp（对端只给颜色、不给厚度）。
     */
    private fun styledFaceDrawable(
        fillColor: Int,
        gradient: com.jobeen.ime.skin.model.KeyGradient?,
        strokeColor: Int,
        strokeWidthPx: Int,
        shadowLayer: com.jobeen.ime.skin.model.KeyShadowLayer?,
        lowerEdgeColor: Int?,
        radiusPx: Float,
        continuous: Boolean,
    ): Drawable {
        val face = StyledKeyDrawable(
            solidColor = fillColor,
            gradient = gradient,
            cornerRadiusPx = radiusPx,
            strokeWidthPx = strokeWidthPx,
            strokeColor = strokeColor,
            continuous = continuous,
            shadowColor = shadowLayer?.effectiveColor(),
            shadowRadiusPx = shadowLayer?.let { dp(it.radiusDp) } ?: 0f,
            shadowDxPx = shadowLayer?.let { dp(it.offsetXDp) } ?: 0f,
            shadowDyPx = shadowLayer?.let { dp(it.offsetYDp) } ?: 0f,
            lowerEdgeColor = lowerEdgeColor,
            lowerEdgeHeightPx = if (lowerEdgeColor != null) {
                dp(1f).roundToInt().coerceAtLeast(1)
            } else {
                0
            },
        )
        return LayerDrawable(arrayOf(face)).apply {
            setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
        }
    }

    /**
     * 审计 H 项 physics 粒子：按压时生成粒子视图（Jime 粒子数约定：
     * 随机图 5 粒、指定图每图 1 粒、上限 8 粒；对端不给粒子数）。
     * 粒子从起点（含随机偏移）运动到终点，透明开关控制淡出、
     * 旋转开关控制角度插值；动画结束时视图自移除。
     */
    private fun spawnPhysicsParticles() {
        if (physicsBitmaps.isEmpty()) return
        val view = appearanceView
        if (view.width == 0 || view.height == 0) return
        for ((def, bitmaps) in physicsBitmaps) {
            val count = if (def.randomImage) 5 else min(bitmaps.size, 8)
            repeat(count) { index ->
                val bitmap = if (def.randomImage) {
                    bitmaps[kotlin.random.Random.nextInt(bitmaps.size)]
                } else {
                    bitmaps[index % bitmaps.size]
                }
                val particle = android.widget.ImageView(context).apply {
                    isClickable = false
                    isFocusable = false
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(bitmap)
                }
                appearanceView.apply {
                    add(particle, lParams(dp(24), dp(24)) {
                        startToStart = parentId
                        endToEnd = parentId
                        topToTop = parentId
                        bottomToBottom = parentId
                    })
                }
                fun offset(value: Float?, size: Int): Float {
                    if (value == null) return 0f
                    return if (def.positionUnit ==
                        com.jobeen.ime.skin.model.KeyAnimationPositionUnit.FRACTION
                    ) {
                        value * size
                    } else {
                        dp(value)
                    }
                }
                val randomX = def.randomOffsetX?.let {
                    (kotlin.random.Random.nextFloat() * 2f - 1f) * offset(it, view.width)
                } ?: 0f
                val randomY = def.randomOffsetY?.let {
                    (kotlin.random.Random.nextFloat() * 2f - 1f) * offset(it, view.height)
                } ?: 0f
                val startX = offset(def.startX, view.width) + randomX
                val startY = offset(def.startY, view.height) + randomY
                val endX = offset(def.endX, view.width) + randomX
                val endY = offset(def.endY, view.height) + randomY
                particle.translationX = startX
                particle.translationY = startY
                if (def.useRotation) {
                    particle.rotation = def.startAngleDeg ?: 0f
                }
                physicsViews += particle
                val animator = particle.animate()
                    .translationX(endX)
                    .translationY(endY)
                    .setDuration(def.durationMs.toLong())
                if (def.useRotation) {
                    animator.rotation(def.endAngleDeg ?: particle.rotation)
                }
                if (def.useOpacity) {
                    animator.alpha(0f)
                }
                animator.withEndAction {
                    appearanceView.removeView(particle)
                    physicsViews -= particle
                }.start()
            }
        }
    }

    /**
     * 工具项 14：transform 目标视图（未知目标回落整键并记一次 WARN；
     * 无对应层时同样回落整键，不丢动画）。
     */
    private fun transformViewFor(
        def: com.jobeen.ime.skin.model.KeyAnimationDef.Transform,
    ): android.view.View {
        return when (resolveTransformTarget(def.target)) {
            TransformTarget.WHOLE_KEY -> appearanceView
            TransformTarget.FACE -> faceView ?: appearanceView
            TransformTarget.PRESSED_FACE -> pressedFaceView ?: appearanceView
            TransformTarget.LABEL -> labelView ?: appearanceView
            null -> {
                val target = def.target ?: ""
                if (loggedUnknownTransformTargets.add(target)) {
                    DiagLog.w(
                        "animation", "transform_target_unknown",
                        mapOf("target" to target),
                    )
                }
                appearanceView
            }
        }
    }

    /**
     * 审计 H 项 transform 启动：从当前键面变换值插值到终态
     * （endScale/endTranslation/endAlpha/endRotationDeg，null
     * 项不变；平移单位 dp 或尺寸占比）。贝塞尔优先于 easing 名；
     * hold 时只跑一程并保持，重复/回弹在 hold 下不生效。
     */
    private fun startTransform(
        def: com.jobeen.ime.skin.model.KeyAnimationDef.Transform,
    ) {
        val view = transformViewFor(def)
        transformView = view
        transformGeneration++
        val generation = transformGeneration
        transformAnimator?.cancel()
        view.animate().cancel()
        transformBase = TransformBase(
            view.scaleX, view.scaleY, view.translationX, view.translationY,
            view.alpha, view.rotation,
        )
        def.anchorX?.let { if (view.width > 0) view.pivotX = it * view.width }
        def.anchorY?.let { if (view.height > 0) view.pivotY = it * view.height }
        transformActive = true
        transformCurrentFraction = 0f
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = def.durationMs.toLong()
            startDelay = def.delayMs.toLong()
            interpolator = def.bezier?.let {
                android.view.animation.PathInterpolator(it[0], it[1], it[2], it[3])
            } ?: interpolatorFor(def.easing)
            if (!def.holdUntilRelease) {
                repeatCount = def.repeatCount
                repeatMode = if (def.autoReverse) {
                    ValueAnimator.REVERSE
                } else {
                    ValueAnimator.RESTART
                }
            }
            addUpdateListener { animated ->
                transformCurrentFraction = animated.animatedValue as Float
                applyTransformFraction(def, transformCurrentFraction)
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationCancel(animation: Animator) = Unit
                override fun onAnimationRepeat(animation: Animator) = Unit
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != transformGeneration) return
                    transformAnimator = null
                    if (def.holdUntilRelease) return
                    val atEnd = !def.autoReverse || def.repeatCount % 2 == 0
                    if (def.restoreOnFinish && atEnd) {
                        restoreTransform(def)
                    } else if (def.restoreOnFinish) {
                        // 自动回弹已回到起点，无须复原。
                        transformActive = false
                    }
                    // restoreOnFinish=false：终态保持，active 保留，
                    // 脱窗时复原（见 onDetachedFromWindow）。
                }
            })
        }
        transformAnimator = animator
        animator.start()
    }

    /** 审计 H 项 transform 复原：按抬起时长从当前进度插值回起点。 */
    private fun restoreTransform(
        def: com.jobeen.ime.skin.model.KeyAnimationDef.Transform,
    ) {
        if (transformView == null) transformView = transformViewFor(def)
        val from = transformCurrentFraction
        transformGeneration++
        val generation = transformGeneration
        transformAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = def.releaseDurationMs.toLong()
            interpolator = interpolatorFor(def.easing)
            addUpdateListener { animated ->
                transformCurrentFraction = animated.animatedValue as Float
                applyTransformFraction(def, transformCurrentFraction)
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) = Unit
                override fun onAnimationCancel(animation: Animator) = Unit
                override fun onAnimationRepeat(animation: Animator) = Unit
                override fun onAnimationEnd(animation: Animator) {
                    if (generation != transformGeneration) return
                    transformAnimator = null
                    transformActive = false
                    applyTransformFraction(def, 0f)
                }
            })
        }
        transformAnimator = animator
        animator.start()
    }

    /** 审计 H 项 transform 插值：终态为绝对目标值（平移按单位换算）。 */
    private fun applyTransformFraction(
        def: com.jobeen.ime.skin.model.KeyAnimationDef.Transform,
        fraction: Float,
    ) {
        val base = transformBase ?: return
        val view = transformView ?: appearanceView
        def.endScale?.let { end ->
            view.scaleX = base.scaleX + (end - base.scaleX) * fraction
            view.scaleY = base.scaleY + (end - base.scaleY) * fraction
        }
        fun translate(value: Float?, size: Int): Float? {
            if (value == null) return null
            return if (def.positionUnit ==
                com.jobeen.ime.skin.model.KeyAnimationPositionUnit.FRACTION
            ) {
                value * size
            } else {
                dp(value)
            }
        }
        translate(def.endTranslationX, view.width)?.let { end ->
            view.translationX = base.transX + (end - base.transX) * fraction
        }
        translate(def.endTranslationY, view.height)?.let { end ->
            view.translationY = base.transY + (end - base.transY) * fraction
        }
        def.endAlpha?.let { end ->
            view.alpha = base.alpha + (end - base.alpha) * fraction
        }
        def.endRotationDeg?.let { end ->
            view.rotation = base.rotation + (end - base.rotation) * fraction
        }
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

    private fun strokeColorFor(pressed: Boolean = false): Int {
        // 审计 A 项：按压描边未指定时回落常态描边（与阴影/
        // 下边缘的按压回落口径一致）。
        var strokeColor = if (pressed) {
            def.skinPressedBorderColor ?: def.skinBorderColor
        } else {
            def.skinBorderColor
        } ?: when (def.variant) {
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
            // 审计 H 项动效触发（与按压层淡入淡出互不干扰）：
            // - transform 拥有键面变换时，缩放类动效让路（同视图属性）；
            // - 否则样式 scale 定义优先于全局动效规格；
            // - cartoon 按压播、抬起停；physics 按压时抛粒子；
            // - transform 按 trigger 在压下/抬起时启动，hold 时抬起复原。
            val styleAnimations = def.skinAnimations
            val transformDef = styleAnimations?.filterIsInstance<
                com.jobeen.ime.skin.model.KeyAnimationDef.Transform
                >()?.firstOrNull()
            if (transformDef == null) {
                val styleScale = styleAnimations?.filterIsInstance<
                    com.jobeen.ime.skin.model.KeyAnimationDef.Scale
                    >()?.firstOrNull()
                if (styleScale != null) {
                    if (pressed || styleScale.autoReverse) {
                        val target = if (pressed) styleScale.scale else 1f
                        val duration = if (pressed) {
                            styleScale.pressDurationMs
                        } else {
                            styleScale.releaseDurationMs
                        }
                        appearanceView.animate()
                            .scaleX(target)
                            .scaleY(target)
                            .setDuration(duration.toLong())
                            .setInterpolator(interpolatorFor(
                                com.jobeen.ime.skin.model.KeyAnimationEasing.EASE_OUT,
                            ))
                            .start()
                    }
                } else {
                    // 按键动效（2026-10-29 item-1）：按压缩放自动回弹；
                    // 分段时长（审计 H 项）未指定时沿用单程时长，
                    // autoReverse=false 时抬起保持缩放（脱窗时复位）。
                    def.skinKeyAnimation?.let { spec ->
                        if (pressed || spec.autoReverse) {
                            val target = if (pressed) spec.pressedScale else 1f
                            val duration = if (pressed) {
                                spec.pressDurationMs ?: spec.durationMs
                            } else {
                                spec.releaseDurationMs ?: spec.durationMs
                            }
                            appearanceView.animate()
                                .scaleX(target)
                                .scaleY(target)
                                .setDuration(duration.toLong())
                                .setInterpolator(interpolatorFor(spec.easing))
                                .start()
                        }
                    }
                }
            }
            if (pressed) {
                cartoonPlayers.forEach { it.start() }
                spawnPhysicsParticles()
                if (transformDef != null &&
                    transformDef.trigger ==
                    com.jobeen.ime.skin.model.KeyAnimationTrigger.PRESS
                ) {
                    startTransform(transformDef)
                }
            } else {
                cartoonPlayers.forEach { it.stop() }
                if (transformDef != null) {
                    if (transformDef.trigger ==
                        com.jobeen.ime.skin.model.KeyAnimationTrigger.RELEASE
                    ) {
                        startTransform(transformDef)
                    } else if (transformDef.holdUntilRelease && transformActive) {
                        restoreTransform(transformDef)
                    }
                }
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
        // 审计 H 项动效复位：停帧序列、清粒子、停变换并复原
        // （alpha 按启用态复原，不干扰 setEnabled 的 0.3 暗态）。
        cartoonPlayers.forEach { it.stop() }
        physicsViews.toList().forEach { particle ->
            particle.animate().cancel()
            appearanceView.removeView(particle)
        }
        physicsViews.clear()
        transformGeneration++
        transformAnimator?.cancel()
        transformAnimator = null
        if (transformActive) {
            transformActive = false
            val view = transformView ?: appearanceView
            view.translationX = 0f
            view.translationY = 0f
            view.rotation = 0f
            view.alpha = if (isEnabled) 1f else 0.3f
            transformView = null
        }
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        boundsValid = false
        val heightFraction = layoutHeightFraction
        if (layoutMarginLeft != 0f || layoutMarginRight != 0f || heightFraction != null) {
            val w = right - left
            val h = bottom - top
            val layoutWidth = (w * (1f - layoutMarginLeft - layoutMarginRight)).roundToInt()
            // S7：高度占比 + 纵向对齐——键面高按占比缩，顶边按
            // 对齐落（TOP 贴顶、CENTER 居中、BOTTOM 贴底）。
            val layoutHeight = if (heightFraction != null) {
                (h * heightFraction.coerceIn(0f, 1f)).roundToInt()
            } else {
                h
            }
            // 局部名避让接收者属性（updateLayoutParams 内 topMargin
            // 指接收者属性，局部同名会被判为 val 重赋值）。
            val topMarginPx = if (heightFraction != null) {
                when (layoutVertical) {
                    com.jobeen.ime.skin.model.KeyBoundsVertical.TOP -> 0
                    com.jobeen.ime.skin.model.KeyBoundsVertical.BOTTOM -> h - layoutHeight
                    else -> (h - layoutHeight) / 2
                }
            } else {
                0
            }
            appearanceView.updateLayoutParams<LayoutParams> {
                leftMargin = (w * layoutMarginLeft).roundToInt()
                rightMargin = (w * layoutMarginRight).roundToInt()
                topMargin = topMarginPx
                height = layoutHeight
            }
            appearanceView.measure(
                MeasureSpec.makeMeasureSpec(layoutWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(layoutHeight, MeasureSpec.EXACTLY),
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

    /** 工具项 14：变换 label 目标即主标签视图。 */
    override val labelView: android.view.View? get() = mainText

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
        // 分段字体（工具项 10）：图标贴回前挂段跨，图标键整体换
        // 文本时段跨随旧文本丢弃，不污染字形。
        applyKeyFontSpans(mainText, mainText.typeface?.style ?: Typeface.NORMAL)
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

    /** 工具项 14：变换 label 目标即主标签视图。 */
    override val labelView: android.view.View? get() = mainText

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
        // 分段字体（工具项 10）：图标贴回前挂段跨。
        applyKeyFontSpans(mainText, mainText.typeface?.style ?: Typeface.NORMAL)
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
                // S1：图标字形字号取样式，缺省 24。
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.skinIconSizeDp ?: 24f)
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

    /** 工具项 14：变换 label 目标即主标签视图。 */
    override val labelView: android.view.View? get() = mainText

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
        // 分段字体（工具项 10）：图标贴回前挂段跨。
        applyKeyFontSpans(mainText, mainText.typeface?.style ?: Typeface.NORMAL)
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
