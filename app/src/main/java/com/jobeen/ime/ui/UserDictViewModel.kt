// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.jobeen.ime.ui.screen.UserDictUiState

/**
 * 用户词典页状态持有者（C-L-42）：
 *
 * - uiState 原是 Activity 字段，旋转重建后整页状态（进行中 busy、
 *   词典列表、WebDAV 字段、对话框）全丢；移到 ViewModel 后旋转
 *   只重建 View，状态保留；
 * - SavedStateHandle 只记 busy＋任务 runId（进程被杀后重建仍能
 *   恢复“传输进行中”提示；其余字段重进页时由 Activity 从
 *   UserDictPrefs 读回／重新加载，不进 handle）；
 * - 终端态 60 秒丢弃与 consumeIfNewTerminal 口径不变（仍在
 *   Activity 的 observeRemoteTransfer 里）。
 */
class UserDictViewModel(
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val uiState = UserDictUiState()

    init {
        // 进程被杀后重建：busy 仍在进行中（前台服务未死），恢复提示。
        if (savedStateHandle.get<Boolean>(KEY_BUSY) == true) {
            uiState.busy = true
            uiState.busyMessage = savedStateHandle.get<String>(KEY_BUSY_MESSAGE) ?: ""
        }
    }

    /** 本次等待的传输 runId（RemoteTransferState.runId）。 */
    val runId: Long?
        get() = savedStateHandle.get<Long>(KEY_RUN_ID)

    fun setBusy(message: String) {
        uiState.busyMessage = message
        uiState.busy = true
        savedStateHandle[KEY_BUSY] = true
        savedStateHandle[KEY_BUSY_MESSAGE] = message
    }

    fun setIdle() {
        uiState.busy = false
        uiState.busyMessage = ""
        savedStateHandle[KEY_BUSY] = false
        savedStateHandle.remove<String>(KEY_BUSY_MESSAGE)
    }

    /** 传输开始（RUNNING 态到达）时记 runId，供重建后核对归属。 */
    fun noteRunId(runId: Long) {
        savedStateHandle[KEY_RUN_ID] = runId
    }

    companion object {
        private const val KEY_BUSY = "user_dict_busy"
        private const val KEY_BUSY_MESSAGE = "user_dict_busy_message"
        private const val KEY_RUN_ID = "user_dict_run_id"
    }
}
