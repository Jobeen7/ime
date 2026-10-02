package com.jobeen.ime.base.util

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 输入框字段策略：密码类输入框的统一判定。
 *
 * 密码框内禁用：粘贴提示横幅（避免复制过的密码明文露出）、语音输入。
 * 覆盖文本密码（普通/可见/Web）与数字 PIN 密码。
 */
object InputFieldPolicy {

    fun isPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }
}
