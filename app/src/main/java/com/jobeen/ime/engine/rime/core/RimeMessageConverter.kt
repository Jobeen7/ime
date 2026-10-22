// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.data.SystemSegmentSymbol
import com.jobeen.ime.engine.data.UserSegmentSymbol
import timber.log.Timber

/**
 * 将 [RimeMessage] 翻译为用户可消费的 [EngineMessage]。
 * 不同输入方案（全拼 / 双拼）对音节注释的切分方式不同，通过
 * [BaseRimeMessageConverter] 由各方案实现定制。
 */
interface RimeMessageConverter {
    fun convert(message: RimeMessage<*>): EngineMessage
}


/**
 * 音节注释切分器：把一个音节的拼写（spelling）分成“已输入/声母”与“剩余/韵母”两部分。
 *
 * @param raw 该音节已键入的原始码
 * @param spelling 该音节的拼写（全拼为拼音，双拼为解析后的拼音）
 * @return 第一项为声母（或被键入部分），第二项为韵母（或剩余部分）
 */
interface SyllableSpellingSplitter {
    fun split(raw: String, spelling: String): Pair<String, String>
}

/** 全拼：按“已键入的拼音长度”固定位置切分。 */
object PinYinSpellingSplitter : SyllableSpellingSplitter {
    override fun split(raw: String, spelling: String): Pair<String, String> {
        val len = raw.length
        return spelling.take(len) to spelling.drop(len)
    }
}

// 拼音声母表（按最长匹配优先排序）
private val PINYIN_INITIALS = arrayOf(
    "zh", "ch", "sh",
    "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
    "j", "q", "x", "r", "z", "c", "s", "y", "w",
)

/** 双拼：两码确定整字（整段为 [normalPart]）；一码则按声母匹配，声母高亮、韵母置灰。 */
object ShuangPinSpellingSplitter : SyllableSpellingSplitter {
    override fun split(raw: String, spelling: String): Pair<String, String> {
        if (raw.length >= 2) {
            // 双码已确定整个字 -> 全部按已输入高亮
            return spelling to ""
        }
        // 单码：匹配声母，命中部分高亮，剩余韵母置灰
        val initial = PINYIN_INITIALS.firstOrNull { spelling.startsWith(it) } ?: ""
        return initial to spelling.removePrefix(initial)
    }
}

abstract class BaseRimeMessageConverter : RimeMessageConverter {

    protected abstract val splitter: SyllableSpellingSplitter

    /**
     * 最近一轮组字的原始状态（随 DynamicPreedit / Composition 两路同源
     * 消息刷新）：是否存在「未解析数字段」。两路消息都由同一
     * handleComposition 在候选消息之前连续发出、转换串行进行，故候选
     * 转换时读到的必是同轮组字状态；引擎直达本转换器的消息流不丢消息
     * （分发点 tryEmit 失败会挂起补发），状态不会因 UI 侧易变消息丢弃
     * 而过期。
     */
    private var hasUnresolvedDigitSegment = false

    /** 当前方案布局（随 Schema 消息刷新），取值与方案 yaml 的 layout 字段同源。 */
    private var currentLayout = ""

    /** T9 布局标识：方案 layout 字段的值，与 T9Keyboard.NAME 同源。 */
    private fun isT9Layout(): Boolean = currentLayout == "T9"

    /** 键帽表情过滤的触发条件：T9 方案且当前组字含未解析数字段。 */
    private fun shouldFilterKeycapCandidates(): Boolean =
        isT9Layout() && hasUnresolvedDigitSegment

