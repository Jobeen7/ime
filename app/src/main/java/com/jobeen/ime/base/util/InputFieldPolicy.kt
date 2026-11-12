package com.jobeen.ime.base.util

import android.annotation.SuppressLint
import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * 输入框字段策略：密码类与「禁止个性化学习」的统一判定；登录
 * 账号框的新窗口复位豁免。
 *
 * 密码框内禁用：粘贴提示横幅（避免复制过的密码明文露出）、语音输入。
 * 覆盖文本密码（普通/可见/Web）与数字 PIN 密码。
 * 登录账号框（邮箱／人名／网址类标识字段）与密码框一样豁免新窗口
 * 复位（WO30 §4）：点这类框不回九宫格，停在当前键盘。
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
        return isNoPersonalizedLearning(info.inputType, info.imeOptions)
    }

    /**
     * 纯函数：位掩码判定，可单测（EditorInfo 本身不进 JVM 单测）。
     * 密码框不学习回归测试即测此表：密码类（文本三种变体＋数字 PIN）
     * 或声明 IME_FLAG_NO_PERSONALIZED_LEARNING 时一律禁学习。
     */
    @SuppressLint("NewApi")
    internal fun isNoPersonalizedLearning(inputType: Int, imeOptions: Int): Boolean {
        if (isPasswordField(inputType)) return true
        return (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
    }

    fun isPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        return isPasswordField(info.inputType)
    }

    /** 纯函数：位掩码判定，可单测。 */
    internal fun isPasswordField(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
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

    fun isAccountField(info: EditorInfo?): Boolean {
        if (info == null) return false
        return isAccountField(info.inputType)
    }

    /**
     * 登录账号框（纯函数：位掩码判定，可单测）：邮箱／人名／网址类
     * 标识字段，与密码框一样豁免新窗口复位（WO30 §4）。
     * 只认 EditorInfo 声明的账号类 variation：普通文本（NORMAL）
     * 无法与一般文本框区分，不豁免；邮寄地址／邮件主题／消息类
     * 不是账号标识，不豁免。对端参照：CyIME 的 address_fields_english
     * 把网址与邮箱归为一组自动切英文。
     */
    internal fun isAccountField(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME ||
            variation == InputType.TYPE_TEXT_VARIATION_URI
    }
}
