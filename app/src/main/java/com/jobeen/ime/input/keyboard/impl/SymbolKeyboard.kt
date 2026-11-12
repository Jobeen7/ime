package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jobeen.ime.R
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
import com.jobeen.ime.input.keyboard.key.shouldInterceptHorizontalSwipe
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.symbol.BarSide
import com.jobeen.ime.skin.symbol.BoardAutoReturn
import com.jobeen.ime.skin.symbol.HorizontalSymbolStripState
import com.jobeen.ime.skin.symbol.KaomojiSwipe
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.skin.symbol.SharedPrefsSymbolBarTypeStore
import com.jobeen.ime.skin.symbol.SharedPrefsUserSymbolBarStore
import com.jobeen.ime.skin.symbol.UserSymbolBarOps
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbols
import com.jobeen.ime.skin.symbol.SymbolBarState
import com.jobeen.ime.skin.symbol.SymbolBarTypeCycler
import com.jobeen.ime.skin.symbol.SymbolGridResolver
import com.jobeen.ime.skin.symbol.SymbolGridSpec
import com.jobeen.ime.skin.symbol.SymbolParentCategory
import com.jobeen.ime.skin.symbol.SymbolSubCategory
import com.jobeen.ime.skin.symbol.UserSymbolBarStore
import com.jobeen.ime.ui.SymbolBarSettingsActivity
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

        /** 左右滑翻页的最小横移（dp）；未达此距离只算点按／竖滚（与颜文字板同口径）。 */
        private const val SWIPE_MIN_DISTANCE_DP = 48
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

    /**
     * 左侧竖栏（2026-11-12 符号栏重做）：显示用户自定义符号列（默认 16），
     * 上下滑动换符号；可见 7 行（另 1 行高固定留给底部“自定义”入口）。
     * 不再显示分类——分类切换走顶部栏的类型切换钮。
     */
    private val sidePanelKey = SidePanelKeyView(
        context, colors,
        KeyDef.Appearance.SidePannel(
            rowSpan = 4, visableRow = 7, margin = false,
            variant = KeyDef.Appearance.Variant.Alternative,
            border = KeyDef.Appearance.Border.On,
        ),
        keepSelection = false,
    )

    private val gridView = GridKeyboardView(context, colors, COLUMNS, ROWS).apply {
        onKeyAction = { action ->
            keyActionListener?.onKeyAction(action)
            // 板内提交：提交后自动返回；
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

    // 接线轮 R5：皮肤符号栏（快照有 symbolBar 规格时才建条；
    // 无规格时本键盘与旧行为完全一致；栏高 48dp）。
    private val barSpec = SkinSnapshotProvider.peek()?.symbolBar
    private val barState = SymbolBarState(SharedPrefsSymbolBarTypeStore(context))
    private var barView: LinearLayout? = null
    private var barSwitchBtn: TextView? = null
    private val barPopup = KeyboardPopup(context)

    /** 用户自定义符号（2026-11-12 符号栏重做）：竖栏数据源，设置页改动下次打开即生效。 */
    private val userSymbolStore: UserSymbolBarStore = SharedPrefsUserSymbolBarStore(context)

    /**
     * 标点模式（WO30 §3(9) 续）：竖栏显示／提交按此换算；默认全角，
     * 与九宫格竖栏同口径（T9Keyboard.state）；唯一写入方是
     * updatePunctuationMode（KeyboardStateManager.switchTo 下发）。
     */
    private var punctuationMode: PunctuationMode = PunctuationMode.FullWidth

    /** 左侧竖栏底部“自定义”入口（固定不滚动，点开设置页）。 */
    private val customEntryView: TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        // 12sp：两行（⊕／自定义）在 customH（sidePanelH/8，约 28dp）内放得下；
        // 14sp 两行约 34dp 会被裁掉下半行。
        setTextSize(12f)
        setTextColor(colors.specialKeyText)
        setBackgroundColor(colors.specialKeyBackground)
        maxLines = 2
        text = context.getString(R.string.symbol_bar_customize)
        isClickable = true
        setOnClickListener {
            val intent = Intent(context, SymbolBarSettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

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

    /** 左侧竖栏项（用户自定义符号列；设置改动后由 rebuildSidePanelSymbols 重建）。 */
    private var sidePanelItemDefs: List<KeyDef> = emptyList()

    /** 左右滑判定用：翻页最小横移。 */
    private val swipeMinDistancePx = context.dp(SWIPE_MIN_DISTANCE_DP).toFloat()
    private var downX = 0f
    private var downY = 0f
    private var swipingHorizontally = false

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

        rebuildSidePanelSymbols()

        sidePanelKey.setOnItemActionListener { action ->
            if (action is KeyboardAction.CommitAction) {
                // 竖栏符号：点按提交，提交后自动返回（与网格／横条同口径）。
                dispatchBoardCommit(action.text)
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
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
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
        addView(customEntryView, lParams(0, matchParent))
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
        // 2026-11-12：竖栏不再显示分类，无需 selectIndex；分类切换
        // 只走顶部栏类型切换钮。
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
     * 左右滑切分类（WO30 §3(5)，颜文字板 swipeCategory 同款）：
     * 翻页（pageSubCollection）到端无动作时才走到这里；到端停，
     * 不循环绕回。与顶栏按钮同款：先记 barState.currentType 再
     * applyCategory（网格＋切换钮文案＋子条一起换）。
     */
    fun swipeCategory(forward: Boolean): Boolean {
        val changed = if (forward) boardState.nextParent() else boardState.prevParent()
        if (changed) {
            val label = boardState.parentLabels[boardState.parentIndex]
            barState.switchType(label)
            applyCategory(label)
        }
        return changed
    }

    /**
     * 左右滑翻页（施工单 18，与 emoji 板同款接线）：横向位移达到
     * 翻页最小距离（48dp）且横向占优时才拦截（子键收 ACTION_CANCEL，
     * 点按不触发）；未达翻页距离的横向小拖动不拦截、仍归子键点按
     * （I-M2，见 EmojiKeyboard 同款注释）；竖向位移归网格滚动，
     * 不拦截；抬手时按 [KaomojiSwipe.directionFor] 判定方向翻页；
     * 翻页到端时改切分类（WO30 §3(5)），首／末分类到端才无动作。
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                swipingHorizontally = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipingHorizontally) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (shouldInterceptHorizontalSwipe(dx, dy, swipeMinDistancePx)) {
                        swipingHorizontally = true
                        return true
                    }
                }
                return swipingHorizontally
            }
            MotionEvent.ACTION_CANCEL -> {
                swipingHorizontally = false
                return false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                if (swipingHorizontally) {
                    swipingHorizontally = false
                    val dx = event.x - downX
                    val dy = event.y - downY
                    KaomojiSwipe.directionFor(dx, dy, swipeMinDistancePx)?.let { direction ->
                        // WO30 §3(5)：先翻页；到端无动作时改切分类，
                        // 首／末分类到端才无动作（不循环绕回）。
                        val forward = direction == KaomojiSwipe.Direction.NEXT
                        if (!pageSubCollection(forward)) {
                            swipeCategory(forward)
                        }
                    }
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                swipingHorizontally = false
                return true
            }
        }
        return super.onTouchEvent(event)
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
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
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
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
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

    /**
     * 左侧竖栏重建（2026-11-12 符号栏重做）：显示用户自定义符号列
     * （默认 16 符号），点格提交。init 与 onAttach 都调用——板实例
     * 复用时设置改动下次打开符号面板即生效。WO30 §3(9) 续：显示与
     * 提交都按标点模式换算（与九宫格竖栏同口径）；「、」两模式都
     * 原样（无单字符半角形）。
     */
    private fun rebuildSidePanelSymbols() {
        val displays = UserSymbolBarOps.sidePanelSymbols(
            userSymbolStore.load().symbols,
            punctuationMode,
        )
        sidePanelItemDefs = displays.map { display ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = display, textSize = 15f, percentWidth = 0.5f,
                    margin = false, variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(display))),
            )
        }
        sidePanelKey.updateItems(sidePanelItemDefs)
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
        // 2026-11-12：竖栏区 8 行高——上 7 行是可滚符号列，下 1 行是
        // 固定“自定义”入口；行高一致（sidePanelH/8）。
        val customH = sidePanelH / 8
        // 侧栏底区整段归返回钮（板锁已删）。
        val returnRegionH = contentH - sidePanelH
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(sidePanelH - customH, MeasureSpec.EXACTLY))
        customEntryView.measure(mES(sw, MeasureSpec.EXACTLY), mES(customH, MeasureSpec.EXACTLY))
        sideReturnBtn.measure(
            mES(sw, MeasureSpec.EXACTLY), mES(returnRegionH, MeasureSpec.EXACTLY)
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
        val customH = sidePanelH / 8
        barView?.layout(0, 0, r - l, barH)
        subStripView.layout(0, barH, r - l, barH + subH)
        hStripView.layout(0, barH + subH, r - l, barH + subH + hH)
        val top = barH + subH + hH
        sidePanelKey.layout(0, top, sw, top + sidePanelH - customH)
        customEntryView.layout(0, top + sidePanelH - customH, sw, top + sidePanelH)
        sideReturnBtn.layout(0, top + sidePanelH, sw, top + contentH)
        gridView.layout(sw, top, r - l, top + contentH)
        rippleView.layout(0, 0, r - l, b - t)
    }

    private fun mES(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)

    override fun name(): String = NAME
    override fun updateSpaceKeyText(text: String) {}
    override fun updateDynamicTexts(values: Map<String, String>) {}
    override fun updatePunctuationMode(mode: PunctuationMode) {
        // 与九宫格同口径（T9Keyboard.updatePunctuationMode）：模式变了
        // 才重建竖栏（init 与 onAttach 的重建不受影响）；本板是
        // FrameLayout 而非 BaseKeyboard，IKeyboard 的该方法无超类实现可调。
        val changed = mode != punctuationMode
        punctuationMode = mode
        if (changed) rebuildSidePanelSymbols()
    }
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
     * 再自动返回（板锁已删，提交恒返回）。
     */
    private fun dispatchBoardCommit(text: String) {
        keyActionListener?.onKeyAction(KeyboardAction.CommitAction(text))
        maybeAutoReturn()
    }

    private fun maybeAutoReturn() {
        if (BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.SYMBOLIC)) {
            keyActionListener?.onKeyAction(KeyboardAction.BoardCommitReturnAction)
        }
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
        // 2026-11-12：竖栏符号每附着重建（设置改动下次打开符号面板即生效）。
        rebuildSidePanelSymbols()
        requestLayout()
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
            // 2026-11-12：竖栏不再显示分类，无需 selectIndex。
            barSwitchBtn?.text = boardState.parentLabels.firstOrNull()
                ?: categorys.first().first.label
            rebuildSubStrip()
        }
        sidePanelKey.resetPosition()
    }
}
