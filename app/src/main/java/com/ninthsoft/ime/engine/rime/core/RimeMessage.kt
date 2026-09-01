// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.SystemSegmentSymbol
import com.ninthsoft.ime.engine.data.UserSegmentSymbol
import com.ninthsoft.ime.engine.event.KeyModifiers
import com.ninthsoft.ime.engine.rime.core.RimeMessage.CandidateListMessage
import com.ninthsoft.ime.engine.rime.core.RimeMessage.CandidateMenuMessage
import com.ninthsoft.ime.engine.rime.core.RimeMessage.CommitTextMessage
import com.ninthsoft.ime.engine.rime.core.RimeMessage.CompositionMessage
import com.ninthsoft.ime.engine.rime.core.RimeMessage.InlinePreeditMessage
import com.ninthsoft.ime.engine.rime.core.RimeMessage.SchemaMessage
import com.ninthsoft.ime.engine.rime.core.RimeMessage.StatusMessage
import timber.log.Timber

sealed class RimeMessage<T>(val data: T) {

    val messageType: MessageType
        get() = when (this) {
            is UnknownMessage -> MessageType.Unknown
            is SchemaMessage -> MessageType.Schema
            is OptionMessage -> MessageType.Option
            is DeployMessage -> MessageType.Deploy
            is CommitTextMessage -> MessageType.Commit
            is InlinePreeditMessage -> MessageType.InlinePreedit
            is CompositionMessage -> MessageType.Composition
            is CandidateMenuMessage -> MessageType.Menu
            is StatusMessage -> MessageType.Status
            is CandidateListMessage -> MessageType.Candidate
            is KeyMessage -> MessageType.Key
            is DynamicPreeditMessage -> MessageType.DynamicPreedit
        }

