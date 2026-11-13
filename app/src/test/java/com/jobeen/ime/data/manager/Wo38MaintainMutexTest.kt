// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.manager

import com.jobeen.ime.skin.runtime.SkinGeneration
import com.jobeen.ime.ui.SingleFlightClaim
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO38 E1-4 maintain() 维护互斥审计的测试钉子。
 *
 * 审计结论（2026-11-13，代码库全仓 grep＋逐入口核查）：
 * - `maintain()` 根本不存在：全仓 grep（大小写不敏感）零匹配，
 *   无函数可补锁；实际维护入口是部署／同步／切换三路，见下表。
 * - 部署：AppStartup.initialize 全程 synchronized(lock)，单调用点
 *   （Application.onCreate，每进程一次）；KeyboardDeployController
 *   全检查—置位在 stateLock 内原子完成＋executing 双跑守门
 *   （D-M7）；RimeEngine.deployAndAwait 持 deployMutex（tryLock）
 *   ＋ deployGateLock 查—置串行＋ EngineDeployState CAS 抢占，
 *   reload() 同闸丢弃（RimeEngine.kt，另一批在改，只读审计）。
 * - 同步：WebDavSync 全部变更入口（upload／download／
 *   uploadSchemes／downloadSchemes／syncAllDicts）持 syncMutex；
 *   FAB 侧 FabSyncState 单飞守门＋同一 syncMutex；定期任务
 *   PeriodicSyncJobService 逐词典走同一 upload（JobScheduler
 *   单实例＋每词典互斥串行，重叠只多做无用功、不 corrupt）。
 *   deleteRemoteFile（RimeScreen 孤儿清理，用户确认的单发动作）
 *   未持 syncMutex，但删的是无本地副本的远端孤儿文件、清理只删
 *   根目录非目录项（方案文件在 schemes/ 子目录下够不着）；与同
 *   步的最坏交错是远端文件被随后同步重建或某条目 404 计入失败，
 *   本地无损、下次同步自愈——不是并发进入 hazard，不补锁（补了
 *   反而让清理等一次可能数分钟的同步，属行为劣化）。
 * - 切换：方案切换经 RimeEngine 串行动作通道
 *   （for (action in actions) reduce(action)，单消费者）；目录切
 *   换由 UI 线程 dirSwitching 标志＋EngineDeployState.isRunning()
 *   ＋UpdateImportGate 交叉闸守门（SchemaSettingsScreen，另一批
 *   在改，只读审计）；皮肤切换的 SkinGeneration／FingerprintCache
 *   各自持锁（SkinSwitchPolicy）。
 *
 * 本文件只钉审计中**尚无并发覆盖**的三处守门；已有并发覆盖的不
 * 重复钉（出处见各条）：
 * - KeyboardDeployController 并发 requestDeploy／execute 已由
 *   KeyboardDeployControllerTest.concurrentRequestDeploy_exactlyOneWins
 *   与 concurrentExecute_exactlyOneRuns 钉住；
 * - SkinGeneration／FingerprintCache 顺序行为已由
 *   Batch5SkinGuardsTest.switchPolicy_fingerprintCacheAndDecisions 钉住；
 * - EngineDeployState 标志往返与 DeployKeyGate 真值表已由
 *   EngineDeployStateTest 钉住（未钉并发抢占，本文件补）。
 *
 * WebDavSync.syncMutex 与 RimeEngine.deployMutex／deployGateLock
 * 需网络／引擎会话，JVM 单测够不着，只审计、不单测。
 */
class Wo38MaintainMutexTest {

    /** 多线程同起跑：起跑枪响前全部线程就绪，避免串行起跑掩盖竞争。 */
    private fun runConcurrent(threadCount: Int, block: () -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        repeat(threadCount) {
            Thread {
                try {
                    start.await()
                    block()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue("threads did not finish in time", done.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun singleFlightClaim_concurrentTryClaim_exactlyOneWins() {
        // U-S1 单飞守门：16 路并发抢占，只一路抢到；释放后可再抢。
        val claim = SingleFlightClaim()
        val wins = AtomicInteger(0)
        runConcurrent(16) {
            if (claim.tryClaim()) wins.incrementAndGet()
        }
        assertEquals(1, wins.get())
        assertFalse(claim.tryClaim())
        claim.release()
        assertTrue(claim.tryClaim())
        claim.release()
    }

    @Test
    fun deployState_concurrentTryAcquireRunning_exactlyOneWins() {
        // 部署位 CAS 抢占：16 路并发，只一路抢到；本测试认领到的
        // 位在 finally 释放，不污染其他测试（全局单例）。
        assertFalse(
            "deploy flag leaked by another test",
            EngineDeployState.isRunning(),
        )
        val acquired = AtomicInteger(0)
        var mine = false
        try {
            runConcurrent(16) {
                if (EngineDeployState.tryAcquireRunning()) acquired.incrementAndGet()
            }
            assertEquals(1, acquired.get())
            assertTrue(EngineDeployState.isRunning())
            mine = true
        } finally {
            if (mine) EngineDeployState.releaseRunning()
        }
        assertFalse(EngineDeployState.isRunning())
    }

    @Test
    fun skinGeneration_concurrentBump_allDistinct() {
        // 皮肤世代号：32 路并发 bump，返回值两两不同、恰为 1..32。
        val gen = SkinGeneration()
        val seen = Collections.synchronizedSet(HashSet<Long>())
        runConcurrent(32) {
            seen += gen.bump()
        }
        assertEquals(32, seen.size)
        assertEquals((1L..32L).toSet(), seen)
        assertEquals(32L, gen.current())
    }
}
