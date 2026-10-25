// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import timber.log.Timber
import java.util.UUID

/**
 * 应用级方案切换协调器：点选切换、目录切换、导入安装三个入口共用
 * 同一操作锁与同一状态源。
 *
 * - 唯一一台 [SchemeSwitchOrchestrator]：状态机语义不动，本对象只做
 *   委托 + StateFlow 镜像；页面不再 remember 自己的编排器，改为订阅
 *   [state]，重进即看到运行 / 失败 / 回退态，完成回调不再依赖已销毁
 *   组合的局部状态。
 * - 一个 [Mutex] 串行化操作：[tryBegin] 以 tryLock 非阻塞抢锁，抢到
 *   才生成 operationId 并登记当前操作；抢不到（另一方进行中）返回
 *   null，调用方拒绝并提示忙，不排队。锁由操作全程持有（含引擎
 *   部署等待），[endOperation] 凭 id 释放，id 不符不释放、不解锁。
 * - 目录切换与点选切换互斥：[isBusy] 同时看状态机忙态、目录切换
 *   Monitor（SWITCHING）与当前操作三者，任一为真即拒绝新入口，
 *   不再留两套忙态。目录切换的状态显示仍走 [DirSwitchMonitor]
 *  （续切判定与失败提示读它），互斥守门统一读这里。
 * - 导入安装的操作锁可移交目录切换：确认导入在安装成功后调
 *   switchDirectory 时把自己的 operationId 交过去，目录切换接管该
 *   锁直至切换协程结束，中间不留另一入口插队的窗口；目录切换未
 *   启动（守门早退）时不接管，导入方自行释放。
 */
enum class SchemeSwitchOperationKind {
    POINT_SWITCH,
    DIR_SWITCH,
    IMPORT_INSTALL,
}

/** 进行中的操作：每次操作生成唯一 id，记录目标与目录。 */
data class SchemeSwitchOperation(
    val id: String,
    val kind: SchemeSwitchOperationKind,
    val targetId: String,
    val dir: String,
)

object SchemeSwitchCoordinator {

    private val orchestrator = SchemeSwitchOrchestrator()
    private val operationMutex = Mutex()

    private val _state =
        MutableStateFlow<SchemeSwitchState>(SchemeSwitchState.Idle)
    val state: StateFlow<SchemeSwitchState> = _state.asStateFlow()

    private val _currentOperation = MutableStateFlow<SchemeSwitchOperation?>(null)
    val currentOperation: StateFlow<SchemeSwitchOperation?> =
        _currentOperation.asStateFlow()

    private fun syncState() {
        _state.value = orchestrator.state
    }

    /**
     * 三路互斥守门：状态机忙态、目录切换进行中、已有操作持有锁，
     * 任一为真即忙。页面与导入入口的 busy 判断一律读这里。
     */
    fun isBusy(): Boolean =
        orchestrator.isBusy() ||
            DirSwitchMonitor.status.value.phase == DirSwitchPhase.SWITCHING ||
            _currentOperation.value != null

    /**
     * 非阻塞抢操作锁：已忙时返回 null（调用方拒绝并提示忙）；
     * 成功时登记操作并返回唯一 operationId，调用方须在操作结束时
     * 以该 id 调 [endOperation] 释放（目录切换接管的情形除外，见
     * 类注释）。操作日志写清 id、种类、目标与目录。
     */
    fun tryBegin(
        kind: SchemeSwitchOperationKind,
        targetId: String = "",
        dir: String = "",
    ): String? {
        if (!operationMutex.tryLock()) return null
        if (orchestrator.isBusy() ||
            DirSwitchMonitor.status.value.phase == DirSwitchPhase.SWITCHING
        ) {
            operationMutex.unlock()
            return null
        }
        val operation = SchemeSwitchOperation(
            id = UUID.randomUUID().toString(),
            kind = kind,
            targetId = targetId,
            dir = dir,
        )
        _currentOperation.value = operation
        Timber.d(
            "Scheme switch operation begin: id=%s kind=%s target=%s dir=%s",
            operation.id,
            kind,
            targetId,
            dir,
        )
        return operation.id
    }

    /**
     * 凭 id 释放操作锁：id 与当前操作不符时不释放（防止别路误删
     * 他路的锁）；已无当前操作时同样不解锁，避免 Mutex 误解锁。
     */
    fun endOperation(operationId: String) {
        val current = _currentOperation.value
        if (current?.id != operationId) return
        _currentOperation.value = null
        operationMutex.unlock()
        Timber.d("Scheme switch operation end: id=%s", operationId)
    }

    // ---- 状态机委托：转移条件与返回值与 SchemeSwitchOrchestrator 一致，
    // 每次转移后同步 StateFlow 镜像。----

    fun beginSwitch(
        currentId: String,
        targetId: String,
        enabledOrder: List<String>,
    ): SwitchPlan? {
        val plan = orchestrator.beginSwitch(currentId, targetId, enabledOrder)
        syncState()
        return plan
    }

    fun resolveSwitch(verified: Boolean): SwitchVerification {
        val result = orchestrator.resolveSwitch(verified)
        syncState()
        return result
    }

    fun resolveDeploy(verified: Boolean): Boolean {
        val result = orchestrator.resolveDeploy(verified)
        syncState()
        return result
    }

    fun beginRollback(): RollbackPlan? {
        val plan = orchestrator.beginRollback()
        syncState()
        return plan
    }

    fun resolveRollback(verified: Boolean): Boolean {
        val result = orchestrator.resolveRollback(verified)
        syncState()
        return result
    }

    fun dismiss() {
        orchestrator.dismiss()
        syncState()
    }

    /**
     * 仅供单测：把协调器复位到空闲。状态机只从 Failed / RolledBack
     * 可 dismiss 回空闲；进行中的操作须由测试先以正确 id 结束，
     * 遗留的锁在此一并放掉，保证测试间隔离（生产代码不得调用）。
     */
    fun resetForTest() {
        orchestrator.dismiss()
        _currentOperation.value = null
        if (operationMutex.isLocked) {
            operationMutex.unlock()
        }
        syncState()
    }
}
