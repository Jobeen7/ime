package com.jobeen.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.jobeen.ime.skin.runtime.SkinColorMode
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.input.panel.panelColorScheme
import com.jobeen.ime.skin.model.FloatingCandidateStyle
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider

/**
 * 浮动候选窗（WO52 1.4）：PopupWindow＋控制器无关的 Android 壳。
 *
 * - 内容复用 [CandidateGridView]（与内嵌栏同一渲染，不另写一套候选 UI；
 *   字形即内嵌栏的候选字形——Jime 皮肤模型无 `text.bubble` 节点，
 *   复用网格即复用候选字形）。
 * - 底部自带上一页/下一页行（皮肤键 floatingCandidatePagingButtonDisplayed
 *   门控、pageNo>0／!isLastPage 可见性由调用方经 [setPagingButtons] 下发）；
 *   网格自身的皮肤底部钮行不启用（不调 refreshTheme(context)，bottomRowSpec
 *   恒 null），不与自带翻页行重复。
 * - 宽 = 键盘宽；高按内容行数现算（至多 5 行×32dp＋翻页行 40dp，
 *   411dp 真机宽下两钮各半宽，放得下）。
 * - PopupWindow 沿用本仓 KeyPreviewPopup 范式（focusable=false，
 *   touchable=true：点候选可点按，键盘焦点仍在编辑框）。
 */
