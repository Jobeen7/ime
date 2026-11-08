// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import kotlin.math.abs

/**
 * 颜文字板左右滑切分类的纯判定（H2-7，D17 颜文字面板左右滑切换分类）：
 *
 * - 左滑（dx < 0，内容向左走）→ 下一分类；右滑（dx > 0）→ 上一分类，
 *   与分页器的前进／后退方向一致。
 * - 横向须占优（|dx| > |dy|）且达到最小距离，否则不判定——
 *   竖向拖动归网格滚动，短移归点按，不抢。
 * - 本函数只判方向，不碰状态：分类切换由
 *   [SubClassifiedBoardState.nextParent]/[SubClassifiedBoardState.prevParent]
 *   执行（到端夹取、不循环绕回，与 nextPage/prevPage 同口径）。
 */
object KaomojiSwipe {

    enum class Direction { NEXT, PREV }

    fun directionFor(dxPx: Float, dyPx: Float, minDistancePx: Float): Direction? {
        val adx = abs(dxPx)
        val ady = abs(dyPx)
        if (adx < minDistancePx) return null
        if (adx <= ady) return null
        return if (dxPx < 0f) Direction.NEXT else Direction.PREV
    }
}
