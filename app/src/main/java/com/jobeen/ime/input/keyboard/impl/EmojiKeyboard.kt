package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.jobeen.ime.R
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.data.PunctuationMode
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
import com.jobeen.ime.skin.symbol.EmojiBoardBuilder
import com.jobeen.ime.skin.symbol.EmojiCustomStore
import com.jobeen.ime.skin.symbol.SharedPrefsEmojiCustomStore
import com.jobeen.ime.skin.symbol.EmojiSearchIndex
import com.jobeen.ime.skin.symbol.RecentEmojiOps
import com.jobeen.ime.skin.symbol.RecentEmojiStore
import com.jobeen.ime.skin.symbol.SharedPrefsRecentEmojiStore
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbols
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.input.keyboard.key.TextKeyView
import splitties.dimensions.dp
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

@SuppressLint("ViewConstructor")
class EmojiKeyboard(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
) : FrameLayout(context), ISubCollectionPageable {

    companion object {
        const val NAME = "Emoji"
        private const val COLUMNS = 5
        private const val ROWS = 5

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
            if (action is KeyboardAction.CommitAction) {
                recordRecent(action.text)
                maybeAutoReturn()
            }
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
     * 二级面板数据源：用户自定义分组（7.4）＞ 皮肤二级表，否则既有一级表
     * 转二级（每父仅一“全部”子类，此时子条隐藏、行为与旧板一致）；
     * 最近使用（7.1）拼到首组前面（见 [EmojiBoardBuilder]）。
     * 板在每次附着（reset）时重建：最近使用／自定义分组改了，
     * 下次打开面板即跟变。
     */
    private val recentStore: RecentEmojiStore = SharedPrefsRecentEmojiStore(context)
    private val customStore: EmojiCustomStore = SharedPrefsEmojiCustomStore(context)

    private var twoStageSymbols: SubClassifiedSymbols =
        EmojiBoardBuilder.build(recentStore.load(), SkinSnapshotProvider.peek()?.subClassifiedSymbols)
    private var boardState = SubClassifiedBoardState(twoStageSymbols)

    /**
     * 搜索（7.2）：顶栏 EditText＋过滤。索引首次输入时才从资产加载
     * （531KB JSON，构造期不读）；查询非空时网格只喂搜索结果
     * （分类／子条暂隐），清空即回分类浏览。
     */
    private var searchQuery: String = ""
    private var searchResults: List<SymbolItem> = emptyList()
    private var searchIndex: List<com.jobeen.ime.skin.symbol.EmojiIndexEntry>? = null

    private val searchView = EditText(context).apply {
        hint = context.getString(R.string.emoji_search_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextSize(15f)
        setTextColor(colors.keyText)
        setHintTextColor(colors.altText)
        setBackgroundColor(colors.specialKeyBackground)
        val pad = context.dp(12)
        setPadding(pad, 0, pad, 0)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySearchQuery(s?.toString().orEmpty())
            }
        })
    }

    private fun applySearchQuery(query: String) {
        searchQuery = query
        if (query.isBlank()) {
            searchResults = emptyList()
            refreshGrid()
            rebuildSubStrip()
            return
        }
        if (searchIndex == null) {
            searchIndex = EmojiSearchIndex.loadFromAssets(context)
        }
        searchResults = EmojiSearchIndex.search(searchIndex.orEmpty(), query)
            .map { SymbolItem.Plain(it) }
        refreshGrid()
        subStripView.visibility = View.GONE
    }

    private fun clearSearch() {
        if (searchQuery.isNotEmpty()) {
            searchView.setText("")
        } else {
            searchQuery = ""
            searchResults = emptyList()
        }
    }

    /** 搜索框是否聚焦（B-EM-2 改道门：只在聚焦时接字符 commit）。 */
    val isSearchFocused: Boolean get() = searchView.hasFocus()

    /**
     * B-EM-2：字符 commit 改道进搜索框（供 KawaiiPanel.interceptCommit
     * 经 KeyboardWindowView 接线调用，与剪贴板搜索态同构）。
     * 未聚焦返回 false（调用方继续正常上屏）；聚焦时把清过的文本
     * 插进光标处（TextWatcher 随即过滤），返回 true。
     */
    fun appendSearchText(text: String): Boolean {
        if (!searchView.hasFocus()) return false
        val current = searchView.text?.toString().orEmpty()
        val cursor = searchView.selectionStart.coerceIn(0, current.length)
        val (next, nextCursor) = insertIntoSearchQuery(current, cursor, text)
        // setText 会触发 TextWatcher → applySearchQuery；光标复位到插入点后。
        searchView.setText(next)
        searchView.setSelection(nextCursor.coerceIn(0, next.length))
        return true
    }

    /**
     * WO31 连续竖滑：网格一次喂当前子类的全部项（currentItems），
     * 不再按 25 个一页切 visibleItems。GridKeyboardView 按
     * 5 列 × 5 行视口排布，超出部分由竖向滚动呈现；切分类／子
     * 类／reset 时 setItems 把滚动归零（回首屏，与旧 page=0 同效）。
     * boardState 的 page 字段自此在 emoji 板上恒为 0，不再驱动网格。
     */
    private fun refreshGrid() {
        val items = if (searchQuery.isNotBlank()) searchResults else boardState.currentItems
        gridView.setItems(items.map { keyDef(it) })
    }

    /** 二级子条（48dp 横条，网格之上；单子类时隐藏）。 */
    private val subStripView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(colors.specialKeyBackground)
        visibility = View.GONE
    }

    private var sidePanelItemDefs: List<KeyDef> = emptyList()

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

        sidePanelKey.setOnItemActionListener { action ->
            if (action is KeyboardAction.CommitAction && boardState.parentLabels.any { it == action.text }) {
                applyCategory(action.text)
            } else {
                keyActionListener?.onKeyAction(action)
            }
        }
        rebuildBoard()

        addView(searchView, lParams(matchParent, 0))
        addView(sidePanelKey, lParams(0, matchParent))
        addView(sideReturnBtn, lParams(0, matchParent))
        addView(gridView, lParams(0, matchParent))
        addView(subStripView, lParams(matchParent, 0))
        addView(rippleView, LayoutParams(matchParent, matchParent))
    }

    /** 板重建（最近使用／自定义分组／皮肤表任一变了，下次附着即跟变）。 */
    private fun rebuildBoard() {
        twoStageSymbols = EmojiBoardBuilder.build(
            recentStore.load(),
            SkinSnapshotProvider.peek()?.subClassifiedSymbols,
            // 用户自定义分组（7.4）：有存档时面板改用自定义分组。
            customGroups = customStore.load()?.groups?.map { it.label to it.symbols },
        )
        boardState = SubClassifiedBoardState(twoStageSymbols)
        boardState.selectParent(0)
        rebuildSidePanel()
        refreshGrid()
        sidePanelKey.selectIndex(0)
        rebuildSubStrip()
    }

    /** 侧栏分类格重建：父类标签即分类表顺序，只登记不覆写。 */
    private fun rebuildSidePanel() {
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
    }

    /**
     * 最近使用记一次（7.1）：只记板内当前子类里真实提交的 Emoji
     * （分类切换的提交走侧栏、不经过此处；皮肤动作项不记）。
     */
    private fun recordRecent(text: String) {
        val effective = if (searchQuery.isNotBlank()) searchResults else boardState.currentItems
        val hit = effective.any {
            val resolved = SymbolItemActionResolver.resolve(it)
            resolved is KeyboardAction.CommitAction && resolved.text == text
        }
        if (hit) {
            recentStore.save(RecentEmojiOps.record(recentStore.load(), text))
        }
    }

    private fun applyCategory(label: String) {
        clearSearch()
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
     * 接口保留（ISubCollectionPageable 契约，符号板仍用）。
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
        // 搜索条 48dp 置顶（411dp 真机宽：整行一个输入框＋左右 12dp 内边距，放得下）。
        val searchH = context.dp(48)
        // 子条占顶 48dp（隐藏时 0），其余内容整体下移并压缩。
        val subH = if (subStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val contentH = th - searchH - subH
        // WO48：侧栏 4/5、返回区 1/5，与符号页同口径（sidePanelGeometry）。
        val (sidePanelH, returnRegionH) = SymbolKeyboard.sidePanelGeometry(contentH)
        // 侧栏底区整段归返回钮（板锁已删）。
        searchView.measure(mES(tw, MeasureSpec.EXACTLY), mES(searchH, MeasureSpec.EXACTLY))
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
        val searchH = context.dp(48)
        val subH = if (subStripView.visibility == View.VISIBLE) context.dp(48) else 0
        val top = searchH + subH
        val contentH = (b - t) - searchH - subH
        val sidePanelH = SymbolKeyboard.sidePanelGeometry(contentH).first
        searchView.layout(0, 0, r - l, searchH)
        subStripView.layout(0, searchH, r - l, top)
        sidePanelKey.layout(0, top, sw, top + sidePanelH)
        sideReturnBtn.layout(0, top + sidePanelH, sw, top + contentH)
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
        clearSearch()
        rebuildBoard()
        sidePanelKey.resetPosition()
    }
}

/**
 * B-EM-2：搜索框插字纯函数（与剪贴板搜索态的清换行口径同构）：
 * 换行不进查询，其余文本插进光标处；返回（新查询，光标新位）。
 * 光标越界时夹到合法范围。
 */
internal fun insertIntoSearchQuery(query: String, cursor: Int, text: String): Pair<String, Int> {
    val clean = text.replace("\n", "").replace("\r", "")
    if (clean.isEmpty()) return query to cursor.coerceIn(0, query.length)
    val c = cursor.coerceIn(0, query.length)
    val next = query.substring(0, c) + clean + query.substring(c)
    return next to (c + clean.length)
}
