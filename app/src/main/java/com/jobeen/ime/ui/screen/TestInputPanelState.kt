// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 测试输入面板输入类型：chip 切换，键盘布局跟随当前布局与输入类型。 */
enum class TestInputMode { TEXT, NUMBER, PASSWORD }

/**
 * 测试输入面板开合状态机（纯状态逻辑，可 JVM 单测）：
 * - open()：打开面板；
 * - close()：关闭面板（返回／下滑收起由底部面板自身处理，收起时调 close()）；
 * - mode：当前输入类型（chip 直接写；收起不重置，下次打开沿用）；
 * - onNavigateAway()：进分组子页时收起面板（面板只属于主页）。
 */
class TestInputPanelState {
    var isOpen by mutableStateOf(false)
        private set

    var mode by mutableStateOf(TestInputMode.TEXT)

    fun open() {
        isOpen = true
    }

    fun close() {
        isOpen = false
    }

    fun onNavigateAway() {
        isOpen = false
    }
}

/**
 * 面板内“当前输入法不是 Jime”提示是否可见：面板打开且 Jime 不是当前输入法时才可见；
 * 面板收起或 Jime 已是当前输入法时不显示，避免静默假装调起的是 Jime。
 */
fun testInputImeHintVisible(panelOpen: Boolean, isImeConfigured: Boolean): Boolean =
    panelOpen && !isImeConfigured
