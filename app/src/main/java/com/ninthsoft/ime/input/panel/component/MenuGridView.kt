package com.ninthsoft.ime.input.panel.component

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
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.input.panel.PanelAction
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

    // Each inner array is one horizontally scrollable page.
    private val items = arrayOf(
        arrayOf(
            MenuItem(
                context.getString(R.string.menu_emoji),
                R.drawable.ic_keyboard_emoticon,
                PanelAction.EmojiKeyboard
            ),
            MenuItem(
                context.getString(R.string.menu_symbol),
                R.drawable.ic_keyboard_symbol,
                PanelAction.SymbolKeyboard
            ),
            MenuItem(
                context.getString(R.string.model_prediction),
                R.drawable.ic_keyboard_lightbulb_on_outline,
                PanelAction.TogglePrediction
            ),
            MenuItem(
                context.getString(R.string.menu_candidate_comment),
                R.drawable.ic_keyboard_bubble,
                PanelAction.ToggleShowComment
            ),
            MenuItem(
                context.getString(R.string.menu_traditional_chinese),
                R.drawable.ic_keyboard_traditional_ch,
                PanelAction.ToggleTraditionalChinese
            ),
            MenuItem(
                context.getString(R.string.menu_emoji_input),
                R.drawable.ic_keyboard_sticker_emoji,
                PanelAction.ToggleEmojiInput
            ),
            MenuItem(
                context.getString(R.string.menu_ascii_mode),
                R.drawable.ic_keyboard_alphabet_a,
                PanelAction.ToggleAsciiMode
            ),
            MenuItem(
                context.getString(R.string.menu_voice),
                R.drawable.ic_keyboard_voice,
                PanelAction.ToggleVoice
            ),
        ),
        arrayOf(
            MenuItem(
                context.getString(R.string.menu_clipboard),
                R.drawable.ic_keyboard_clipboard,
                PanelAction.Clipboard
            ),
            MenuItem(
                context.getString(R.string.phrase_tab),
                R.drawable.ic_keyboard_star_david,
                PanelAction.CommonPhrases
            ),
            MenuItem(
                context.getString(R.string.menu_cursor),
                R.drawable.ic_keyboard_cursor_move,
                PanelAction.CursorMove
            ),
            MenuItem(
                context.getString(R.string.menu_schema),
                R.drawable.ic_keyboard_tune,
                PanelAction.SchemaSettings
            ),
            MenuItem(
                context.getString(R.string.menu_settings),
                R.drawable.ic_keyboard_setting,
                PanelAction.Settings
            ),
            MenuItem(
                context.getString(R.string.menu_theme),
                R.drawable.ic_keyboard_palette,
                PanelAction.Palette
            ),
            MenuItem(
                context.getString(R.string.menu_reload_engine),
                R.drawable.ic_keyboard_reload,
                PanelAction.ReloadEngine
            ),
            MenuItem(
                context.getString(R.string.menu_about),
                R.drawable.ic_keyboard_information_outline,
                PanelAction.About
            ),
        ),
    )

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

        pager.layoutParams = pager.layoutParams.apply { height = pageHeight }
        indicators.layoutParams = indicators.layoutParams.apply { height = indicatorHeight }
        for (pageIndex in 0 until pages.childCount) {
            val page = pages.getChildAt(pageIndex) as ViewGroup
            page.layoutParams = page.layoutParams.apply { width = totalWidth; height = pageHeight }
            for (rowIndex in 0 until page.childCount) {
                val row = page.getChildAt(rowIndex) as ViewGroup
                row.setPadding(
                    pad,
                    if (rowIndex == 0) pad else gap,
                    pad,
                    if (rowIndex == page.childCount - 1) pad else 0,
                )
                for (itemIndex in 0 until row.childCount) {
                    val item = row.getChildAt(itemIndex)
                    item.layoutParams = item.layoutParams.apply {
                        width = itemSize
                        height = itemSize
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
        PanelAction.ToggleAsciiMode -> R.drawable.ic_keyboard_alphabet_a
        else -> android.R.drawable.ic_menu_help
    }


    private fun itemIconColor(item: MenuItem): Int = itemIconColor(item.action)

    private fun itemIconColor(action: PanelAction?): Int =
        if (isToggleAction(action) && isEnabled(action!!)) colors.panel.toolbarActived else colors.panel.toolbarIcon

    private fun itemTextColor(item: MenuItem): Int = itemTextColor(item.action)

    private fun itemTextColor(action: PanelAction?): Int =
        if (isToggleAction(action) && isEnabled(action!!)) colors.panel.toolbarActived else colors.panel.toolbarText
}
