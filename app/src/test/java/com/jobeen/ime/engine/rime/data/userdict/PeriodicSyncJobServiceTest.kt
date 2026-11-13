// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import com.jobeen.ime.input.ImeInputMethodService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 定期同步待同步词典表的 JVM 可测部分。
 *
 * 口径（与 syncAllDicts 一致）：stats 是输入统计数据库、导出恒 -1，
 * 不是用户词库，滤掉不计失败；仅含 stats 时待同步表为空、无上传无失败。
 * 上传循环本身（JobService / Rime 会话）不在此测，需真机复核。
 */
class PeriodicSyncJobServiceTest {

    @Test
    fun dictsToSync_statsFilteredOut() {
        assertEquals(
            listOf("wanxiang_lite", "en"),
            PeriodicSyncJobService.dictsToSync(listOf("wanxiang_lite", "stats", "en")),
        )
    }

    @Test
    fun dictsToSync_normalDictsKept() {
        assertEquals(
            listOf("wanxiang_lite", "en"),
            PeriodicSyncJobService.dictsToSync(listOf("wanxiang_lite", "en")),
        )
    }

    @Test
    fun dictsToSync_onlyStats_empty() {
        assertEquals(
            emptyList<String>(),
            PeriodicSyncJobService.dictsToSync(listOf("stats")),
        )
    }

    @Test
    fun dictsToSync_emptyList_empty() {
        assertEquals(
            emptyList<String>(),
            PeriodicSyncJobService.dictsToSync(emptyList()),
        )
    }

    // WO39 E2-9：可见性门判定（纯函数）。

    @Test
    fun decideSyncStart_disabledAndVisible_skippedNotRescheduled() {
        assertEquals(
            PeriodicSyncJobService.SyncGateDecision.SKIP_DISABLED,
            PeriodicSyncJobService.decideSyncStart(enabled = false, keyboardVisible = true),
        )
    }

    @Test
    fun decideSyncStart_disabledAndHidden_skippedNotRescheduled() {
        assertEquals(
            PeriodicSyncJobService.SyncGateDecision.SKIP_DISABLED,
            PeriodicSyncJobService.decideSyncStart(enabled = false, keyboardVisible = false),
        )
    }

    @Test
    fun decideSyncStart_enabledAndHidden_rescheduled() {
        assertEquals(
            PeriodicSyncJobService.SyncGateDecision.RESCHEDULE,
            PeriodicSyncJobService.decideSyncStart(enabled = true, keyboardVisible = false),
        )
    }

    @Test
    fun decideSyncStart_enabledAndVisible_runs() {
        assertEquals(
            PeriodicSyncJobService.SyncGateDecision.RUN,
            PeriodicSyncJobService.decideSyncStart(enabled = true, keyboardVisible = true),
        )
    }

    @Test
    fun keyboardVisibleFlag_defaultsFalse() {
        // 进程新起只跑 job 时服务未跑过、标志默认假——键盘确实未可见。
        assertFalse(ImeInputMethodService.isKeyboardVisible)
    }
}
