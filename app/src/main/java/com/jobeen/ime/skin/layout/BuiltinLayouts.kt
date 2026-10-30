package com.jobeen.ime.skin.layout

/**
 * 内置布局 id 登记（2026-10-28 修复轮：内置布局 App 侧接应）。
 *
 * 取值与各出厂键盘的 NAME 常量同源（T9Keyboard.NAME 等），此处
 * 写死字符串以避免 layout 包反向依赖 input 包；漂移由单测
 * BuiltinLayoutsTest 锁死（断言与四键盘 NAME 常量相等）。
 * 精确匹配：与内置 id 同名的自创布局不再走全局布局索引，
 * 一律按本皮肤的内置覆盖处理（见 JimeImporter 内置分流）。
 */
object BuiltinLayouts {
    val IDS: Set<String> = setOf("T9", "Qwerty", "T15", "Number")

    fun isBuiltin(id: String): Boolean = id in IDS
}
