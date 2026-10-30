package com.jobeen.ime.data.manager

import android.content.Context
import androidx.core.content.edit

object CandidateManager {
    const val PREFS_NAME = "candidate_settings"
    const val KEY_TRADITIONAL_ENABLED = "traditional_chinese_enabled"
    const val KEY_EMOJI_ENABLED = "emoji_enabled"
    const val KEY_ASCII_MODE_ENABLED = "ascii_mode_enabled"
    private const val KEY_PREDICTION_ENABLED = "prediction_enabled"
    private const val KEY_RERANK_ENABLED = "rerank_enabled"
    const val KEY_SHOW_INDEX = "show_index"
    const val KEY_SHOW_COMMENT = "show_comment"
    const val KEY_BORDER = "show_border"

    fun isPredictionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREDICTION_ENABLED, true)
    }

    fun setPredictionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PREDICTION_ENABLED, enabled)
        }
    }

    fun isTraditionalChineseEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TRADITIONAL_ENABLED, false)

    fun setTraditionalChineseEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_TRADITIONAL_ENABLED, enabled)
        }
    }

    fun isEmojiEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EMOJI_ENABLED, false)

    fun setEmojiEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_EMOJI_ENABLED, enabled)
        }
    }

    fun isAsciiModeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASCII_MODE_ENABLED, true)

    fun setAsciiModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_ASCII_MODE_ENABLED, enabled)
        }
    }

    fun isRerankEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RERANK_ENABLED, true)
    }

    fun setRerankEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_RERANK_ENABLED, enabled)
        }
    }

    fun isShowIndex(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_INDEX, true)
    }

    fun setShowIndex(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_INDEX, enabled)
        }
    }

    fun isShowComment(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_COMMENT, false)
    }

    fun setShowComment(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_COMMENT, enabled)
        }
    }

    fun isBorderEnabled(context: Context): Boolean {
        return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BORDER, false)
    }

    fun setBorderEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_BORDER, !enabled)
        }
    }

    /** 候选栏行数限制开关（默认开）：关 = 展开网格按视口自然行数显示，不截行 */
    fun isLineLimitEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("candidate.line_limit", true)
    }

    fun setLineLimitEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("candidate.line_limit", enabled)
        }
    }

    /** 展开候选网格最大行数（默认 5，1~10）：超过则网格内滚动 */
    fun getMaxLines(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("candidate.max_lines", 5).coerceIn(1, 10)
    }

    fun setMaxLines(context: Context, lines: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt("candidate.max_lines", lines.coerceIn(1, 10))
        }
    }

    /** 自动收缩（默认开）：内容行数少于可见行数时网格缩到内容高度，否则撑满键盘区 */
    fun isAutoShrinkEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("candidate.auto_shrink", true)
    }

    fun setAutoShrinkEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("candidate.auto_shrink", enabled)
        }
    }

    /** 完整显示（默认开）：关 = 普通格内超宽候选词截断加省略号；超宽独行词仍横向滚动 */
    fun isFullDisplayEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("candidate.full_display", true)
    }

    fun setFullDisplayEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("candidate.full_display", enabled)
        }
    }

    /** 行内联想（默认关，需手动开）：组字气泡后以淡色显示首选候选，仅预览不改输入 */
    fun isInlineSuggestionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("candidate.inline_suggestion", false)
    }

    fun setInlineSuggestionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("candidate.inline_suggestion", enabled)
        }
    }
}

/**
 * 展开候选网格的行高策略（纯函数，可单测）：
 * 网格行高恒为自然行高（32dp），可见行数 = 视口能放下的行数
 * 钳到最大行数；自动收缩时内容不足则缩到内容高度。
 */
object CandidateLinePolicy {
    fun resolveGridHeight(
        contentRows: Int,
        maxLines: Int,
        limitEnabled: Boolean,
        autoShrink: Boolean,
        naturalRowH: Int,
        fullHeight: Int,
    ): Int {
        if (contentRows <= 0 || naturalRowH <= 0) return fullHeight
        val effectiveMax = if (limitEnabled) maxLines.coerceIn(1, 10) else Int.MAX_VALUE
        val visibleAtFull = (fullHeight / naturalRowH).coerceIn(1, effectiveMax)
        return if (autoShrink && contentRows < visibleAtFull) {
            contentRows * naturalRowH
        } else {
            fullHeight
        }
    }

    /** 截断加省略号（纯函数）：measure 由调用方传入文本测量，避免 Android 依赖 */
    fun truncateWithEllipsis(
        text: String,
        maxWidth: Float,
        measure: (String) -> Float,
    ): String {
        if (text.isEmpty() || measure(text) <= maxWidth) return text
        if (measure("…") > maxWidth) return "…"
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(text.substring(0, mid) + "…") <= maxWidth) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + "…"
    }
}
