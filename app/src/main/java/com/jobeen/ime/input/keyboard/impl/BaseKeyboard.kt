package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.constraintlayout.widget.ConstraintLayout
import com.jobeen.ime.R
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.input.keyboard.key.AltTextKeyView
import com.jobeen.ime.input.keyboard.key.CustomGestureView
import com.jobeen.ime.input.keyboard.key.ImageKeyView
import com.jobeen.ime.input.keyboard.key.ImageTextKeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyPreviewPopup
import com.jobeen.ime.input.keyboard.key.HintGridPopup
import com.jobeen.ime.input.keyboard.key.KeyboardPopup
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardRippleView
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager
import com.jobeen.ime.input.keyboard.key.SidePanelKeyView
import com.jobeen.ime.input.keyboard.key.SwipeThresholds
import com.jobeen.ime.input.keyboard.key.TextKeyView
import com.jobeen.ime.skin.model.HintGridSelection
import com.jobeen.ime.skin.model.StyleStatePriority
import com.jobeen.ime.skin.runtime.HintSymbolsResolver
import com.jobeen.ime.skin.runtime.KeyOverrideApplier
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.symbol.SymbolGridResolver
import com.jobeen.ime.skin.symbol.SymbolGridSpec
import kotlin.math.abs
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

// 长按弹窗选中条目的高亮蓝（新定值；全仓无现行选中蓝可复用）
private const val LONG_PRESS_HIGHLIGHT_BLUE: Int = 0xFF0A84FF.toInt()

