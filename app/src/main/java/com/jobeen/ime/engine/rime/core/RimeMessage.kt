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
            is BarrierMessage -> MessageType.Barrier
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

    /**
     * 上屏落屏栅栏（WO40，引擎内部信标、非 native 通知）：
     * CommitAndAct 在上屏 Commit 入队后，用 [Rime.handleMessage] 把
     * 它投进同一条 notificationQueue——与 Commit 同 FIFO、同一
     * 单线程消费者、同一条 messageChannel 观察者链；观察者按序
     * 处理到它时，其前的 Commit 必已落屏（同一观察者顺序处理、
     * 同一通道内控制类消息保序）。id 是配对令牌：超时后迟到的
     * 栅栏不得完成下一轮等待（见 [com.jobeen.ime.engine.CommitBarrierGate]）。
     * native 层永不产此类通知；参数形态不符时 id 取 -1（永不与
     * 真实等待配对，见 [nativeCreate]）。
     */
    data class BarrierMessage(val id: Long) : RimeMessage<Long>(id)

    enum class MessageType {
        Unknown, Schema, Option, Deploy, Commit, InlinePreedit, Composition, Menu, Status, Candidate, Key, DynamicPreedit, Barrier
    }

    companion object {
        private val types = MessageType.entries.toTypedArray()

        /**
         * native 通知的原始数据记录（不在本线程调 native）。
         *
         * 未知类型码／未知部署状态串／参数形态不符时不丢整条通知
         * （E-L1）：退化为 UnknownMessage 交给下游忽略，而不是在
         * dispatchMessage 的通用 catch 里整条丢弃（丢 Deploy 通知会
         * 让 deployAndAwait 靠 10 秒超时按成功处理；丢 Schema/Option
         * 则缓存滞后一轮）。本函数永不抛错。
         */
        @Suppress("UNCHECKED_CAST")
        fun nativeCreate(type: Int, params: Array<Any>): RimeMessage<*> {
            if (type !in types.indices) return UnknownMessage(params)
            return try {
                nativeCreateUnchecked(type, params)
            } catch (e: RuntimeException) {
                UnknownMessage(params)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun nativeCreateUnchecked(type: Int, params: Array<Any>): RimeMessage<*> =
            when (types[type]) {
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

            // 内部栅栏：native 永不发 Barrier 类型码；参数形态不符时
            // id 取 -1，永不与真实等待配对（等待方 id 自 1 起递增）。
            MessageType.Barrier -> BarrierMessage(
                (params.getOrNull(0) as? Long) ?: -1L
            )

            else -> UnknownMessage(params)
        }
    }
}
