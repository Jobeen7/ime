// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.skin.notify.ConditionCurrent
import com.jobeen.ime.skin.notify.ConditionEvaluator
import com.jobeen.ime.skin.notify.ConditionStyle
import com.jobeen.ime.skin.notify.NotificationEvent
import com.jobeen.ime.skin.notify.NotificationState
import com.jobeen.ime.skin.notify.SkinNotification

/**
 * 皮肤运行态（接线轮 R1/R2）：
 *
 * 进程级持有者，桥接事件层与构建期装配器——键盘构建只在
 * 布局构造时跑，通知锁定与条件现值必须跨越多次构建存活，
 * 故放在快照持有者之外的独立单例内：
 *
 * - 通知：窗口层五类事件经 [onEvent] 喂入；只有锁定表真正
 *   变化时才通知监听方（窗口据此重建键盘，预编辑每键一变
 *   也只在首次锁定变化时重建一次，不会每键重建）；
 * - 条件：接线层经 [updateConditionCurrent] 写入最近一次
 *   现值；装配器构建期取 [gatedStyleNames]／[activeStyleNames]
 *   进样式解析；
 * - 快照切换时由 [reset] 清空（旧皮肤的锁定不带到新皮肤）；
 *   reset 本身不通知监听方（应用事务自有刷新路径）。
 *
 * 线程：字段均为 @Volatile＋不可变快照，窗口主线程喂入、
 * 异步刷新写入、构建期读取三方只做整体替换，不崩。
 *
 * 日志纪律：本类不记任何日志；调用方只记源名与目标名，
 * 绝不记预编辑文本、候选词、剪贴板。
 */
object SkinRuntimeState {

    @Volatile
    private var notificationState: NotificationState? = null

    @Volatile
    private var conditionEntries: List<ConditionStyle> = emptyList()

    @Volatile
    private var conditionCurrent: Map<String, ConditionCurrent> = emptyMap()

    private val stateChangedListeners = mutableListOf<() -> Unit>()

    fun addStateChangedListener(listener: () -> Unit) {
        synchronized(stateChangedListeners) {
            if (!stateChangedListeners.contains(listener)) stateChangedListeners += listener
        }
    }

    fun removeStateChangedListener(listener: () -> Unit) {
        synchronized(stateChangedListeners) { stateChangedListeners -= listener }
    }

    private fun notifyStateChanged() {
        val copy = synchronized(stateChangedListeners) { stateChangedListeners.toList() }
        copy.forEach { runCatching { it() } }
    }

    /** 快照切换：按新皮肤的通知表／条件表重建（无皮肤 = 双空）。 */
    fun reset(
        notifications: List<SkinNotification> = emptyList(),
        conditionStyles: List<ConditionStyle> = emptyList(),
    ) {
        notificationState = NotificationState(notifications)
        conditionEntries = conditionStyles
        conditionCurrent = emptyMap()
    }

    /**
     * 喂入一则通知事件。
     * @return 锁定表是否真正变化（失配／重复锁定返回 false）。
     */
    fun onEvent(event: NotificationEvent): Boolean {
        val state = notificationState ?: return false
        val before = state.snapshot()
        if (!state.onEvent(event)) return false
        val changed = before != state.snapshot()
        if (changed) notifyStateChanged()
        return changed
    }

    /** 当前锁定表（目标样式名／工具栏按钮 id → 通知）。 */
    fun latchedNotifications(): Map<String, SkinNotification> =
        notificationState?.snapshot() ?: emptyMap()

    /** 被条件样式命名的全部样式名（门控集合）。 */
    fun gatedStyleNames(): Set<String> =
        conditionEntries.flatMap { it.styleNames }.toSet()

    /** 当前条件成立的样式名集合。 */
    fun activeStyleNames(): Set<String> =
        ConditionEvaluator.activeStyleNames(conditionEntries, conditionCurrent)

    /**
     * 写入条件现值。
     * @return 现值是否真正变化。
     */
    fun updateConditionCurrent(current: Map<String, ConditionCurrent>): Boolean {
        if (current == conditionCurrent) return false
        conditionCurrent = current
        notifyStateChanged()
        return true
    }

    fun conditionCurrent(): Map<String, ConditionCurrent> = conditionCurrent
}