abstract class BaseKeyboard(
    context: Context,
    protected val colors: KeyboardColors.ColorScheme,
    private val keyLayout: List<List<KeyDef>>,
) : ConstraintLayout(context), IKeyboard {
    override var keyActionListener: KeyActionListener? = null
    private val previewPopup = KeyPreviewPopup(context)
    private val keyboardPopup = KeyboardPopup(context)
    private val hintGridPopup = HintGridPopup(context)
    // 复用坐标数组，避免每次按键分配
    private val keyLoc = IntArray(2)
    private val boardLoc = IntArray(2)
    protected val keyRows: List<ConstraintLayout>
    val rippleView: KeyboardRippleView

    private var spanPanelViews: List<KeyView> = emptyList()
    private var spaceKeyView: TextKeyView? = null
    /** 空格键的皮肤文案（建键时记下的布局 label；空串 = 皮肤未定义）。 */
    private var spaceKeySkinText: String? = null
    private var returnKeyView: ImageKeyView? = null

    companion object {
        /**
         * 空格文案决议（空格标跟皮肤走）：皮肤空格文案非空时用皮肤文案；
         * 皮肤未定义空格文案（空串/缺失）时回落传入的方案名（现行行为）。
         * 纯函数，可单测；视图刷新本身依赖 Android View，走真机验收。
         */
        fun resolveSpaceText(skinText: String?, fallback: String): String =
            if (!skinText.isNullOrEmpty()) skinText else fallback
    }

    /**
     * 布局键→视图注册表：组装期建成（行内键＋跨行键全量），不重建布局
     * 即可按 KeyDef 单键失效。键以布局的 KeyDef 本体标识（同一键盘
     * 实例内稳定；KeyDef 为 open class、按实例相等）。
     */
    private val keyViewsByDef: Map<KeyDef, KeyView>
    private var returnKeyIcon: Int = 0
    /** G1 动态文字运行时跟随：可原地刷新文本的动态键（setter + 来源 + 静态回退）。 */
    private data class DynamicView(
        val setText: (String) -> Unit,
        val source: String,
        val fallback: String,
    )

    private val dynamicViews = mutableListOf<DynamicView>()

    /** 前景层实时变量刷新器（审计 K 余项：与主显动态键同批刷新）。 */
    private val foregroundDynamicViews = mutableListOf<KeyView.ForegroundDynamic>()

    /** 文字回车键刷新器（自定义布局的文本回车键，建键时按行为记下）。 */
    private val enterTextSetters = mutableListOf<(String) -> Unit>()

    /** 回车键视图（文本回车键，建键时按行为记下；底色分色用）。 */
    private val enterKeyViews = mutableListOf<KeyView>()

    override fun updateSpaceKeyText(text: String) {
        // 参数 text 是回退值（方案名）：皮肤有空格文案时不用它，
        // 只有皮肤未定义空格文案时才写它（现行行为）。
        spaceKeyView?.updateText(resolveSpaceText(spaceKeySkinText, text))
    }

    /**
     * 单键失效：只重绘该键视图，不触整窗 requestLayout。
     * update 可选地先改该键的文本/图标（如 TextKeyView.updateText），
     * 再由本方法重绘；update 本身已带 invalidate 的键无需再调。
     * 未知 def 直接返回。函数类型参数在最后，trailing-lambda 可直挂。
     */
    fun invalidateKey(def: KeyDef, update: (KeyView) -> Unit = {}) {
        keyViewsByDef[def]?.let { view ->
            update(view)
            view.invalidate()
        }
    }

    override fun updateDynamicTexts(values: Map<String, String>) {
        for (dynamic in dynamicViews) {
            dynamic.setText(
                KeyOverrideApplier.resolveDynamicDisplayText(
                    dynamic.fallback, dynamic.source, values,
                ),
            )
        }
        // 前景层实时变量与主显同一供给、同一线程、同一批刷新。
        for (dynamic in foregroundDynamicViews) {
            dynamic.setText(
                KeyOverrideApplier.resolveForegroundLiveText(dynamic.template, values),
            )
        }
    }

    private fun textSetter(view: KeyView): ((String) -> Unit)? = when (view) {
        is TextKeyView -> { text -> view.updateText(text) }
        is AltTextKeyView -> { text -> view.updateText(text) }
        is ImageTextKeyView -> { text -> view.updateText(text) }
        else -> null
    }

    protected open fun updateSidePanel(items: List<KeyDef>) {
        spanPanelViews.filterIsInstance<SidePanelKeyView>().firstOrNull()?.updateItems(items)
    }

    protected open fun resetSidePanelPosition() {
        spanPanelViews.filterIsInstance<SidePanelKeyView>().firstOrNull()?.resetPosition()
    }

    fun setSidePanelItemListener(listener: (KeyboardAction) -> Unit) {
        spanPanelViews.filterIsInstance<SidePanelKeyView>().firstOrNull()
            ?.setOnItemActionListener(listener)
    }

    init {
        data class SpanDef(val def: KeyDef, val startRow: Int, val endRow: Int, val alignEnd: Boolean)

        val spanDefs = mutableListOf<SpanDef>()
        for (ri in keyLayout.indices) {
            for (def in keyLayout[ri]) {
                if (def.appearance.rowSpan > 1) {
                    spanDefs.add(
                        SpanDef(
                            def,
                            ri,
                            (ri + def.appearance.rowSpan - 1).coerceAtMost(keyLayout.size - 1),
                            def.appearance.alignSpanEnd,
                        )
                    )
                }
            }
        }
        val spanViews = spanDefs.map { createKeyView(it.def).also { v -> v.id = generateViewId() } }
        spanPanelViews = spanViews

        val registry = mutableMapOf<KeyDef, KeyView>()
        spanDefs.forEachIndexed { i, sd -> registry[sd.def] = spanViews[i] }

        keyRows = keyLayout.mapIndexed { rowIndex, row ->
            val parts =
                row.filterNot { it.appearance.rowSpan > 1 }
            val keyViews = parts.map { def -> createKeyView(def).also { registry[def] = it } }
            val leftSpanWidth = spanDefs
                .filter { rowIndex in it.startRow..it.endRow && !it.alignEnd }
                .maxOfOrNull { it.def.appearance.percentWidth } ?: 0f
            val rightSpanWidth = spanDefs
                .filter { rowIndex in it.startRow..it.endRow && it.alignEnd }
                .maxOfOrNull { it.def.appearance.percentWidth } ?: 0f
            val occupiedWidth = leftSpanWidth + rightSpanWidth
            val spanScale = if (occupiedWidth > 0f) 1f / (1f - occupiedWidth) else 1f

            constraintLayout {
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
                }
                // 原 expandKeypressArea 分支已删：该开关恒 false、全仓
                // 无写入方，分支永不执行（死代码清理 L7）
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                val leftSpanAtRow =
                    spanDefs.indexOfFirst { index in it.startRow..it.endRow && !it.alignEnd }
                val rightSpanAtRow =
                    spanDefs.indexOfFirst { index in it.startRow..it.endRow && it.alignEnd }
                if (leftSpanAtRow >= 0) {
                    leftToRightOf(spanViews[leftSpanAtRow])
                } else {
                    leftOfParent()
                }
                if (rightSpanAtRow >= 0) {
                    rightToLeftOf(spanViews[rightSpanAtRow])
                } else {
                    rightOfParent()
                }
            })
        }

        for (i in spanDefs.indices) {
            val sd = spanDefs[i]
            add(spanViews[i], lParams {
                topToTop = keyRows[sd.startRow].id
                bottomToBottom = keyRows[sd.endRow].id
                if (sd.alignEnd) rightOfParent() else leftOfParent()
                matchConstraintPercentWidth = sd.def.appearance.percentWidth
            })
        }

        // 注册表收口：行内键在行装配时已记，跨行键在上文已记。
        keyViewsByDef = registry

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
                spaceKeySkinText =
                    (def.appearance as? KeyDef.Appearance.Text)?.displayText
            }
            borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
            // 手势可调口径（2026-10-29 批量第 4 项）：建键时按偏好注入，
            // 与滑动阈值同口径（建键时取值，下次键盘重建生效）
            longPressDelayMs = KeyboardManager.Keyboard.Gesture.getLongPressTimeoutMs(context).toLong()
            repeatIntervalMs = KeyboardManager.Keyboard.Gesture.getRepeatIntervalMs(context).toLong()
            doubleTapTimeoutMs = KeyboardManager.Keyboard.Gesture.getDoubleTapTimeoutMs(context).toLong()
            minSwipeVelocityPxPerSec =
                KeyboardManager.Keyboard.Gesture.getMinSwipeVelocityPxPerSec(context).toFloat()
            // 松开振动参与：按键面结构属性，实际发振由反馈缓存的开关 gating
            releaseFeedbackEnabled = true
            val slideStepChars = KeyboardManager.Keyboard.Gesture.getSlideStepChars(context)
            // 皮肤逐键音效注入（详设 §7.2-3）：解析结果已由覆写
            // 装配器挂在 def 上；出厂 def 两字段恒为默认，注入后
            // 播放路径与 1.1.14 一致
            skinSoundAssetId = def.skinSoundAssetId
            skinSoundMuted = def.skinSoundMuted
            // G1 前景多层叠加：视图建成后挂一次（文本/图标层压主文字之上）
            attachForegroundLayers()
            // 前景层实时变量：含变量的文本层刷新器随视图登记，
            // 与主显动态键同一批由 updateDynamicTexts 刷新。
            this@BaseKeyboard.foregroundDynamicViews += foregroundDynamics
            // 文字回车键跟随（回车文案跟 returnKeyType）：自定义布局
            // 的文本回车键（Press ReturnAction 的文本键）记下刷新器，
            // updateEditorInfo 按编辑框动作改文案；内置图标回车键
            // 另走图标（textSetter 对 ImageKeyView 为空，自然排除）。
            if (def.behaviors.any { b ->
                    b is KeyDef.Behavior.Press && b.action is KeyboardAction.ReturnAction
                }) {
                textSetter(this)?.let { enterTextSetters += it }
                enterKeyViews += this
            }
            // G1 动态文字运行时跟随：记下可刷新文本的动态键
            // （来源 + 静态回退），供 updateDynamicTexts 原地刷新。
            val dynamicSource = def.appearance.skinDynamicText
            if (dynamicSource != null) {
                textSetter(this)?.let { setter ->
                    dynamicViews += DynamicView(
                        setter,
                        dynamicSource,
                        def.appearance.skinDynamicFallback ?: (displayText ?: ""),
                    )
                }
            }
            onPressedChanged = { key ->
                if (key.isPressed) {
                    val isSwitchAction = def.behaviors.any { b ->
                        b is KeyDef.Behavior.Press && (b.action is KeyboardAction.LayoutSwitchAction || b.action is KeyboardAction.RotateSchema || b.action is KeyboardAction.ResumeAction)
                    }
                    if (!isSwitchAction) {
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
                    // 复用类级 boardLoc：与 onPressedChanged 同为触摸线程
                    // 同步使用、取到坐标立即消费，不存在交错改写窗口，
                    // 不再每次侧栏波纹新建 IntArray
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
                        var gridHighlight: Int? = null
                        var gridSpec: SymbolGridSpec? = null
                        var originX = 0f
                        var initialMove = true
                        setOnTouchListener { view, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (keyboardPopup.isShowing()) {
                                        if (initialMove) {
                                            originX = event.x
                                            initialMove = false
                                            // 接线轮 R6：单行格＝弹出项本身（无空位），
                                            // 步距＝itemStep、抖动＝半步，复现旧 roundToInt 手感；
                                            // dy=0（单行忽略纵向漂移）。
                                            val start = keyboardPopup.selectedIndex
                                            gridHighlight = start
                                            gridSpec = SymbolGridSpec.create(
                                                cells = popup.keys.map { keyboardPopup.itemLabel(it) },
                                                columns = popup.keys.size,
                                                anchorIndex = start,
                                                defaultIndex = start,
                                                cellSizePx = keyboardPopup.itemStep,
                                                sensitivity = 1f,
                                                jitterPx = keyboardPopup.itemStep / 2f,
                                            )
                                        }
                                        val spec = gridSpec ?: return@setOnTouchListener true
                                        val h = SymbolGridResolver.highlightFor(
                                            spec, event.x - originX, 0f,
                                        )
                                        if (h != gridHighlight) {
                                            gridHighlight = h
                                            if (h != null) keyboardPopup.selectIndex(h)
                                            else keyboardPopup.clearSelection()
                                            InputFeedbacks.hapticFeedback(view)
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
                            gridHighlight = keyboardPopup.selectedIndex
                            initialMove = true
                            return@setOnLongClickListener true
                        }
                        onTouchUpListener = {
                            if (keyboardPopup.isShowing()) {
                                // 接线轮 R6：出界取消（highlight 为 null）时只关闭不提交；
                                // 回界内恢复后抬手照常提交恢复格。
                                val h = gridHighlight
                                if (h != null) {
                                    popup.keys.getOrNull(h)?.let { onAction(it) }
                                }
                                keyboardPopup.dismiss()
                                gridHighlight = null
                            }
                        }
                    }

                    else -> {}
                }
            }
            // 按住上档标记（审计 E：momentary shift）：循环结束
            // 后再链式接线（见循环后），避免与 popups/语音分支
            // 的单槽位监听互相覆盖。
            var hasMomentaryShift = false
            def.behaviors.forEach { behavior ->
                when (behavior) {
                    is KeyDef.Behavior.Press -> {
                        if (behavior.action is KeyboardAction.MomentaryShiftAction) {
                            // 按住上档不走点击分发：按下/抬手由文末
                            // 链式钩子驱动大写状态（无点击语义）。
                            hasMomentaryShift = true
                        } else {
                            setOnClickListener({
                                // 分发期状态解析（审计 E：preedit >
                                // uppercased > normal）：组合与大写在点击
                                // 瞬间求值，不在装配期定死。
                                onAction(
                                    resolvePressAction(
                                        behavior,
                                        KeyboardStateManager.isComposingNow,
                                        isUppercased,
                                    )
                                )
                            })
                        }
                    }

                    is KeyDef.Behavior.LongPress -> {
                        longPressEnabled = true
                        setOnLongClickListener {
                            val action = behavior.action
                            if (action is KeyboardAction.VoiceInputAction) {
                                // 语音长按穿线：把触发瞬间记下的指针 id 带上，
                                // 父容器从录音开始就认这根手指，不等首次 MOVE
                                onAction(
                                    KeyboardAction.VoiceInputAction(
                                        pointerId = (it as? CustomGestureView)?.longPressPointerId ?: -1
                                    )
                                )
                            } else {
                                onAction(action)
                            }
                            return@setOnLongClickListener true
                        }
                        if (behavior.action is KeyboardAction.VoiceInputAction) {
                            // 语音拖拽需要 swipeEnabled=true，否则 MOVE 在 longPressTriggered
                            // 时会提前 return，打乱手势状态（v1.0.972 删除 SwipeMoveCursor
                            // 后引入的问题）。这里只开 flag，不加光标移动逻辑。
                            swipeEnabled = true
                            // 语音长按的取消改走位移判据（20dp）：默认的
                            // 24px 步进/出界摘除会被拇指沉降漂移误触、
                            // 把长按吃成普通空格（审计 G1）；慢滑移光标
                            // 的大位移仍能自然摘除。只设这一个视图，
                            // 其他键的长按口径不变。
                            longPressCancelSlopPx = dp(20f)
                            onTouchMoveListener = { pointerId, rawX, rawY ->
                                onAction(KeyboardAction.VoiceDragPosition(pointerId, rawX, rawY))
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
                        // G1 左右滑：仅当左右动作至少其一存在时开 X 阈值，
                        // 否则 X 阈值仍为 800dp（与旧行为一致，纵滑不受横向漂移干扰）。
                        val hasHorizontal = behavior.leftAction != null ||
                            behavior.rightAction != null
                        // 滑动触发距离（2026-10-28 截图 6 项）：逐键 > 皮肤 > 设置 > 36dp；
                        // 800dp 等效禁用槽原样保留，不走可调解析。
                        val snapshot = SkinSnapshotProvider.peek()
                        val threshold = SwipeThresholds.resolve(
                            def.id?.let { snapshot?.keyOverrides?.get(it)?.swipeThresholdDp },
                            snapshot?.swipeThresholdDp,
                            KeyboardManager.Keyboard.SwipeThreshold.getDp(context),
                        )
                        swipeThresholdX = dp(if (hasHorizontal) threshold else 800f)
                        swipeThresholdY = dp(threshold)
                        onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                            when (event.type) {
                                // 滑动方向前景色（审计 E）：滑动中按方向
                                // 暂显该方向色（仅上/下有样式槽），抬手/
                                // 取消恢复默认色；无样式时全程默认色。
                                CustomGestureView.GestureType.Move -> {
                                    applyTransientSwipeColor(
                                        this,
                                        def,
                                        swipeDirectionFor(
                                            hasHorizontal,
                                            event.totalX,
                                            event.totalY,
                                        ),
                                    )
                                    false
                                }

                                CustomGestureView.GestureType.Up -> {
                                    clearTransientSwipeColor(this, def)
                                    if (!event.consumed) {
                                        val action = resolveSwipeAction(
                                            behavior,
                                            swipeDirectionFor(
                                                hasHorizontal,
                                                event.totalX,
                                                event.totalY,
                                            ),
                                            KeyboardStateManager.isComposingNow,
                                        )
                                        if (action != null) {
                                            onAction(action)
                                            true
                                        } else false
                                    } else false
                                }

                                CustomGestureView.GestureType.Cancel -> {
                                    clearTransientSwipeColor(this, def)
                                    false
                                }

                                else -> false
                            }
                        }
                    }

                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { onAction(behavior.action) }
                    }

                    is KeyDef.Behavior.SwipeMoveCursor -> {
                        swipeEnabled = true
                        swipeThresholdX = dp(32f)
                        // 纵向滑动不处理，阈值设大等效禁用
                        swipeThresholdY = dp(800f)
                        onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                            if (event.type == CustomGestureView.GestureType.Move && event.countX != 0) {
                                val direction = if (event.countX > 0) 1 else -1
                                // 滑动步长：每步进移动 slideStepChars 个字符（默认 1）
                                repeat(kotlin.math.abs(event.countX) * slideStepChars) {
                                    onAction(KeyboardAction.CursorMoveAction(direction))
                                }
                                // 消费掉手势，抬手时不再触发按键点击（空格）
                                true
                            } else {
                                false
                            }
                        }
                    }
                }
            }
            // 长按面板挂载：仅无工厂长按的键才挂接——有 Preview/
            // Keyboard 弹窗或 LongPress 行为的键保持原行为；
            // 网格版优先，网格无效时回落旧版单行。挂接位置在
            // 行为循环之后、按住上档链式接线之前，使上档链式
            // 钩子能追加在面板的抬手回调之后。
            val hasFactoryLongPress = def.popups?.any {
                it is KeyDef.Popup.Preview || it is KeyDef.Popup.Keyboard
            } == true || def.behaviors.any { it is KeyDef.Behavior.LongPress }
            val panelSnapshot = SkinSnapshotProvider.peek()
            val gridPanel = if (!hasFactoryLongPress) {
                HintSymbolsResolver.resolveGridPanel(
                    def.appearance.skinHintSymbolsGridStyle,
                    panelSnapshot?.hintSymbolsGridStyles ?: emptyMap(),
                    panelSnapshot?.hintSymbolNodes ?: emptyMap(),
                    panelSnapshot?.keyStyles ?: emptyMap(),
                    colors,
                    KeyOverrideApplier::toRuntime,
                )
            } else {
                null
            }
            if (gridPanel != null) {
                mountGridPanel(this, gridPanel)
            }
            if (!hasFactoryLongPress && gridPanel == null) {
                val legacyPanel = HintSymbolsResolver.resolveLegacyPanel(
                    def.appearance.skinHintSymbolsStyle,
                    panelSnapshot?.hintSymbolsStyles ?: emptyMap(),
                    panelSnapshot?.hintSymbolNodes ?: emptyMap(),
                    KeyOverrideApplier::toRuntime,
                )
                if (legacyPanel != null) {
                    var panelHighlight: Int? = null
                    var panelSpec: SymbolGridSpec? = null
                    var panelOriginX = 0f
                    var panelInitialMove = true
                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_MOVE -> {
                                if (keyboardPopup.isShowing()) {
                                    if (panelInitialMove) {
                                        panelOriginX = event.x
                                        panelInitialMove = false
                                        // 单行格＝面板项本身（无空位），步距＝itemStep、
                                        // 抖动＝半步，复现工厂弹窗手感；dy=0（单行忽略纵向漂移）。
                                        val start = keyboardPopup.selectedIndex
                                        panelHighlight = start
                                        panelSpec = SymbolGridSpec.create(
                                            cells = legacyPanel.labels,
                                            columns = legacyPanel.actions.size,
                                            anchorIndex = start,
                                            defaultIndex = start,
                                            cellSizePx = keyboardPopup.itemStep,
                                            sensitivity = 1f,
                                            jitterPx = keyboardPopup.itemStep / 2f,
                                        )
                                    }
                                    val spec = panelSpec ?: return@setOnTouchListener true
                                    val h = SymbolGridResolver.highlightFor(
                                        spec, event.x - panelOriginX, 0f,
                                    )
                                    if (h != panelHighlight) {
                                        panelHighlight = h
                                        if (h != null) keyboardPopup.selectIndex(h)
                                        else keyboardPopup.clearSelection()
                                        InputFeedbacks.hapticFeedback(view)
                                    }
                                    return@setOnTouchListener true
                                }
                            }

                            else -> {}
                        }
                        false
                    }
                    setOnLongClickListener {
                        showLegacyPanel(it as KeyView, legacyPanel)
                        panelHighlight = keyboardPopup.selectedIndex
                        panelInitialMove = true
                        return@setOnLongClickListener true
                    }
                    onTouchUpListener = {
                        if (keyboardPopup.isShowing()) {
                            // 出界取消（highlight 为 null）时只关闭不提交；
                            // 回界内恢复后抬手照常提交恢复格（与工厂弹窗同口径）。
                            val h = panelHighlight
                            if (h != null) {
                                legacyPanel.actions.getOrNull(h)?.let { onAction(it) }
                            }
                            keyboardPopup.dismiss()
                            panelHighlight = null
                        }
                    }
                }
            }
            if (hasMomentaryShift) {
                // 按住上档链式接线：popups/语音分支已占位时
                // 不得覆盖（单槽位监听），一律追加在原监听之后；
                // 循环后接线与 Set 迭代顺序无关。
                val prevDown = onTouchDownListener
                val prevUp = onTouchUpListener
                onTouchDownListener = { v ->
                    prevDown?.invoke(v)
                    onMomentaryShiftDown()
                }
                onTouchUpListener = { v ->
                    prevUp?.invoke(v)
                    onMomentaryShiftUp()
                }
            }
        }
    }

    private fun showPreview(view: KeyView) {
        val text = view.displayText ?: return
        // 逐键短按气泡（hintStyle）：尺寸与溢出检测由皮肤逐键
        // 指定，未配时弹窗走默认尺寸与顶层 bubbleOverflowCheck。
        val hintBubble = view.def.skinHintBubble
        previewPopup.show(
            anchor = view,
            text = text,
            bubbleWidthPx = hintBubble?.size?.let { context.dp(it.width).toInt() },
            bubbleHeightPx = hintBubble?.size?.let { context.dp(it.height).toInt() },
            checkOverflow = hintBubble?.checkIfOverflowsParentHeight,
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
            keyPressedColor = LONG_PRESS_HIGHLIGHT_BLUE,
        )
    }

    /**
     * 长按面板（旧版单行）显示：与工厂弹窗同色口径，面板级
     * 背景样式可解时用样式面色（面板背景／选中背景），逐格
     * 显示文本与前景色走注册表节点（无前景色时走变体默认色），
     * 初始选中走样式 selectedIndex（工厂三分位规则不适用）。
     */
    private fun showLegacyPanel(view: KeyView, panel: HintSymbolsResolver.LegacyPanel) {
        val snapshot = SkinSnapshotProvider.peek()
        val backgrounds = snapshot?.let {
            HintSymbolsResolver.resolveBackgrounds(panel.style, it.keyStyles, colors)
        }
        val itemTextColors = snapshot?.let {
            HintSymbolsResolver.resolveItemTextColors(panel.styleNames, it.keyStyles, colors)
        }
        keyboardPopup.show(
            anchor = view,
            items = panel.actions,
            textColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyText
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
                KeyDef.Appearance.Variant.None -> colors.keyText
            },
            bgColor = backgrounds?.background?.face ?: colors.keyBackground,
            keyBgColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyBackground
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyBackground
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyBackground
                KeyDef.Appearance.Variant.None -> Color.TRANSPARENT
            },
            keyPressedColor = backgrounds?.selectedBackground?.face
                ?: LONG_PRESS_HIGHLIGHT_BLUE,
            itemLabels = panel.labels,
            itemTextColors = itemTextColors,
            initialSelectedIndex = panel.initialIndex,
        )
    }

    /**
     * 长按网格面板手势挂载：手指按下位置为位移原点（对端语义，
     * 非气泡出现位置）；阈值内维持初始态（显式 selected 格或
     * 无高亮——引擎阈值内分支恒回原点格，不适用于无 selected
     * 的情形，故视图层自行区分）；超阈值走位移引擎，换选震动
     * 一次；越界清除高亮（抬手取消，移回可重选）；抬手提交
     * 当前高亮格，无高亮不提交；CANCEL 关闭面板（不提交）。
     */
    private fun mountGridPanel(keyView: KeyView, panel: HintSymbolsResolver.GridPanel) {
        val style = panel.style
        val initialHighlight: Pair<Int, Int>? = style.selectedRow?.let { row ->
            style.selectedCol?.let { col ->
                if (style.cellAt(row, col) != null) row to col else null
            }
        }
        val origin: Pair<Int, Int>? = HintGridSelection.initialCell(style)
        var downX = 0f
        var downY = 0f
        var gridHighlight: Pair<Int, Int>? = initialHighlight
        keyView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                }

                android.view.MotionEvent.ACTION_MOVE -> {
                    if (hintGridPopup.isShowing()) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        val h = if (abs(dx) <= style.moveThreshold &&
                            abs(dy) <= style.moveThreshold
                        ) {
                            initialHighlight
                        } else {
                            HintGridSelection.selectByDisplacement(
                                style, origin, dx, dy,
                                hintGridPopup.cellStepX, hintGridPopup.cellStepY,
                            )
                        }
                        if (h != gridHighlight) {
                            gridHighlight = h
                            if (h != null) hintGridPopup.selectCell(h.first, h.second)
                            else hintGridPopup.clearSelection()
                            InputFeedbacks.hapticFeedback(view)
                        }
                        return@setOnTouchListener true
                    }
                }

                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (hintGridPopup.isShowing()) {
                        hintGridPopup.dismiss()
                        gridHighlight = null
                    }
                }

                else -> {}
            }
            false
        }
        keyView.setOnLongClickListener {
            showGridPanel(keyView, panel, initialHighlight)
            gridHighlight = hintGridPopup.selectedCell
            return@setOnLongClickListener true
        }
        keyView.onTouchUpListener = {
            if (hintGridPopup.isShowing()) {
                val h = gridHighlight
                if (h != null) {
                    panel.rows.getOrNull(h.first)?.getOrNull(h.second)?.let { onAction(it.action) }
                }
                hintGridPopup.dismiss()
                gridHighlight = null
            }
        }
    }

    /**
     * 长按网格面板显示：格尺寸走样式 size，未配时走锚键尺寸
     * （锚键未布局时回落 42dp）；面板级背景样式可解时用样式
     * 面色，否则走默认键色与长按高亮蓝；初始选中只取显式
     * selected（无 selected 时弹出后无高亮）。
     */
    private fun showGridPanel(
        view: KeyView,
        panel: HintSymbolsResolver.GridPanel,
        initialSelectedCell: Pair<Int, Int>?,
    ) {
        val snapshot = SkinSnapshotProvider.peek()
        val backgrounds = snapshot?.let {
            HintSymbolsResolver.resolveGridBackgrounds(panel.style, it.keyStyles, colors)
        }
        val style = panel.style
        val cellWidthPx = style.size?.let { context.dp(it.width).toInt() }
            ?: view.width.takeIf { it > 0 } ?: context.dp(42).toInt()
        val cellHeightPx = style.size?.let { context.dp(it.height).toInt() }
            ?: view.height.takeIf { it > 0 } ?: context.dp(42).toInt()
        hintGridPopup.show(
            anchor = view,
            keyboardView = this,
            panel = panel,
            cellWidthPx = cellWidthPx.coerceAtLeast(1),
            cellHeightPx = cellHeightPx.coerceAtLeast(1),
            spacingHPx = context.dp(style.spacingHorizontal).toInt(),
            spacingVPx = context.dp(style.spacingVertical).toInt(),
            insetLeftPx = context.dp(style.insets.left).toInt(),
            insetTopPx = context.dp(style.insets.top).toInt(),
            insetRightPx = context.dp(style.insets.right).toInt(),
            insetBottomPx = context.dp(style.insets.bottom).toInt(),
            textColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyText
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
                KeyDef.Appearance.Variant.None -> colors.keyText
            },
            bgColor = backgrounds?.background?.face ?: colors.keyBackground,
            selectedBgColor = backgrounds?.selectedBackground?.face
                ?: LONG_PRESS_HIGHLIGHT_BLUE,
            initialSelectedCell = initialSelectedCell,
        )
    }

    protected open fun onAction(action: KeyboardAction) {
        // 性能埋点（2026-10-29 item-1 B）：按键分发起点，终点在
        // MessageHandler 的 Commit 处理（配对窗口 2s，首个提交为准）。
        com.jobeen.ime.skin.perf.PerfMonitor.noteKeyPress()
        // 输入统计（2026-10-29 item-1 C）：按键计数（语音拖动
        // 探针除外，见 InputStats.noteKeyPress）。
        com.jobeen.ime.input.stats.InputStats.noteKeyPress(action)
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

    /** 大写是否激活（含按住上档）：Qwerty 覆写；其余键盘恒 false。 */
    protected open val isUppercased: Boolean get() = false

    /** 按住上档按下：Qwerty 覆写（进 Momentary）；其余键盘无操作。 */
    protected open fun onMomentaryShiftDown() {}

    /** 按住上档抬手：Qwerty 覆写（回 None）；其余键盘无操作。 */
    protected open fun onMomentaryShiftUp() {}

    /**
     * 主文字默认色（TextKeyView/AltTextKeyView 构造期同口径）：
     * 图标色 > 皮肤文字色 > 变体色。滑动/大写暂显色恢复时共用。
     */
    protected fun defaultMainTextColor(appearance: KeyDef.Appearance): Int =
        appearance.skinIconTintColor ?: appearance.skinTextColor ?: when (appearance.variant) {
            KeyDef.Appearance.Variant.Normal,
            KeyDef.Appearance.Variant.AltForeground -> colors.keyText
            KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
            KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
            KeyDef.Appearance.Variant.None -> colors.keyText
        }

    private fun setMainTextColor(view: View, appearance: KeyDef.Appearance, color: Int?) {
        val target = color ?: defaultMainTextColor(appearance)
        when (view) {
            is TextKeyView -> view.mainText.setTextColor(target)
            is AltTextKeyView -> view.mainText.setTextColor(target)
            else -> Unit
        }
    }

    /**
     * 大写态前景色（子类覆写：QwertyKeyboard 按当前大写态取
     * 锁定/大写色；缺省 null = 默认色）。滑动暂显清除时恢复到
     * 该色（§5 序：滑动＞锁定＞大写＞基础），而非直接回默认。
     */
    protected open fun capsForegroundColor(appearance: KeyDef.Appearance): Int? = null

    private fun applyTransientSwipeColor(view: View, def: KeyDef, direction: SwipeDirection?) {
        // §5 序：滑动色优先；该方向无滑动色时退大写态色（再无则默认色，
        // 由 setMainTextColor 的 null 回落处理）。
        val color = StyleStatePriority.foregroundColor(
            swipeUpColor = def.appearance.skinSwipeUpTextColor,
            swipeDownColor = def.appearance.skinSwipeDownTextColor,
            capsLockedColor = null,
            uppercasedColor = null,
            swipeDir = when (direction) {
                SwipeDirection.UP -> StyleStatePriority.SwipeDir.UP
                SwipeDirection.DOWN -> StyleStatePriority.SwipeDir.DOWN
                else -> StyleStatePriority.SwipeDir.NONE
            },
            isCapsLocked = false,
            isUppercased = false,
        ) ?: capsForegroundColor(def.appearance)
        setMainTextColor(view, def.appearance, color)
    }

    private fun clearTransientSwipeColor(view: View, def: KeyDef) {
        // §5 恢复口径：滑动色清除后回到大写态色（无则默认色，
        // 由 setMainTextColor 的 null 回落处理）。
        setMainTextColor(view, def.appearance, capsForegroundColor(def.appearance))
    }

    override fun onAttach() = Timber.d("Keyboard onAttach")

    override fun onDetach() {
        Timber.d("Keyboard onDetach")
        rippleView.cancelRipple()
        resetSidePanelPosition()
        previewPopup.dismiss()
        keyboardPopup.dismiss()
        hintGridPopup.dismiss()
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
        // 文字回车键文案跟随编辑框动作：空输入/组字中恒为换行
        // （与图标回车口径一致：此时回车只换行，不发送/搜索）。
        val enterLabel = if (empty || isComposing) {
            "换行"
        } else {
            com.jobeen.ime.input.keyboard.window.ReturnKeyTypeText.labelFor(action)
        }
        enterTextSetters.forEach { it(enterLabel) }
        // 回车键底色分色（默认灰 vs 动作 accent）：空输入/组字中恒为
        // 默认灰（此时回车只换行）；动作型走主题 accent，其余走默认灰。
        // 覆盖只作用于视图，不动 KeyDef 皮肤面色；图标回车与文本回车同口径。
        val enterBackground =
            if (!empty && !isComposing &&
                com.jobeen.ime.input.keyboard.window.ReturnKeyTypeText.isAccentBackground(action)
            ) {
                colors.accentKeyBackground
            } else {
                // 深色回车默认灰走对端半透明值（浅色仍走实心灰）：
                // 深浅由当前方案键面亮度判定，不读主题 id。
                com.jobeen.ime.input.keyboard.window.ReturnKeyTypeText.defaultGrayForDark(
                    com.jobeen.ime.input.keyboard.window.ReturnKeyTypeText
                        .isDarkBackground(colors.keyBackground),
                )
            }
        returnKeyView?.let {
            it.backgroundOverride = enterBackground
            it.refreshBackground()
        }
        enterKeyViews.forEach {
            it.backgroundOverride = enterBackground
            it.refreshBackground()
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

/** 滑动方向（纯决策，与 CustomGestureView 事件口径一致）。 */
internal enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/**
 * 分发期点击动作解析（审计 E：preedit > uppercased > normal）：
 * 组合中 preedit 动作优先；无 preedit 动作时大写动作次之；
 * 均无时用普通动作。组合且无 preedit 时不跳过大写（两态
 * 独立，preedit 只压过普通，不压过大写缺省）。
 */
internal fun resolvePressAction(
    behavior: KeyDef.Behavior.Press,
    isComposing: Boolean,
    isUppercased: Boolean,
): KeyboardAction = StyleStatePriority.action(
    preeditAction = behavior.preeditAction,
    uppercasedAction = behavior.uppercasedAction,
    baseAction = behavior.action,
    isComposing = isComposing,
    isUppercased = isUppercased,
)

/**
 * 滑动主导轴判定（与旧 Up 分支逐字同口径）：横向步数绝对值
 * 不小于纵向且有左右动作时走横向，否则走纵向；零位移无方向。
 */
internal fun swipeDirectionFor(
    hasHorizontal: Boolean,
    totalX: Int,
    totalY: Int,
): SwipeDirection? = when {
    hasHorizontal && totalX != 0 &&
        kotlin.math.abs(totalX) >= kotlin.math.abs(totalY) ->
        if (totalX < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
    totalY < 0 -> SwipeDirection.UP
    totalY > 0 -> SwipeDirection.DOWN
    else -> null
}

/**
 * 分发期滑动动作解析：组合中该方向 preedit 动作优先，
 * 否则该方向普通动作；无方向或该方向无动作时为 null。
 */
internal fun resolveSwipeAction(
    behavior: KeyDef.Behavior.Swipe,
    direction: SwipeDirection?,
    isComposing: Boolean,
): KeyboardAction? {
    if (direction == null) return null
    if (isComposing) {
        val preedit = when (direction) {
            SwipeDirection.UP -> behavior.preeditUpAction
            SwipeDirection.DOWN -> behavior.preeditDownAction
            SwipeDirection.LEFT -> behavior.preeditLeftAction
            SwipeDirection.RIGHT -> behavior.preeditRightAction
        }
        if (preedit != null) return preedit
    }
    return when (direction) {
        SwipeDirection.UP -> behavior.upAction
        SwipeDirection.DOWN -> behavior.downAction
        SwipeDirection.LEFT -> behavior.leftAction
        SwipeDirection.RIGHT -> behavior.rightAction
    }
}
