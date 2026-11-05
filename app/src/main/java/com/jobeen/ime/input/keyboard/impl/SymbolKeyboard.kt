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
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.input.keyboard.key.KeyView
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.keyboard.key.KeyboardPopup
import com.jobeen.ime.input.keyboard.key.KeyboardRippleView
import com.jobeen.ime.input.keyboard.key.SidePanelKeyView
import com.jobeen.ime.input.keyboard.key.TextKeyView
import com.jobeen.ime.input.keyboard.window.KeyboardStateManager
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.symbol.BarSide
import com.jobeen.ime.skin.symbol.BoardAutoReturn
import com.jobeen.ime.skin.symbol.HorizontalSymbolStripState
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.skin.symbol.SharedPrefsSymbolBarTypeStore
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbols
import com.jobeen.ime.skin.symbol.SymbolBarState
import com.jobeen.ime.skin.symbol.SymbolBarTypeCycler
import com.jobeen.ime.skin.symbol.SymbolGridResolver
import com.jobeen.ime.skin.symbol.SymbolGridSpec
import com.jobeen.ime.skin.symbol.SymbolParentCategory
import com.jobeen.ime.skin.symbol.SymbolSubCategory
import splitties.dimensions.dp
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import com.jobeen.ime.data.Symbol.Category

