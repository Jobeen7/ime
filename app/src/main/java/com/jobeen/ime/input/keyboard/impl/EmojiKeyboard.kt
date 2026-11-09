package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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
import com.jobeen.ime.input.keyboard.key.KeyboardRippleView
import com.jobeen.ime.input.keyboard.key.SidePanelKeyView
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.symbol.BoardAutoReturn
import com.jobeen.ime.skin.symbol.KaomojiSwipe
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbols
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.input.keyboard.key.TextKeyView
import kotlin.math.abs
import splitties.dimensions.dp
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import com.jobeen.ime.data.Symbol.Category

@SuppressLint("ViewConstructor")
class EmojiKeyboard(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
) : FrameLayout(context), ISubCollectionPageable {

    companion object {
        const val NAME = "Emoji"
        private const val COLUMNS = 5
        private const val ROWS = 5
        private const val SWIPE_MIN_DISTANCE_DP = 48
        private var categorys: List<Pair<Category, Array<String>>> = Symbol.Emoji

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
            textStyle = Typeface.BOLD,
            percentWidth = 1f,
            variant = KeyDef.Appearance.Variant.Alternative,
        ),
        behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ResumeAction)),
        // 功能锚点（定稿五.6）：只登记不覆写
        id = KeyIds.Emoji.EMOJI_RETURN,
    )

    private val sideReturnBtn =
        TextKeyView(context, colors, returnKeyDef.appearance as KeyDef.Appearance.Text)

    /**
     * 二级面板数据源：皮肤定义二级表时用皮肤表，否则既有一级表
     * 转二级（每父仅一“全部”子类，此时子条隐藏、行为与旧板一致）。
     */
    private val twoStageSymbols: SubClassifiedSymbols =
        SkinSnapshotProvider.peek()?.subClassifiedSymbols
            ?: SubClassifiedSymbols.fromSingleLevel(categorys)
    private val boardState = SubClassifiedBoardState(twoStageSymbols)

    /** 横滑翻页（W13-1）：与颜文字板同口径，见 onInterceptTouchEvent。 */
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val swipeMinDistancePx = context.dp(SWIPE_MIN_DISTANCE_DP).toFloat()
    private var downX = 0f
    private var downY = 0f
    private var swipingHorizontally = false

    /** 二级子条（48dp 横条，网格之上；单子类时隐藏）。 */
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

        isClickable = true

        sidePanelItemDefs = boardState.parentLabels.mapIndexed { index, label ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = label, textSize = 15f, percentWidth = 0.5f,
                    margin = false, variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(label))),
                // 功能锚点（定稿五.6）：序号 = 分类表顺序，只登记不覆写
                id = KeyIds.Emoji.CATS.getOrNull(index),
            )
        }
        sidePanelKey.updateItems(sidePanelItemDefs)

        sidePanelKey.setOnItemActionListener { action ->
            if (action is KeyboardAction.CommitAction && boardState.parentLabels.any { it == action.text }) {
                applyCategory(action.text)
            } else {
                keyActionListener?.onKeyAction(action)
            }
        }
        rebuildSubStrip()

        addView(sidePanelKey, lParams(0, matchParent))
        addView(sideReturnBtn, lParams(0, matchParent))
        addView(gridView, lParams(0, matchParent))
        addView(subStripView, lParams(matchParent, 0))
        addView(rippleView, LayoutParams(matchParent, matchParent))
    }

    private fun applyCategory(label: String) {
        val index = boardState.parentLabels.indexOf(label)
        if (index < 0) return
        boardState.selectParent(index)
        gridView.setItems(boardState.visibleItems.map { keyDef(it) })
        sidePanelKey.selectIndex(index)
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
     * 左右滑翻页（W13-1，回归修复）：左滑下一页、右滑上一页，
     * 到端停、不循环；只换网格，父类／子类选中与子条不动
     * （与按键翻页 pageSubCollection 同一入口）。
     * 拦截口径与颜文字板一致：横向位移超 touchSlop 且横向
     * 占优时拦截（子键收 ACTION_CANCEL，点按不触发）；竖向
     * 位移归网格滚动，不拦截；抬手在 onTouchEvent 里按位移
     * 判定方向（复用 KaomojiSwipe.directionFor）。
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
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
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
                        pageSubCollection(direction == KaomojiSwipe.Direction.NEXT)
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        val sw = (tw * 0.15f).toInt()
        val gw = tw - sw
        // 子条占顶 48dp（隐藏时 0），其余内容整体下移并压缩。
        val subH = if (subStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val contentH = th - subH
        val sidePanelH = contentH * 3 / 4
        // 侧栏底区整段归返回钮（板锁已删）。
        val returnRegionH = contentH - sidePanelH
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(sidePanelH, MeasureSpec.EXACTLY))
        sideReturnBtn.measure(
            mES(sw, MeasureSpec.EXACTLY), mES(returnRegionH, MeasureSpec.EXACTLY)
        )
        gridView.measure(mES(gw, MeasureSpec.EXACTLY), mES(contentH, MeasureSpec.EXACTLY))
        subStripView.measure(mES(tw, MeasureSpec.EXACTLY), mES(subH, MeasureSpec.EXACTLY))
        // 波纹层是全屏覆盖层：此前从未被 measure/layout，宽高恒为 0，
        // startRipple 在尺寸守卫处直接返回、波纹从不显示（L1）
        rippleView.measure(mES(tw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        setMeasuredDimension(tw, th)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sw = ((r - l) * 0.15f).toInt()
        val subH = if (subStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val contentH = (b - t) - subH
        val sidePanelH = contentH * 3 / 4
        subStripView.layout(0, 0, r - l, subH)
        sidePanelKey.layout(0, subH, sw, subH + sidePanelH)
        sideReturnBtn.layout(0, subH + sidePanelH, sw, subH + contentH)
        gridView.layout(sw, subH, r - l, subH + contentH)
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
        rippleView.visibility = if (enabled) VISIBLE else INVISIBLE
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

    /** 板内提交后自动返回：板锁已删，提交恒返回（未锁定时缺省）。 */
    private fun maybeAutoReturn() {
        if (BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.EMOJIS)) {
            keyActionListener?.onKeyAction(KeyboardAction.BoardCommitReturnAction)
        }
    }

    override fun onAttach() {
        reset()
    }

    override fun onDetach() {
        rippleView.cancelRipple()
    }

    fun reset() {
        boardState.selectParent(0)
        gridView.setItems(boardState.visibleItems.map { keyDef(it) })
        sidePanelKey.selectIndex(0)
        rebuildSubStrip()
        sidePanelKey.resetPosition()
    }
}
