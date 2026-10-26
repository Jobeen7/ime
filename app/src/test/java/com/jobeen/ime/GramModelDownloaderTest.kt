package com.jobeen.ime

import com.jobeen.ime.base.ngram.DownloadGate
import com.jobeen.ime.base.ngram.UpdateImportGate
import com.jobeen.ime.base.ngram.deployBlocksUpdate
import com.jobeen.ime.data.manager.EngineDeployState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨下载器共用下载闸（4.3/N3）：语法模型单独下载与方案更新共用
 * 一把闸，任一路持有期间另一路必须被拒（BUSY），释放后可再抢。
 * DownloadGate 是进程级单例，本类是其唯一测试使用者，抢放成对。
 * 更新/导入交叉闸与部署挡更新判定见本类后半用例（缺陷 1 规格）。
 */
class GramModelDownloaderTest {

    @Test
    fun downloadGate_secondAcquireRejectedUntilRelease() {
        assertTrue(DownloadGate.tryAcquire())
        try {
            assertTrue(DownloadGate.isBusy)
            // 已被持有：第二路（无论同路重复调用还是跨路调用）被拒
            assertFalse(DownloadGate.tryAcquire())
        } finally {
            DownloadGate.release()
        }
        assertFalse(DownloadGate.isBusy)
        assertTrue(DownloadGate.tryAcquire())
        DownloadGate.release()
    }

    // ---- 更新/导入交叉闸：更新两路与导入事务互斥 ----

    // 断言与旧版逐条对应，仅调用形态随凭证 API 迁移（抢占返回凭证、
    // 释放交回凭证）；新增他方凭证误放不生效一节。旧版首行的裸释放
    // （防前序用例泄漏）已无对应物：本类是交叉闸唯一测试使用者，
    // 且每次抢放成对、无泄漏源。
    @Test
    fun updateImportGate_secondAcquireRejectedUntilRelease() {
        val first = UpdateImportGate.tryAcquire()
        assertNotNull(first)
        try {
            assertTrue(UpdateImportGate.isHeld)
            // 对方（更新或导入）持有期间，另一方入口必须被拒
            assertNull(UpdateImportGate.tryAcquire())
            // 他方凭证误放不生效：持有仍在、入口仍被拒
            UpdateImportGate.release(Any())
            assertTrue(UpdateImportGate.isHeld)
            assertNull(UpdateImportGate.tryAcquire())
        } finally {
            UpdateImportGate.release(first)
        }
        assertFalse(UpdateImportGate.isHeld)
        val second = UpdateImportGate.tryAcquire()
        assertNotNull(second)
        UpdateImportGate.release(second)
        assertFalse(UpdateImportGate.isHeld)
    }

    // ---- 部署挡更新判定：进行中或超时孤儿任一即挡 ----

    @Test
    fun deployBlocksUpdate_runningOrOrphanBlocks_idlePasses() {
        assertTrue(deployBlocksUpdate(deployRunning = true, orphanRunning = false))
        assertTrue(deployBlocksUpdate(deployRunning = false, orphanRunning = true))
        assertTrue(deployBlocksUpdate(deployRunning = true, orphanRunning = true))
        assertFalse(deployBlocksUpdate(deployRunning = false, orphanRunning = false))
    }

    @Test
    fun deployBlocksUpdate_liveStateFollowsEngineDeployState() {
        EngineDeployState.setRunning(false)
        EngineDeployState.setOrphanRunning(false)
        try {
            assertFalse(deployBlocksUpdate())
            EngineDeployState.setRunning(true)
            assertTrue(deployBlocksUpdate())
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(true)
            assertTrue(deployBlocksUpdate())
        } finally {
            EngineDeployState.setRunning(false)
            EngineDeployState.setOrphanRunning(false)
        }
        assertFalse(deployBlocksUpdate())
    }
}