@SuppressLint("ViewConstructor")
class FloatingCandidateWindow(
    context: Context,
    var onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    var onSidePanelAction: ((KeyboardAction) -> Unit)? = null,
    var onPageCandidates: ((backward: Boolean) -> Unit)? = null,
    /** C-L-21：窗体被解散时回调（点窗外／系统收起），调用方清 rect。 */
    var onDismissed: (() -> Unit)? = null,
) {

    /** 翻页行高（dp）：两钮一排。 */
    companion object {
        const val PAGING_ROW_HEIGHT_DP = 40
        const val MAX_ROWS = 5
    }

    private val density = context.resources.displayMetrics.density
    private val colors = panelColorScheme(context, null)
    // 注意：GradientDrawable 自带 colors 属性（API 29），按钮背景的
    // apply 块内写 colors.keyBackground 会被遮成 IntArray——先取局部量。
    private val keyBackgroundColor: Int = colors.keyBackground
    private val keyTextColor: Int = colors.keyText

    private val grid = CandidateGridView(
        context = context,
        colors = colors,
        onCandidateSelected = { onCandidateSelected?.invoke(it) },
        onSidePanelAction = { onSidePanelAction?.invoke(it) },
    ).apply {
        // 根因（.2/.3 不能打字）：ComponentView 构造即 GONE，浮动窗
        // 路径只调 grid.updateCandidates、从不调 grid.show()，网格永
        // 为 GONE——LinearLayout 量/排都跳过 GONE 子项，gridCanvas
        // .width 恒 0，recomputeLayout() 每帧清空 positions，
        // contentRowCount() 恒 0，窗只剩翻页行。内嵌栏走 show() 才
        // 可见；浮动窗的显隐由 PopupWindow 管，网格本身必须常
        // VISIBLE 才能参与布局。
        visibility = View.VISIBLE
        // 浮动窗内翻页钮门改皮肤键（网格自身底部钮行未启用，此覆写只
        // 防将来某路又把皮肤钮行带进来时门错）。
        pagingButtonsEnabledOverride = currentStyle().pagingButtonDisplayed
    }

    private val prevButton = TextView(context).apply {
        text = context.getString(com.jobeen.ime.R.string.floating_candidate_prev)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setIncludeFontPadding(false)
        setTextColor(keyTextColor)
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            setColor(keyBackgroundColor)
            cornerRadius = 6f * density
        }
        setOnClickListener { onPageCandidates?.invoke(true) }
    }

    private val nextButton = TextView(context).apply {
        text = context.getString(com.jobeen.ime.R.string.floating_candidate_next)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setIncludeFontPadding(false)
        setTextColor(keyTextColor)
        isClickable = true
        isFocusable = false
        background = GradientDrawable().apply {
            setColor(keyBackgroundColor)
            cornerRadius = 6f * density
        }
        setOnClickListener { onPageCandidates?.invoke(false) }
    }

    private val pagingRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        addView(prevButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(nextButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
    }

    private val containerBg = GradientDrawable()

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = containerBg
        addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            pagingRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (PAGING_ROW_HEIGHT_DP * density).toInt(),
            ),
        )
    }

    private var popupWindow: PopupWindow? = null
    private var lastList: List<EngineMessage.Candidate> = emptyList()
    private var lastRevision: Long = 0L

    val isShowing: Boolean get() = popupWindow?.isShowing == true

    /** 当前皮肤浮动窗样式（快照缺失时取缺省）。 */
    fun currentStyle(): FloatingCandidateStyle =
        SkinSnapshotProvider.peek()?.floatingCandidate ?: FloatingCandidateStyle()

    /** 刷新容器背景（深浅＋圆角，皮肤键）＋当前皮肤配色。 */
    fun refreshStyle() {
        val style = currentStyle()
        val bg = if (SkinColorMode.isDark(container.context)) {
            style.backgroundColorDark
        } else {
            style.backgroundColorLight
        }
        containerBg.setColor(bg)
        containerBg.cornerRadius = style.cornerRadiusDp * density
        // C-L-20：配色每次重读当前皮肤（panelColorScheme），不用
        // 构造时快照的颜色——切皮肤后浮动窗配色跟上。只走颜色重载
        // （grid.refreshTheme(colors)），不碰网格底部钮行（恒 null）。
        val colors = panelColorScheme(container.context, null)
        grid.refreshTheme(colors)
        prevButton.setTextColor(colors.keyText)
        nextButton.setTextColor(colors.keyText)
        (prevButton.background as? GradientDrawable)?.setColor(colors.keyBackground)
        (nextButton.background as? GradientDrawable)?.setColor(colors.keyBackground)
        grid.pagingButtonsEnabledOverride = style.pagingButtonDisplayed
    }

    /** 候选整表替换（native 模式）；revision 0 = 未版本化恒放行。 */
    fun setCandidates(list: List<EngineMessage.Candidate>, revision: Long = 0L) {
        lastList = list
        lastRevision = revision
        grid.updateCandidates(list, revision)
    }

    /** 追加页并入（浮动窗只在 native 模式出现、正常无追加；有则合并）。 */
    fun appendCandidates(list: List<EngineMessage.Candidate>) {
        lastList = lastList + list
        grid.updateCandidates(lastList, 0L)
    }

    /** T9 拼音侧栏（与内嵌网格同一路）。 */
    fun onPossibleCandidatePinYin(data: List<com.jobeen.ime.engine.data.CandidatePinYin>) {
        grid.onPossibleCandidatePinYin(data)
    }

    /** 翻页钮可见性（控制器算好下发；两钮全隐时整行 GONE）。 */
    fun setPagingButtons(prevVisible: Boolean, nextVisible: Boolean) {
        prevButton.visibility = if (prevVisible) View.VISIBLE else View.GONE
        nextButton.visibility = if (nextVisible) View.VISIBLE else View.GONE
        pagingRow.visibility =
            if (prevVisible || nextVisible) View.VISIBLE else View.GONE
    }

    private fun pagingRowHeightPx(): Int =
        if (pagingRow.visibility == View.VISIBLE) {
            (PAGING_ROW_HEIGHT_DP * density).toInt()
        } else {
            0
        }

    /**
     * 按当前列表算窗高（离线量＋排后重算行布局）：行数取内容行与
     * [MAX_ROWS] 的较小值（多出行内滚动），＋翻页行高。
     */
    fun measureHeight(widthPx: Int): Int {
        val maxHeight = MAX_ROWS * grid.naturalRowHeightPx() + pagingRowHeightPx()
        container.measure(
            MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
        )
        container.layout(0, 0, widthPx, maxHeight)
        // 离线排过后画布已有真实宽度：无门重算行布局（重算的是窗
        // 自身已存的最新表，不是新帧；首次 setCandidates 时宽度
        // 为 0 导致行数算空，重算必须落地，.2 诊断机制 B）。
        grid.refreshLayout()
        val rows = grid.contentRowCount().coerceIn(1, MAX_ROWS)
        if (grid.contentRowCount() == 0 && lastList.isNotEmpty()) {
            // 可观测性（.2 诊断 A/B 判据）：表非空但行数为 0——行
            // 布局没算出来；与 candidate_grid 门日志对照定机制。
            DiagLog.w(
                "floating_candidate", "measure_height_empty_rows",
                mapOf("size" to lastList.size, "lastRevision" to lastRevision),
            )
        }
        return rows * grid.naturalRowHeightPx() + pagingRowHeightPx()
    }

    /** 显示（已显示时只改位置尺寸）。 */
    fun show(anchor: View, x: Int, y: Int, width: Int, height: Int) {
        refreshStyle()
        val popup = popupWindow ?: PopupWindow(
            container,
            width,
            height,
            false,
        ).apply {
            isTouchable = true
            isOutsideTouchable = false
            elevation = 8f * density
            // C-L-21：窗体解散（点窗外／系统收起）即回调调用方清 rect，
            // 否则 rect 残留、可触区一直按浮动窗算。
            setOnDismissListener { onDismissed?.invoke() }
        }.also { popupWindow = it }
        if (popup.isShowing) {
            popup.update(x, y, width, height)
        } else {
            popup.width = width
            popup.height = height
            popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
        }
    }

    /** 已显示时改位置尺寸。 */
    fun updatePlacement(x: Int, y: Int, width: Int, height: Int) {
        popupWindow?.takeIf { it.isShowing }?.update(x, y, width, height)
    }

    fun dismiss() {
        // 会话边界：常驻网格的代次门复位——下一组字会话的首帧不再
        // 被旧会话顶高的代次拦住（引擎重建后 requestId 从头计；
        // .2 诊断机制 A）。调用方 dismissFloatingWindow 经 onDismissed
        // 同样走到此处，系统解散亦覆盖。
        grid.resetRevisionGate()
        popupWindow?.dismiss()
    }
}
