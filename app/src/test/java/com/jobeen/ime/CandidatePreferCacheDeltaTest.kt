package com.jobeen.ime

import com.jobeen.ime.data.database.CandidatePrefer
import com.jobeen.ime.data.database.CandidatePreferDao
import com.jobeen.ime.data.manager.CandidatePreferCache
import com.jobeen.ime.data.manager.mergeLoadedSnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * CandidatePreferCache 串行化加载/落点的时序规格测试（§3.6 复核后重做）。
 *
 * 规格（从已核实的问题推导）：读库点与写入端的「DAO upsert →
 * noteUpsert 落点」必须严格先后，旧实现两边无先后关系、靠窗口增量
 * 账本事后对账，账本在「点击的 DB 写先于读数落地」时双计。新实现
 * 把读库与落点收进同一条串行通道（limitedParallelism(1)）FIFO
 * 执行，只剩两种合法时序：
 * - 读数整体在点击对子之前：点击不在读数里，落点在合并完成后
 *   直加缓存（点击不丢）；
 * - 读数整体在点击对子之后：读数已含点击，落点更早已计入缓存，
 *   合并取 maxOf（不重复计数）。
 * 合并因此回归纯 maxOf，不再有增量叠加。另：
 * - 加载期间被 invalidate：快照按代次整份丢弃，已投递未执行的
 *   落点按代次作废，不泄漏到下一轮加载；
 * - 加载失败：不置 loaded、已落点计数保留，重试的合并不得把
 *   同一批点击再算一次。
 *
 * 通过 internal 入口（finishLoad）直接驱动状态机，DB 读数以
 * finishLoad 的入参模拟在不同时点取得的快照；noteUpsert 的落点
 * 是异步投递，断言前先 flushLandings 等落点执行完。
 *
 * 生产写路径自第十一批起统一走 recordClick（upsert、节流裁剪与
 * 落点在缓存侧同一串行通道段内成对完成，见
 * recordClickPairingPreventsDoubleCount 的假 DAO 实跑覆盖）；其
 * 余用例仍以 finishLoad/noteUpsert 直接驱动时序状态机。
 */
class CandidatePreferCacheDeltaTest {

    @Before
    fun reset() {
        CandidatePreferCache.invalidate()
    }

    private fun gen(): Int = CandidatePreferCache.generation

    private fun flush() = runBlocking { CandidatePreferCache.flushLandings() }

