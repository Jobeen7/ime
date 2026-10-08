package com.jobeen.ime.input.keyboard.window

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.jobeen.ime.input.keyboard.impl.IKeyboard
import com.jobeen.ime.input.keyboard.impl.BaseKeyboard
import com.jobeen.ime.input.keyboard.impl.ISidePanelKeyboard
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.keyboard.impl.EmojiKeyboard
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.panel.KawaiiPanel
import com.jobeen.ime.input.pinner.PreeditPinner
import com.jobeen.ime.input.speech.SpeechOverlayView
import com.jobeen.ime.base.speech.ModelProvider
import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.base.speech.SpeechUiBridge
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.input.ImeInputConnection
import com.jobeen.ime.input.dialog.SchemaPickerDialog
import com.jobeen.ime.input.keyboard.impl.T15Keyboard
import com.jobeen.ime.input.panel.PanelListener
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class KeyboardWindowView(
    context: Context,
    private val keyboardStateManager: KeyboardStateManager,
    private val panelListener: PanelListener? = null,
) : FrameLayout(context), IManagedView {

    companion object {
        const val PANEL_HEIGHT_DP = 48
    }

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    // 实测：语音拖拽时 UP 事件到不了空格按键的 onTouchUpListener（MOVE 能到，
    // UP/CANCEL 都到不了）。在父容器直接拦截，触发语音拖拽松手逻辑。
    // 只认启动语音的那根手指（voicePointerId，由拖拽 MOVE 上报的指针 id）：
    // 其他手指的抬起不得结束本次录音。id 未知（工具栏点击启动等无拖拽场景）
    // 时保持旧行为，拦截任意 UP/CANCEL。
    // 锁定录音时不拦截：浮层空白处点按不应结束录音。
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (isVoiceRecording && !voiceOverlay.isLocked &&
            (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
             ev.actionMasked == android.view.MotionEvent.ACTION_POINTER_UP ||
             ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL)) {
            val upPointerId = runCatching { ev.getPointerId(ev.actionIndex) }.getOrDefault(-1)
            if (voicePointerId == -1 || upPointerId == voicePointerId) {
                transformed(KeyboardAction.VoiceDragUp)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    val panel = KawaiiPanel(
        context = context,
        listener = panelListener,
    )

    init {
        panel.onRecordingStop = {
            isVoiceRecording = false
            panel.recording = false
            SherpaSpeechClient.stopHoldSession(discard = true)
            voiceOverlay.hide()
        }
    }

    private val preeditPinner = PreeditPinner(context)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val voiceOverlay = SpeechOverlayView(context).apply {
        onSpeechActionListener = object : SpeechOverlayView.OnSpeechActionListener {
            override fun onClose() {
                stopVoiceInput()
            }

            override fun onLockStateChanged(isLocked: Boolean) {
                // 点击解锁 = 免提录音结束：没有手指再按着，继续录下去只能靠关按钮收尾，
                // 语义上等同松手——结束并上屏。锁定（点锁）时不做处理，录音继续。
                if (!isLocked && isVoiceRecording) {
                    stopVoiceInput(discard = false)
                }
            }
        }
    }

    private var isVoiceRecording = false
        set(value) {
            field = value
            // 录音一结束，启动手势令牌随之失效，下次录音重新认领
            if (!value) voicePointerId = -1
        }

    /** 启动本次语音拖拽手势的指针 id；-1 表示未知（非拖拽启动） */
    private var voicePointerId = -1

    private val addPhraseLayer = InputBoxLayerView(context).apply {
        visibility = View.GONE
    }

    private val imeToastView = ImeToastView(context)

    var keyActionListener: KeyActionListener
        get() = keyboardStateManager.keyActionListener
        set(value) {
            keyboardStateManager.keyActionListener = KeyActionListener { action ->
                transformed(action)?.let { value.onKeyAction(it) }
            }
        }

    fun transformed(action: KeyboardAction): KeyboardAction? {
        val transformed: KeyboardAction? = when (action) {
            is KeyboardAction.RotateSchema -> {
                val schemeId = keyboardStateManager.rotateSchema()
                return KeyboardAction.SelectSchema(schemeId)
            }

            is KeyboardAction.LayoutSwitchAction -> {
                keyboardStateManager.switchTo(action.target)
                null
            }

            is KeyboardAction.ResumeAction -> {
                keyboardStateManager.resume()
                null
            }

            is KeyboardAction.ShowInputMethodPickerAction -> {
                val dialog = SchemaPickerDialog.build(
                    context = context,
                    schemas = keyboardStateManager.getSchemas(),
                    currentSchemaId = keyboardStateManager.getCurrentSchema()?.id,
                    colors = cachedColors,
                    onSchemaSelected = { schemaId -> keyboardStateManager.selectSchema(schemaId) })
                (context as ImeInputMethodService).showDialog(dialog)
                null
            }

            is KeyboardAction.StopVoiceInputAction -> {
                stopVoiceInput()
                null
            }

            is KeyboardAction.VoiceDragPosition -> {
                if (isVoiceRecording) {
                    // 认领启动手势的指针：父容器此后只认这根手指的抬起
                    if (voicePointerId == -1) voicePointerId = action.pointerId
                    voiceOverlay.onDragPosition(action.rawX, action.rawY)
                }
                null
            }

            is KeyboardAction.VoiceDragUp -> {
                if (isVoiceRecording) {
                    when (voiceOverlay.currentDragTarget) {
                        SpeechOverlayView.DragTarget.CLOSE -> {
                            stopVoiceInput(discard = true)
                        }

                        SpeechOverlayView.DragTarget.LOCK -> {
                            voiceOverlay.setDragLocked()
                        }

                        SpeechOverlayView.DragTarget.NONE -> {
                            stopVoiceInput(discard = false)
                        }
                    }
                }
                null
            }

            is KeyboardAction.VoiceInputAction -> {
                if (isVoiceRecording) {
                    stopVoiceInput()
                } else {
                    startVoiceInput()
                }
                null
            }

            else -> action
        }
        return transformed
    }


    fun onConfigChanged(key: String) {
        when (key) {
            SchemaManager.KEY_ENABLED_IDS -> keyboardStateManager.onConfigChanged(key)
            KeyboardManager.Keyboard.KEY_HEIGHT, KeyboardManager.Keyboard.KEY_HEIGHT_LANDSCAPE, KeyboardManager.Keyboard.Padding.KEY_HORIZONTAL, KeyboardManager.Keyboard.Padding.KEY_BOTTOM, KeyboardManager.Keyboard.KEY_IGNORE_INSETS -> post {
                panel.view.updateHorizontalPadding(
                    KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat()
                )
                requestLayout()
            }

            KeyboardManager.Keyboard.KeyRadius.KEY, KeyboardManager.Keyboard.KEY_THEME, KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, KeyboardManager.Keyboard.KEY_LIGHT_THEME, KeyboardManager.Keyboard.KEY_DARK_THEME, KeyboardManager.Keyboard.Gap.KEY_HORIZONTAL, KeyboardManager.Keyboard.Gap.KEY_VERTICAL -> post { refreshColors() }

            KeyboardManager.Keyboard.RippleEffect.KEY -> post {
                keyboardStateManager.setRippleEnabled(
                    KeyboardManager.Keyboard.RippleEffect.isEnabled(context)
                )
            }

            KeyboardManager.Keyboard.KeyBorderStroke.KEY,
            KeyboardManager.Keyboard.ExpandBorder.KEY,
            CandidateManager.KEY_BORDER,
            CandidateManager.KEY_SHOW_INDEX,
            CandidateManager.KEY_SHOW_COMMENT,
                -> post { refreshColors() }
        }
    }

    private var cachedBottomInset = 0

    fun addKeyboardView(keyboard: IKeyboard) {
        val view = keyboard as View
        (view.parent as? ViewGroup)?.removeView(view)
        if (view.parent == null) {
            addView(
                view, 0, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    fun removeKeyboardView(keyboard: IKeyboard) {
        val view = keyboard as View
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun createKeyboard(name: String): IKeyboard {
        val b = when (name) {
            T15Keyboard.NAME -> T15Keyboard(context, cachedColors)
            T9Keyboard.NAME -> T9Keyboard(context, cachedColors)
            SymbolKeyboard.NAME -> SymbolKeyboard(context, cachedColors)
            EmojiKeyboard.NAME -> EmojiKeyboard(context, cachedColors)
            NumberKeyboard.NAME -> NumberKeyboard(context, cachedColors)
            else -> QwertyKeyboard(context, cachedColors)
        }
        b.setRippleEnabled(KeyboardManager.Keyboard.RippleEffect.isEnabled(context))
        return b
    }

    private val keyboardFactory: (String) -> IKeyboard = { name -> createKeyboard(name) }

    fun onShowKeyboard(keyboard: IKeyboard) {
        currentKeyboard = keyboard
        addKeyboardView(keyboard)
    }

    fun onHideKeyboard(keyboard: IKeyboard) {
        removeKeyboardView(keyboard)
        if (currentKeyboard === keyboard) currentKeyboard = null
    }

    fun onKeyboardChanged(keyboard: IKeyboard) {
        currentKeyboard = keyboard
        addKeyboardView(keyboard)
    }

    init {
        keyboardStateManager.setKeyboardFactory(keyboardFactory)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom,
            )
            if (bottom != cachedBottomInset) {
                cachedBottomInset = bottom
                view.requestLayout()
            }
            insets
        }

        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText,
            cachedColors.accentKeyBackground,
            cachedColors.accentKeyText
        )

        setBackgroundColor(cachedColors.background)

        addView(panel.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            panel.candidateGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.textEditingView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.clipboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.menuGridView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.confirmOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        addView(
            addPhraseLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(imeToastView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addPhraseLayer.onConfirm = { panelListener?.onAddPhraseSave(it) }
        addPhraseLayer.onClose = { panelListener?.onAddPhraseCancel() }
    }

    fun toggleMenu() {
        panel.toggleMenu()
    }

    var addPhraseActive = false
        private set

    fun enterAddPhraseMode(buffer: ImeInputConnection) {
        addPhraseActive = true
        addPhraseLayer.refreshTheme(cachedColors)
        addPhraseLayer.title = context.getString(R.string.phrase_add_title)
        addPhraseLayer.hint = context.getString(R.string.phrase_input_hint)
        addPhraseLayer.bind(buffer)
        addPhraseLayer.show()
        requestLayout()
    }

    fun exitAddPhraseMode() {
        if (!addPhraseActive) return
        addPhraseActive = false
        addPhraseLayer.hide()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        panel.onFinishInputView(true)
        // 语音桥回调在销毁时置空（桥内已是弱引用后备，这里再显式清一遍，双保险）
        SpeechUiBridge.clear()
        preeditPinner.detach(wm)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
        val bPad = dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context))
        val barH = (PANEL_HEIGHT_DP * density).roundToInt()
        val cHeight = contentHeight()
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val bottomInset = resolveBottomInset()
        val contentW = (totalWidth - 2 * hPad).coerceAtLeast(0)

        val stripH = if (addPhraseActive) (fullScreenHeight() * 0.20f).roundToInt() else 0

        panel.view.measure(
            MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY),
        )

        // 搜索态多出一段结果列表区（键盘全尺寸不变，窗口总高相应拉高）
        val clipStripH = clipSearchStripHeight(cHeight, barH, bPad, bottomInset)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.textEditingView || child === panel.clipboardView || child === panel.menuGridView || child === panel.confirmOverlay || child === addPhraseLayer || child === imeToastView || child.isGone) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        // 覆盖层大多时候是 GONE 的：旧实现每次 onMeasure 都连 GONE 的一起
        // 全量测一遍，逐个加可见性守卫（布局阶段对 GONE 子视图同样跳过）
        if (!panel.textEditingView.isGone) {
            panel.textEditingView.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        if (!panel.clipboardView.isGone) {
            val clipH = if (clipStripH > 0) clipStripH else cHeight
            panel.clipboardView.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(clipH, MeasureSpec.EXACTLY),
            )
        }

        if (!panel.menuGridView.isGone) {
            panel.menuGridView.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        if (!panel.confirmOverlay.isGone) {
            panel.confirmOverlay.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        if (addPhraseActive && !addPhraseLayer.isGone) {
            addPhraseLayer.measure(
                MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(stripH, MeasureSpec.EXACTLY),
            )
        }

        if (!imeToastView.isGone) {
            imeToastView.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.AT_MOST),
            )
        }

        val totalHeight = stripH + barH + clipStripH + cHeight + bPad + bottomInset
        setMeasuredDimension(totalWidth, totalHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
        val barH = (PANEL_HEIGHT_DP * resources.displayMetrics.density).roundToInt()
        val cHeight = contentHeight()
        val contentW = right - left - 2 * hPad
        val stripH = if (addPhraseActive) (fullScreenHeight() * 0.20f).roundToInt() else 0
        val y0 = stripH + barH

        panel.view.layout(0, stripH, right - left, stripH + barH)

        // 搜索态：键盘整体下移一段（结果列表区在上），尺寸与行高不变
        val clipStripH = clipSearchStripHeight(
            cHeight, barH, dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context)),
            resolveBottomInset()
        )
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.candidateGrid || child === panel.textEditingView || child === panel.clipboardView || child === panel.menuGridView || child === panel.confirmOverlay || child === addPhraseLayer || child === imeToastView || child.isGone) continue
            if (clipStripH > 0 && child !== panel.candidateGrid) {
                child.layout(hPad, y0 + clipStripH, hPad + contentW, y0 + clipStripH + cHeight)
            } else {
                child.layout(hPad, y0, hPad + contentW, y0 + cHeight)
            }
        }

        panel.candidateGrid.layout(hPad, y0, hPad + contentW, y0 + cHeight)
        panel.textEditingView.layout(hPad, y0, hPad + contentW, y0 + cHeight)
        panel.clipboardView.layout(
            hPad, y0, hPad + contentW,
            y0 + if (clipStripH > 0) clipStripH else cHeight
        )
        panel.menuGridView.layout(hPad, y0, hPad + contentW, y0 + cHeight)
        panel.confirmOverlay.layout(hPad, y0, hPad + contentW, y0 + cHeight)
        addPhraseLayer.layout(0, 0, right - left, stripH)
        val toastLeft = ((right - left) - imeToastView.measuredWidth) / 2
        val bottomPadding = dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context))
        val toastBottom = bottom - top - bottomPadding - resolveBottomInset() - dpToPx(12)
        imeToastView.layout(
            toastLeft,
            toastBottom - imeToastView.measuredHeight,
            toastLeft + imeToastView.measuredWidth,
            toastBottom,
        )
    }


    /**
     * 剪贴板文本输入态（搜索/条目编辑）的结果列表区高度（同文大海版
     * 形态）：点搜索后整个窗口往上拉高到宿主标题栏下方（顶部预留约
     * 136dp 给状态栏+标题，即「放在名字下面」），工具栏（搜索框）之下、
     * 键盘之上是一段独立的结果列表区，键盘保持全尺寸不变。结果在该区
     * 内滚动看全部，不需要收起键盘。非输入态为 0（列表与键盘同框叠放，
     * 同旧行为）。
     */
    private fun clipSearchStripHeight(
        cHeight: Int, barH: Int, bPad: Int, bottomInset: Int,
    ): Int {
        if (!panel.clipSearchActive && !panel.clipEditActive) return 0
        val topReserve = (136f * resources.displayMetrics.density).roundToInt()
        val keyboardBlock = barH + cHeight + bPad + bottomInset
        val target = fullScreenHeight() - topReserve - keyboardBlock
        // 兜底：不小于原来的 55% 段高，也不吃掉整个屏幕
        return target.coerceIn((cHeight * 0.55f).roundToInt(), fullScreenHeight())
    }

    /** 当前输入框是否为密码框（onStartInput 时更新）：密码框禁用语音与粘贴横幅。 */
    private var passwordField = false

    fun onStartInput(info: EditorInfo) {
        passwordField = com.jobeen.ime.base.util.InputFieldPolicy.isPasswordField(info)
        panel.suppressCopyBanner = passwordField
        panel.view.setExpanded(false)
        panel.onStartInputView()
        keyboardStateManager.startInput(info)
    }

    fun refreshColors() {
        panel.view.setExpanded(false)
        cachedColors = KeyboardColors.resolve(context)
        setBackgroundColor(cachedColors.background)
        panel.refreshTheme()
        addPhraseLayer.refreshTheme(cachedColors)
        imeToastView.refreshTheme(cachedColors)
        preeditPinner.refreshTheme(context)
        keyboardStateManager.rebuild()
        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText,
            cachedColors.accentKeyBackground,
            cachedColors.accentKeyText
        )
    }

    // 仅当解析出的配色与当前缓存不一致时才全量刷新（主题/跟随系统深浅变化等场景）。
    fun refreshColorsIfChanged() {
        val resolved = KeyboardColors.resolve(context)
        if (resolved != cachedColors) {
            refreshColors()
        }
    }

    fun refreshLayout() = requestLayout()

    private var currentKeyboard: IKeyboard? = null

    fun setCandidates(list: List<EngineMessage.Candidate>, hasMore: Boolean = false) =
        panel.setCandidates(list, hasMore)

    fun appendCandidates(list: List<EngineMessage.Candidate>, total: Int) =
        panel.appendCandidates(list, total)

    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        panel.onPossibleCandidatePinYin(pinyins)
        (currentKeyboard as? ISidePanelKeyboard)?.onPossibleCandidatePinYin(pinyins)
    }

    fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>) {
        preeditPinner.updateDynamicPreedit(items)
        if (items.isEmpty()) {
            preeditPinner.hide(wm)
        } else {
            val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
            preeditPinner.show(context, wm, panel.view, hPad)
        }
    }

    private fun contentHeight(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val percent = if (isLandscape) {
            KeyboardManager.Keyboard.getHeightPercentLandscape(context)
        } else {
            KeyboardManager.Keyboard.getHeightPercent(context)
        }
        val fullHeight = fullScreenHeight()
        return (fullHeight * percent / 100).coerceAtLeast(minimumHeight)
    }

    private fun fullScreenHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.maximumWindowMetrics.bounds.height()
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(
            dm
        )
        return dm.heightPixels
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun resolveBottomInset(): Int {
        if (KeyboardManager.Keyboard.getIgnoreInsets(context)) return 0
        if (cachedBottomInset > 0) return cachedBottomInset
        val computed = computeBottomInset()
        if (computed > 0) cachedBottomInset = computed
        return computed
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun computeBottomInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = WindowInsetsCompat.toWindowInsetsCompat(
                wm.maximumWindowMetrics.windowInsets, this
            )
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val systemGestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            return maxOf(navBars.bottom, mandatory.bottom, systemGestures.bottom)
        }
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    override fun onAttach() = keyboardStateManager.onAttach()

    override fun onDetach() {
        if (isVoiceRecording) {
            isVoiceRecording = false
            panel.recording = false
            SherpaSpeechClient.stopHoldSession(discard = true)
            voiceOverlay.hide()
        }
        keyboardStateManager.onDetach()
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        showMicPermissionPrompt()
        return false
    }

    private fun showMicPermissionPrompt() {
        panel.confirmOverlay.confirm(
            message = context.getString(R.string.voice_permission_message),
            onConfirm = {
                val intent = Intent(
                    context, com.jobeen.ime.base.speech.SpeechPermissionActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            },
            centerHorizontal = true,
            centerVertical = true,
        )
    }

    private fun showModelDownloadPrompt() {
        // 防御性重置：不依赖异步 onFailed 回调的时序，任何路径下先回到干净状态，
        // 避免工具栏 recording 变暗态残留。
        isVoiceRecording = false
        panel.recording = false
        voiceOverlay.hide()
        panel.confirmOverlay.confirm(
            message = context.getString(R.string.voice_model_missing_message),
            onConfirm = {
                val intent = Intent(
                    context, com.jobeen.ime.ui.VoiceSettingsActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(
                        com.jobeen.ime.ui.VoiceSettingsActivity.EXTRA_AUTO_DOWNLOAD,
                        true,
                    )
                }
                runCatching { context.startActivity(intent) }
            },
            onCancel = null,
            centerHorizontal = true,
            centerVertical = true,
        )
    }

    // 语音桥回调实例：本视图字段强持有，SpeechUiBridge 内部弱引用后备，
    // 视图销毁后桥的槽位自动失效，不会反向强持已销毁的视图
    private val speechRecordingStartedCallback: () -> Unit = {
        // 依赖就绪、录音真正开始后才展示动画，避免未就绪时一闪而过导致抖动
        voiceOverlay.show()
        voiceOverlay.bringToFront()
    }
    private val speechAmplitudeCallback: (Float) -> Unit = { amp ->
        voiceOverlay.updateAmplitude(amp)
    }
    private val speechDoneCallback: () -> Unit = {
        isVoiceRecording = false
        panel.recording = false
        if (!voiceOverlay.isLocked) {
            voiceOverlay.hide()
        }
    }
    private val speechFailedCallback: () -> Unit = {
        isVoiceRecording = false
        panel.recording = false
        voiceOverlay.hide()
    }
    private val speechModelMissingCallback: (ModelProvider) -> Unit =
        { _ -> showModelDownloadPrompt() }

    private fun registerSpeechCallbacks() {
        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = speechRecordingStartedCallback
        SpeechUiBridge.onAmplitude = speechAmplitudeCallback
        SpeechUiBridge.onDone = speechDoneCallback
        SpeechUiBridge.onFailed = speechFailedCallback
        SpeechUiBridge.onModelMissing = speechModelMissingCallback
    }

    private fun startVoiceInput() {
        if (isVoiceRecording) return
        // 密码框禁用语音输入
        if (passwordField) return
        if (!ensureRecordAudioPermission()) return
        // 模型缺失时直接弹下载提示，不进入录音态：
        // 避免工具栏闪一下变暗、语音动画又永远不出现。
        if (!SherpaSpeechClient.isModelReady(context)) {
            showModelDownloadPrompt()
            return
        }
        isVoiceRecording = true
        panel.recording = true
        voiceOverlay.unlock()
        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText
        )
        if (voiceOverlay.parent == null) {
            addView(voiceOverlay)
        }

        registerSpeechCallbacks()

        SherpaSpeechClient.startHoldSession(context as ImeInputMethodService)
    }

    private fun stopVoiceInput(discard: Boolean = false) {
        // 按箭头撤销时，即使用户松手后录音已结束（isVoiceRecording=false），悬浮层也必须关闭，
        // 否则悬浮层会残留。stopHoldSession 内部有 CAS 保护，重复调用无害。
        isVoiceRecording = false
        panel.recording = false
        SherpaSpeechClient.stopHoldSession(discard = discard)
        voiceOverlay.hide()
    }


    fun toggleVoiceLocked() {
        if (isVoiceRecording) {
            stopVoiceInput()
        } else {
            startVoiceInputLocked()
        }
    }

    private fun startVoiceInputLocked() {
        if (isVoiceRecording) return
        // 密码框禁用语音输入
        if (passwordField) return
        if (!ensureRecordAudioPermission()) return
        // 模型缺失时直接弹下载提示，不进入录音态（同 startVoiceInput）
        if (!SherpaSpeechClient.isModelReady(context)) {
            showModelDownloadPrompt()
            return
        }
        isVoiceRecording = true
        panel.recording = true
        voiceOverlay.unlock()
        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText
        )
        if (voiceOverlay.parent == null) {
            addView(voiceOverlay)
        }
        voiceOverlay.setDragLocked()

        registerSpeechCallbacks()

        SherpaSpeechClient.startHoldSession(context as ImeInputMethodService)
    }

    fun onInputChanged(
        info: EditorInfo?, text: String, virtualInputConnection: Boolean = false,
    ): Any {
        if (isVoiceRecording && text.isEmpty()) {
            isVoiceRecording = false
            panel.recording = false
            SherpaSpeechClient.stopHoldSession(discard = true)
            voiceOverlay.hide()
        }
        panel.onInputChanged(text)
        keyboardStateManager.onInputChanged(info, text, virtualInputConnection)
        return Unit
    }

    fun showImeToast(message: CharSequence) {
        imeToastView.showToast(message, cachedColors)
        imeToastView.bringToFront()
    }

    fun switchKeyboard(name: String) = keyboardStateManager.switchTo(name)
    fun onDepolyFinished() = keyboardStateManager.refreshSchemas()
}
