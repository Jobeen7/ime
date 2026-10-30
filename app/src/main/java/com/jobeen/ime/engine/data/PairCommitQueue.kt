// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.data

/**
 * 成对提交的光标回退侧队列（2026-10-28 截图 6 项）。
 *
 * 为什么不用消息字段：JNI CommitProto 构造签名固定为 (String)V，
 * 无法在消息体内携带回退量；直送 actions 通道又会超车排队中的
 * native 提交（见 RimeEngine.requestCommit 的超车注释），故成对
 * 提交仍走作业通道的 emitCommit， 回退量改走本侧队列。
 *
 * 正确性：作业通道串行 + 消息通道控制类 FIFO ⇒ 入队顺序 =
 * MessageHandler 出队顺序，一一对应；连续成对（如 (( ）也不丢。
 * 每次 Commit 处理必出队一次（拦截态同样出队丢弃），正常提交
 * 出队得 0、无操作，队列平时为空。
 */
object PairCommitQueue {

    private val queue = ArrayDeque<Int>()

    @Synchronized
    fun offer(cursorBack: Int) {
        queue.addLast(cursorBack)
    }

    /** 取队首回退量；空队列返回 0。 */
    @Synchronized
    fun poll(): Int = queue.removeFirstOrNull() ?: 0

    /** 回滚最近一次入队（emit 未产生消息时调用，避免污染下一条）。 */
    @Synchronized
    fun removeLast() {
        queue.removeLastOrNull()
    }
}