    override fun convert(message: RimeMessage<*>): EngineMessage = when (message) {
        is RimeMessage.CommitTextMessage -> {
            EngineMessage.Commit(message.data.text.orEmpty(), direct = message.direct)
        }

        is RimeMessage.CompositionMessage -> {
            hasUnresolvedDigitSegment = compositionHasUnresolvedDigitSegment(message.data)
            val preedit = message.data.preedit.orEmpty()
            val cursor = message.data.cursorPos
            EngineMessage.Composition(preedit, cursor)
        }

        is RimeMessage.CandidateListMessage -> {
            val converted = message.data.candidates.mapIndexed { i, c ->
                EngineMessage.Candidate(
                    index = i,
                    text = c.text,
                    comment = c.comment,
                    type = c.type,
                )
            }
            // 键帽过滤必须在编号之后做：index 是引擎全局下标（bulk 取词
            // 恒自 0 起），选词按它定位；先滤再编号会让其后每个候选的
            // 下标错位一位、点选打错词。与 restoreCandidates 里已删词
            // 过滤同口径：只摘条目、保留其余条目的原下标
            val candidates = if (shouldFilterKeycapCandidates()) {
                converted.filterNot { isKeycapSequence(it.text) }
            } else {
                converted
            }
            EngineMessage.Candidates(
                list = candidates,
                highlighted = message.data.highlighted,
                page = 0,
                total = message.data.total,
            )
        }

        is RimeMessage.StatusMessage -> {
            EngineMessage.Status(
                isComposing = message.data.isComposing
            )
        }

        is RimeMessage.CandidateMenuMessage -> {
            EngineMessage.CandidateMenu(
                isLastPage = message.data.isLastPage,
                pageSize = message.data.pageSize,
                pageNumber = message.data.pageNumber,
                selectKeys = message.data.selectKeys,
                selectLabels = message.data.selectLabels.asIterable().toList(),
                highlightedCandidateIndex = message.data.highlightedCandidateIndex,
                candidates = message.data.candidates.mapIndexed { index, item ->
                    EngineMessage.CandidateMenu.Candidate(
                        index = index,
                        text = item.text,
                        comment = item.comment,
                        label = item.label,
                        type = item.type,
                    )
                })
        }

        is RimeMessage.InlinePreeditMessage -> {
            EngineMessage.InlinePreedit(message.data)
        }

        is RimeMessage.DynamicPreeditMessage -> {
            hasUnresolvedDigitSegment =
                compositionHasUnresolvedDigitSegment(message.data.composition)
            buildDynamicPreedit(message.data.composition)
        }

        is RimeMessage.SchemaMessage -> {
            currentLayout = message.data.layout
            EngineMessage.Schema(
                message.data.id,
                message.data.name,
                message.data.layout,
                message.data.punctuation,
                message.data.kind
            )
        }

        is RimeMessage.DeployMessage -> {
            val deployState = when (message.data) {
                RimeMessage.DeployMessage.State.Start -> EngineMessage.Depoly.State.Start
                RimeMessage.DeployMessage.State.Success -> EngineMessage.Depoly.State.Success
                RimeMessage.DeployMessage.State.Failure -> EngineMessage.Depoly.State.Failure
                RimeMessage.DeployMessage.State.Finish -> EngineMessage.Depoly.State.Finish
            }
            EngineMessage.Depoly(deployState)
        }

        is RimeMessage.OptionMessage -> {
            EngineMessage.Unknown
        }

        else -> {
            // 只记消息类型不记内容：data 可能携带组字/候选原文
            Timber.d("EngineMessage.Unknown type=%s", message.javaClass.simpleName)
            EngineMessage.Unknown
        }
    }

