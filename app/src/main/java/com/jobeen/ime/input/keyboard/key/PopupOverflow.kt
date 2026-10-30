// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

/**
 * 气泡溢出调位（纯函数，可 JVM 单测）。
 *
 * 键盘窗口内的气泡默认显示在锚点按键上方；当上方空间不足
 * （气泡顶边会超出窗口顶边，即超出键盘高度）时，翻转到锚点
 * 下方；下方同样放不下时贴窗口底边。横向始终夹在窗口内。
 * 窗口尺寸未知时调用方回退旧行为，本函数不处理该分支。
 */
object PopupOverflow {

    data class Position(val x: Int, val y: Int)

    fun resolve(
        anchorCenterX: Int,
        anchorTop: Int,
        anchorHeight: Int,
        popupWidth: Int,
        popupHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
        gapPx: Int,
    ): Position {
        val x = (anchorCenterX - popupWidth / 2)
            .coerceIn(0, (windowWidth - popupWidth).coerceAtLeast(0))
        val aboveY = anchorTop - popupHeight - gapPx
        val y = if (aboveY >= 0) {
            aboveY
        } else {
            val belowY = anchorTop + anchorHeight + gapPx
            if (belowY + popupHeight <= windowHeight) belowY
            else (windowHeight - popupHeight).coerceAtLeast(0)
        }
        return Position(x, y)
    }
}