    data class UnknownMessage(val params: Array<Any>) : RimeMessage<Array<Any>>(params) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is UnknownMessage && params.contentEquals(other.params))

        override fun hashCode(): Int = params.contentHashCode()
    }

    data class SchemaMessage(val schema: SchemaItem) : RimeMessage<SchemaItem>(schema)

    data class OptionMessage(val option: String, val value: Boolean) :
        RimeMessage<OptionMessage.Data>(Data(option, value)) {
        data class Data(val option: String, val value: Boolean)
    }

    data class DeployMessage(val state: State) : RimeMessage<DeployMessage.State>(state) {
        enum class State { Start, Success, Failure, Finish }
    }

    data class CommitTextMessage(val commit: CommitProto) : RimeMessage<CommitProto>(commit)

    data class InlinePreeditMessage(val preedit: String) : RimeMessage<String>(preedit)

    data class DynamicPreeditMessage(val composition: CompositionProto) :
        RimeMessage<DynamicPreeditMessage.DynamicPreedit>(
            DynamicPreedit(composition)
        ) {
        data class DynamicPreedit(val composition: CompositionProto)
    }

    data class CompositionMessage(val composition: CompositionProto) :
        RimeMessage<CompositionProto>(composition)

    data class CandidateMenuMessage(val menu: MenuProto) : RimeMessage<MenuProto>(menu)

    data class StatusMessage(val status: StatusProto) : RimeMessage<StatusProto>(status)

    data class CandidateListMessage(
        val total: Int = -1,
        val highlighted: Int = 0,
        val candidates: Array<CandidateProto> = arrayOf(),
    ) : RimeMessage<CandidateListMessage.Data>(Data(total, highlighted, candidates)) {
        data class Data(
            val total: Int = -1,
            val highlighted: Int = 0,
            val candidates: Array<CandidateProto> = arrayOf(),
        ) {
            override fun equals(other: Any?): Boolean =
                this === other || (other is Data && total == other.total && highlighted == other.highlighted && candidates.contentEquals(
                    other.candidates
                ))

            override fun hashCode(): Int {
                var r = total
                r = 31 * r + highlighted
                r = 31 * r + candidates.contentHashCode()
                return r
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as CandidateListMessage

            if (total != other.total) return false
            if (highlighted != other.highlighted) return false
            if (!candidates.contentEquals(other.candidates)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = total
            result = 31 * result + highlighted
            result = 31 * result + candidates.contentHashCode()
            return result
        }
    }

    data class KeyMessage(
        val key: KeyValue,
        val modifiers: KeyModifiers,
        val isVirtual: Boolean,
    ) : RimeMessage<KeyMessage.Data>(Data(key, modifiers, isVirtual)) {
        data class Data(
            val value: KeyValue,
            val modifiers: KeyModifiers,
            val isVirtual: Boolean,
        )
    }

    enum class MessageType {
        Unknown, Schema, Option, Deploy, Commit, InlinePreedit, Composition, Menu, Status, Candidate, Key, DynamicPreedit
    }

    companion object {
        private val types = MessageType.entries.toTypedArray()

        @Suppress("UNCHECKED_CAST")
        fun nativeCreate(type: Int, params: Array<Any>): RimeMessage<*> = when (types[type]) {
            MessageType.Schema -> {
                val raw = params[0] as String
                val parts = raw.split('/', limit = 4)
                val schemaId = parts[0]
                val schema = Rime.getSchemaList().firstOrNull { it.id == schemaId }
                SchemaMessage(
                    SchemaItem(
                        id = schemaId,
                        name = schema?.name ?: parts.getOrElse(1) { "" },
                        layout = schema?.layout ?: parts.getOrElse(2) { "" },
                        punctuation = schema?.punctuation ?: parts.getOrElse(3) { "" },
                    )
                )
            }

            MessageType.Option -> {
                val raw = params[0] as String
                OptionMessage(raw.substringAfter('!'), !raw.startsWith('!'))
            }

            MessageType.Deploy -> DeployMessage(
                DeployMessage.State.valueOf(
                    (params[0] as String).replaceFirstChar { it.titlecase() })
            )

            MessageType.Commit -> CommitTextMessage(params[0] as CommitProto)
            MessageType.InlinePreedit -> InlinePreeditMessage(params[0] as String)
            MessageType.Composition -> CompositionMessage(params[0] as CompositionProto)
            MessageType.Menu -> CandidateMenuMessage(params[0] as MenuProto)
            MessageType.Status -> StatusMessage(params[0] as StatusProto)
            MessageType.Candidate -> CandidateListMessage(
                params[0] as Int,
                params[1] as Int,
                params[2] as Array<CandidateProto>,
            )

            MessageType.Key -> KeyMessage(
                KeyValue(params[0] as Int),
                KeyModifiers.of(params[1] as Int),
                params[2] as Boolean,
            )

            MessageType.DynamicPreedit -> DynamicPreeditMessage(
                params[0] as CompositionProto
            )

            else -> UnknownMessage(params)
        }
    }
}


fun RimeMessage<*>.EngineMessage(): EngineMessage = when (this) {
    is CommitTextMessage -> {
        EngineMessage.Commit(data.text.orEmpty())
    }

    is CompositionMessage -> {
        val preedit = data.preedit.orEmpty()
        val cursor = data.cursorPos
        EngineMessage.Composition(preedit, cursor)
    }

    is CandidateListMessage -> {
        val candidates = data.candidates.mapIndexed { i, c ->
            EngineMessage.Candidate(
                index = i,
                text = c.text,
                comment = c.comment,
            )
        }
        EngineMessage.Candidates(
            list = candidates,
            highlighted = data.highlighted,
            page = 0,
        )
    }

    is StatusMessage -> {
        EngineMessage.Status(
            schemaName = data.schemaName,
            isAsciiMode = data.isAsciiMode,
        )
    }

    is CandidateMenuMessage -> {
        EngineMessage.CandidateMenu(
            isLastPage = data.isLastPage,
            pageSize = data.pageSize,
            pageNumber = data.pageNumber,
            selectKeys = data.selectKeys,
            selectLabels = data.selectLabels.asIterable().toList(),
            highlightedCandidateIndex = data.highlightedCandidateIndex,
            candidates = data.candidates.mapIndexed { index, item ->
                EngineMessage.CandidateMenu.Candidate(
                    index = index,
                    text = item.text,
                    comment = item.comment,
                    label = item.label,
                )
            })
    }

    is InlinePreeditMessage -> {
        EngineMessage.InlinePreedit(data)
    }

    is RimeMessage.DynamicPreeditMessage -> {
        val items = mutableListOf<EngineMessage.DynamicPreedit.DynamicPreeditItem>()
        val composition = data.composition
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
            val len = raw.length
            val normalPart = proto.spelling.take(len)
            val secondaryPart = proto.spelling.drop(len)
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
        EngineMessage.DynamicPreedit(items)
    }

    is SchemaMessage -> {
        EngineMessage.Schema(data.id, data.name, data.layout, data.punctuation)
    }

    is RimeMessage.DeployMessage -> {
        val deployState = when (data) {
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
        Timber.d("EngineMessage.Unknown %s", data.toString())
        EngineMessage.Unknown
    }
}
