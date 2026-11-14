package com.jobeen.ime.input.panel.component

import com.jobeen.ime.engine.CandidatePagingMode
import com.jobeen.ime.engine.CandidatePagingPolicy
import com.jobeen.ime.skin.model.FloatingCandidateStyle

/**
 * 浮动候选窗控制器（WO52 1.4）：纯状态＋决策，不碰 View／PopupWindow，
 * 可单测。窗口本体见 [FloatingCandidateWindow]，光标锚点与候选路由
 * 见 KeyboardWindowView。
 *
 * 激活条件（单子未明写触发器，按 12-内嵌联动定）：内嵌输入模式开
 * （组字串已进编辑框、候选栏不再 inline 显示）且当前有候选。
 * 未开内嵌时浮动窗恒不激活，行为与旧版一致。
 */
class FloatingCandidateController {

    /** 光标锚点（屏幕坐标，int）：null = 光标取不到（走回退位置）。 */
    data class AnchorRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** 当前皮肤的浮动窗样式（快照缺失时取缺省）。 */
    var style: FloatingCandidateStyle = FloatingCandidateStyle()

    /** 光标更新通道是否可用（requestCursorUpdates 成功与否）。 */
    var cursorUpdatesAvailable: Boolean = false

    /** 最新光标锚点；null = 取不到（失败回退）。 */
    var cursorRect: AnchorRect? = null
        private set

    /** 当前是否有候选（空帧即无内容 → 关闭浮动窗）。 */
    var hasCandidates: Boolean = false
        private set

    /** 当前页号／末页／模式（翻页钮可见性与强制 native 分支用）。 */
    var pageNo: Int = 0
        private set
    var isLastPage: Boolean = false
        private set
    var pagingMode: CandidatePagingMode = CandidatePagingMode.SWIPE
        private set

    /** 是否激活：内嵌开且有候选。激活时引擎强制 native 分页（1.1）。 */
    fun shouldShow(embeddedModeEnabled: Boolean): Boolean =
        embeddedModeEnabled && hasCandidates

    /** 候选帧到达：记有无内容与页态；空帧即无内容（调用方关闭浮动窗）。 */
    fun updateCandidates(
        isEmpty: Boolean,
        pageNo: Int,
        isLastPage: Boolean,
        mode: CandidatePagingMode,
    ) {
        hasCandidates = !isEmpty
        this.pageNo = pageNo
        this.isLastPage = isLastPage
        this.pagingMode = mode
    }

    /** 光标锚点到达（onUpdateCursorAnchorInfo 换算后）。 */
    fun updateCursorAnchor(rect: AnchorRect?) {
        cursorRect = rect
    }

    /** requestCursorUpdates 失败：光标取不到，走回退位置。 */
    fun markCursorUnavailable() {
        cursorUpdatesAvailable = false
        cursorRect = null
    }

    /** 浮动窗关闭时复位候选态（页号／末页随下一帧重算）。 */
    fun onHidden() {
        hasCandidates = false
    }

    /**
     * 上一页/下一页按钮可见性（1.1＋1.4 共用口径）：门是皮肤键
     * floatingCandidatePagingButtonDisplayed，上一页 pageNo>0、
     * 下一页 !isLastPage。
     */
    fun pagingButtonsVisible(): Pair<Boolean, Boolean> =
        CandidatePagingPolicy.pagingButtonsVisible(
            pagingButtonsEnabled = style.pagingButtonDisplayed,
            pageNo = pageNo,
            isLastPage = isLastPage,
        )

    /** 按样式位置档算出摆放（光标 rect 为 null 时 resolve 内走回退）。 */
    fun resolvePlacement(
        keyboardLeft: Int,
        keyboardTop: Int,
        keyboardRight: Int,
        windowWidth: Int,
        windowHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
        gapPx: Int,
    ): FloatingCandidatePositioning.Placement {
        val cursor = cursorRect
        return FloatingCandidatePositioning.resolve(
            preference = FloatingCandidatePosition.parse(style.position),
            fallback = FloatingCandidatePosition.parse(style.fallbackPosition),
            cursorLeft = cursor?.left,
            cursorTop = cursor?.top,
            cursorRight = cursor?.right,
            cursorBottom = cursor?.bottom,
            keyboardLeft = keyboardLeft,
            keyboardTop = keyboardTop,
            keyboardRight = keyboardRight,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            gapPx = gapPx,
        )
    }
}