    private fun buildDynamicPreedit(composition: CompositionProto): EngineMessage {
        val items = mutableListOf<EngineMessage.DynamicPreedit.DynamicPreeditItem>()
        val preedits = composition.syllables
        // 分隔符必须用空串：joinToString 默认 ", " 使拼出串恒含逗号与空格，
        // 多音节时 all{isDigit()} 恒假，「整串」分支只剩单音节（及空表真空真）
        // 可命中——多段纯数字/空拼写输入会误落逐音节路径（潜伏 bug）
        val spelling = composition.syllables.joinToString("") { it.spelling }
        if (preedits.isEmpty() || spelling.all { it.isDigit() }) {
            if (composition.preedit.isNullOrBlank()) {
                return EngineMessage.DynamicPreedit(items)
            }
            items.add(
                EngineMessage.DynamicPreedit.DynamicPreeditItem(
                    text = composition.preedit,
                    type = EngineMessage.DynamicPreedit.DynamicPreeditType.Normal
                )
            )
            return EngineMessage.DynamicPreedit(items)
        }

        var confirmedProto: SyllableProto? = null
        preedits.forEachIndexed { index, proto ->
            if (confirmedProto != null) {
                val cp = confirmedProto
                if (cp.textSyllableStart >= 0 && cp.textSyllableEnd >= 0 && cp.textSyllableStart <= index && index <= cp.textSyllableEnd) {
                    return@forEachIndexed
                }
            }
            val segmented = proto.rawInput.endsWith(UserSegmentSymbol.toString())
            val raw = proto.rawInput.trimEnd(UserSegmentSymbol)
            val (normalPart, secondaryPart) = splitter.split(raw, proto.spelling)
            if (proto.text.isNotEmpty()) {
                confirmedProto = proto
                items.add(
                    EngineMessage.DynamicPreedit.DynamicPreeditItem(
                        text = proto.text,
                        type = EngineMessage.DynamicPreedit.DynamicPreeditType.Normal
                    )
                )
                return@forEachIndexed
            }
            var selected = false
            if (normalPart.isNotEmpty()) {
                items.add(
                    EngineMessage.DynamicPreedit.DynamicPreeditItem(
                        text = normalPart,
                        type = EngineMessage.DynamicPreedit.DynamicPreeditType.Normal
                    )
                )
                selected = true
            }
            if (secondaryPart.isNotEmpty()) {
                items.add(
                    EngineMessage.DynamicPreedit.DynamicPreeditItem(
                        text = secondaryPart,
                        type = EngineMessage.DynamicPreedit.DynamicPreeditType.Secondary
                    )
                )
                selected = true
            }
            if (!selected) {
                items.add(
                    EngineMessage.DynamicPreedit.DynamicPreeditItem(
                        text = proto.rawInput,
                        type = EngineMessage.DynamicPreedit.DynamicPreeditType.Normal
                    )
                )
            }
            items.add(
                EngineMessage.DynamicPreedit.DynamicPreeditItem(
                    text = if (segmented) UserSegmentSymbol.toString() else SystemSegmentSymbol.toString(),
                    type = EngineMessage.DynamicPreedit.DynamicPreeditType.Normal
                )
            )
        }
        return EngineMessage.DynamicPreedit(items)
    }

    /**
     * 组字中是否存在「未解析数字段」：某段的原始码含数字、却既没被选中
     * 成词（text 为空）、也没解析出拼音字母（spelling 无字母）——该段在
     * 组字里只能以原始数字形态出现（如 qu'77 的尾段 77、ji'5 的尾段 5）。
     * 已确认候选覆盖的段与其内部的段不算未解析（与 [buildDynamicPreedit]
     * 的 confirmed 段跳过同口径）。
     */
    private fun compositionHasUnresolvedDigitSegment(composition: CompositionProto): Boolean {
        var confirmedProto: SyllableProto? = null
        composition.syllables.forEachIndexed { index, proto ->
            val cp = confirmedProto
            if (cp != null &&
                cp.textSyllableStart >= 0 && cp.textSyllableEnd >= 0 &&
                cp.textSyllableStart <= index && index <= cp.textSyllableEnd
            ) {
                return@forEachIndexed
            }
            if (proto.text.isNotEmpty()) {
                confirmedProto = proto
                return@forEachIndexed
            }
            val raw = proto.rawInput.trimEnd(UserSegmentSymbol)
            if (raw.any { it.isDigit() } && proto.spelling.none { it.isLetter() }) {
                return true
            }
        }
        return false
    }
}

/**
 * 键帽序列判定：单个基字符（0-9、#、*）＋可选的 U+FE0F（变体选择符）
 * ＋ U+20E3（组合包围键帽符），如 "7️⃣"。这类文本由 emoji 简化器把
 * 「七」等数字字转换而来（wanxiang_emoji 词表），字面就是按键数字
 * 本身；在组字还带着未解析数字段时出现只会与原始数字相互混淆。
 */
internal fun isKeycapSequence(text: String): Boolean {
    if (text.length !in 2..3) return false
    if (text[text.length - 1] != '\u20E3') return false
    val base = text[0]
    if (base !in '0'..'9' && base != '#' && base != '*') return false
    return text.length == 2 || text[1] == '\uFE0F'
}

/** 全拼/双拼通用转换器单例：根据方案 kind 设置音节注释切分器。 */
object EngineMessageConverter : BaseRimeMessageConverter() {
    private var activeSplitter: SyllableSpellingSplitter = PinYinSpellingSplitter

    override val splitter: SyllableSpellingSplitter
        get() = activeSplitter

    /** kind 属于双拼方案时使用声母/韵母切分，否则保持全拼固定长度切分。 */
    fun applySchemaKind(kind: String) {
        activeSplitter = if (kind.contains("DoublePinyin", ignoreCase = true)) {
            ShuangPinSpellingSplitter
        } else {
            PinYinSpellingSplitter
        }
    }
}