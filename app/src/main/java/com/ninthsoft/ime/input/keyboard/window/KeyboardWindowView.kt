package com.ninthsoft.ime.input.keyboard.window

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
import com.ninthsoft.ime.input.keyboard.impl.IKeyboard
import com.ninthsoft.ime.input.keyboard.impl.ISidePanelKeyboard
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.EmojiKeyboard
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.impl.T9Keyboard
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.pinner.PreeditPinner
import com.ninthsoft.ime.input.speech.SpeechOverlayView
import com.ninthsoft.ime.base.speech.SherpaSpeechClient
import com.ninthsoft.ime.base.speech.SpeechUiBridge
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.ImeInputConnection
import com.ninthsoft.ime.input.dialog.SchemaPickerDialog
import com.ninthsoft.ime.input.panel.PanelListener
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

            override fun onLockStateChanged(isLocked: Boolean) {}
        }
    }

    private var isVoiceRecording = false

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
                    voiceOverlay.onDragPosition(action.rawX, action.rawY)
                }
                null
            }

            is KeyboardAction.VoiceDragUp -> {
                if (isVoiceRecording) {
                    when (voiceOverlay.currentDragTarget) {
                        SpeechOverlayView.DragTarget.CLOSE -> {
                            stopVoiceInput()
                        }

                        SpeechOverlayView.DragTarget.LOCK -> {
                            voiceOverlay.setDragLocked()
                        }

                        SpeechOverlayView.DragTarget.NONE -> {
                            stopVoiceInput()
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

            KeyboardManager.Keyboard.KeyBorderStroke.KEY -> post { refreshColors() }
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
        preeditPinner.hide(wm)
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

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.textEditingView || child === panel.clipboardView || child === panel.menuGridView || child === panel.confirmOverlay || child === addPhraseLayer || child === imeToastView || child.isGone) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        panel.textEditingView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        panel.clipboardView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        panel.menuGridView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        panel.confirmOverlay.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        addPhraseLayer.measure(
            MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(stripH, MeasureSpec.EXACTLY),
        )

        imeToastView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.AT_MOST),
        )

        val totalHeight = stripH + barH + cHeight + bPad + bottomInset
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

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.candidateGrid || child === panel.textEditingView || child === panel.clipboardView || child === panel.menuGridView || child === panel.confirmOverlay || child === addPhraseLayer || child === imeToastView || child.isGone) continue
            child.layout(hPad, y0, hPad + contentW, y0 + cHeight)
        }

        panel.candidateGrid.layout(hPad, y0, hPad + contentW, y0 + cHeight)
        panel.textEditingView.layout(hPad, y0, hPad + contentW, y0 + cHeight)
        panel.clipboardView.layout(hPad, y0, hPad + contentW, y0 + cHeight)
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


    fun onStartInput(info: EditorInfo) {
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

    fun setCandidates(list: List<EngineMessage.Candidate>) = panel.setCandidates(list)

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
                    context, com.ninthsoft.ime.base.speech.SpeechPermissionActivity::class.java
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
        if (!isVoiceRecording) return
        panel.confirmOverlay.confirm(
            message = context.getString(R.string.voice_model_missing_message),
            onConfirm = {
                isVoiceRecording = false
                val intent = Intent(
                    context, com.ninthsoft.ime.ui.VoiceSettingsActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(
                        com.ninthsoft.ime.ui.VoiceSettingsActivity.EXTRA_AUTO_DOWNLOAD,
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

    private fun startVoiceInput() {
        if (isVoiceRecording) return
        if (!ensureRecordAudioPermission()) return
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

        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = {
            // 依赖就绪、录音真正开始后才展示动画，避免未就绪时一闪而过导致抖动
            voiceOverlay.show()
            voiceOverlay.bringToFront()
        }
        SpeechUiBridge.onAmplitude = { amp ->
            voiceOverlay.updateAmplitude(amp)
        }
        SpeechUiBridge.onDone = {
            isVoiceRecording = false
            panel.recording = false
            if (!voiceOverlay.isLocked) {
                voiceOverlay.hide()
            }
        }
        SpeechUiBridge.onFailed = {
            isVoiceRecording = false
            panel.recording = false
            voiceOverlay.hide()
        }
        SpeechUiBridge.onModelMissing = { _ -> showModelDownloadPrompt() }

        SherpaSpeechClient.startHoldSession(context as ImeInputMethodService)
    }

    private fun stopVoiceInput() {
        if (!isVoiceRecording) return
        isVoiceRecording = false
        panel.recording = false
        SherpaSpeechClient.stopHoldSession()
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
        if (!ensureRecordAudioPermission()) return
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

        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = {
            voiceOverlay.show()
            voiceOverlay.bringToFront()
        }
        SpeechUiBridge.onAmplitude = { amp ->
            voiceOverlay.updateAmplitude(amp)
        }
        SpeechUiBridge.onDone = {
            isVoiceRecording = false
            panel.recording = false
            if (!voiceOverlay.isLocked) {
                voiceOverlay.hide()
            }
        }
        SpeechUiBridge.onFailed = {
            isVoiceRecording = false
            panel.recording = false
            voiceOverlay.hide()
        }
        SpeechUiBridge.onModelMissing = { _ -> showModelDownloadPrompt() }

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
