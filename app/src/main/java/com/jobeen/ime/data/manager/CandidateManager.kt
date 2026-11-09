package com.jobeen.ime.data.manager

import android.content.Context
import androidx.core.content.edit
import com.jobeen.ime.engine.data.EngineMessage

object CandidateManager {
    const val PREFS_NAME = "candidate_settings"
    const val KEY_TRADITIONAL_ENABLED = "traditional_chinese_enabled"
    const val KEY_EMOJI_ENABLED = "emoji_enabled"
    const val KEY_ASCII_MODE_ENABLED = "ascii_mode_enabled"
    private const val KEY_PREDICTION_ENABLED = "prediction_enabled"
    private const val KEY_RERANK_ENABLED = "rerank_enabled"
    const val KEY_EMAIL_SUFFIX_ENABLED = "email_suffix_enabled"
    const val KEY_RARE_CHARACTERS_ENABLED = "rare_characters_enabled"
    const val KEY_SHOW_INDEX = "show_index"
    const val KEY_SHOW_COMMENT = "show_comment"
    const val KEY_BORDER = "show_border"
    private const val KEY_CANDIDATE_SPACING = "candidate.spacing_dp"
    private const val KEY_CANDIDATE_SCALE = "candidate.scale"
    /** 候选间距范围（dp）与滑块中位值（= 竖网格旧写死默认值 8dp）。 */
    const val CANDIDATE_SPACING_MIN_DP = 0f
    const val CANDIDATE_SPACING_MAX_DP = 24f
    const val CANDIDATE_SPACING_DEFAULT_DP = 8f
    /** 候选栏缩放范围（倍率）与默认值（1.0 = 旧行为）。 */
    const val CANDIDATE_SCALE_MIN = 0.8f
    const val CANDIDATE_SCALE_MAX = 1.5f
    const val CANDIDATE_SCALE_DEFAULT = 1.0f

