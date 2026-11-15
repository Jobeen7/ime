// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.ContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-L-22：syncUserData 的单飞守门——运行态时第二次调用直接返回，
 * 不重复跑跨进程同步。FakeContext（仓内既有 ContextWrapper(null)
 * 套路）即可：守门在 launch 之前就返回，context 根本不会被碰到
 * （守门失效时 launch 内的 rimeMaintenanceClient() 会在 stub
 * Context 上抛错，测试即失败）。
 */
class FabActionsTest {

    private class FakeContext : ContextWrapper(null)

    @Test
    fun syncUserData_secondTapWhileRunning_returnsImmediately() {
        FabSyncState.setRunning(true)
        try {
            FabActions.syncUserData(FakeContext(), CoroutineScope(Dispatchers.Unconfined))
            assertTrue(FabSyncState.isRunning())
        } finally {
            FabSyncState.setRunning(false)
        }
    }
}
