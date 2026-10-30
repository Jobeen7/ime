package com.jobeen.ime.input.keyboard.window

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.view.Choreographer
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import java.io.File
import com.jobeen.ime.input.keyboard.impl.IKeyboard
import com.jobeen.ime.input.keyboard.impl.BaseKeyboard
import com.jobeen.ime.input.keyboard.impl.ISidePanelKeyboard
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import com.jobeen.ime.data.keyboard.theme.GlobalStylePrefs
import com.jobeen.ime.data.keyboard.theme.GlobalStyleResolver
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.keyboard.theme.VideoPrefs
import com.jobeen.ime.data.manager.CandidateLinePolicy
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.data.manager.SchemaManager
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.input.panel.toolbar.ToolbarUserStore
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.skin.model.DynamicTextSource
import com.jobeen.ime.input.keyboard.impl.EmojiKeyboard
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.input.keyboard.impl.NumberKeyboard
import com.jobeen.ime.input.keyboard.impl.QwertyKeyboard
import com.jobeen.ime.input.keyboard.impl.SymbolKeyboard
import com.jobeen.ime.input.keyboard.impl.T9Keyboard
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.panel.KawaiiPanel
import com.jobeen.ime.input.pinner.PreeditPinner
import com.jobeen.ime.input.speech.SpeechOverlayView
import com.jobeen.ime.base.speech.SherpaSpeechClient
import com.jobeen.ime.base.speech.SpeechUiBridge
import com.jobeen.ime.base.speech.VoiceInputSwitchStore
import com.jobeen.ime.base.speech.cloud.BuiltinPlugins
import com.jobeen.ime.base.speech.cloud.CloudSessionFailure
import com.jobeen.ime.base.speech.cloud.CloudVoiceGate
import com.jobeen.ime.base.speech.cloud.CloudVoiceGateResult
import com.jobeen.ime.base.speech.cloud.CloudVoiceSession
import com.jobeen.ime.base.speech.cloud.KeystoreSecretStore
import com.jobeen.ime.base.speech.cloud.PluginStore
import com.jobeen.ime.base.speech.cloud.ServiceTextSink
import com.jobeen.ime.base.speech.cloud.SharedPrefsStore
import com.jobeen.ime.base.speech.cloud.VoiceRecorderAdapter
import com.jobeen.ime.base.util.TraditionalConverter
import com.jobeen.ime.input.ImeInputMethodService
import com.jobeen.ime.input.ImeInputConnection
import com.jobeen.ime.input.dialog.SchemaPickerDialog
import com.jobeen.ime.input.keyboard.impl.GenericLayoutKeyboard
import com.jobeen.ime.skin.model.GeometryField
import com.jobeen.ime.skin.model.KeyboardGeometryField
import com.jobeen.ime.skin.model.OptionLabelResolver
import com.jobeen.ime.skin.model.OptionStateMapper
import com.jobeen.ime.skin.notify.ConditionStyleParser
import com.jobeen.ime.skin.notify.NotificationEvent
import com.jobeen.ime.skin.notify.NotificationSource
import com.jobeen.ime.skin.runtime.CustomLayoutRuntime
import com.jobeen.ime.skin.runtime.PlaceholderValues
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.runtime.SkinRuntime
import com.jobeen.ime.skin.runtime.SkinRuntimeState
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.base.util.appScope
import com.jobeen.ime.engine.EngineFactory
import com.jobeen.ime.engine.rime.core.IRimeJob
import com.jobeen.ime.engine.rime.core.RimeSchema
import com.jobeen.ime.engine.rime.util.OptionsApplier
import kotlinx.coroutines.launch
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

        /** 配色刷新防抖窗口：窗口内的多次设置变更合并为一次全量刷新 */
        const val REFRESH_COLORS_DEBOUNCE_MS = 200L
    }

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    /**
     * 主题视频背景（2026-10-29 外观轮）：懒建，失败回调复评
     * 视频层状态（失败→静态背景，GIF 层随之恢复）。
     */
    private val videoController by lazy {
        VideoBackgroundController(this) { syncVideoBackground() }
    }

    /** 当前窗口背景 GIF 动图（SDK≥28 时解原文件；无 GIF 时 null）。 */
    private var gifDrawable: android.graphics.drawable.AnimatedImageDrawable? = null

    /**
     * 键盘顶角（2026-10-29 外观轮全局样式）：贴底键盘只论顶角——
     * 上方两角按全局值倒圆，下方两角保持直角；未设为直角（现行行为）。
     * 轮廓提供器常驻（半径 0 时路径即矩形），裁剪只在半径＞0 时开。
     */
    private var windowTopCornerRadiusPx: Float = 0f
    @Suppress("DEPRECATION")
    private val topCornerOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return
            val r = windowTopCornerRadiusPx.coerceIn(0f, minOf(w / 2f, h / 2f))
            val path = Path().apply {
                moveTo(0f, h.toFloat())
                lineTo(0f, r)
                quadTo(0f, 0f, r, 0f)
                lineTo(w - r, 0f)
                quadTo(w.toFloat(), 0f, w.toFloat(), r)
                lineTo(w.toFloat(), h.toFloat())
                close()
            }
            outline.setConvexPath(path)
        }
    }

    private fun applyWindowCorners() {
        windowTopCornerRadiusPx = (GlobalStylePrefs.get(context).keyboardTopCornerDp
            ?.coerceIn(0f, 32f) ?: 0f) * resources.displayMetrics.density
        if (outlineProvider !== topCornerOutlineProvider) {
            outlineProvider = topCornerOutlineProvider
        }
        clipToOutline = windowTopCornerRadiusPx > 0f
        invalidateOutline()
    }

    // 实测：语音拖拽时 UP 事件到不了空格按键的 onTouchUpListener（MOVE 能到，
    // UP/CANCEL 都到不了）。在父容器直接拦截，触发语音拖拽松手逻辑。
    // 只认启动语音的那根手指（voicePointerId，长按触发瞬间由
    // VoiceInputAction 穿线带入，录音开始即已认领，不再等首次 MOVE）：
    // 其他手指的抬起不得结束本次录音。id 未知（工具栏锁定启动等无指针
    // 场景）时保持旧行为，任意 UP 可结束。
    // ACTION_CANCEL 无条件结束：手势是被系统打断的，事件里的指针 id
    // 不可靠（可能取不到或不是启动手指），再做 id 比对会漏掉收尾。
    // 锁定录音时不拦截：浮层空白处点按不应结束录音。
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (isVoiceRecording && !voiceOverlay.isLocked) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_CANCEL -> {
                    transformed(KeyboardAction.VoiceDragUp)
                }

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_POINTER_UP -> {
                    val upPointerId =
                        runCatching { ev.getPointerId(ev.actionIndex) }.getOrDefault(-1)
                    if (voicePointerId == -1 || upPointerId == voicePointerId) {
                        transformed(KeyboardAction.VoiceDragUp)
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    val panel = KawaiiPanel(
        context = context,
        listener = panelListener,
    )

    // 接线轮 R1/R2：选项偏好监听与运行态变化监听（init 内注册，
    // 故声明必须在 init 之前；onDetachedFromWindow 内注销）。
    private val optionPrefs =
        context.getSharedPreferences(CandidateManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val optionPrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 菜单开关改偏好 → 引擎偏好监听同批应用选项；本刷新经
            // 引擎作业队列读值，天然排在应用作业之后，不读到旧值。
            if (key != null && OptionsApplier.isOptionDependency(key)) refreshOptionStateAsync()
        }
    private val runtimeStateListener: () -> Unit = { post { refreshNotificationState() } }

    init {
        // 接线轮 R1/R2：运行态变化监听（post 重建）与选项偏好监听。
        SkinRuntimeState.addStateChangedListener(runtimeStateListener)
        optionPrefs.registerOnSharedPreferenceChangeListener(optionPrefsListener)
        panel.onRecordingStop = {
            if (cloudVoiceActive) {
                stopCloudVoice(discard = true)
            } else {
                isVoiceRecording = false
                panel.recording = false
                SherpaSpeechClient.stopHoldSession(discard = true)
                voiceOverlay.hide()
            }
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

    // 云端语音会话（严格可选）：与离线共用 isVoiceRecording 旗标与
    // 浮层，退出一律经 stopCloudVoice；离线路径不受任何影响。
    private var cloudVoiceSession: CloudVoiceSession? = null
    private var cloudVoiceActive = false

    private var isVoiceRecording = false
        set(value) {
            field = value
            // 录音一结束，启动手势令牌随之失效，下次录音重新认领
            if (!value) voicePointerId = -1
        }

    /** 启动本次语音的指针 id（长按触发瞬间经 VoiceInputAction 穿线带入）；
     * -1 表示未知（工具栏锁定启动等无指针场景），松手判定回退为任意 UP */
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

            is KeyboardAction.SwitchSchemaByValue -> {
                val resolved = SchemaTargetResolver.resolve(
                    action.value,
                    keyboardStateManager.getSchemas().map {
                        SchemaTargetResolver.SchemaRef(it.id, it.name)
                    },
                )
                if (resolved == null) {
                    DiagLog.w(
                        "keymap", "switch_schema_unresolved",
                        mapOf("target" to action.value),
                    )
                    return null
                }
                return KeyboardAction.SelectSchema(resolved)
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
                // 关闭收尾经 showDialog 注册给服务侧单一监听转调，不再
                // 由弹窗自己 setOnDismissListener（会被服务侧覆盖）；
                // 方案弹窗不压暗非弹层区域，dim 传 false
                (context as ImeInputMethodService).showDialog(dialog, dim = false) {
                    SchemaPickerDialog.handleDismiss(dialog)
                }
                null
            }

            is KeyboardAction.StopVoiceInputAction -> {
                stopVoiceInput()
                null
            }

            is KeyboardAction.VoiceDragPosition -> {
                if (isVoiceRecording) {
                    // 启动指针已在长按触发时经 VoiceInputAction 认领；这里仅
                    // 兜底旧路径（id 仍未知时以首次 MOVE 上报的指针补认领）
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
                    // 穿线带入的启动指针 id 随启动一并记下（见 startVoiceInput）
                    startVoiceInput(action.pointerId)
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

            KeyboardManager.Keyboard.KeyRadius.KEY, KeyboardManager.Keyboard.KEY_THEME, KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, KeyboardManager.Keyboard.KEY_LIGHT_THEME, KeyboardManager.Keyboard.KEY_DARK_THEME, KeyboardManager.Keyboard.Gap.KEY_HORIZONTAL, KeyboardManager.Keyboard.Gap.KEY_VERTICAL, SkinColorMode.KEY -> scheduleRefreshColors()

            KeyboardManager.Keyboard.RippleEffect.KEY -> post {
                keyboardStateManager.setRippleEnabled(
                    KeyboardManager.Keyboard.RippleEffect.isEnabled(context)
                )
            }

            KeyboardManager.Keyboard.KeyBorderStroke.KEY,
            KeyboardManager.Keyboard.ExpandBorder.KEY,
            KeyboardManager.Keyboard.Mnemonic.KEY,
            KeyboardManager.Keyboard.Decoration.KEY,
            CandidateManager.KEY_BORDER,
            CandidateManager.KEY_SHOW_INDEX,
            CandidateManager.KEY_SHOW_COMMENT,
                -> scheduleRefreshDisplaySettings()

            // 外观轮全局样式：模糊键走背景重建专路（只换背景位图，
            // 不重建键盘）；其余键走强制刷新（圆角/栏开关不在配色内，
            // 普通路的配色门控会把它们挡下）。
            in GlobalStylePrefs.ALL_KEYS -> {
                if (key in GlobalStylePrefs.BLUR_KEYS) {
                    SkinSnapshotProvider.refreshBackgroundBlur(context)
                } else {
                    scheduleRefreshDisplaySettings()
                }
            }

            // 外观轮全局字体：版本＋1 时解析缓存已清，强制刷新
            // 重建按键与面板画笔（字体不在配色内，普通路会被挡下）。
            in GlobalFonts.ALL_KEYS -> scheduleRefreshDisplaySettings()

            // 外观轮视频开关：只同步视频层，不重建键盘。
            in VideoPrefs.ALL_KEYS -> syncVideoBackground()
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
        val b = when {
            // 自创布局在 else 兜底之前显式认领（B3 §3.3）：认领失败
            // 走 buildCustomKeyboard 的回退链，绝不静默变 Qwerty 而不留痕
            name.startsWith(CustomLayoutRuntime.PREFIX) -> buildCustomKeyboard(name)
            else -> when (name) {
                T15Keyboard.NAME -> T15Keyboard(context, cachedColors)
                T9Keyboard.NAME -> T9Keyboard(context, cachedColors)
                SymbolKeyboard.NAME -> SymbolKeyboard(context, cachedColors)
                EmojiKeyboard.NAME -> EmojiKeyboard(context, cachedColors)
                NumberKeyboard.NAME -> NumberKeyboard(context, cachedColors)
                else -> QwertyKeyboard(context, cachedColors)
            }
        }
        b.setRippleEnabled(KeyboardManager.Keyboard.RippleEffect.isEnabled(context))
        return b
    }

    /**
     * 自创布局键盘构建（B3 §3.5 第 3 项回退链）：快照已解析布局
     * 过覆写装配后喂通用键盘类；解析缺失（运行期数据竞态，如
     * 构建后布局被删）→ 记 ERROR 并回退方案默认键盘，绝不空白。
     */
    private fun buildCustomKeyboard(name: String): IKeyboard {
        val snapshot = SkinSnapshotProvider.current(context)
        // 横屏取横屏结构（2026-10-27 第一组）：方向来自当前视图
        // 配置；有横屏专属绑定时取绑定布局，否则取竖屏布局的
        // 横屏权重变体；旋转重建键盘时自动切换，无须重建快照。
        val landscape = context.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val effective = CustomLayoutRuntime.effectiveLayout(
            snapshot, PlaceholderValues.current(context), landscape = landscape,
            isDark = SkinColorMode.isDark(context),
            mnemonicEnabled = KeyboardManager.Keyboard.Mnemonic.isEnabled(context),
            decorationEnabled = KeyboardManager.Keyboard.Decoration.isEnabled(context),
        )
        if (effective == null) {
            DiagLog.e(
                "layout", "layout_keyboard_build_failed",
                mapOf("layout" to name, "stage" to "factory_resolve"),
            )
            val schemaDefault = keyboardStateManager.getCurrentSchema()?.layout
            val fallback = schemaDefault?.takeIf {
                !it.startsWith(CustomLayoutRuntime.PREFIX)
            } ?: QwertyKeyboard.NAME
            return createKeyboard(fallback)
        }
        val effectiveLayoutId = if (landscape && snapshot?.resolvedLayoutLandscape != null) {
            snapshot.resolvedLayoutLandscape.layoutId
        } else {
            snapshot?.resolvedLayout?.layoutId
        }
        DiagLog.d(
            "layout", "layout_applied_to_keyboard",
            mapOf(
                "layoutId" to effectiveLayoutId,
                "orientation" to if (landscape) "landscape" else "portrait",
                "overrideHits" to CustomLayoutRuntime.overrideHitCount(snapshot, landscape),
            ),
        )
        return GenericLayoutKeyboard(context, cachedColors, name, effective)
    }

    private val keyboardFactory: (String) -> IKeyboard = { name -> createKeyboard(name) }

    fun onShowKeyboard(keyboard: IKeyboard) {
        currentKeyboard = keyboard
        pushDynamicTexts()
        addKeyboardView(keyboard)
        feedSchemeAndReturnKeyEvents()
    }

    fun onHideKeyboard(keyboard: IKeyboard) {
        removeKeyboardView(keyboard)
        if (currentKeyboard === keyboard) currentKeyboard = null
    }

    fun onKeyboardChanged(keyboard: IKeyboard) {
        currentKeyboard = keyboard
        pushDynamicTexts()
        addKeyboardView(keyboard)
        feedSchemeAndReturnKeyEvents()
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
        refreshToolbarHidden()
        applyWindowCorners()
        // 皮肤运行态注册：应用事务的首帧等待与延迟验证推进挂在本窗口上
        SkinRuntime.attachWindow(this)
        SkinRuntime.onWindowAttached()
        // 主题视频背景：挂载即进门控，播放门另由输入／可见事件推进。
        videoController.onAttached()
        videoController.onViewShownChanged(isShown)
        syncVideoBackground()
        // 性能埋点（2026-10-29 item-1 B）：冷启动结算（仅首个
        // 窗口）＋掉帧计数（帧间隔＞100ms 记一次，回调本身只做
        // 一次减法比较，不进热路径）。
        com.jobeen.ime.skin.perf.PerfMonitor.noteFirstFrame()
        lastFrameMs = -1L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * 掉帧探针：vsync 回调时间差即帧间隔；超阈值记一次。
     * detach 时注销，挂接期间每帧自续（isAttachedToWindow 门）。
     */
    private var lastFrameMs: Long = -1L
    private val frameCallback: Choreographer.FrameCallback =
        Choreographer.FrameCallback { frameTimeNanos ->
        val ms = frameTimeNanos / 1_000_000L
        if (lastFrameMs > 0L) {
            com.jobeen.ime.skin.perf.PerfMonitor.noteFrameInterval(ms - lastFrameMs)
        }
        lastFrameMs = ms
        if (isAttachedToWindow) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    /**
     * 视频层恒居最底：键盘视图总插在 0 位（addKeyboardView），
     * 会把视频层顶到键盘上；任一子视图加入后都把它复位到 0 位
     * （已在 0 位时不动，避免递归）。
     */
    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        val videoView = videoController.viewIfCreated
        if (videoView != null && videoView.parent === this && indexOfChild(videoView) != 0) {
            removeView(videoView)
            addView(videoView, 0)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        videoController.onWindowVisibilityChanged(visibility == View.VISIBLE)
        videoController.onViewShownChanged(isShown)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        // 输入统计（2026-10-29 item-1 C）：窗口 detach 时落盘一次
        // （平时 5s 防抖写，detach 是兜底 flush）。
        com.jobeen.ime.input.stats.InputStats.flush()
        videoController.onDetached()
        gifDrawable?.stop()
        gifDrawable = null
        SkinRuntime.detachWindow(this)
        SkinRuntimeState.removeStateChangedListener(runtimeStateListener)
        optionPrefs.unregisterOnSharedPreferenceChangeListener(optionPrefsListener)
        panel.onFinishInputView(true)
        // 方案选择弹窗挂在本窗口的 token 上，窗口销毁时一并关掉，
        // 避免弹窗残留在已销毁的窗口上（单例在 build 覆盖前也会自关）
        SchemaPickerDialog.dismiss()
        // 设置变化的配色刷新若还挂在防抖窗口里，销毁后不必再执行
        removeCallbacks(pendingRefresh)
        // 全高缓存随窗口生命周期结束失效，重建/重新挂载时重新查询
        cachedFullScreenHeight = -1
        // 语音桥回调在销毁时置空（桥内已是弱引用后备，这里再显式清一遍，双保险）
        SpeechUiBridge.clear()
        preeditPinner.detach(wm)
        super.onDetachedFromWindow()
    }

    // 工具栏隐藏开关缓存：全局偏好（toolbar_user.json 顶层 hideToolbar，
    // 不分皮肤）。文件读只在刷新点，不进 onMeasure 热路径；开关改动
    // 在工具栏下次刷新时生效（与工具栏编辑同口径）。
    private var toolbarHiddenCached: Boolean = false

    private fun refreshToolbarHidden() {
        val hidden = runCatching {
            ToolbarUserStore.load(File(context.filesDir, ToolbarUserStore.FILE_NAME)).data.hideToolbar
        }.getOrDefault(false)
        if (hidden != toolbarHiddenCached) {
            toolbarHiddenCached = hidden
            panel.view.visibility = if (hidden) View.GONE else View.VISIBLE
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
        val bPad = dpToPx(effectiveBottomInsetDp())
        // 隐藏时工具栏条高为 0：条区不占高，键盘区上移；覆盖层（剪贴板／
        // 菜单等）不受影响（各自仍按内容高布局）。
        val barH = if (toolbarHiddenCached) 0 else (PANEL_HEIGHT_DP * density).roundToInt()
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
        val barH = if (toolbarHiddenCached) 0 else (PANEL_HEIGHT_DP * resources.displayMetrics.density).roundToInt()
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

        // 候选栏行策略（2026-10-29 批量第 4 项）：展开网格按行策略定高，
        // 内容不足且自动收缩时缩到内容高度，键盘区其余部分照常显示
        val candidateGrid = panel.candidateGrid
        val gridH = CandidateLinePolicy.resolveGridHeight(
            candidateGrid.contentRowCount(), candidateGrid.lineMax,
            candidateGrid.lineLimitEnabled, candidateGrid.lineAutoShrink,
            candidateGrid.naturalRowHeightPx(), cHeight,
        )
        candidateGrid.layout(hPad, y0, hPad + contentW, y0 + gridH)
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
     * 剪贴板文本输入态（搜索/条目编辑）的结果列表区高度（拉高
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
        videoController.onInputStarted()
    }

    /** 输入结束（服务 finishInputView 转发）：视频停解码、静态背景接管。 */
    fun onFinishInputView() {
        videoController.onInputFinished()
    }

    // 设置变化触发的配色刷新走防抖合并（清点 Q4 起两条通道并成一条带
    // force 标志的调度，语义两路不变）：主题类设置常成串变更（主题包
    // 一次写多项、跟随系统切换连带多 key），每次都全量重建键盘纯浪费；
    // 短窗口内只执行最后一次。高度类的即时 requestLayout 分流不在此列。
    // 普通路（force=false）到点后仍要过变化门控：防抖窗口（200ms）盖
    // 不住跨度更长的成串写入，且半径/间距类设置根本不改配色——解析
    // 结果与缓存一致时整轮跳过，不再无条件全量重建。
    // 显示开关（按键描边/展开边框/候选边框/序号/注释）不进配色解析，
    // 走普通路会被「解析结果与缓存一致」的门控整份挡下、改了永不生效
    // （K1 回归）；它们走 force 路直达 refreshColors()——其内的键盘
    // 重建与面板显示设置失效会让各视图重新读取这些开关。窗口内两路
    // 混合调度时 force 取或：任一路要求直达，本轮即直达。
    private var pendingRefreshForce = false

    private val pendingRefresh = Runnable {
        val force = pendingRefreshForce
        pendingRefreshForce = false
        if (force) refreshColors() else refreshColorsIfChanged()
    }

    private fun scheduleRefresh(force: Boolean) {
        pendingRefreshForce = pendingRefreshForce || force
        removeCallbacks(pendingRefresh)
        postDelayed(pendingRefresh, REFRESH_COLORS_DEBOUNCE_MS)
    }

    private fun scheduleRefreshColors() = scheduleRefresh(force = false)

    private fun scheduleRefreshDisplaySettings() = scheduleRefresh(force = true)

    /**
     * 上次全量刷新时记录的方向（2026-10-27 第一组旋转加固）：
     * 纯方向变化不改变配色解析结果，旧 refreshColorsIfChanged
     * 只比配色会把旋转漏掉——自创布局的横屏结构（横屏专属
     * 绑定 / 横屏权重变体）只在键盘重建时选取，旧键盘会一
     * 直沿用竖屏结构。本字段让方向变化本身成为重建触发条件。
     */
    private var lastOrientation: Int = resources.configuration.orientation

    fun refreshColors() {
        // 全量刷新会重建键盘，主题切换又常与系统界面变化（深浅色连带状态
        // 栏/导航栏外观、边距）同时发生：全高与底部边距同属「窗口几何」
        // 缓存，一并失效待下次查询重算（边距另有 insets 监听回填、0 为
        // 未缓存哨兵值，重算无副作用）。失效点收在本函数内，两个入口
        // （refreshColorsIfChanged 的变化路径、防抖调度的设置项变更路径）
        // 口径一致，不再各挂一份。
        cachedFullScreenHeight = -1
        cachedBottomInset = 0
        lastOrientation = resources.configuration.orientation
        panel.view.setExpanded(false)
        cachedColors = KeyboardColors.resolve(context)
        applyWindowBackground()
        syncVideoBackground()
        applyWindowCorners()
        panel.refreshTheme()
        refreshToolbarHidden()
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

    /**
     * 窗口背景（皮肤 v3 详设 §2.7；背景绘制点即本类，无独立背景层）：
     * 有已解码的预处理背景位图时以位图为窗口背景（位图内已含
     * 模糊与色罩合成），否则回退快照/主题的纯色背景（八.3 不空白）。
     */
    /**
     * 视频层与 GIF 层互斥同步（2026-10-29 外观轮）：
     * 视频在上时停 GIF（省解码）；视频不在时 GIF 按自身帧率跑。
     */
    private fun syncVideoBackground() {
        val videoActive = videoController.sync(
            SkinSnapshotProvider.peek(),
            VideoPrefs.isEnabled(context),
        )
        if (videoActive) {
            gifDrawable?.stop()
        } else {
            gifDrawable?.start()
        }
    }

    private fun applyWindowBackground() {
        // 动画 GIF（SDK≥28）：解原文件成 AnimatedImageDrawable 按
        // 自身帧率重绘；模糊／调节不作用动图帧（见控制器注释）；
        // 解码失败回落制备位图（原有口径）；28 以下静态首帧。
        gifDrawable?.stop()
        gifDrawable = null
        val bg = SkinSnapshotProvider.peek()?.background
        if (bg != null && bg.backgroundAssetExt == "gif" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            val raw = SkinLibrary.layout.assetFiles(bg.assetSha256)
                .firstOrNull { it.isFile }
            val animated = raw?.let {
                runCatching {
                    val source = android.graphics.ImageDecoder.createSource(it)
                    android.graphics.ImageDecoder.decodeDrawable(source)
                        as? android.graphics.drawable.AnimatedImageDrawable
                }.getOrNull()
            }
            if (animated != null) {
                gifDrawable = animated
                background = animated
                // 是否起播由 syncVideoBackground 定（视频在上时停 GIF）。
                return
            }
        }
        val bitmap = SkinSnapshotProvider.currentBackgroundBitmap()
        if (bitmap != null && !bitmap.isRecycled) {
            val base = android.graphics.drawable.BitmapDrawable(resources, bitmap)
            // 磨砂叠加层：位图背景上再盖一层半透明遮罩（深色下黑、浅色下白），
            // 强度为 0 或未设时不盖；动图背景不走此路（与模糊同口径）。
            val overlay = GlobalStyleResolver.effectiveFrostOverlay(
                GlobalStylePrefs.get(context),
            )
            background = if (overlay != null) {
                val scrimColor = if (SkinColorMode.isDark(context)) {
                    Color.BLACK
                } else {
                    Color.WHITE
                }
                val scrim = ColorDrawable(scrimColor).apply {
                    alpha = (overlay * 255).roundToInt().coerceIn(0, 255)
                }
                LayerDrawable(arrayOf(base, scrim))
            } else {
                base
            }
        } else {
            background = null
            setBackgroundColor(cachedColors.background)
        }
    }

    /** 底部安全间距生效值：皮肤 presence 指定时取皮肤值，否则全局偏好（定稿四.3）。 */
    private fun effectiveBottomInsetDp(): Int {
        val snapshot = SkinSnapshotProvider.peek()
        if (snapshot != null && GeometryField.BOTTOM_INSET in snapshot.geometryPresence) {
            return snapshot.effectiveGeometry.getValue(GeometryField.BOTTOM_INSET).roundToInt()
        }
        return KeyboardManager.Keyboard.Padding.getBottomDp(context)
    }

    /**
     * 窗口是否真在绘制：键盘隐藏时本视图仍 attached 在 IME 窗口上，
     * 只有 isShown 且窗口可见性为 VISIBLE 才说明屏幕上看得见、
     * onDraw 会发生（皮肤应用事务的选路判据，见 SkinRuntime）。
     */
    override fun isVisibleNow(): Boolean =
        isShown && windowVisibility == View.VISIBLE

    /** 皮肤应用事务的窗口接口：刷新一次，刷新后的首帧回调一次（详设 §2.4）。 */
    override fun refreshSkinTheme(onFirstDraw: (() -> Unit)?) {
        post {
            if (onFirstDraw != null) {
                lateinit var listener: android.view.ViewTreeObserver.OnDrawListener
                listener = object : android.view.ViewTreeObserver.OnDrawListener {
                    // Android 禁止在 onDraw 回调内注销 OnDrawListener
                    // （抛 IllegalStateException）：旧代码在此自注销，
                    // 此前因窗口不绘制从未触发；延迟验证让首帧真正
                    // 到达后立即整进程闪退（真机实证 16:28:58），
                    // 随后启动对账把未验证的 pending 回滚，表现为
                    // 「应用不成功」。修复：注销推迟到下一消息循环，
                    // fired 保证注销前即使再绘制也不重复回调。
                    var fired = false

                    override fun onDraw() {
                        if (fired) return
                        fired = true
                        post {
                            runCatching {
                                viewTreeObserver.removeOnDrawListener(listener)
                            }
                        }
                        onFirstDraw()
                    }
                }
                viewTreeObserver.addOnDrawListener(listener)
            }
            refreshColors()
        }
    }

    // 仅当解析出的配色与当前缓存不一致时才全量刷新（主题/跟随系统深浅变化等场景）。
    // 2026-10-27 第一组旋转加固：方向变化同样触发全量刷新——
    // 配色相等不代表布局相等：自创布局横屏结构、键盘高度百分比
    // （竖/横屏分别取）都随方向变，只比配色会漏重建。其他配置
    // 变化（字体缩放/区域等）仍走旧口径：配色不变即不重建。
    // 服务侧 onConfigurationChanged 与本视图 onConfigurationChanged
    // 可能各调一次：首调在 refreshColors() 内更新 lastOrientation，
    // 次调方向已一致、配色已一致，自然 no-op，不重复重建。
    fun refreshColorsIfChanged() {
        val resolved = KeyboardColors.resolve(context)
        val orientationChanged = resources.configuration.orientation != lastOrientation
        if (resolved != cachedColors || orientationChanged) {
            // 几何缓存的失效在 refreshColors() 内统一做，这里只管门控
            refreshColors()
        }
    }

    fun refreshLayout() = requestLayout()

    private var currentKeyboard: IKeyboard? = null

    /** G1 动态文字运行时跟随：最新候选首项与组字串（无值 = null，刷新时回落静态文本）。 */
    private var lastCandidateFirst: String? = null
    private var lastComposing: String? = null

    /** 首候选注释（重点候选 8-8；空注释不推送，键回落静态文本）。 */
    private var lastCandidateComment: String? = null

    /**
     * 选项标签供给钩子（重点候选 8-8）：调用方按当前方案 switches
     * ＋引擎选项值算出（OptionLabelResolver），键为动态键全名
     * （如 `optionLabel$ascii_mode`）；未设置时选项标签键回落
     * 静态 label。本窗口不直接读引擎（避免同步阻塞与异步竞态）。
     */
    var optionLabelValues: Map<String, String> = emptyMap()

    /**
     * 接线轮 R1/R2/R3：通知与条件运行态喂给（去重标记——同值
     * 重复事件不重复喂入；预编辑文本只作变化比较，绝不记日志）。
     */
    private var lastFedSchemaId: String? = null
    private var lastFedReturnKeyType: String? = null
    private var lastFedPreedit: String? = null
    private var lastKnownOptionValues: Map<String, Boolean> = emptyMap()
    private var optionRefreshSeq = 0

    /**
     * 接线轮 R1：锁定／条件变化后的键盘刷新（post 投递，不在
     * 事件分发中途同步重建）。键盘重建让装配器取最新锁定与门
     * 控；面板刷新让工具栏钮取最新锁定动作。
     */
    fun refreshNotificationState() {
        keyboardStateManager.rebuild()
        panel.refreshTheme()
        refreshToolbarHidden()
    }

    /** 接线轮 R1：方案／回车类型变化时喂给通知状态（变化才喂）。 */
    private fun feedSchemeAndReturnKeyEvents() {
        val schemaId = keyboardStateManager.getCurrentSchema()?.id
        if (schemaId != lastFedSchemaId) {
            lastFedSchemaId = schemaId
            if (schemaId != null) {
                SkinRuntimeState.onEvent(
                    NotificationEvent(NotificationSource.SCHEME, schemaId, null),
                )
            }
            refreshOptionStateAsync()
        }
        val returnType = keyboardStateManager.currentReturnKeyType()
        if (returnType != lastFedReturnKeyType) {
            lastFedReturnKeyType = returnType
            SkinRuntimeState.onEvent(
                NotificationEvent(NotificationSource.RETURN_KEY_TYPE, returnType, null),
            )
        }
    }

    /**
     * 接线轮 R2/R3：异步刷新选项态（方案变化／偏好开关／部署
     * 后调用）。经引擎作业队列一次读齐当前方案开关表与条件
     * 选项值；过期结果（中途又切方案）直接丢弃。
     *
     * - 选项值差分喂给 rime_option 通知（值取 "true"/"false"）；
     * - 条件现值写入运行态（rime$* 布尔＋符号锁定态：符号
     *   键盘为活动键盘时为真）；
     * - 选项标签经 OptionLabelResolver 算出填入 optionLabelValues
     *   并原地推送（无值／多选项开关／态标签不足回落静态 label）。
     * 读值失败（引擎未就绪等）静默跳过，不崩不记值。
     */
    fun refreshOptionStateAsync() {
        val seq = ++optionRefreshSeq
        val schemaId = keyboardStateManager.getCurrentSchema()?.id ?: return
        val snapshot = SkinSnapshotProvider.peek()
        val conditionOptionNames = snapshot?.conditionStyles
            ?.mapNotNull { entry ->
                entry.conditionKey.removePrefix(ConditionStyleParser.RIME_OPTION_PREFIX)
                    .takeIf { entry.conditionKey.startsWith(ConditionStyleParser.RIME_OPTION_PREFIX) }
            }.orEmpty()
        appScope.launch {
            val engine = EngineFactory.current() as? IRimeJob ?: return@launch
            val rimeSchema = engine.awaitJob<RimeSchema?>(null) { currentSchema() }
                ?: return@launch
            if (rimeSchema.schemaId != schemaId) return@launch
            val switches = rimeSchema.switches.map { sw ->
                OptionLabelResolver.Switch(sw.name, sw.options, sw.states)
            }
            val wanted = (switches.flatMap { it.optionNames } + conditionOptionNames).toSet()
            val values = if (wanted.isEmpty()) {
                emptyMap()
            } else {
                engine.awaitJob(emptyMap<String, Boolean>()) {
                    wanted.associateWith { getRuntimeOption(it) }
                }
            }
            if (seq != optionRefreshSeq) return@launch
            for ((name, value) in values) {
                if (lastKnownOptionValues[name] != value) {
                    SkinRuntimeState.onEvent(
                        NotificationEvent(
                            NotificationSource.RIME_OPTION, name, value.toString(),
                        ),
                    )
                }
            }
            lastKnownOptionValues = values
            // 接线轮 R2：条件现值经纯映射写入运行态（门控集与
            // 激活集由装配器构建期取用，见 KeyStyleResolver）。
            SkinRuntimeState.updateConditionCurrent(
                OptionStateMapper.mapConditionCurrent(
                    conditionOptionNames, values,
                    currentKeyboard?.name() == SymbolKeyboard.NAME,
                ),
            )
            // 接线轮 R3：选项标签经纯映射填入钩子并原地推送
            // （无值项缺席，键回落静态 label）。
            optionLabelValues = OptionStateMapper.mapOptionLabels(switches, values)
            pushDynamicTexts()
        }
    }

    /** 把最新动态值推给当前键盘原地刷新（无动态键时调用无影响）。 */
    private fun pushDynamicTexts() {
        val values = buildMap<String, String> {
            lastCandidateFirst?.let { put(DynamicTextSource.CANDIDATE_FIRST.jsonName, it) }
            lastComposing?.let { put(DynamicTextSource.COMPOSING.jsonName, it) }
            keyboardStateManager.getCurrentSchema()?.name?.let {
                put(DynamicTextSource.SCHEME_NAME.jsonName, it)
            }
            put(
                DynamicTextSource.RETURN_KEY_TYPE.jsonName,
                keyboardStateManager.currentReturnKeyType(),
            )
            lastCandidateComment?.let { put(DynamicTextSource.CANDIDATE_COMMENT.jsonName, it) }
            optionLabelValues.forEach { (key, value) -> put(key, value) }
        }
        currentKeyboard?.updateDynamicTexts(values)
    }

    fun setCandidates(list: List<EngineMessage.Candidate>, hasMore: Boolean = false) {
        panel.setCandidates(list, hasMore)
        lastCandidateFirst = list.firstOrNull()?.text
        lastCandidateComment = list.firstOrNull()?.comment?.takeIf { it.isNotEmpty() }
        pushDynamicTexts()
        // 行内联想：首选到后刷新气泡幽灵文本；气泡正显示时重测宽重显
        refreshInlineSuggestion()
        if (preeditPinner.view.preeditItems.isNotEmpty()) {
            val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
            preeditPinner.show(context, wm, panel.view, hPad)
        }
    }

    fun appendCandidates(list: List<EngineMessage.Candidate>, total: Int) =
        panel.appendCandidates(list, total)

    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        panel.onPossibleCandidatePinYin(pinyins)
        (currentKeyboard as? ISidePanelKeyboard)?.onPossibleCandidatePinYin(pinyins)
    }

    /** 行内联想（2026-10-29 批量第 5 项）：开关开且组字非空时，
     *  首选候选（与组字拼接文本不同时才显示，避免重复显示同一文本）
     *  以淡色缀在组字气泡后；仅预览，不改输入、不改上屏。开关即时读偏好，
     *  设置页切换后下一次候选/组字更新即生效，无需重建键盘。 */
    private fun refreshInlineSuggestion() {
        val items = preeditPinner.view.preeditItems
        val ghost = if (CandidateManager.isInlineSuggestionEnabled(context) && items.isNotEmpty()) {
            val joined = items.joinToString("") { it.text }
            lastCandidateFirst?.takeIf { it.isNotEmpty() && it != joined }
        } else {
            null
        }
        preeditPinner.setInlineSuggestion(ghost)
    }

    fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>) {
        preeditPinner.updateDynamicPreedit(items)
        // 行内联想：组字变化时先算幽灵文本，再按含幽灵文本的宽度显隐
        refreshInlineSuggestion()
        if (items.isEmpty()) {
            preeditPinner.hide(wm)
        } else {
            val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
            preeditPinner.show(context, wm, panel.view, hPad)
        }
        // 接线轮 R1：预编辑变化事件（只比较变化，不记文本内容；
        // 通知解析已禁 matchKey／matchValue，事件恒双空）。
        val joined = items.joinToString("") { it.text }
        if (joined != lastFedPreedit) {
            lastFedPreedit = joined
            SkinRuntimeState.onEvent(NotificationEvent(NotificationSource.PREEDIT, null, null))
        }
        lastComposing = items.joinToString("") { it.text }.takeIf { it.isNotEmpty() }
        pushDynamicTexts()
    }

    private fun contentHeight(): Int {
        // 高度百分比优先级（2026-10-28 布局专用高度加入）：
        // 1. 当前布局的布局专用 heightPercent（内置覆盖/自创布局
        //    文件顶层，50–200；横屏取横屏绑定，无则取竖屏绑定）；
        // 2. 皮肤 geometry heightPercent（竖横屏同值覆盖 App 设置）；
        // 3. 未指定时按方向读原有竖/横屏设置。peek() 不触发快照
        // 加载（应用事务的强制 refreshColors 已先装好快照并重建
        // 键盘，重测时快照必已就位）；快照缺失 = 无皮肤生效，
        // 走原路径。当前键盘为空（尚未显示）时布局项取不到，
        // 安全回退到皮肤级/App 设置，显示后重测即生效。
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val snapshot = SkinSnapshotProvider.peek()
        val currentName = currentKeyboard?.name()
        val layoutPercent: Int? = when {
            snapshot == null || currentName == null -> null
            currentName.startsWith(CustomLayoutRuntime.PREFIX) -> {
                val resolved = if (isLandscape) {
                    snapshot.resolvedLayoutLandscape ?: snapshot.resolvedLayout
                } else {
                    snapshot.resolvedLayout
                }
                resolved?.heightPercent?.roundToInt()
            }
            else -> snapshot.builtinLayouts[currentName]?.heightPercent?.roundToInt()
        }
        val skinPercent = snapshot
            ?.keyboardGeometry
            ?.takeIf { KeyboardGeometryField.HEIGHT_PERCENT in it.presence }
            ?.values
            ?.getValue(KeyboardGeometryField.HEIGHT_PERCENT)
            ?.roundToInt()
        val percent = layoutPercent ?: skinPercent ?: if (isLandscape) {
            KeyboardManager.Keyboard.getHeightPercentLandscape(context)
        } else {
            KeyboardManager.Keyboard.getHeightPercent(context)
        }
        val fullHeight = fullScreenHeight()
        return (fullHeight * percent / 100).coerceAtLeast(minimumHeight)
    }

    /** 窗口高度缓存：onMeasure/onLayout 一轮内会被多处反复调用，每次都查
     * WindowManager 是纯浪费；同一窗口生命周期内结果不变，缓存复用。
     * -1 表示未缓存；配置变化、窗口销毁、配色实际变化走全量刷新时失效。
     * 注意：这里是「当前窗口」高度（分屏下即分屏窗口高），不是整机最大
     * 高度——键盘高度百分比以此为基准，分屏下才不会被放大。函数名
     * fullScreenHeight 为历史名称，仅保留以减少调用点改动。 */
    private var cachedFullScreenHeight: Int = -1

    private fun fullScreenHeight(): Int {
        cachedFullScreenHeight.takeIf { it > 0 }?.let { return it }
        val height = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(
                dm
            )
            dm.heightPixels
        }
        cachedFullScreenHeight = height
        return height
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/分屏等配置变化后全高可能已变，缓存失效待下次查询重算；
        // 底部边距同属窗口几何缓存一并失效（0 为未缓存哨兵，resolve 时
        // 重算，insets 监听也会回填）。注意与 refreshColors() 内的清零
        // 不是重复：那是配色全量刷新路径（且服务侧只在配色实际变化时
        // 才走），无配色变化的纯旋转/分屏此前走不到那里，旧边距被
        // resolveBottomInset() 的 cached > 0 短路直接复用。
        cachedFullScreenHeight = -1
        cachedBottomInset = 0
        // 2026-10-27 第一组旋转加固：视图侧配置变化也走同一门控
        // （方向变化 → 全量重建，取新方向的布局与高度百分比；
        // 服务侧已先调过时本调自然 no-op）。视图是否收到本回调
        // 取决于窗口分发，不可靠——服务侧 onConfigurationChanged
        // 的 refreshColorsIfChanged 仍是主路径，本调只是补位。
        refreshColorsIfChanged()
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
                wm.currentWindowMetrics.windowInsets, this
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
        // 窗口隐藏 = 本轮输入结束：动态值清零，动态键回落静态文本。
        lastCandidateFirst = null
        lastComposing = null
        pushDynamicTexts()
        if (cloudVoiceActive) {
            stopCloudVoice(discard = true)
        } else if (isVoiceRecording) {
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

    // ---- 云端语音路由（严格可选；离线永远默认） ----
    /**
     * 云端路由：返回 true 表示本次按键已被云端侧消费（已启动云端
     * 会话，或已弹不可用提示），调用方不再走离线路径；返回 false
     * 表示未选云端，调用方原样走离线路径。
     *
     * 口径：只开启未选插件＝选择未完成，静默回离线；已选插件但
     * 不可用（缺失／密钥未填／超时停用／无网络）＝选择已破损，
     * 弹提示＋回落入口，不进录音态（绝不静默录离线）。
     */
    private fun tryStartCloudVoice(): Boolean {
        val prefs = SharedPrefsStore(context, PluginStore.PREFS_NAME)
        val store = PluginStore(context.filesDir, prefs)
        // 内置插件路由前种子安装（缺失即补，失败不阻塞路由判定）。
        runCatching {
            BuiltinPlugins.ensure(store) { name ->
                context.assets.open(name).use { it.readBytes() }
            }
        }
        if (!store.isOptIn()) return false
        val preferred = store.getPreferred() ?: return false
        val installed = store.get(preferred)
        val secrets = KeystoreSecretStore(prefs)
        val secretsMissing = installed?.meta?.authHeaders
            ?.any { !secrets.has(preferred, it.field) } ?: true
        val gate = CloudVoiceGate.decide(
            optIn = true,
            preferredId = preferred,
            pluginInstalled = installed != null,
            budgetDisabled = store.isBudgetDisabled(preferred),
            secretsMissing = secretsMissing,
            networkAvailable = CloudVoiceGate.networkAvailable(context),
        )
        if (gate != CloudVoiceGateResult.Cloud) {
            showCloudUnavailablePrompt()
            return true
        }
        val plugin = installed ?: return false
        startCloudSession(store, plugin, secrets)
        return true
    }

    private fun startCloudSession(
        store: PluginStore,
        installed: PluginStore.InstalledPlugin,
        secrets: KeystoreSecretStore,
    ) {
        val service = context as ImeInputMethodService
        val session = CloudVoiceSession(
            plugin = installed,
            settings = store.getSettings(installed.meta.id),
            secrets = secrets,
            sink = ServiceTextSink(service, service.activeInputConnection()),
            listener = object : CloudVoiceSession.Listener {
                override fun onStarted() {
                    voiceOverlay.bringToFront()
                }

                override fun onAmplitude(amp: Float) {
                    voiceOverlay.updateAmplitude(amp)
                }

                override fun onDone() {
                    cloudVoiceActive = false
                    cloudVoiceSession = null
                    isVoiceRecording = false
                    panel.recording = false
                    if (!voiceOverlay.isLocked) {
                        voiceOverlay.hide()
                    }
                }

                override fun onFailed(reason: CloudSessionFailure) {
                    cloudVoiceActive = false
                    cloudVoiceSession = null
                    isVoiceRecording = false
                    panel.recording = false
                    voiceOverlay.hide()
                    showImeToast(context.getString(R.string.cloud_voice_failed))
                }
            },
            markBudgetDisabled = { store.setBudgetDisabled(installed.meta.id, true) },
            recorderFactory = { onChunk, onAmplitude, onInitFailed, onCaptureFailed, onFocusLost ->
                VoiceRecorderAdapter(
                    context, onChunk, onAmplitude, onInitFailed, onCaptureFailed, onFocusLost,
                )
            },
            networkAvailable = { CloudVoiceGate.networkAvailable(context) },
            displayTransform = { text ->
                if (CandidateManager.isTraditionalChineseEnabled(context)) {
                    TraditionalConverter.toTraditional(text)
                } else {
                    text
                }
            },
        )
        cloudVoiceSession = session
        cloudVoiceActive = true
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
        voiceOverlay.show()
        voiceOverlay.bringToFront()
        session.start()
    }

    /** 云端退出统一口：文本结算在会话内，界面收尾在这里。 */
    private fun stopCloudVoice(discard: Boolean) {
        cloudVoiceActive = false
        isVoiceRecording = false
        panel.recording = false
        cloudVoiceSession?.stop(discard)
        cloudVoiceSession = null
        voiceOverlay.hide()
    }

    private fun showCloudUnavailablePrompt() {
        isVoiceRecording = false
        panel.recording = false
        voiceOverlay.hide()
        panel.confirmOverlay.confirm(
            message = context.getString(R.string.cloud_voice_unavailable_message),
            onConfirm = {
                val intent = Intent(
                    context, com.jobeen.ime.ui.VoiceSettingsActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        // 浮层已在启动瞬间即显示（见 startVoiceInput：音频先行后录音
        // 本地立即开始，冷启动备引擎期间不再留「按了没反应」的空白）；
        // 就绪回调只负责确保浮层在最前。动画本体一律不动
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
        // 失败必须可见（旧实现静默收起，用户无从知道发生了什么）：
        // 客户端留了具体文案用文案，否则用通用文案
        showImeToast(SherpaSpeechClient.consumeStartFailureHint() ?: "语音启动失败，请重试")
    }
    private val speechModelMissingCallback: () -> Unit =
        { showModelDownloadPrompt() }

    private fun registerSpeechCallbacks() {
        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = speechRecordingStartedCallback
        SpeechUiBridge.onAmplitude = speechAmplitudeCallback
        SpeechUiBridge.onDone = speechDoneCallback
        SpeechUiBridge.onFailed = speechFailedCallback
        SpeechUiBridge.onModelMissing = speechModelMissingCallback
    }

    private fun startVoiceInput(pointerId: Int = -1) {
        if (isVoiceRecording) return
        // 密码框禁用语音输入
        if (passwordField) return
        // 语音总开关关闭时不启动（空格长按与语音键同经此口）。
        if (!VoiceInputSwitchStore.shared(
                File(context.filesDir, VoiceInputSwitchStore.FILE_NAME),
            ).enabled
        ) return
        if (!ensureRecordAudioPermission()) return
        // 云端已选且可用 → 走云端；云端已选但不可用 → 提示＋回落入口，
        // 不进录音态；未选云端 → 离线路径原样。
        if (tryStartCloudVoice()) {
            voicePointerId = pointerId
            return
        }
        // 模型缺失时直接弹下载提示，不进入录音态：
        // 避免工具栏闪一下变暗、语音动画又永远不出现。
        if (!SherpaSpeechClient.isModelReady(context)) {
            showModelDownloadPrompt()
            return
        }
        // 启动成功才记指针：前面的失败路径没进录音态，不留过期 id；
        // -1（无指针来源）保持父容器的回退语义——任意 UP 可结束
        voicePointerId = pointerId
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
        // 启动即显示浮层：录音已在本地开始，引擎冷启动期间浮层先
        // 在（动画本体不变、不加任何文字），就绪后由回调带到最前
        voiceOverlay.show()
        voiceOverlay.bringToFront()
    }

    private fun stopVoiceInput(discard: Boolean = false) {
        // 云端会话经同一出口收尾（文本结算在会话内，界面收尾在这里）。
        if (cloudVoiceActive) {
            stopCloudVoice(discard = discard)
            return
        }
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
        // 语音总开关关闭时不启动（工具栏语音键与自定义语音按钮同经此口）。
        if (!VoiceInputSwitchStore.shared(
                File(context.filesDir, VoiceInputSwitchStore.FILE_NAME),
            ).enabled
        ) return
        if (!ensureRecordAudioPermission()) return
        // 云端路由与按住录音同一口径（见 startVoiceInput）。
        if (tryStartCloudVoice()) return
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
        // 同 startVoiceInput：锁定录音同样启动即显示浮层
        voiceOverlay.show()
        voiceOverlay.bringToFront()
    }

    fun onInputChanged(
        info: EditorInfo?, text: String, virtualInputConnection: Boolean = false,
    ): Any {
        // 空探针 discard 只在本会话已出过 partial 后才成立：未出首个
        // partial 前探到光标前后为空是常态（空编辑器正是最常见的
        // 语音场景），宿主 App 在录音开始阶段发一次选区事件就会
        // 误杀刚起的会话（审计 W1）；已出 partial 后探空才视作
        // 编辑器内容被外部清空
        if (cloudVoiceActive && text.isEmpty() && cloudVoiceSession?.hasPartial == true) {
            stopCloudVoice(discard = true)
        } else if (isVoiceRecording && text.isEmpty() && SherpaSpeechClient.sessionHasPartial) {
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
}
