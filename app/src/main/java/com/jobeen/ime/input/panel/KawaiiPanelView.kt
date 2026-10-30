package com.jobeen.ime.input.panel

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.panel.toolbar.CustomButtonSlot
import com.jobeen.ime.input.panel.toolbar.CustomDisplayKind
import com.jobeen.ime.input.panel.toolbar.EffectiveToolbarButton
import com.jobeen.ime.input.panel.toolbar.SkinToolbarApplier
import com.jobeen.ime.input.panel.toolbar.ToolbarBackground
import com.jobeen.ime.input.panel.toolbar.ToolbarRenderer
import com.jobeen.ime.input.panel.toolbar.ToolbarUserStore
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.icon.MaterialSymbols
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import com.jobeen.ime.input.panel.toolbar.ToolbarRendererResources
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import java.io.File
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("UseCompatLoadingForDrawables")
class KawaiiPanelView(context: Context) : View(context) {

    var currentRenderer: IRenderer
    var scrollX = 0f
    var onTap: ((KawaiiPanel.TouchResult?) -> Unit)? = null
    var onExpandChanged: ((Boolean, List<EngineMessage.Candidate>) -> Unit)? = null
    var isExpanded: Boolean = false
        private set

    var recording: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            currentRenderer.recording = value
            invalidate()
        }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        onExpandChanged?.invoke(false, emptyList())
        invalidate()
    }

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        if (expanded) {
            val candidates = (currentRenderer as? ComposingRenderer)?.candidates ?: return
            onExpandChanged?.invoke(true, candidates)
        } else {
            onExpandChanged?.invoke(false, emptyList())
        }
        invalidate()
    }

    private val paints = Paints(context)

    // 背景渐变缓存：只在尺寸/颜色变化时重建，onDraw 内零分配
    private val bgGradPaint = Paint()
    private var bgGradW = -1
    private var bgGradH = -1
    private var bgGradColor = 0
    private var bgGradKeyboardBg = 0
    /**
     * 栏背景全局开关（2026-10-29 外观轮）：共享背景＝栏不画
     * 自有底、窗口背景横跨键盘＋栏；磨砂＝栏底半透明叠加
     * （窗口背景柔和透出，非逐像素实时模糊，零热路径开销）。
     * refreshTheme 内读取偏好缓存，onDraw 只读缓存字段。
     */
    private var barSharedBackground: Boolean = false
    private var barBlurAlpha: Int = 255

    /**
     * 用户覆盖工具栏的栏背景（工具栏编辑轮）：非 null 时 onDraw
     * 以它代替换渐变底（纯色＝实色矩形，透明＝不画底，图片＝
     * 居中裁剪铺满；图片解码失败则回落渐变底，永不空白）。
     * 生效栏切换回默认／皮肤时清回 null。
     */
    private var userBarBackground: ToolbarBackground? = null
    private var userBarBitmap: Bitmap? = null
    private val userBarColorPaint = Paint()

    /**
     * 自定义按钮手势机（工具栏编辑轮，仅自定义钮生效，池钮
     * 走既有点击路、行为零变化）：
     * - DOWN 命中自定义钮 → 记钮＋ 500ms 长按待发；
     * - MOVE 累计位移 ＞8dp → 取消长按、点按失效；主导轴 ≥36dp
     *   （与按键滑动阈值同口径）→ 发对应方向滑动动作、只发一次；
     * - UP 未发过动作且未失效 → 点按；300ms 内同钮再点且有
     *   双击绑定 → 发双击，否则再发一次点按（点按立即发，不
     *   为等双击拖 300ms；无双击绑定时双击即两次点按）。
     * 录音态不发任何自定义动作（与 handleUp 的录音门同口径）。
     */
    private var customDown: com.jobeen.ime.input.panel.toolbar.ToolbarCustomButton? = null
    private var customGestureDone: Boolean = false
    private var customTapInvalid: Boolean = false
    private var lastCustomTapId: String? = null
    private var lastCustomTapTime: Long = 0L
    private val customLongPressRunnable = Runnable {
        val def = customDown ?: return@Runnable
        if (fireCustom(def, CustomButtonSlot.LONG_PRESS)) {
            customGestureDone = true
        }
    }

    /** 自定义槽位执行：无绑定／录音态／转译失败皆静默（失败记日志，只记元数据）。 */
    private fun fireCustom(
        def: com.jobeen.ime.input.panel.toolbar.ToolbarCustomButton,
        slot: CustomButtonSlot,
    ): Boolean {
        if (recording) return false
        val binding = def.bindings[slot] ?: return false
        val runtime = runCatching {
            KeyOverrideApplier.toRuntime(SkinAction.Command(binding.action, binding.value))
        }.onFailure { error ->
            DiagLog.e(
                "toolbar", "toolbar_custom_action_failed",
                mapOf(
                    "slot" to slot.jsonName,
                    "action" to binding.action.formatName,
                    "errorClass" to error.javaClass.simpleName,
                ),
            )
        }.getOrNull() ?: return false
        val renderer = currentRenderer as? ToolbarRenderer ?: return false
        renderer.pressCx = lastTouchX
        renderer.pressCy = height / 2f
        renderer.pressRadiusMax = height * 0.55f
        renderer.pressRadius = 0f
        startPressAnimation(renderer)
        onTap?.invoke(KawaiiPanel.TouchResult.CustomKeyAction(runtime))
        return true
    }

    private fun clearCustomGesture() {
        longPressHandler.removeCallbacks(customLongPressRunnable)
        customDown = null
        customGestureDone = false
        customTapInvalid = false
    }

    /**
     * 用户覆盖工具栏应用（init＋refreshTheme 后调用，用户胜过
     * 皮肤；无用户配置则清覆盖、回落既有皮肤／默认路）。
     * 皮肤 id 取当前快照，无快照＝内置主题态（builtin 键）。
     * 图片解码上限最长边 256px；失败记日志、该钮回落文字
     * （图片钮 display 为空时画空白，与缺图标既有行为一致）。
     */
    private fun applyUserToolbar(renderer: ToolbarRenderer) {
        userBarBitmap?.recycle()
        userBarBitmap = null
        userBarBackground = null
        val skinId = SkinSnapshotProvider.peek()?.ref?.skinId
            ?: ToolbarUserStore.BUILTIN_SKIN_KEY
        val file = File(context.filesDir, ToolbarUserStore.FILE_NAME)
        val loaded = ToolbarUserStore.load(file)
        if (loaded.corrupt) {
            DiagLog.e(
                "toolbar", "toolbar_user_load_failed",
                mapOf("errorClass" to (loaded.errorClass ?: "unknown")),
            )
        }
        val resolved = ToolbarUserStore.resolve(skinId, loaded.data)
        if (resolved == null) {
            renderer.clearUserCenter()
            return
        }
        val imageDir = File(context.filesDir, ToolbarUserStore.IMAGE_DIR)
        val items = resolved.buttons.map { button ->
            when (button) {
                is EffectiveToolbarButton.Pool -> ToolbarRenderer.UserCenterItem.Pool(button.action)
                is EffectiveToolbarButton.Custom -> {
                    val def = button.def
                    val bitmap = if (def.kind == CustomDisplayKind.IMAGE) {
                        val name = ToolbarUserStore.sanitizeImageFile(def.imageFile)
                        if (name == null) null else decodeToolbarImage(File(imageDir, name), def.id)
                    } else null
                    ToolbarRenderer.UserCenterItem.Custom(def, bitmap)
                }
            }
        }
        renderer.updateUserCenter(items)
        userBarBackground = resolved.background
        val bg = resolved.background
        if (bg is ToolbarBackground.Image) {
            val name = ToolbarUserStore.sanitizeImageFile(bg.file)
            userBarBitmap = if (name == null) null else decodeToolbarImage(
                File(imageDir, name), "background",
            )
        }
        invalidate()
    }

    private fun decodeToolbarImage(file: File, id: String): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.onFailure { error ->
            DiagLog.w(
                "toolbar", "toolbar_image_decode_failed",
                mapOf("id" to id, "errorClass" to error.javaClass.simpleName),
            )
        }.getOrNull()
    }
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isScrolling = false
    private val screenDensity = resources.displayMetrics.density

    init {
        paints.updateColors(context)
        paints.applyDensity(screenDensity)
        val hPad = KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat()
        currentRenderer = ToolbarRenderer(
            ToolbarRendererResources(
                context.getDrawable(R.drawable.ic_keyboard_menu),
                context.getDrawable(R.drawable.ic_keyboard_arrow_back),
                context.getDrawable(R.drawable.ic_keyboard_clipboard),
                context.getDrawable(R.drawable.ic_keyboard_keyboard_close),
                context.getDrawable(R.drawable.ic_keyboard_trash),
                context.getDrawable(R.drawable.ic_toolbar_emoji),
                context.getDrawable(R.drawable.ic_toolbar_select_all),
                context.getDrawable(R.drawable.ic_toolbar_copy),
                context.getDrawable(R.drawable.ic_toolbar_paste),
                context.getDrawable(R.drawable.ic_keyboard_search),
            ),
            hPad,
            centerActions = ToolbarConfig.DEFAULT_ACTIONS,
            centerIconOverrides = emptyMap(),
            // Symbol 名解析接线（参考项）：中央按钮 icon 不是
            // 包内资产 id 时查 Material Symbols 注册表。
            symbolIconProvider = { name ->
                MaterialSymbols.drawable(
                    context, name, (24f * screenDensity).toInt().coerceAtLeast(1),
                )
            },
        ).also { renderer ->
            // 初始渲染器即应用皮肤工具栏（若快照有构成）；无皮肤时
            // 用出厂默认（构造参数已传入）。其后用户覆盖层再压一层
            // （有用户配置则胜出，无则不动既有路）。
            SkinToolbarApplier.resolveFor(context)?.let { applied ->
                renderer.updateSkinToolbar(applied.toolbar, applied.icons)
            }
            applyUserToolbar(renderer)
        }
    }

    fun refreshTheme() {
        paints.updateColors(context)
        val barStyle = GlobalStylePrefs.get(context)
        barSharedBackground = barStyle.sharedBackground
        barBlurAlpha = if (barStyle.barBlur) 140 else 255
        // 工具栏配置可能刚被设置页改过、或皮肤刚应用/切换：长驻
        // 渲染器在此重抉择（有皮肤构成用皮肤，否则回落出厂默认）。
        // 其后用户覆盖层再压一层（工具栏编辑轮，分层：用户＞皮肤＞默认）。
        (currentRenderer as? ToolbarRenderer)?.let { renderer ->
            SkinToolbarApplier.applyTo(renderer, context)
            applyUserToolbar(renderer)
        }
        invalidate()
    }

    fun refreshDensity() {
        paints.applyDensity(resources.displayMetrics.density)
        invalidate()
    }

    fun updateHorizontalPadding(hPadDp: Float) {
        when (val r = currentRenderer) {
            is ToolbarRenderer -> r.horizontalPaddingDp = hPadDp
            is ComposingRenderer -> r.horizontalPaddingDp = hPadDp
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        // 用户自定义栏背景（工具栏编辑轮）优先于渐变底；透明＝不画底。
        val userBg = userBarBackground
        if (userBg is ToolbarBackground.Transparent) {
            // 不画底，窗口背景直接透出。
        } else if (userBg is ToolbarBackground.Color) {
            userBarColorPaint.color = userBg.argb
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), userBarColorPaint)
        } else if (userBg is ToolbarBackground.Image && userBarBitmap != null) {
            val bitmap = userBarBitmap!!
            val scale = max(
                width.toFloat() / bitmap.width,
                height.toFloat() / bitmap.height,
            )
            val w = bitmap.width * scale
            val h = bitmap.height * scale
            val dst = RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
            canvas.drawBitmap(bitmap, null, dst, null)
        } else if (!barSharedBackground) {
            val bgColor = paints.bgPaint.color
            val kbBg = paints.keyboardBackground
            if (width != bgGradW || height != bgGradH || bgColor != bgGradColor || kbBg != bgGradKeyboardBg) {
                bgGradPaint.set(paints.bgPaint)
                bgGradPaint.shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(bgColor, bgColor, kbBg),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP,
                )
                bgGradW = width
                bgGradH = height
                bgGradColor = bgColor
                bgGradKeyboardBg = kbBg
            }
            bgGradPaint.alpha = barBlurAlpha
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgGradPaint)
        }
        currentRenderer.draw(canvas, width, height, paints, scrollX, isExpanded, screenDensity)
    }

    private var pressAnimator: ValueAnimator? = null
    private var expandLongPressed = false
    private var dragTotalX = 0f
    private var dragTotalY = 0f

    // 当前手势的指针 id：只认第一根落指的手指，其他手指的事件不干扰本手势
    private var activePointerId = -1

    // 延迟触发的工具栏动作（Palette 300ms / CloseKeyboard 100ms）：
    // 用具名 Runnable 以便取消——新按下、输入结束、View 分离时必须清掉，
    // 否则过期的点击会在错误的状态下迟到触发
    private var delayedPalette: Runnable? = null
    private var delayedClose: Runnable? = null

    fun cancelDelayedTaps() {
        delayedPalette?.let { removeCallbacks(it) }
        delayedClose?.let { removeCallbacks(it) }
        delayedPalette = null
        delayedClose = null
    }

    override fun onDetachedFromWindow() {
        cancelDelayedTaps()
        longPressHandler.removeCallbacks(longPressRunnable)
        clearCustomGesture()
        // 工具栏按压扩散动画同为本 View 自有资源：不取消会在分离后
        // 继续驱动已脱窗 View 的重绘（§6#6）
        pressAnimator?.cancel()
        pressAnimator = null
        // 只 cancel 会把渲染器状态冻结在中间值（pressRadius>0 即被
        // ToolbarRenderer 绘制），重挂后留下一个静止的半透明按压圆：
        // 与动画一并把渲染器状态直接清零
        (currentRenderer as? ToolbarRenderer)?.let {
            it.pressRadius = 0f
            it.pressAlpha = 0
        }
        super.onDetachedFromWindow()
    }
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        val result = currentRenderer.hitTest(
            lastTouchX, lastTouchY, width, height, scrollX, isExpanded, screenDensity,
        )
        if (result is KawaiiPanel.TouchResult.ExpandCandidates || result is KawaiiPanel.TouchResult.CollapseCandidates) {
            expandLongPressed = true
            onTap?.invoke(KawaiiPanel.TouchResult.LongPressExpand)
        } else if (result is KawaiiPanel.TouchResult.ToolbarAction && result.action is PanelAction.AddPhrase) {
            expandLongPressed = true
            onTap?.invoke(KawaiiPanel.TouchResult.LongPressClearPhrases)
        }
    }

    private fun isRecordingAllowed(result: KawaiiPanel.TouchResult?): Boolean {
        return when (result) {
            is KawaiiPanel.TouchResult.ToolbarAction -> result.action is PanelAction.CloseKeyboard

            is KawaiiPanel.TouchResult.CollapseCandidates -> true
            else -> false
        }
    }

    private fun startPressAnimation(renderer: ToolbarRenderer) {
        pressAnimator?.cancel()
        // 峰值 alpha 160：120 时素白、落日两套浅色主题的涟漪混合色与
        // 面板底对比只有约 2.3 / 1.9（低于 3:1）；提到 160 并配合墨色
        // 取各主题 keyText 后三套分别为约 7.6 / 5.8 / 4.1（复算口径
        // 见 KeyboardThemePresets 各主题 toolbarPressed 注释）
        renderer.pressAlpha = 160
        renderer.pressRadius = 0f
        pressAnimator = ValueAnimator.ofFloat(0f, renderer.pressRadiusMax).apply {
            duration = 300
            addUpdateListener {
                renderer.pressRadius = animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun playToolbarPressAt(x: Float, y: Float) {
        val renderer = currentRenderer as? ToolbarRenderer ?: return
        renderer.hitTest(x, y, width, height, scrollX, isExpanded, screenDensity)
        startPressAnimation(renderer)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 已有手指在进行手势时，第二根手指不重启手势
                if (activePointerId != -1) return true
                activePointerId = event.getPointerId(0)
                cancelDelayedTaps()
                lastTouchX = event.getX(0)
                lastTouchY = event.getY(0)
                dragTotalX = 0f
                dragTotalY = 0f
                isScrolling = false
                expandLongPressed = false
                parent.requestDisallowInterceptTouchEvent(true)
                if (!recording) longPressHandler.postDelayed(longPressRunnable, 500)
                // 自定义钮 DOWN 探针（仅自定义钮建手势态，池钮不动既有路）。
                val customHit = (currentRenderer as? ToolbarRenderer)?.userCenterHit(
                    event.getX(0), width, screenDensity,
                ) as? ToolbarRenderer.UserCenterItem.Custom
                if (customHit != null) {
                    customDown = customHit.def
                    customGestureDone = false
                    customTapInvalid = false
                    longPressHandler.postDelayed(customLongPressRunnable, 500)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return true
                val ex = event.getX(idx)
                val ey = event.getY(idx)
                val dx = ex - lastTouchX
                val dy = ey - lastTouchY
                lastTouchX = ex
                lastTouchY = ey
                dragTotalX += dx
                dragTotalY += dy
                // 长按取消按累计位移判定（dragTotal 即相对按下点的实际位移）：
                // 旧实现用单步位移，慢速小步拖动每步都达不到阈值，手已滑走长按仍触发
                if (abs(dragTotalX) + abs(dragTotalY) > 8 * screenDensity) {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    // 自定义钮同理：位移超 8dp 即取消长按、点按失效（滑动另判）。
                    if (customDown != null) {
                        longPressHandler.removeCallbacks(customLongPressRunnable)
                        customTapInvalid = true
                    }
                }
                // 自定义钮滑动：主导轴位移 ≥36dp（与按键滑动阈值同口径）发
                // 对应方向动作、只发一次；无该方向绑定则不发、不改态。
                val customDef = customDown
                if (customDef != null && !customGestureDone &&
                    max(abs(dragTotalX), abs(dragTotalY)) >= 36 * screenDensity
                ) {
                    val slot = if (abs(dragTotalX) >= abs(dragTotalY)) {
                        if (dragTotalX < 0) CustomButtonSlot.SWIPE_LEFT else CustomButtonSlot.SWIPE_RIGHT
                    } else {
                        if (dragTotalY < 0) CustomButtonSlot.SWIPE_UP else CustomButtonSlot.SWIPE_DOWN
                    }
                    if (fireCustom(customDef, slot)) {
                        customGestureDone = true
                        longPressHandler.removeCallbacks(customLongPressRunnable)
                    }
                }
                if (currentRenderer !is ComposingRenderer) return true
                if (isScrolling || (abs(dragTotalX) > 8 * screenDensity && abs(dragTotalX) >= abs(
                        dragTotalY
                    ))
                ) {
                    isScrolling = true
                    val renderer = currentRenderer as ComposingRenderer
                    scrollX = (scrollX + dx).coerceIn(-renderer.maxScrollX, 0f)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                // 与 CustomGestureView 同一守卫：双指同压时被追踪手指已以
                // POINTER_UP 收尾过，剩余手指的 ACTION_UP 不得再命中一次
                if (activePointerId == event.getPointerId(0)) {
                    activePointerId = -1
                    handleUp(event.x, event.y)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 被追踪的手指先抬起（还有其他手指在按）时同样结束手势
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    val idx = event.actionIndex
                    activePointerId = -1
                    handleUp(event.getX(idx), event.getY(idx))
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                activePointerId = -1
                cancelDelayedTaps()
                longPressHandler.removeCallbacks(longPressRunnable)
                clearCustomGesture()
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /** 主动抬起的统一收尾（坐标取抬起的那根手指） */
    private fun handleUp(upX: Float, upY: Float) {
        longPressHandler.removeCallbacks(longPressRunnable)
        parent.requestDisallowInterceptTouchEvent(false)
        // 自定义钮手势收尾（优先于既有路：本手势起于自定义钮时，
        // 既有 hitTest 路不跑，避免池钮动作误发）。
        val customDef = customDown
        if (customDef != null) {
            val done = customGestureDone
            val invalid = customTapInvalid
            clearCustomGesture()
            if (recording) return
            if (done || invalid) return
            lastTouchX = upX
            lastTouchY = upY
            val now = SystemClock.uptimeMillis()
            val doubleTap = lastCustomTapId == customDef.id && now - lastCustomTapTime < 300
            if (doubleTap && customDef.bindings.containsKey(CustomButtonSlot.DOUBLE_TAP)) {
                lastCustomTapId = null
                fireCustom(customDef, CustomButtonSlot.DOUBLE_TAP)
            } else {
                lastCustomTapId = customDef.id
                lastCustomTapTime = now
                fireCustom(customDef, CustomButtonSlot.TAP)
            }
            return
        }
        if (expandLongPressed) return
        if (!isScrolling) {
            val result = currentRenderer.hitTest(
                upX, upY, width, height, scrollX, isExpanded, screenDensity,
            )
            if (recording && !isRecordingAllowed(result)) return
            if (currentRenderer is ToolbarRenderer && result != null) {
                val action = (result as? KawaiiPanel.TouchResult.ToolbarAction)?.action
                val isClipAction =
                    action is PanelAction.ClipTab || action is PanelAction.AddPhrase || action is PanelAction.ClearClipboard || action is PanelAction.ClearPhrases
                val animateAfterRender =
                    action is PanelAction.SwitchKeyboard || action is PanelAction.CursorMove
                if (!isClipAction && !animateAfterRender) {
                    startPressAnimation(currentRenderer as ToolbarRenderer)
                }
                if (result is KawaiiPanel.TouchResult.ToolbarAction && result.action is PanelAction.Palette) {
                    val r = Runnable { onTap?.invoke(result) }
                    delayedPalette = r
                    postDelayed(r, 300L)
                    return
                }
            }
            val delayedAction =
                result is KawaiiPanel.TouchResult.ToolbarAction && result.action is PanelAction.CloseKeyboard
            if (delayedAction) {
                val r = Runnable { onTap?.invoke(result) }
                delayedClose = r
                postDelayed(r, 100L)
            } else {
                onTap?.invoke(result)
            }
            if (result is KawaiiPanel.TouchResult.ToolbarAction && (result.action is PanelAction.SwitchKeyboard || result.action is PanelAction.CursorMove)) {
                post { playToolbarPressAt(result.tapX, result.tapY) }
            }
        }
    }
}
