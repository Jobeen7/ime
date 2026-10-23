package com.jobeen.ime.skin.draft

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import kotlin.math.pow

/**
 * 对比度警告（定稿十四.4）：仅警告、不拦截。按 WCAG 相对亮度
 * 计算关键文字/背景对的对比度，低于 3:1（大文本下限）时给出
 * 一条中文提示；编辑器据此在保存区上方展示，不阻止应用。
 */
object ContrastWarnings {

    const val MIN_RATIO = 3.0

    private data class Pair(val label: String, val foreground: Int, val background: Int)

    fun check(scheme: KeyboardColors.ColorScheme): List<String> {
        val pairs = listOf(
            Pair("按键文字 / 按键底色", scheme.keyText, scheme.keyBackground),
            Pair("特殊键文字 / 特殊键底色", scheme.specialKeyText, scheme.specialKeyBackground),
            Pair("强调键文字 / 强调键底色", scheme.accentKeyText, scheme.accentKeyBackground),
            Pair("候选文字 / 候选区底色", scheme.panel.candidateText, scheme.panel.candidateBackground),
            Pair("工具栏文字 / 面板底色", scheme.panel.toolbarText, scheme.panel.background),
        )
        return pairs.mapNotNull { pair ->
            val ratio = contrastRatio(pair.foreground, pair.background)
            if (ratio < MIN_RATIO) {
                "${pair.label}对比度不足（${"%.1f".format(ratio)}:1，建议 ≥3:1）"
            } else {
                null
            }
        }
    }

    /** WCAG 对比度 (L1 + 0.05) / (L2 + 0.05)，L1 为较亮一方。 */
    fun contrastRatio(foreground: Int, background: Int): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun linear(channel: Int): Double {
            val s = channel / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        // 通道以位运算提取（不依赖 android.graphics.Color，保持纯函数可单测）
        return 0.2126 * linear((color shr 16) and 0xFF) +
            0.7152 * linear((color shr 8) and 0xFF) +
            0.0722 * linear(color and 0xFF)
    }
}
