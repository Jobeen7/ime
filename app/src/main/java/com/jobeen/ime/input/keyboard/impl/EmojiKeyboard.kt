package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
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
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbols
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.input.keyboard.key.TextKeyView
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
        // WO31 连续竖滑：竖向拦截只在内容可滚且竖向占优时才生效
        // （短分类 maxScroll=0 时不拦截，点按不受影响，不变死手势）。
        verticalScrollOnlyWhenScrollable = true
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

    /**
     * WO31 连续竖滑：网格一次喂当前子类的全部项（currentItems），
     * 不再按 25 个一页切 visibleItems。GridKeyboardView 按
     * 5 列 × 5 行视口排布，超出部分由竖向滚动呈现；切分类／子
     * 类／reset 时 setItems 把滚动归零（回首屏，与旧 page=0 同效）。
     * boardState 的 page 字段自此在 emoji 板上恒为 0，不再驱动网格。
     */
    private fun refreshGrid() {
        gridView.setItems(boardState.currentItems.map { keyDef(it) })
    }

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
        refreshGrid()
        sidePanelKey.selectIndex(index)
        rebuildSubStrip()
    }

    /**
     * WO31 连续竖滑，无分页可翻：窗口层 #subCollectionPageUp/Down
     * 在 emoji 板上恒为空操作（不换页、不换分类、不下发引擎）。
     * 接口保留（ISubCollectionPageable 契约，颜文字／符号板仍用）。
     */
    override fun pageSubCollection(forward: Boolean): Boolean = false

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
                        refreshGrid()
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
        // WO48：侧栏 4/5、返回区 1/5，与符号页同口径（sidePanelGeometry）。
        val (sidePanelH, returnRegionH) = SymbolKeyboard.sidePanelGeometry(contentH)
        // 侧栏底区整段归返回钮（板锁已删）。
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
        val sidePanelH = SymbolKeyboard.sidePanelGeometry(contentH).first
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
        refreshGrid()
        sidePanelKey.selectIndex(0)
        rebuildSubStrip()
        sidePanelKey.resetPosition()
    }
}