    @Test
    fun clicksLandedAfterLoadAddOnTopOfDbValue() {
        // 时序一（读数在点击对子之前）：先完成加载（DB 值 10 不含
        // 之后发生的两次点击），点击随后落点直加，不被 DB 值盖掉
        val g = gen()
        CandidatePreferCache.finishLoad(mapOf("甲" to 10), g)
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.noteUpsert("甲")
        flush()
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])

        // 已 loaded：后续 finishLoad 不得再合并（loaded 守卫）
        CandidatePreferCache.finishLoad(mapOf("甲" to 999), g)
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun windowClickAlreadyInDbSnapshotIsNotDoubleCounted() {
        // 时序二（读数在点击对子之后）：窗口内点了一次，其 DB 写
        // 先于读数落地，DB 快照（11）已包含这次点击；落点也已把
        // 缓存计到 1。合并取 maxOf 得 11——旧账本实现会在此再
        // 叠加窗口增量算成 12（双计），本用例即为钉住该回归
        CandidatePreferCache.noteUpsert("甲")
        flush()
        assertEquals(1, CandidatePreferCache.cacheCopy()["甲"])
        val g = gen()
        CandidatePreferCache.finishLoad(mapOf("甲" to 11), g)
        assertEquals(11, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun windowUpsertOnNewKeyKeepsDirectCount() {
        CandidatePreferCache.noteUpsert("新词")
        CandidatePreferCache.noteUpsert("新词")
        CandidatePreferCache.noteUpsert("新词")
        flush()
        val g = gen()
        CandidatePreferCache.finishLoad(mapOf("甲" to 5), g)
        val cache = CandidatePreferCache.cacheCopy()
        // 新键不在 DB 快照里：落点直写计数即全部计数，合并不得动它
        assertEquals(3, cache["新词"])
        // DB 独有键按 DB 值落缓存
        assertEquals(5, cache["甲"])
    }

    @Test
    fun upsertsBeforeLoadAreNotDoubleCounted() {
        // 加载开始前发生的点击：DB 写入已落地，DB 读数（1）已包含它，
        // 合并不得再 +1
        CandidatePreferCache.noteUpsert("乙")
        flush()
        val g = gen()
        CandidatePreferCache.finishLoad(mapOf("乙" to 1), g)
        assertEquals(1, CandidatePreferCache.cacheCopy()["乙"])
    }

    @Test
    fun dbValueWinsWhenLargerThanPreLoadDirectCount() {
        CandidatePreferCache.noteUpsert("乙")
        flush()
        val g = gen()
        CandidatePreferCache.finishLoad(mapOf("乙" to 7), g)
        assertEquals(7, CandidatePreferCache.cacheCopy()["乙"])
    }

    @Test
    fun invalidateDuringLoadDiscardsSnapshotAndLandings() {
        val g0 = gen()
        CandidatePreferCache.noteUpsert("甲")
        // 加载期间发生还原/裁剪：代次推进、缓存清空；已投递的落点
        // 按代次作废，快照回写按代次丢弃
        CandidatePreferCache.invalidate()
        flush()
        CandidatePreferCache.finishLoad(mapOf("甲" to 10), g0)
        assertFalse(CandidatePreferCache.cacheCopy().containsKey("甲"))

        // 下一轮干净加载：旧点击（+1）不得泄漏进来
        val g1 = gen()
        CandidatePreferCache.finishLoad(mapOf("甲" to 4), g1)
        assertEquals(4, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun failedLoadPreservesLandedUpsertsAndRetryDoesNotDoubleCount() {
        val g = gen()
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.noteUpsert("甲")
        flush()
        CandidatePreferCache.finishLoad(null, g)
        // 失败不置 loaded、已落点计数保留，供进程内继续使用
        assertEquals(2, CandidatePreferCache.cacheCopy()["甲"])

        // 重试：这次 DB 读数（2）已包含那两次点击（其 DB 写入早已
        // 落地），合并取 maxOf 不得算成 4
        CandidatePreferCache.finishLoad(mapOf("甲" to 2), g)
        assertEquals(2, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun landedCountsSurviveReloadCycle() {
        // 加载后落点的计数（12 = DB 10 + 2 次点击）是真值
        val g = gen()
        CandidatePreferCache.finishLoad(mapOf("甲" to 10), g)
        CandidatePreferCache.noteUpsert("甲")
        CandidatePreferCache.noteUpsert("甲")
        flush()
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])

        // 还原后重新加载：DB 值已是 12（点击都已落库），合并只认
        // DB 值，不得再叠加成 14
        CandidatePreferCache.invalidate()
        val g2 = gen()
        CandidatePreferCache.finishLoad(mapOf("甲" to 12), g2)
        assertEquals(12, CandidatePreferCache.cacheCopy()["甲"])
    }

    @Test
    fun recordClickPairingPreventsDoubleCount() {
        runBlocking {
            // 经生产写入口 recordClick 落库+落点：返回时对子已在
            // 串行通道内成对完成，DB 与缓存同为 1
            val dao = FakePreferDao()
            CandidatePreferCache.recordClick("甲", dao)
            assertEquals(1, dao.countOf("甲"))
            assertEquals(1, CandidatePreferCache.cacheCopy()["甲"])

            // 随后一轮加载的读数（1）已包含这次点击：合并取 maxOf，
            // 落点不得再 +1——旧实现里 upsert 在另一条通道、落点经
            // noteUpsert 投递，读段插进对子中间时此交错双计为 2
            val g = gen()
            CandidatePreferCache.finishLoad(mapOf("甲" to 1), g)
            assertEquals(1, CandidatePreferCache.cacheCopy()["甲"])

            // 再点一次：DB 与缓存同步推进到 2
            CandidatePreferCache.recordClick("甲", dao)
            assertEquals(2, dao.countOf("甲"))
            assertEquals(2, CandidatePreferCache.cacheCopy()["甲"])
        }
    }

    /** 内存版 DAO：只实现 recordClick/getAllCounts 用到的计数语义。 */
    private class FakePreferDao : CandidatePreferDao {
        private val counts = mutableMapOf<String, Int>()

        fun countOf(text: String): Int = counts[text] ?: 0

        override suspend fun get(text: String): CandidatePrefer? = null

        override suspend fun getAllByTextIn(texts: List<String>): List<CandidatePrefer> =
            emptyList()

        override suspend fun getAllCounts(): List<CandidatePreferDao.PreferCount> =
            counts.map { (text, count) -> CandidatePreferDao.PreferCount(text, count) }

        override suspend fun upsert(text: String, context: String, now: Long) {
            counts[text] = (counts[text] ?: 0) + 1
        }

        override suspend fun count(): Int = counts.size

        override suspend fun getAllFull(): List<CandidatePrefer> = emptyList()

        override suspend fun insertAll(items: List<CandidatePrefer>) = Unit

        override suspend fun deleteAll() {
            counts.clear()
        }

        override suspend fun pruneToLimit(limit: Int) = Unit
    }

    @Test
    fun mergeArithmeticDirectly() {
        val cache = ConcurrentHashMap<String, Int>()
        cache["新词"] = 3
        cache["丙"] = 9
        mergeLoadedSnapshot(
            cache,
            loadedMap = mapOf("甲" to 10, "乙" to 1, "丙" to 4),
        )
        assertEquals(10, cache["甲"]) // 快照独有键按 DB 值
        assertEquals(1, cache["乙"]) // 快照独有键按 DB 值
        assertEquals(9, cache["丙"]) // 两边都有取较大者（纯 maxOf，无增量叠加）
        assertEquals(3, cache["新词"]) // 不在快照里的键不动
    }
}
