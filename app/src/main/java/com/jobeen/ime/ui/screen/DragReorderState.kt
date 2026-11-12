// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt

/**
 * 长按拖拽排序的共用状态（U-S7）：符号栏与方案管理页同款机制。
 * 拖拽态（行下标＋跟手偏移）集中在此，结束／取消一律复位——
 * 旧代码两处都没有 onDragCancel，手势被父级滚动／系统打断时
 * dragIndex 永不复位，行永久保持放大＋阴影＋偏移。
 * 用法：remember { DragReorderState() }，pointerInput 内按
 * onDragStart／onDrag／onDragEnd／onDragCancel 接线。
 */
class DragReorderState {
    /** 正在拖的行下标，-1＝未拖拽。 */
    var dragIndex by mutableIntStateOf(-1)
        private set

    /** 跟手纵向偏移（px），仅拖拽行渲染时用。 */
    var dragOffset by mutableFloatStateOf(0f)
        private set

    /** 行高（px），由 onGloballyPositioned 回填；0＝尚未量出。 */
    var itemHeight by mutableFloatStateOf(0f)

    val isDragging: Boolean get() = dragIndex >= 0

    fun onDragStart(index: Int) {
        dragIndex = index
        dragOffset = 0f
    }

    fun onDrag(deltaY: Float) {
        if (dragIndex < 0) return
        dragOffset += deltaY
    }

    /**
     * 拖拽结束：按落点行高取整算目标下标，返回 (起点, 目标)；
     * 不动（目标＝起点、未量出行高、未拖拽）返回 null。
     * 无论如何都复位拖拽态。未量出行高时不移动——旧方案管理页
     * 此处无守门，行高为 0 会把目标算到首／末行；拖拽只能发生在
     * 已布局的行上，行高此时恒大于 0，守门只覆盖不可达态。
     */
    fun onDragEnd(itemCount: Int): Pair<Int, Int>? {
        if (dragIndex < 0) return null
        val from = dragIndex
        val target = if (itemHeight > 0f) {
            (from + (dragOffset / itemHeight).roundToInt()).coerceIn(0, itemCount - 1)
        } else {
            from
        }
        dragIndex = -1
        dragOffset = 0f
        return if (target != from) from to target else null
    }

    /** 拖拽取消（手势被打断）：不移动，只复位拖拽态。 */
    fun onDragCancel() {
        dragIndex = -1
        dragOffset = 0f
    }
}
