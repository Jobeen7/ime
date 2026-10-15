// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.core

import com.jobeen.ime.engine.event.KeyModifiers

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

    /**
     * @property direct 直送标记：仅 [RimeApi.emitCommit] 产生的 Commit
     * 为 true。这类上屏借道消息管线只为与 native Commit 同 FIFO 排序，
     * 下游不得按「打字上屏」记账（见 RimeEngine 的 Commit 分支处理）。
     * native 产生的 Commit 恒为 false（见 [nativeCreate] 的构造约束）。
     */
    data class CommitTextMessage(
        val commit: CommitProto,
        val direct: Boolean = false,
    ) : RimeMessage<CommitProto>(commit)

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
                // 本函数在通知分发线程上执行，只记录原始数据（方案通知的
                // 原始串只有 id/name 两段）：此前在这里调 cachedSchemaList()
                // 查 layout/punctuation/kind 是分发线程上的 librime native
                // 直调，与 rime-main 串行线之外的 finalize 存在 UAF 窗口。
                // 完整条目改由 Rime 的消息处理点在 rime-main 补取后替换本
                // 消息再分发（见 Rime.handleRimeMessage），未补到时其余
                // 字段保持为空。
                val raw = params[0] as String
                val parts = raw.split('/', limit = 4)
                SchemaMessage(
                    SchemaItem(
                        id = parts[0],
                        name = parts.getOrElse(1) { "" },
                        layout = parts.getOrElse(2) { "" },
                        punctuation = parts.getOrElse(3) { "" },
                        kind = "",
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

            MessageType.Commit -> CommitTextMessage(
                params[0] as CommitProto,
                // 直送标记只经第二个参数传递，且只有 emitCommit 会传：
                // native 通知与 emitResponse 等 native Commit 产生点的
                // 参数恒为单元素，标记缺省 false，构造上无法被误置
                direct = (params.getOrNull(1) as? Boolean) == true,
            )
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
