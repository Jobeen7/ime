package com.jobeen.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.jobeen.ime.R
import com.jobeen.ime.base.feedback.InputFeedbacks
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.panel.menu.KeyboardMenuEntries
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent

@SuppressLint("ViewConstructor")
class MenuGridView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    var onAction: ((PanelAction) -> Unit)? = null,
) : ComponentView(context, colors) {

    private data class MenuItem(
        val label: String,
        val icon: Int,
        val action: PanelAction,
    )

    // 菜单页入口（重点候选 8-10）：顺序与动作取 KeyboardMenuEntries
    // 注册表；标签与图标由本视图按 id 映射（新增入口只改注册表＋
    // 本映射，不动分页逻辑；页内行数自适应，6 项页为 4＋2 两行）。
    private val items: Array<Array<MenuItem>> = KeyboardMenuEntries.PAGES.map { page ->
        page.map { entry ->
            MenuItem(
                context.getString(labelResFor(entry.id)),
                iconResFor(entry.id),
                entry.action,
            )
        }.toTypedArray()
    }.toTypedArray()

    private fun labelResFor(id: String): Int = when (id) {
        "emoji" -> R.string.menu_emoji
        "symbol" -> R.string.menu_symbol
        "prediction" -> R.string.model_prediction
        "candidate_comment" -> R.string.menu_candidate_comment
        "traditional_chinese" -> R.string.menu_traditional_chinese
        "ascii_mode" -> R.string.menu_ascii_mode
        "emoji_input" -> R.string.menu_emoji_input
        "voice" -> R.string.menu_voice
        "clipboard" -> R.string.menu_clipboard
        "phrases" -> R.string.phrase_tab
        "cursor" -> R.string.menu_cursor
        "schema_settings" -> R.string.menu_schema
        "settings" -> R.string.menu_settings
        "theme" -> R.string.menu_theme
        "reload_engine" -> R.string.menu_reload_engine
        "about" -> R.string.menu_about
        "select_all" -> R.string.select_all
        "copy" -> R.string.copy
        "paste" -> R.string.paste
        "undo" -> R.string.menu_undo
        "redo" -> R.string.menu_redo
        "deploy_sync" -> R.string.menu_deploy_sync
        else -> R.string.menu_settings
    }

    private fun iconResFor(id: String): Int = when (id) {
        "emoji" -> R.drawable.ic_keyboard_emoticon
        "symbol" -> R.drawable.ic_keyboard_symbol
        "prediction" -> R.drawable.ic_keyboard_lightbulb_on_outline
        "candidate_comment" -> R.drawable.ic_keyboard_bubble
        "traditional_chinese" -> R.drawable.ic_keyboard_traditional_ch
        "ascii_mode" -> R.drawable.ic_keyboard_english_mode
        "emoji_input" -> R.drawable.ic_keyboard_sticker_emoji
        "voice" -> R.drawable.ic_keyboard_voice
        "clipboard" -> R.drawable.ic_keyboard_clipboard
        "phrases" -> R.drawable.ic_keyboard_star_david
        "cursor" -> R.drawable.ic_keyboard_cursor_move
        "schema_settings" -> R.drawable.ic_keyboard_tune
        "settings" -> R.drawable.ic_keyboard_setting
        "theme" -> R.drawable.ic_keyboard_palette
        "reload_engine" -> R.drawable.ic_keyboard_reload
        "about" -> R.drawable.ic_keyboard_information_outline
        "select_all" -> R.drawable.ic_toolbar_select_all
        "copy" -> R.drawable.ic_toolbar_copy
        "paste" -> R.drawable.ic_toolbar_paste
        "undo" -> R.drawable.ic_keyboard_undo
        "redo" -> R.drawable.ic_keyboard_redo
        "deploy_sync" -> R.drawable.ic_keyboard_clipboard_cloud
        else -> R.drawable.ic_keyboard_setting
    }

    private val columns = 4
    private val radius = dp(16).toFloat()
    private val iconSize = dp(28)
    private val indicatorSize = dp(6)
    private val indicatorGap = dp(6)

    var gap: Int = dp(10)
        private set

    var pad: Int = dp(16)
        private set

    var itemSize: Int = 0
        private set

    private val pager = object : HorizontalScrollView(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val handled = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                postDelayed({ snapToPage() }, 120L)
            }
            return handled
        }
    }.apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private val pages = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private val indicators = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        orientation = LinearLayout.HORIZONTAL
    }

    private var currentPage = 0

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        pager.addView(pages, ViewGroup.LayoutParams(matchParent, wrapContent))
        root.addView(
            pager,
            LinearLayout.LayoutParams(matchParent, 0).apply { weight = 1f },
        )
        root.addView(
            indicators,
            LinearLayout.LayoutParams(matchParent, dp(18)),
        )
        addView(root, LayoutParams(matchParent, matchParent))

        items.forEach { pageItems -> pages.addView(createPage(pageItems)) }
        repeat(items.size) { index ->
            indicators.addView(createIndicator(index))
        }
        updateIndicators()
        pager.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            val width = pager.width
            if (width > 0) {
                val page = ((scrollX + width / 2) / width).coerceIn(0, items.lastIndex)
                if (page != currentPage) {
                    currentPage = page
                    updateIndicators()
                }
            }
        }
    }

    private fun snapToPage() {
        val width = pager.width
        if (width <= 0 || items.isEmpty()) return
        val targetPage = ((pager.scrollX + width / 2) / width).coerceIn(0, items.lastIndex)
        pager.smoothScrollTo(targetPage * width, 0)
    }

    private fun createPage(pageItems: Array<MenuItem>): LinearLayout {
        val page = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val rows = (pageItems.size + columns - 1) / columns
        repeat(rows) { rowIndex ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(matchParent, wrapContent)
                setPadding(
                    pad,
                    if (rowIndex == 0) pad else gap,
                    pad,
                    if (rowIndex == rows - 1) pad else 0,
                )
            }
            repeat(columns) { colIndex ->
                val itemIndex = rowIndex * columns + colIndex
                if (itemIndex < pageItems.size) {
                    row.addView(
                        createItemView(pageItems[itemIndex]),
                        LinearLayout.LayoutParams(0, 0).apply {
                            if (colIndex < columns - 1) marginEnd = gap
                        },
                    )
                }
            }
            page.addView(row)
        }
        return page
    }

    private fun createIndicator(index: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(indicatorSize, indicatorSize).apply {
            if (index > 0) marginStart = indicatorGap
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
    }

    private fun updateIndicators() {
        for (index in 0 until indicators.childCount) {
            val indicator = indicators.getChildAt(index)
            val color = if (index == currentPage) {
                colors.panel.toolbarIcon
            } else {
                colors.panel.toolbarIcon and 0x66FFFFFF
            }
            (indicator.background as GradientDrawable).setColor(color)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val oldVisibility = visibility
        visibility = VISIBLE

        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val totalHeight = MeasureSpec.getSize(heightMeasureSpec)
        val indicatorHeight = dp(18)
        val pageHeight = (totalHeight - indicatorHeight).coerceAtLeast(0)
        val rows = items.maxOfOrNull { (it.size + columns - 1) / columns } ?: 0
        if (rows > 0) {
            val widthBased = (totalWidth - pad * 2 - gap * (columns - 1)) / columns
            val heightBased = (pageHeight - pad * 2 - gap * (rows - 1)) / rows
            itemSize = minOf(widthBased, heightBased).coerceAtLeast(0)
        }

        // 尺寸没变时不要重设 LayoutParams：原实现每次 measure 都给全部子 View
        // 赋值 layoutParams，会触发额外的 requestLayout 连锁
        pager.layoutParams.let { lp ->
            if (lp.height != pageHeight) {
                lp.height = pageHeight
                pager.layoutParams = lp
            }
        }
        indicators.layoutParams.let { lp ->
            if (lp.height != indicatorHeight) {
                lp.height = indicatorHeight
                indicators.layoutParams = lp
            }
        }
        for (pageIndex in 0 until pages.childCount) {
            val page = pages.getChildAt(pageIndex) as ViewGroup
            page.layoutParams.let { lp ->
                if (lp.width != totalWidth || lp.height != pageHeight) {
                    lp.width = totalWidth
                    lp.height = pageHeight
                    page.layoutParams = lp
                }
            }
            for (rowIndex in 0 until page.childCount) {
                val row = page.getChildAt(rowIndex) as ViewGroup
                val topPad = if (rowIndex == 0) pad else gap
                val bottomPad = if (rowIndex == page.childCount - 1) pad else 0
                if (row.paddingLeft != pad || row.paddingTop != topPad ||
                    row.paddingRight != pad || row.paddingBottom != bottomPad
                ) {
                    row.setPadding(pad, topPad, pad, bottomPad)
                }
                for (itemIndex in 0 until row.childCount) {
                    val item = row.getChildAt(itemIndex)
                    item.layoutParams.let { lp ->
                        if (lp.width != itemSize || lp.height != itemSize) {
                            lp.width = itemSize
                            lp.height = itemSize
                            item.layoutParams = lp
                        }
                    }
                }
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        visibility = oldVisibility
    }

    private fun createItemView(item: MenuItem): View {
        val bg = GradientDrawable().apply {
            setColor(this@MenuGridView.colors.keyBackground)
            cornerRadius = radius
        }
        val parentId = View.generateViewId()
        return constraintLayout {
            id = parentId
            tag = item.action
            background = bg
            isClickable = true
            isFocusable = true
            setOnClickListener {
                InputFeedbacks.hapticFeedback(this)
                onAction?.invoke(item.action)
            }

            val icon = imageView {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(item.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_help)
                imageTintList = ColorStateList.valueOf(itemIconColor(item))
            }
            add(icon, lParams(iconSize, iconSize) {
                centerHorizontally()
                topToTop = parentId
                bottomToBottom = parentId
                verticalBias = 0.30f
            })

            val label = textView {
                text = item.label
                textSize = 12f
                setTextColor(itemTextColor(item))
                typeface = Typeface.DEFAULT
                gravity = Gravity.CENTER
            }
            add(label, lParams(wrapContent, wrapContent) {
                centerHorizontally()
                topToTop = parentId
                bottomToBottom = parentId
                verticalBias = 0.85f
            })
        }
    }

    override fun refreshTheme(newColors: KeyboardColors.ColorScheme) {
        super.refreshTheme(newColors)
        setBackgroundColor(newColors.background)
        for (pageIndex in 0 until pages.childCount) {
            val page = pages.getChildAt(pageIndex) as ViewGroup
            for (rowIndex in 0 until page.childCount) {
                val row = page.getChildAt(rowIndex) as ViewGroup
                for (itemIndex in 0 until row.childCount) {
                    val item = row.getChildAt(itemIndex) as ViewGroup
                    (item.background as? GradientDrawable)?.setColor(newColors.keyBackground)
                    val action = item.tag as? PanelAction
                    for (childIndex in 0 until item.childCount) {
                        when (val child = item.getChildAt(childIndex)) {
                            is ImageView -> {
                                if (isToggleAction(action)) {
                                    child.setImageResource(toggleIcon(action!!))
                                }
                                child.imageTintList = ColorStateList.valueOf(itemIconColor(action))
                            }

                            is TextView -> child.setTextColor(itemTextColor(action))
                        }
                    }
                }
            }
        }
        updateIndicators()
    }

    fun refreshPredictionState() {
        for (pageIndex in 0 until pages.childCount) {
            val page = pages.getChildAt(pageIndex) as ViewGroup
            for (rowIndex in 0 until page.childCount) {
                val row = page.getChildAt(rowIndex) as ViewGroup
                for (itemIndex in 0 until row.childCount) {
                    val item = row.getChildAt(itemIndex) as ViewGroup
                    if (!isToggleAction(item.tag as? PanelAction)) continue
                    for (childIndex in 0 until item.childCount) {
                        when (val child = item.getChildAt(childIndex)) {
                            is ImageView -> {
                                child.setImageResource(toggleIcon(item.tag as PanelAction))
                                child.imageTintList =
                                    ColorStateList.valueOf(itemIconColor(item.tag as PanelAction))
                            }

                                is TextView -> child.setTextColor(itemTextColor(item.tag as PanelAction))
                        }
                    }
                }
            }
        }
    }

    private fun isToggleAction(action: PanelAction?): Boolean =
        action == PanelAction.TogglePrediction ||
            action == PanelAction.ToggleShowComment ||
            action == PanelAction.ToggleTraditionalChinese ||
            action == PanelAction.ToggleEmojiInput ||
            action == PanelAction.ToggleAsciiMode

    private fun isEnabled(action: PanelAction): Boolean = when (action) {
        PanelAction.TogglePrediction -> CandidateManager.isPredictionEnabled(context)
        PanelAction.ToggleShowComment -> CandidateManager.isShowComment(context)
        PanelAction.ToggleTraditionalChinese -> CandidateManager.isTraditionalChineseEnabled(context)
        PanelAction.ToggleEmojiInput -> CandidateManager.isEmojiEnabled(context)
        PanelAction.ToggleAsciiMode -> CandidateManager.isAsciiModeEnabled(context)
        else -> false
    }


    private fun toggleIcon(action: PanelAction): Int = when (action) {
        PanelAction.TogglePrediction ->  R.drawable.ic_keyboard_lightbulb_on_outline
        PanelAction.ToggleShowComment -> R.drawable.ic_keyboard_bubble
        PanelAction.ToggleTraditionalChinese -> R.drawable.ic_keyboard_traditional_ch
        PanelAction.ToggleEmojiInput -> R.drawable.ic_keyboard_sticker_emoji
        PanelAction.ToggleAsciiMode -> R.drawable.ic_keyboard_english_mode
        else -> android.R.drawable.ic_menu_help
    }


    private fun itemIconColor(item: MenuItem): Int = itemIconColor(item.action)

    private fun itemIconColor(action: PanelAction?): Int =
        if (isToggleAction(action) && isEnabled(action!!)) colors.panel.toolbarActived else colors.panel.toolbarIcon

    private fun itemTextColor(item: MenuItem): Int = itemTextColor(item.action)

    private fun itemTextColor(action: PanelAction?): Int =
        if (isToggleAction(action) && isEnabled(action!!)) colors.panel.toolbarActived else colors.panel.toolbarText
}