    fun isPredictionEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREDICTION_ENABLED, true)
    }

    /** 邮箱后缀候选（默认开，独立开关，不受预测开关影响）：打 @ 后给 qq.com 这类后缀 */
    fun isEmailSuffixEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EMAIL_SUFFIX_ENABLED, true)
    }

    /** 生僻字开关（默认开）：开时显示扩展区生僻字，并按 D17 把粤语常用字佢／嘅／咗／冇补到第一、二页；关时扩展区生僻字不进候选栏。默认开 = 保持旧行为（此前无开关，生僻字有字形即显示）；CyIME 的默认关不照搬——CyIME 主词库本无生僻字、开关是补字，Jime 的万象词库已有生僻字、开关是显隐，默认关会拿掉用户现在能打的字。 */
    fun isRareCharactersEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_RARE_CHARACTERS_ENABLED, true)
    }

    fun setRareCharactersEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_RARE_CHARACTERS_ENABLED, enabled)
        }
    }

    fun setEmailSuffixEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_EMAIL_SUFFIX_ENABLED, enabled)
        }
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

    /** 内嵌输入模式（默认关）：开时组字串直送应用输入框内嵌显示（纯文本），关时走悬浮药丸。 */
    fun isInlineInputModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("candidate.inline_input_mode", false)
    }

    fun setInlineInputModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("candidate.inline_input_mode", enabled)
        }
    }

    /**
     * 候选间距（dp，0~24）：用户显式设定时胜过皮肤值
     * （App > 皮肤 > Jime 默认，见 CandidateSizeResolution 口径）；
     * 未设定时返回 null，由消费点回落皮肤值／各面默认值
     * （竖网格 8dp、横条 6dp）。写入时钳到范围。
     */
    fun getCandidateSpacingDp(context: Context): Float? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CANDIDATE_SPACING)) return null
        return prefs.getFloat(KEY_CANDIDATE_SPACING, CANDIDATE_SPACING_DEFAULT_DP)
            .coerceIn(CANDIDATE_SPACING_MIN_DP, CANDIDATE_SPACING_MAX_DP)
    }

    fun setCandidateSpacingDp(context: Context, dp: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putFloat(
                KEY_CANDIDATE_SPACING,
                dp.coerceIn(CANDIDATE_SPACING_MIN_DP, CANDIDATE_SPACING_MAX_DP),
            )
        }
    }

    /** 候选栏缩放（倍率，0.8~1.5，默认 1.0）：1.0 时渲染与旧行为一致。写入时钳到范围。 */
    fun getCandidateScale(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_CANDIDATE_SCALE, CANDIDATE_SCALE_DEFAULT)
            .coerceIn(CANDIDATE_SCALE_MIN, CANDIDATE_SCALE_MAX)
    }

    fun setCandidateScale(context: Context, scale: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putFloat(KEY_CANDIDATE_SCALE, scale.coerceIn(CANDIDATE_SCALE_MIN, CANDIDATE_SCALE_MAX))
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

    /** 长候选先缩字号再两行（纯函数）：字形 advance 随字号线性变，缩放宽度 = 原宽 × scale。 */
    data class LongCandidateLayout(val lines: List<String>, val scale: Float)

    /**
     * 长候选排版（纯函数）：能放下原样返回；一行缩字号能放下且不低于
     * minScale 就缩字号单行；否则下限字号下分两行；第二行仍放不下才
     * 截断加省略号（最后手段）。measure 为基准字号下的文本测量。
     */
    fun layoutLongCandidate(
        text: String,
        maxWidth: Float,
        measure: (String) -> Float,
        minScale: Float = 0.7f,
    ): LongCandidateLayout {
        if (text.isEmpty()) return LongCandidateLayout(listOf(""), 1f)
        val fullW = measure(text)
        if (fullW <= maxWidth) return LongCandidateLayout(listOf(text), 1f)
        val floor = minScale.coerceIn(0.1f, 1f)
        // 1. 先缩字号：一行能放下且不低于下限
        val fitScale = maxWidth / fullW
        if (fitScale >= floor) return LongCandidateLayout(listOf(text), fitScale)
        // 2. 再分两行：下限字号下取最长能放下的前缀为第一行
        val scaled: (String) -> Float = { measure(it) * floor }
        var first = longestFittingPrefix(text, maxWidth, scaled)
        if (first <= 0) {
            return LongCandidateLayout(listOf(truncateWithEllipsis(text, maxWidth, scaled)), floor)
        }
        // 英文短语优先在空格处断行（中文无空格，走贪心断点）；
        // 第一行不能缩得太短（不低于可用宽的 40%），否则宁可贪心断。
        val spaceIdx = text.lastIndexOf(' ', first - 1)
        if (spaceIdx > 0 && scaled(text.substring(0, spaceIdx)) >= maxWidth * 0.4f) {
            first = spaceIdx
        }
        val firstLine = text.substring(0, first)
        val rest = text.substring(first).trimStart()
        val secondLine = when {
            rest.isEmpty() -> ""
            scaled(rest) <= maxWidth -> rest
            else -> truncateWithEllipsis(rest, maxWidth, scaled)
        }
        val lines = if (secondLine.isEmpty()) listOf(firstLine) else listOf(firstLine, secondLine)
        return LongCandidateLayout(lines, floor)
    }

    /** 最长能放下的前缀长度（二分，与 truncateWithEllipsis 同形）。 */
    private fun longestFittingPrefix(
        text: String,
        maxWidth: Float,
        measure: (String) -> Float,
    ): Int {
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(text.substring(0, mid)) <= maxWidth) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/**
 * 候选间距／缩放解析（纯函数，可单测）：
 * 间距按 App > 皮肤 > 各面默认（竖网格 8dp、横条 6dp）；
 * 用户值与皮肤值均已在各自入口钳过范围，解析本身不再钳，
 * 不改任何未设定／皮肤已定值的渲染。
 */
object CandidateSpacingPolicy {
    fun resolveSpacing(userDp: Float?, skinDp: Float?, defaultDp: Float): Float =
        userDp ?: skinDp ?: defaultDp

    /**
     * 缩放后字号（纯函数）：基准字号 × 倍率；倍率钳到
     * 0.8~1.5，结果钳到 8~48dp 防极端值撑破药丸／行高。
     */
    fun scaledTextSizeDp(baseDp: Float, scale: Float): Float =
        (baseDp * scale.coerceIn(
            CandidateManager.CANDIDATE_SCALE_MIN,
            CandidateManager.CANDIDATE_SCALE_MAX,
        )).coerceIn(8f, 48f)
}

/**
 * 生僻字策略（纯函数，可单测；CyIME R2＋D17 的 Jime 版）：
 *
 * - R2 开关：万象词库本就有生僻字（扩展区字权重 1、在表深处），Jime
 *   此前只滤无字形者（反向过滤）。开关关时扩展区单字一律不进候选栏。
 * - D17 排序：粤语常用字佢／嘅／咗／冇在万象词库单字表里没有（冇只
 *   有 mao 读音），开关开着时按读音补到第 10 位（CyIME「约第 10–14
 *   个」的起点）；只在打满一个完整音节时补，多音节／简拼／模糊不补。
 *   26 键按解析出的音节补；九宫格未解析数字码按 T9 数字码表补、读音
 *   写进注释（数字可对多个音节），左栏选定音节后按音节补。
 * - 补字不进用户词库：它是 App 层合成的候选（type = rareChar），
 *   点选走文本上屏、不进 Rime 选词（见 selectCandidateInternal）。
 */
object RareCharPolicy {
    /** 粤语常用字：读音 → 字（CyIME A17 的 COMMON 表）。 */
    val COMMON_BY_SYLLABLE: Map<String, String> = mapOf(
        "qu" to "佢",
        "ge" to "嘅",
        "zuo" to "咗",
        "mou" to "冇",
    )

    /** T9 数字码 → 读音（标准九键：22233344455566677778889999）。 */
    val SYLLABLE_BY_DIGITS: Map<String, String> = mapOf(
        "78" to "qu",
        "43" to "ge",
        "986" to "zuo",
        "668" to "mou",
    )

    /** 补字目标位（0-based）：第 10 位，CyIME「约第 10–14 个」的起点。 */
    const val TARGET_INDEX = 9

    /** 生僻字判定：单个扩展区汉字（扩展 A–H）。不能只判码点 ≥ U+3400——那会把常用汉字（U+4E00 起）、韩文音节、emoji 一并判成生僻字。 */
    fun isRareChar(text: String): Boolean {
        if (text.isEmpty() || text.codePointCount(0, text.length) != 1) return false
        val cp = text.codePointAt(0)
        return cp in 0x3400..0x4DBF || // 扩展 A
            cp in 0x20000..0x2A6DF || // 扩展 B
            cp in 0x2A700..0x2B73F || // 扩展 C
            cp in 0x2B740..0x2B81F || // 扩展 D
            cp in 0x2B820..0x2CEAF || // 扩展 E
            cp in 0x2CEB0..0x2EBEF || // 扩展 F
            cp in 0x30000..0x3134F || // 扩展 G
            cp in 0x31350..0x323AF // 扩展 H
    }

    /** 补字目标：读音＋是否来自未解析数字码（来自数字码时读音写进注释）。 */
    data class SupplementTarget(val syllable: String, val fromDigits: Boolean)

    /**
     * 由组字音节定补字目标（纯函数）：单音节时解析出的音节在表里即补；
     * 音节未解析时原始输入为纯数字码且在数字码表里即补；多音节／空组字／
     * 对不上表一律不补。spellings 为各音节的 spelling，rawInput 为首音
     * 节的原始输入（九宫格未解析时为数字码、可带分词符 '）。
     */
    fun targetSupplement(spellings: List<String>, rawInput: String): SupplementTarget? {
        if (spellings.size != 1) return null
        val spelling = spellings[0]
        if (COMMON_BY_SYLLABLE.containsKey(spelling)) {
            return SupplementTarget(spelling, fromDigits = false)
        }
        val digits = rawInput.trimEnd('\'')
        if (digits.isNotEmpty() && digits.all { it.isDigit() }) {
            val syllable = SYLLABLE_BY_DIGITS[digits] ?: return null
            return SupplementTarget(syllable, fromDigits = true)
        }
        return null
    }

    /** 补字结果：新表＋插入／移位位置（-1 = 未动），供高亮下标与总数重映射。 */
    data class SupplementOutcome(
        val list: List<EngineMessage.Candidate>,
        val insertedAt: Int = -1,
        val movedFrom: Int = -1,
    )

    /**
     * 补字＋提位（纯函数）：开关关／无目标／目标字已被遗忘时原表返回；
     * 目标字已在表中且已在目标位之前时不动；在目标位之后时移到目标位；
     * 不在表中时插到目标位（表短于目标位时放表尾）；数字码补的字带读音注释。
     * 插入的候选 index 取其在新表中的位置（合成候选、不进 Rime 选词，
     * 点选／遗忘均按 type 分流，见 selectCandidateInternal／onCandidateForget）。
     */
    fun applySupplement(
        candidates: List<EngineMessage.Candidate>,
        spellings: List<String>,
        rawInput: String,
        enabled: Boolean,
        isDeleted: (String) -> Boolean,
    ): SupplementOutcome {
        if (!enabled) return SupplementOutcome(candidates)
        val target = targetSupplement(spellings, rawInput) ?: return SupplementOutcome(candidates)
        val char = COMMON_BY_SYLLABLE.getValue(target.syllable)
        if (isDeleted(char)) return SupplementOutcome(candidates)
        val existing = candidates.indexOfFirst { it.text == char }
        val at = TARGET_INDEX.coerceAtMost(candidates.size)
        if (existing >= 0) {
            if (existing <= TARGET_INDEX) return SupplementOutcome(candidates)
            val mutable = candidates.toMutableList()
            val item = mutable.removeAt(existing)
            mutable.add(at, item)
            return SupplementOutcome(mutable, insertedAt = at, movedFrom = existing)
        }
        val item = EngineMessage.Candidate(
            index = at,
            text = char,
            comment = if (target.fromDigits) target.syllable else "",
            type = EngineMessage.Candidate.TYPE_RARE_CHAR,
        )
        val mutable = candidates.toMutableList()
        mutable.add(at, item)
        return SupplementOutcome(mutable, insertedAt = at)
    }

    /**
     * 高亮下标随补字重映射（纯函数）：未动时原样；插入时 ≥插入位者 +1；
     * 移位时被移项的高亮跟到目标位、目标位到原位之间的高亮 +1、其余不动。
     */
    fun remapHighlight(highlighted: Int, outcome: SupplementOutcome): Int {
        val at = outcome.insertedAt
        if (at < 0 || highlighted < 0) return highlighted
        val from = outcome.movedFrom
        return when {
            from >= 0 && highlighted == from -> at
            from >= 0 && highlighted in at until from -> highlighted + 1
            from < 0 && highlighted >= at -> highlighted + 1
            else -> highlighted
        }
    }

    /**
     * 补字后总数（纯函数）：只有确实插入新项时总数 +1；移位时表长不变、
     * 总数不变（否则 hasMore 恒真，面板多发一次分页请求）；未知总数
     *（-1）保持；未动时原样。
     */
    fun adjustedTotal(total: Int, outcome: SupplementOutcome): Int {
        if (total < 0) return total
        if (outcome.insertedAt < 0) return total
        if (outcome.movedFrom >= 0) return total
        return total + 1
    }
}
