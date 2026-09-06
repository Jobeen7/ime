// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

import com.ninthsoft.ime.engine.event.KeyModifiers

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
                        kind = schema?.kind.orEmpty(),
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
