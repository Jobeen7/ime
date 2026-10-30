package com.jobeen.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyDef

/**
 * 通用布局键盘（B3 详设 §3.1/§3.3）：自创布局的运行时载体，
 * 只喂数据——构建、渲染、跨行 span、手势装配全部走 [BaseKeyboard]
 * 与出厂布局同一条路径，手势引擎零改动（铁律 5）。
 *
 * name 契约：返回其运行时名 `custom:<layoutId>`，与状态管理器的
 * 按名缓存一致；同一布局一个缓存实例，布局新代次生效走既有
 * rebuild() 清缓存重建。
 *
 * updatePunctuationMode / updateEditorInfo / updateSpaceKeyText
 * 均由 BaseKeyboard 实现，本类不重写（待核 5 对表结论：空格文案
 * 经 button_space viewId 命中、标点模式逐 KeyView 下发、回车键
 * 图标变换只作用于 Image 回车键——自创布局的文本回车键不参与
 * 图标变换，ReturnAction 原样下发）。
 */
@SuppressLint("ViewConstructor")
class GenericLayoutKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
    private val layoutName: String,
    keyLayout: List<List<KeyDef>>,
) : BaseKeyboard(context, colors, keyLayout) {

    override fun name(): String = layoutName
}