@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
) : FrameLayout(context), ISubCollectionPageable {

    companion object {
        const val NAME = "Symbol"
        private const val COLUMNS = 5
        private const val ROWS = 5
        /** P1-7 附加父类标签：数字符号集／九宫格符号集（各一“全部”子类）。 */
        private const val NUMERIC_PARENT_LABEL = "数字"
        private const val T9_PARENT_LABEL = "九宫格"
        private var categorys: List<Pair<Category, Array<String>>> = Symbol.Symbol

        private fun keyDef(item: SymbolItem): KeyDef {
            return KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = item.displayText, textSize = 18f,
                    percentWidth = 1f / COLUMNS,
                    variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(
                    KeyDef.Behavior.Press(SymbolItemActionResolver.resolve(item))
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
        onKeyAction = { action ->
            keyActionListener?.onKeyAction(action)
            // 板内提交：未锁定时提交后自动返回（审计 G）；
            // 分类切换的提交由侧栏内部拦截，不经过此处。
            if (action is KeyboardAction.CommitAction) maybeAutoReturn()
        }
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
        // 功能锚点（定稿五.6）：只登记不覆写
        id = KeyIds.Symbol.SYM_RETURN,
    )

    private val sideReturnBtn =
        TextKeyView(context, colors, returnKeyDef.appearance as KeyDef.Appearance.Text)

    /**
     * 板锁钮（返回钮上方半格）：点按分发已有锁切换动作，
     * 文案即锁态（🔒 常驻／🔓 提交后返回）；窗口切换锁态
     * 不重建键盘，钮文案须自刷新（见 [refreshLockBtn]）。
     */
    private val sideLockBtn =
        TextKeyView(
            context, colors,
            KeyDef.Appearance.Text(
                displayText = "🔓", textSize = 15f,
                percentWidth = 1f,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
        )

    // 接线轮 R5：皮肤符号栏（快照有 symbolBar 规格时才建条；
    // 无规格时本键盘与旧行为完全一致；栏高 48dp）。
    private val barSpec = SkinSnapshotProvider.peek()?.symbolBar
    private val barState = SymbolBarState(SharedPrefsSymbolBarTypeStore(context))
    private var barView: LinearLayout? = null
    private var barSwitchBtn: TextView? = null
    private val barPopup = KeyboardPopup(context)

    /**
     * 二级面板数据源：皮肤定义二级表时用皮肤表，否则既有一级表
     * 转二级（每父仅一“全部”子类，此时子条隐藏、行为与旧板一致）。
     * P1-7：皮肤另声明 numericSymbols／t9Symbols 时各附加一父类
     * （数字／九宫格，各一“全部”子类），顺序在既有父类之后；
     * 空集不附加（不占侧栏位）。
     */
    private val twoStageSymbols: SubClassifiedSymbols =
        SkinSnapshotProvider.peek()?.let { snap ->
            val base = snap.subClassifiedSymbols
                ?: SubClassifiedSymbols.fromSingleLevel(categorys)
            val extra = buildList {
                snap.numericSymbols?.takeIf { it.items.isNotEmpty() }?.let { collection ->
                    add(
                        SymbolParentCategory(
                            label = NUMERIC_PARENT_LABEL,
                            subCategories = listOf(
                                SymbolSubCategory("全部", collection.items),
                            ),
                        ),
                    )
                }
                snap.t9Symbols?.let { spec ->
                    add(
                        SymbolParentCategory(
                            label = T9_PARENT_LABEL,
                            subCategories = listOf(
                                SymbolSubCategory("全部", spec.dataSource),
                            ),
                        ),
                    )
                }
            }
            if (extra.isEmpty()) base else base.copy(parents = base.parents + extra)
        } ?: SubClassifiedSymbols.fromSingleLevel(categorys)
    private val boardState = SubClassifiedBoardState(twoStageSymbols)

    /** 横向符号条（P1-7）：horizontalSymbols 优先于 t9HorizontalSymbols；无则无条。 */
    private val horizontalStripSpec = SkinSnapshotProvider.peek()?.let { snap ->
        snap.horizontalSymbols ?: snap.t9HorizontalSymbols
    }
    private val horizontalStripState =
        horizontalStripSpec?.let { HorizontalSymbolStripState(it) }

    /** 横向符号条（48dp 横条，子条之下、网格之上；无规格时隐藏）。 */
    private val hStripView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(colors.specialKeyBackground)
        visibility = View.GONE
    }

    /** 二级子条（48dp 横条，栏条之下、网格之上；单子类时隐藏）。 */
    private val subStripView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(colors.specialKeyBackground)
        visibility = View.GONE
    }

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

        sideLockBtn.borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
        sideLockBtn.hMargin = 0
        sideLockBtn.vMargin = 0
        sideLockBtn.setOnClickListener {
            // 窗口层同步翻转锁态（不重建键盘），随后自刷新文案。
            keyActionListener?.onKeyAction(KeyboardAction.ToggleSymbolicLockAction)
            refreshLockBtn()
        }
        sideLockBtn.onPressedChanged = { key ->
            if (key.isPressed) triggerRipple(key)
        }
        refreshLockBtn()

        isClickable = true

        sidePanelItemDefs = boardState.parentLabels.mapIndexed { index, label ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = label, textSize = 15f, percentWidth = 0.5f,
                    margin = false, variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(label))),
                // 功能锚点（定稿五.6）：序号 = 分类表顺序，只登记不覆写
                id = KeyIds.Symbol.CATS.getOrNull(index),
            )
        }
        sidePanelKey.updateItems(sidePanelItemDefs)

        sidePanelKey.setOnItemActionListener { action ->
            if (action is KeyboardAction.CommitAction && boardState.parentLabels.any { it == action.text }) {
                applyCategory(action.text)
                // 接线轮 R5：侧栏选类同样持久化（与栏上切换同一记忆）。
                barState.switchType(action.text)
            } else {
                keyActionListener?.onKeyAction(action)
            }
        }
        rebuildSubStrip()
        rebuildHorizontalStrip()

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
                    dispatchBoardCommit(key.tap)
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
                                    dispatchBoardCommit(it)
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
        addView(sideLockBtn, lParams(0, matchParent))
        addView(sideReturnBtn, lParams(0, matchParent))
        addView(gridView, lParams(0, matchParent))
        addView(subStripView, lParams(matchParent, 0))
        addView(hStripView, lParams(matchParent, 0))
        barView?.let { addView(it, lParams(matchParent, 0)) }
        addView(rippleView, LayoutParams(matchParent, matchParent))
    }

    private fun categoryLabels(): List<String> = categorys.map { it.first.label }

    private fun applyCategory(label: String) {
        val index = boardState.parentLabels.indexOf(label)
        if (index < 0) return
        boardState.selectParent(index)
        gridView.setItems(boardState.visibleItems.map { keyDef(it) })
        sidePanelKey.selectIndex(index)
        barSwitchBtn?.text = label
        rebuildSubStrip()
    }

    /**
     * 二级集合翻页（P1-5）：当前子类按页翻；页内无更多时
     * 返回 false（窗口层空操作，不换父类）。翻页后只换网格，
     * 父类／子类选中与子条不动。
     */
    override fun pageSubCollection(forward: Boolean): Boolean {
        val changed = if (forward) boardState.nextPage() else boardState.prevPage()
        if (changed) gridView.setItems(boardState.visibleItems.map { keyDef(it) })
        return changed
    }

    /**
     * 二级子条重建：当前父类子类少于 2 个时隐藏（既有一级表
     * 转二级的常态，板行为与旧板一致）；否则横排子类格，格内
     * 居中标签＋右上角标（badgeText，无角标时不画）；点格切
     * 子类（只换网格，不动父类选中）；选中格以按压色标示。
     */
    private fun rebuildSubStrip() {
        val subs = boardState.currentParent?.subCategories.orEmpty()
        if (subs.size < 2) {
            subStripView.visibility = View.GONE
            subStripView.removeAllViews()
            return
        }
        subStripView.visibility = View.VISIBLE
        subStripView.removeAllViews()
        subs.forEachIndexed { index, sub ->
            val cell = FrameLayout(context).apply {
                setBackgroundColor(
                    if (index == boardState.subIndex) colors.specialKeyPressed
                    else Color.TRANSPARENT,
                )
                setOnClickListener {
                    if (boardState.selectSub(index)) {
                        gridView.setItems(boardState.visibleItems.map { keyDef(it) })
                        rebuildSubStrip()
                    }
                }
            }
            val label = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(15f)
                setTextColor(colors.specialKeyText)
                text = sub.label
            }
            cell.addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            sub.badgeText?.let { badge ->
                // S12：角标字号／颜色按皮肤（未写沿用旧 9／specialKeyText；
                // 选中格用高亮色，未写高亮时沿用常规色）。
                val badgeSnap = SkinSnapshotProvider.peek()
                val badgeSelected = index == boardState.subIndex
                val badgeView = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(badgeSnap?.symbolBadgeFontSizeDp ?: 9f)
                    setTextColor(
                        if (badgeSelected) {
                            badgeSnap?.symbolBadgeHighlightColor
                                ?: badgeSnap?.symbolBadgeNormalColor
                                ?: colors.specialKeyText
                        } else {
                            badgeSnap?.symbolBadgeNormalColor ?: colors.specialKeyText
                        },
                    )
                    text = badge
                }
                cell.addView(
                    badgeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.END,
                    ),
                )
            }
            subStripView.addView(
                cell,
                LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f,
                ),
            )
        }
    }

    /**
     * 横向符号条重建（P1-7）：无规格时隐藏（旧板行为不变）；
     * 有规格时横排首页面（maxColumns 格，RTL 时顺序已由
     * HorizontalSymbolStripState 反向），点格按符号项动作
     * 分发（与网格键同 resolver、同自动返回口径）。条内翻页
     * 暂无 UI 入口，只显示首页（超页项待后续翻页控件接线）。
     */
    private fun rebuildHorizontalStrip() {
        val state = horizontalStripState
        if (state == null) {
            hStripView.visibility = View.GONE
            hStripView.removeAllViews()
            return
        }
        hStripView.visibility = View.VISIBLE
        hStripView.removeAllViews()
        for (item in state.visibleItems) {
            val cell = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(15f)
                setTextColor(colors.specialKeyText)
                text = item.displayText
                setOnClickListener { dispatchStripItem(item) }
            }
            hStripView.addView(
                cell,
                LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f,
                ),
            )
        }
    }

    /** 横条点按：与网格键同动作解析；提交类动作走同一自动返回口径。 */
    private fun dispatchStripItem(item: SymbolItem) {
        val action = SymbolItemActionResolver.resolve(item)
        keyActionListener?.onKeyAction(action)
        if (action is KeyboardAction.CommitAction) maybeAutoReturn()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        val sw = (tw * 0.15f).toInt()
        val gw = tw - sw
        // 接线轮 R5：栏可见时占顶 48dp，其余内容整体下移并压缩；
        // 栏隐藏时 barH=0，与旧布局完全一致。子条同口径占顶 48dp。
        val barH = if (barView?.visibility == View.VISIBLE) context.dp(48) else 0
        val subH = if (subStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val hH = if (hStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val contentH = th - barH - subH - hH
        val sidePanelH = contentH * 3 / 4
        // 侧栏底区一分为二：上半板锁钮、下半返回钮。
        val returnRegionH = contentH - sidePanelH
        val lockH = returnRegionH / 2
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(sidePanelH, MeasureSpec.EXACTLY))
        sideLockBtn.measure(mES(sw, MeasureSpec.EXACTLY), mES(lockH, MeasureSpec.EXACTLY))
        sideReturnBtn.measure(
            mES(sw, MeasureSpec.EXACTLY), mES(returnRegionH - lockH, MeasureSpec.EXACTLY)
        )
        gridView.measure(mES(gw, MeasureSpec.EXACTLY), mES(contentH, MeasureSpec.EXACTLY))
        subStripView.measure(mES(tw, MeasureSpec.EXACTLY), mES(subH, MeasureSpec.EXACTLY))
        hStripView.measure(mES(tw, MeasureSpec.EXACTLY), mES(hH, MeasureSpec.EXACTLY))
        barView?.measure(mES(tw, MeasureSpec.EXACTLY), mES(barH, MeasureSpec.EXACTLY))
        // 同 EmojiKeyboard：波纹层补 measure/layout，否则宽高恒 0、
        // 波纹从不显示（L1）
        rippleView.measure(mES(tw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        setMeasuredDimension(tw, th)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sw = ((r - l) * 0.15f).toInt()
        val barH = if (barView?.visibility == View.VISIBLE) context.dp(48) else 0
        val subH = if (subStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val hH = if (hStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val contentH = (b - t) - barH - subH - hH
        val sidePanelH = contentH * 3 / 4
        barView?.layout(0, 0, r - l, barH)
        subStripView.layout(0, barH, r - l, barH + subH)
        hStripView.layout(0, barH + subH, r - l, barH + subH + hH)
        val top = barH + subH + hH
        sidePanelKey.layout(0, top, sw, top + sidePanelH)
        val lockH = (contentH - sidePanelH) / 2
        sideLockBtn.layout(0, top + sidePanelH, sw, top + sidePanelH + lockH)
        sideReturnBtn.layout(0, top + sidePanelH + lockH, sw, top + contentH)
        gridView.layout(sw, top, r - l, top + contentH)
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

    /**
     * 板内提交（审计 G）：提交先转发（窗口层透传服务层上屏），
     * 再按锁态决定是否自动返回；锁定时常驻板内。
     */
    private fun dispatchBoardCommit(text: String) {
        keyActionListener?.onKeyAction(KeyboardAction.CommitAction(text))
        maybeAutoReturn()
    }

    private fun maybeAutoReturn() {
        if (BoardAutoReturn.shouldReturnAfterCommit(
                KeyboardTypes.SYMBOLIC,
                KeyboardStateManager.isBoardLocked(KeyboardTypes.SYMBOLIC),
            )
        ) {
            keyActionListener?.onKeyAction(KeyboardAction.BoardCommitReturnAction)
        }
    }

    /** 板锁钮文案即锁态：窗口切换不重建键盘，附着与点按后自刷新。 */
    private fun refreshLockBtn() {
        sideLockBtn.mainText.text =
            if (KeyboardStateManager.symbolicBoardLocked) "🔒" else "🔓"
    }

    override fun onAttach() {
        reset()
        refreshLockBtn()
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
        // 接线轮 R5：类型记忆恢复（存过且仍在父类表内时），否则首类。
        val restored = barState.currentType
        if (restored != null && boardState.parentLabels.contains(restored)) {
            applyCategory(restored)
        } else {
            boardState.selectParent(0)
            gridView.setItems(boardState.visibleItems.map { keyDef(it) })
            sidePanelKey.selectIndex(0)
            barSwitchBtn?.text = boardState.parentLabels.firstOrNull()
                ?: categorys.first().first.label
            rebuildSubStrip()
        }
        sidePanelKey.resetPosition()
    }
}
