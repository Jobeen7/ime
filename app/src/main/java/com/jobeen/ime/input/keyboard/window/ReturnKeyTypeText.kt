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

    /** 回车键默认底色（对端默认灰实测，浅色 systemButton 色）。 */
    val defaultGray: Int = 0xFFB7BCC4.toInt()

    /**
     * 回车键底色是否走主题 accent：动作型（前往/搜索/发送/下一步/完成）
     * 走 accent（对端 [1,4,6,7,9] 蓝组，实测色即主题绿 #23C891）；
     * 其余（含换行、上一步、未指定）走默认灰。
     * 空输入/组字中恒为换行，走默认灰（调用方据此先行判定）。
     */
    fun isAccentBackground(action: Int): Boolean = when (action) {
        EditorInfo.IME_ACTION_GO,
        EditorInfo.IME_ACTION_SEARCH,
        EditorInfo.IME_ACTION_SEND,
        EditorInfo.IME_ACTION_NEXT,
        EditorInfo.IME_ACTION_DONE -> true
        else -> false
    }

    /**
     * imeOptions 动作 → 回车键中文文案（自定义布局文字回车键用；
     * 内置图标回车键另走图标，不用此表）。
     * 未指定／其他 → 换行（回车默认行为）。
     */
    fun labelFor(action: Int): String = when (action) {
        EditorInfo.IME_ACTION_GO -> "前往"
        EditorInfo.IME_ACTION_SEND -> "发送"
        EditorInfo.IME_ACTION_SEARCH -> "搜索"
        EditorInfo.IME_ACTION_NEXT -> "下一步"
        EditorInfo.IME_ACTION_PREVIOUS -> "上一步"
        EditorInfo.IME_ACTION_DONE -> "完成"
        else -> "换行"
    }
}
