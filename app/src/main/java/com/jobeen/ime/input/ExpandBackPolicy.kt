// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.view.KeyEvent

/**
 * 展开候选页返回决策（施工单 10 H2-6）。
 *
 * 纯决策，不调 Android 框架方法、不读偏好、不读键盘状态：
 * 调用方在边界处把展开态传进来（KawaiiPanelView.isExpanded）。
 *
 * 决策表（逐行实现，不许增减分支）：
 * - 展开态＋BACK 下按首击 → 收起展开页（消费掉，本次不收键盘）。
 * - 其余一律放行：未展开、非 BACK 键、抬起、长按重复——走系统默认
 *   （BACK 默认收键盘，不展开时行为与改前一致）。
 */
object ExpandBackPolicy {
    fun shouldCollapseOnBack(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        isExpanded: Boolean,
    ): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK &&
            action == KeyEvent.ACTION_DOWN &&
            repeatCount == 0 &&
            isExpanded
}
