package com.jobeen.ime.engine

/**
 * 候选分页双模式（WO52 1.1）：纯决策，不碰引擎／UI。
 *
 * 两档：
 * - SWIPE（滑动翻页，默认）：候选按批量追加（既有
 *   loadMoreCandidates，48 条/批、CAP 200，不改）；高亮只在
 *   UI 本地（引擎侧不调 highlight），下标是追加后的全局下标。
 * - NATIVE（native 分页）：调 changeCandidatePage；高亮进
 *   引擎（页内下标），下标是当前页内下标。
 *
 * 浮动候选窗激活时强制 NATIVE（与 1.4 联动；1.4 未接入前
 * 该分支恒 false，由调用方传 floatingActive）。
 */
enum class CandidatePagingMode { SWIPE, NATIVE }

object CandidatePagingPolicy {

    /** 模式分派：浮动窗激活一律 native，否则跟开关。 */
    fun resolveMode(swipePagingEnabled: Boolean, floatingActive: Boolean): CandidatePagingMode =
        if (!swipePagingEnabled || floatingActive) CandidatePagingMode.NATIVE
        else CandidatePagingMode.SWIPE

    /**
     * 高亮下标口径（元书 highlightCandidate(session, i, !滑动模式)
     * 的第三参）：true = 页内下标（native 模式，进引擎调
     * highlight_candidate_on_current_page）；false = 全局下标
     * （滑动模式，追加列表的全局下标）。
     */
    fun highlightOnCurrentPage(swipeMode: Boolean): Boolean = !swipeMode

    /**
     * 滑动模式页号：批量追加成功页号+1；取空（无更多候选）则
     * 回到第 0 页。页号只作翻页钮可见性与 native 模式的页码
     * 记账，不改批量路的 48 条/批与 CAP 200。
     */
    fun nextSwipePage(page: Int, batchEmpty: Boolean): Int =
        if (batchEmpty) 0 else page + 1

    /**
     * native 模式页号：翻页成功前后移一页（下限 0）；翻页
     * 失败（已到尽头、无更多页）则回到第 0 页。
     */
    fun nextNativePage(page: Int, backward: Boolean, moved: Boolean): Int {
        if (!moved) return 0
        return if (backward) (page - 1).coerceAtLeast(0) else page + 1
    }

    /**
     * 上一页/下一页按钮可见性（1.1＋1.4 共用口径）：
     * - 门：浮动窗内看皮肤键 floatingCandidatePagingButtonDisplayed，
     *   内嵌栏看设置开关 isManualPagingButtonEnabled；门关则两钮全隐。
     * - 上一页：pageNo > 0 才可见；下一页：非末页才可见。
     * @return first = 上一页可见，second = 下一页可见。
     */
    fun pagingButtonsVisible(
        pagingButtonsEnabled: Boolean,
        pageNo: Int,
        isLastPage: Boolean,
    ): Pair<Boolean, Boolean> {
        if (!pagingButtonsEnabled) return false to false
        return (pageNo > 0) to !isLastPage
    }
}
