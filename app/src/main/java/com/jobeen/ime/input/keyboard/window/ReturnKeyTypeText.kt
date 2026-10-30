// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.window

import android.view.inputmethod.EditorInfo

/**
 * 回车键类型文本（重点候选 8-8，纯函数）：
 * imeOptions 动作 → 与 MultiReturnAction 同口径的动作名；
 * 未指定／其他 → ENTER（回车键默认显示）。
 */
object ReturnKeyTypeText {

    fun fromImeAction(action: Int): String = when (action) {
        EditorInfo.IME_ACTION_GO -> "GO"
        EditorInfo.IME_ACTION_SEND -> "SEND"
        EditorInfo.IME_ACTION_SEARCH -> "SEARCH"
        EditorInfo.IME_ACTION_NEXT -> "NEXT"
        EditorInfo.IME_ACTION_PREVIOUS -> "PREVIOUS"
        EditorInfo.IME_ACTION_DONE -> "DONE"
        else -> "ENTER"
    }
}
