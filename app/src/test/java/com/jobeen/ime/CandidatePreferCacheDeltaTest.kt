package com.jobeen.ime

import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.data.manager.mergeLoadedSnapshot
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * §3.6 CandidatePreferCache 窗口增量记账的时序规格测试。
 *
 * 规格（从已核实的问题与既有代码注释推导，不看新实现细节）：
 * - snapshot 的 DB 读数是加载窗口开始前的快照；窗口内发生的点击
 *   （noteUpsert）其增量必须在回写后体现在缓存里，不能被 DB 值
 *   盖掉（原 maxOf 合并的缺陷）；
 * - 窗口开始前就已发生的点击，其 DB 写入已落地、会被 DB 读数
 *   包含，不得再叠加（否则重复计数）；
 * - 加载期间被 invalidate：快照整份丢弃，窗口增量也不得泄漏到
 *   下一轮加载；
 * - 加载失败：不置 loaded、直写计数保留，下轮重试的合并不得把
 *   已失败那轮的窗口增量再叠加一次；
 * - 合并成功后账本结清：后续正常加载只认 DB 值。
 *
 * 通过 internal 入口（markLoadStarted/finishLoad）直接驱动真实
 * 状态机，DB 读数以 finishLoad 的入参模拟在不同时点取得的快照。
 */
class CandidatePreferCacheDeltaTest {

    @Before
    fun reset() {
        CandidatePreferCache.invalidate()
    }

    private fun gen(): Int = CandidatePreferCache.generation

    @Test
    fun windowUpsertsOnExistingKeyAreAddedToDbValue() {
        val g = gen()
        CandidatePreferCache.markLoadStarted()
        // 加载窗口内对 DB 已有键点了两次（DB 快照值 10 不含这两次）
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.finishLoad(mapOf("甲" to 10), g)
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])

        // 已 loaded：后续 finishLoad 不得再合并（loaded 守卫）
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.finishLoad(mapOf("甲" to 999), g)
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun windowUpsertOnNewKeyKeepsDirectCount() {
        val g = gen()
        CandidatePreferCache.markLoadStarted()
        repeat(3) { CandidatePreferCache.noteUpsert("新词") }
        CandidatePreferCache.finishLoad(mapOf("甲" to 5), g)
        val cache = CandidatePreferCache.cacheCopy()
        // 新键不在 DB 快照里：直写计数即全部计数
        assertEquals(3, cache["新词"])
        // DB 独有键按 DB 值落缓存
        assertEquals(5, cache["甲"])
    }

    @Test
    fun upsertsBeforeLoadAreNotDoubleCounted() {
        // 加载开始前发生的点击：DB 写入已落地，DB 读数（1）已包含它，
        // 回写不得再 +1
        CandidatePreferCache.noteUpsert("乙")
        val g = gen()
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.finishLoad(mapOf("乙" to 1), g)
        assertEquals(1, CandidatePreferCache.cacheCopy()["乙"])
    }

    @Test
    fun dbValueWinsWhenLargerThanPreLoadDirectCount() {
        CandidatePreferCache.noteUpsert("乙")
        val g = gen()
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.finishLoad(mapOf("乙" to 7), g)
        assertEquals(7, CandidatePreferCache.cacheCopy()["乙"])
    }

    @Test
    fun invalidateDuringLoadDiscardsSnapshotAndDelta() {
        val g0 = gen()
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.noteUpsert("甲")
        // 加载期间发生还原/裁剪：代次推进、缓存清空
        CandidatePreferCache.invalidate()
        CandidatePreferCache.finishLoad(mapOf("甲" to 10), g0)
        assertFalse(CandidatePreferCache.cacheCopy().containsKey("甲"))

        // 下一轮干净加载：旧窗口增量（+1）不得泄漏进来
        val g1 = gen()
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.finishLoad(mapOf("甲" to 4), g1)
        assertEquals(4, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun failedLoadPreservesDirectUpsertsAndDeltaDoesNotLeakIntoRetry() {
        val g = gen()
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.finishLoad(null, g)
        // 失败不置 loaded、直写计数保留，供进程内继续使用
        assertEquals(2, CandidatePreferCache.cacheCopy()["甲"])

        // 重试：这次 DB 读数（2）已包含那两次点击（其 DB 写入早已
        // 落地），失败那轮的窗口账本若泄漏会算成 4
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.finishLoad(mapOf("甲" to 2), g)
        assertEquals(2, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun deltaClearedAfterSuccessfulMerge() {
        val g = gen()
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.finishLoad(mapOf("甲" to 10), g)
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])

        // 还原后重新加载：DB 值已是 12（点击都已落库），账本早已
        // 结清，不得再叠加成 14
        CandidatePreferCache.invalidate()
        val g2 = gen()
        CandidatePreferCache.markLoadStarted()
        CandidatePreferCache.finishLoad(mapOf("甲" to 12), g2)
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun mergeArithmeticDirectly() {
        val cache = ConcurrentHashMap<String, Int>()
        cache["新词"] = 3
        mergeLoadedSnapshot(
            cache,
            loadedMap = mapOf("甲" to 10, "乙" to 1),
            delta = mapOf("甲" to 2, "丙" to 9),
        )
        assertEquals(12, cache["甲"]) // DB 值 + 窗口增量
        assertEquals(1, cache["乙"]) // 无增量按 DB 值
        assertEquals(3, cache["新词"]) // 不在快照里的键不动
        assertFalse(cache.containsKey("丙")) // 账本里有、快照与缓存都没有的键不凭空生成
    }
}
