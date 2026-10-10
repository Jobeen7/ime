package com.jobeen.ime.base.util

import android.annotation.SuppressLint
import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 输入框字段策略：密码类与「禁止个性化学习」的统一判定。
 *
 * 密码框内禁用：粘贴提示横幅（避免复制过的密码明文露出）、语音输入。
 * 覆盖文本密码（普通/可见/Web）与数字 PIN 密码。
 */
object InputFieldPolicy {

    /**
     * 是否禁止个性化学习：密码类输入框，或输入框声明了
     * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]（无痕等场景）。
     * 所有学习类行为（选词偏好、搭配、语音纠错学习、候选前文读取、
     * native 用户词典自学习）统一过这一判定，不再各处自写一份。
     *
     * IME_FLAG_NO_PERSONALIZED_LEARNING 是编译期常量、会被内联，
     * API 24/25 上也不存在运行时字段访问，NewApi 提示在此为误报。
     */
    @SuppressLint("NewApi")
    fun isNoPersonalizedLearning(info: EditorInfo?): Boolean {
        if (info == null) return false
        if (isPasswordField(info)) return true
        return (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    }

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
