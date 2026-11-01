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
     * 回车键默认底色（深色）：对端深色 systemButton 实测
     * #D1D1D624（对端 8 位色 alpha 在末尾，换算为 Android
     * ARGB 即 0x24D1D1D6）。两块参考皮肤在深色 systemButton
     * 上一致给出浅灰 + 0x24 alpha（另一块为 #B8B8BD24，同
     * alpha、相近浅灰）；本值取两块共识的 #D1D1D6（该色亦为
     * 对端深色字母键按压色），叠在深色键盘底上呈深灰。
     * 深色下沿用浅色实心灰会在深色底上过亮，故深色必走本值。
     */
    val darkDefaultGray: Int = 0x24D1D1D6.toInt()

    /** 按深浅取回车默认底色（深色 = 半透明对端值，浅色 = 实心灰）。 */
    fun defaultGrayForDark(dark: Boolean): Int =
        if (dark) darkDefaultGray else defaultGray

    /**
     * 按键面底色是否属深色：RGB 亮度（0.299R + 0.587G + 0.114B）
     * 低于 0.5 为深色。纯函数：调用方传当前方案 keyBackground，
     * 不读主题 id（自定义皮肤/莫奈深色板同样适用）。
     */
    fun isDarkBackground(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0 < 0.5
    }

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
