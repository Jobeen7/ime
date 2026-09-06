// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.SystemSegmentSymbol
import com.ninthsoft.ime.engine.data.UserSegmentSymbol
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

    override fun convert(message: RimeMessage<*>): EngineMessage = when (message) {
        is RimeMessage.CommitTextMessage -> {
            EngineMessage.Commit(message.data.text.orEmpty())
        }

        is RimeMessage.CompositionMessage -> {
            val preedit = message.data.preedit.orEmpty()
            val cursor = message.data.cursorPos
            EngineMessage.Composition(preedit, cursor)
        }

        is RimeMessage.CandidateListMessage -> {
            val candidates = message.data.candidates.mapIndexed { i, c ->
                EngineMessage.Candidate(
                    index = i,
                    text = c.text,
                    comment = c.comment,
                    type = c.type,
                )
            }
            EngineMessage.Candidates(
                list = candidates,
                highlighted = message.data.highlighted,
                page = 0,
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

        is RimeMessage.DynamicPreeditMessage -> buildDynamicPreedit(message.data.composition)

        is RimeMessage.SchemaMessage -> {
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
            Timber.d("EngineMessage.Unknown %s", message.data.toString())
            EngineMessage.Unknown
        }
    }

    private fun buildDynamicPreedit(composition: CompositionProto): EngineMessage {
        val items = mutableListOf<EngineMessage.DynamicPreedit.DynamicPreeditItem>()
        val preedits = composition.syllables
        val spelling = composition.syllables.joinToString { it.spelling }
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
}

/** 全拼/双拼通用转换器单例：根据方案 kind 设置音节注释切分器。 */
object EngineMessageConverter : BaseRimeMessageConverter() {
    private var activeSplitter: SyllableSpellingSplitter = PinYinSpellingSplitter

    override val splitter: SyllableSpellingSplitter
        get() = activeSplitter

    /** kind 属于双拼方案时使用声母/韵母切分，否则保持全拼固定长度切分。 */
    fun applySchemaKind(kind: String) {
        activeSplitter = if (kind.contains("double-pinyin", ignoreCase = true)) {
            ShuangPinSpellingSplitter
        } else {
            PinYinSpellingSplitter
        }
    }
}