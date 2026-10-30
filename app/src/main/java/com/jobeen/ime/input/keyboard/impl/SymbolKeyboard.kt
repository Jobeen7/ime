package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.PunctuationMode
import com.jobeen.ime.data.Symbol
import com.jobeen.ime.input.keyboard.key.GridKeyboardView
import com.jobeen.ime.input.keyboard.key.KeyActionListener
import com.jobeen.ime.input.keyboard.key.KeyDef
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyboardPopup
import com.jobeen.ime.input.keyboard.key.KeyboardRippleView
import com.jobeen.ime.input.keyboard.key.SidePanelKeyView
import com.jobeen.ime.input.keyboard.key.TextKeyView
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.symbol.BarSide
import com.jobeen.ime.skin.symbol.SharedPrefsSymbolBarTypeStore
import com.jobeen.ime.skin.symbol.SymbolBarState
import com.jobeen.ime.skin.symbol.SymbolBarTypeCycler
import com.jobeen.ime.skin.symbol.SymbolGridResolver
import com.jobeen.ime.skin.symbol.SymbolGridSpec
import splitties.dimensions.dp
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import com.jobeen.ime.data.Symbol.Category

@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
) : FrameLayout(context), IKeyboard {

    companion object {
        const val NAME = "Symbol"
        private const val COLUMNS = 5
        private const val ROWS = 5
        private var categorys: List<Pair<Category, Array<String>>> = Symbol.Symbol

        private fun keyDef(text: String): KeyDef {
            return KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = text, textSize = 18f,
                    percentWidth = 1f / COLUMNS,
                    variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(
                    KeyDef.Behavior.Press(KeyboardAction.CommitAction(text))
                ),
            )
        }
    }

    override var keyActionListener: KeyActionListener? = null

    private val sidePanelKey = SidePanelKeyView(
        context, colors,
        KeyDef.Appearance.SidePannel(
            rowSpan = 4, visableRow = 4, margin = false,
            variant = KeyDef.Appearance.Variant.Alternative,
            border = KeyDef.Appearance.Border.On,
        ),
        keepSelection = true,
    )

    private val gridView = GridKeyboardView(context, colors, COLUMNS, ROWS).apply {
        onKeyAction = { action -> keyActionListener?.onKeyAction(action) }
        onKeyPressed = { key -> triggerRipple(key) }
    }

    private val rippleView = KeyboardRippleView(context).apply {
        id = generateViewId()
        isClickable = false
        isEnabled = false
        isFocusable = false
    }

    private val returnKeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = "返回", textSize = 15f,
            textStyle = android.graphics.Typeface.BOLD,
            percentWidth = 1f,
            variant = KeyDef.Appearance.Variant.Alternative,
        ),
        behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ResumeAction)),
    )

    private val sideReturnBtn =
        TextKeyView(context, colors, returnKeyDef.appearance as KeyDef.Appearance.Text)

    // 接线轮 R5：皮肤符号栏（快照有 symbolBar 规格时才建条；
    // 无规格时本键盘与旧行为完全一致；栏高 48dp）。
    private val barSpec = SkinSnapshotProvider.peek()?.symbolBar
    private val barState = SymbolBarState(SharedPrefsSymbolBarTypeStore(context))
    private var barView: LinearLayout? = null
    private var barSwitchBtn: TextView? = null
    private val barPopup = KeyboardPopup(context)

    private val sidePanelItemDefs: List<KeyDef>

    init {
        sideReturnBtn.borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
        sideReturnBtn.hMargin = 0
        sideReturnBtn.vMargin = 0
        sideReturnBtn.setOnClickListener {
            keyActionListener?.onKeyAction(KeyboardAction.ResumeAction)
        }
        sideReturnBtn.onPressedChanged = { key ->
            if (key.isPressed) triggerRipple(key)
        }

        isClickable = true

        sidePanelItemDefs = categorys.map { (category, _) ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = category.label, textSize = 15f, percentWidth = 0.5f,
                    margin = false, variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(category.label))),
            )
        }
        sidePanelKey.updateItems(sidePanelItemDefs)

        sidePanelKey.setOnItemActionListener { action ->
            if (action is KeyboardAction.CommitAction && categorys.any { it.first.label == action.text }) {
                applyCategory(action.text)
                // 接线轮 R5：侧栏选类同样持久化（与栏上切换同一记忆）。
                barState.switchType(action.text)
            } else {
                keyActionListener?.onKeyAction(action)
            }
        }

        // 接线轮 R5：符号栏条（皮肤规格驱动；关闭钮恒贴外缘，
        // 切换钮居内；栏键点按提交、长按出角标格）。
        if (barSpec != null) {
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(colors.specialKeyBackground)
                visibility = View.GONE
            }
            fun pressedBackground(): StateListDrawable =
                StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_pressed),
                        ColorDrawable(colors.specialKeyPressed),
                    )
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
            fun cell(text: String): TextView = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(15f)
                setTextColor(colors.specialKeyText)
                this.text = text
                background = pressedBackground()
            }
            fun cellParams() =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            val closeBtn = cell("✕").apply {
                setOnClickListener {
                    barState.close()
                    bar.visibility = View.GONE
                    requestLayout()
                }
            }
            val switchBtn = cell(barState.currentType ?: categorys.first().first.label).apply {
                setOnClickListener {
                    val next = SymbolBarTypeCycler.next(categoryLabels(), barState.currentType)
                        ?: return@setOnClickListener
                    barState.switchType(next)
                    applyCategory(next)
                }
            }
            barSwitchBtn = switchBtn
            if (barSpec.closePosition == BarSide.LEFT) bar.addView(closeBtn, cellParams())
            if (barSpec.typeSwitchPosition == BarSide.LEFT) bar.addView(switchBtn, cellParams())
            for (key in barSpec.keys) {
                val keyCell = cell(key.tap)
                keyCell.setOnClickListener {
                    keyActionListener?.onKeyAction(KeyboardAction.CommitAction(key.tap))
                }
                // 长按角标格：位移选格（接线轮 R6 同款逻辑的单行版）。
                var popupHighlight: Int? = null
                var popupSpec: SymbolGridSpec? = null
                var originX = 0f
                var firstMove = true
                keyCell.setOnLongClickListener {
                    if (key.corners.isEmpty()) return@setOnLongClickListener false
                    barPopup.show(
                        keyCell,
                        key.corners.map { KeyboardAction.CommitAction(it) },
                        colors.specialKeyText,
                        colors.specialKeyBackground,
                        colors.specialKeyBackground,
                        colors.specialKeyPressed,
                    )
                    popupHighlight = barPopup.selectedIndex
                    popupSpec = SymbolGridSpec.create(
                        cells = key.corners,
                        columns = key.corners.size,
                        anchorIndex = barPopup.selectedIndex,
                        defaultIndex = barPopup.selectedIndex,
                        cellSizePx = barPopup.itemStep,
                        sensitivity = 1f,
                        jitterPx = barPopup.itemStep / 2f,
                    )
                    firstMove = true
                    true
                }
                keyCell.setOnTouchListener { _, event ->
                    if (!barPopup.isShowing()) return@setOnTouchListener false
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            if (firstMove) {
                                originX = event.x
                                firstMove = false
                                return@setOnTouchListener true
                            }
                            val spec = popupSpec ?: return@setOnTouchListener true
                            val h = SymbolGridResolver.highlightFor(spec, event.x - originX, 0f)
                            if (h != popupHighlight) {
                                popupHighlight = h
                                if (h != null) barPopup.selectIndex(h) else barPopup.clearSelection()
                                InputFeedbacks.hapticFeedback(keyCell)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val h = popupHighlight
                            barPopup.dismiss()
                            popupHighlight = null
                            if (event.actionMasked == MotionEvent.ACTION_UP && h != null) {
                                key.corners.getOrNull(h)?.let {
                                    keyActionListener?.onKeyAction(KeyboardAction.CommitAction(it))
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                bar.addView(keyCell, cellParams())
            }
            if (barSpec.typeSwitchPosition == BarSide.RIGHT) bar.addView(switchBtn, cellParams())
            if (barSpec.closePosition == BarSide.RIGHT) bar.addView(closeBtn, cellParams())
            barView = bar
        }

        addView(sidePanelKey, lParams(0, matchParent))
        addView(sideReturnBtn, lParams(0, matchParent))
        addView(gridView, lParams(0, matchParent))
        barView?.let { addView(it, lParams(matchParent, 0)) }
        addView(rippleView, LayoutParams(matchParent, matchParent))
    }

    private fun categoryLabels(): List<String> = categorys.map { it.first.label }

    private fun applyCategory(label: String) {
        val index = categorys.indexOfFirst { it.first.label == label }
        if (index < 0) return
        gridView.setItems(categorys[index].second.map { keyDef(it) })
        sidePanelKey.selectIndex(index)
        barSwitchBtn?.text = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        val sw = (tw * 0.15f).toInt()
        val gw = tw - sw
        // 接线轮 R5：栏可见时占顶 48dp，其余内容整体下移并压缩；
        // 栏隐藏时 barH=0，与旧布局完全一致。
        val barH = if (barView?.visibility == View.VISIBLE) context.dp(48) else 0
        val contentH = th - barH
        val sidePanelH = contentH * 3 / 4
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(sidePanelH, MeasureSpec.EXACTLY))
        sideReturnBtn.measure(
            mES(sw, MeasureSpec.EXACTLY), mES(contentH - sidePanelH, MeasureSpec.EXACTLY)
        )
        gridView.measure(mES(gw, MeasureSpec.EXACTLY), mES(contentH, MeasureSpec.EXACTLY))
        barView?.measure(mES(tw, MeasureSpec.EXACTLY), mES(barH, MeasureSpec.EXACTLY))
        // 同 EmojiKeyboard：波纹层补 measure/layout，否则宽高恒 0、
        // 波纹从不显示（L1）
        rippleView.measure(mES(tw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        setMeasuredDimension(tw, th)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sw = ((r - l) * 0.15f).toInt()
        val barH = if (barView?.visibility == View.VISIBLE) context.dp(48) else 0
        val contentH = (b - t) - barH
        val sidePanelH = contentH * 3 / 4
        barView?.layout(0, 0, r - l, barH)
        sidePanelKey.layout(0, barH, sw, barH + sidePanelH)
        sideReturnBtn.layout(0, barH + sidePanelH, sw, barH + contentH)
        gridView.layout(sw, barH, r - l, barH + contentH)
        rippleView.layout(0, 0, r - l, b - t)
    }

    private fun mES(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)

    override fun name(): String = NAME
    override fun updateSpaceKeyText(text: String) {}
    override fun updateDynamicTexts(values: Map<String, String>) {}
    override fun updatePunctuationMode(mode: PunctuationMode) {}
    override fun updateEditorInfo(info: EditorInfo, empty: Boolean, isComposing: Boolean) {}

    override fun setRippleEnabled(enabled: Boolean) {
        rippleView.rippleEnabled = enabled
        rippleView.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun triggerRipple(key: KeyView) {
        val keyLoc = IntArray(2)
        val boardLoc = IntArray(2)
        key.getLocationOnScreen(keyLoc)
        getLocationOnScreen(boardLoc)
        val cx = keyLoc[0] + key.width / 2f - boardLoc[0]
        val cy = keyLoc[1] + key.height / 2f - boardLoc[1]
        rippleView.startRipple(cx, cy, key)
    }

    override fun onAttach() {
        reset()
        // 接线轮 R5：有皮肤栏规格时打开栏（状态对象默认关闭，
        // 键盘策略在附着时打开；关闭钮仅隐藏本实例的栏，
        // 下次附着重新显示——类型记忆不受影响）。
        if (barSpec != null) {
            barState.open()
            barView?.visibility = View.VISIBLE
            requestLayout()
        }
    }

    override fun onDetach() {
        rippleView.cancelRipple()
        barPopup.dismiss()
    }

    fun reset() {
        // 接线轮 R5：类型记忆恢复（存过且仍在分类表内时），否则首类。
        val restored = barState.currentType
        if (restored != null && categorys.any { it.first.label == restored }) {
            applyCategory(restored)
        } else {
            val (_, symbols) = categorys.first()
            gridView.setItems(symbols.map { keyDef(it) })
            sidePanelKey.selectIndex(0)
            barSwitchBtn?.text = categorys.first().first.label
        }
        sidePanelKey.resetPosition()
    }
}
