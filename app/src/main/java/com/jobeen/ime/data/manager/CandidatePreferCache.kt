package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.CandidatePreferDao
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * 选词偏好常驻内存缓存。
 *
 * 每次出候选都要查偏好表做重排/联想加权；表是"写少读多"（只在上屏选词时 upsert），
 * 因此首次按需全量加载进内存，之后读走内存、写经本类串行通道落点更新内存，
 * 省掉每轮候选一次 DB 查询。
 *
 * 写路径用 ConcurrentHashMap.merge 原子递增，不再每次复制整张表。
 */
object CandidatePreferCache {

    private val cache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var loaded = false

    /**
     * 缓存代次：每次 [invalidate] 自增。后台加载开始时记下代次，
     * 回写时若代次已变（加载期间发生过还原/裁剪清空），说明这次
     * 读到的是清空前的数据——整份丢弃、不合并、不置 loaded，
     * 下次 snapshot 重新加载。
     */
    @Volatile
    internal var generation = 0
    private val lock = Any()

    /**
     * 加载、落库与落点的串行通道（IO + limitedParallelism(1)）。
     *
     * 要保的不变量：读库（[snapshot] 的 getAllCounts）与一次点击的
     * 「DAO upsert → 计数落点」对子严格先后——读数要么整体在对子
     * 之前（点击不在读数里，随后由落点直加缓存），要么整体在对子
     * 之后（读数已含点击，落点已先行计入缓存、合并取 maxOf 不重
     * 复）。生产写路径统一走 [recordClick]：upsert、节流裁剪与落
     * 点在同一段通道代码内依次执行。此前 upsert 在 RimeEngine 的
     * preferDbScope 另一条通道上执行、只有落点经 [noteUpsert] 投
     * 递到本通道，对子被拆在两条通道上：读段若排在 upsert 之后、
     * 落点之前执行，读数已含这次点击而落点随后又 +1，该交错下
     * 双计真实可达，收编即为消除这一拆分。
     *
     * 但通道 FIFO 只保证「同一时刻只有一段在跑」，不保证跨挂起
     * 的不可分割：recordClick 在 dao.upsert（Room 挂起调用）处挂
     * 起时会让出通道的唯一 worker，排队中的 snapshot 读库段可以
     * 插入运行——若 upsert 已提交、写协程尚未恢复，读数含本次点
     * 击而落点随后又 +1，双计在收编后依然可达（审查 F6）。跨挂
     * 起的互斥由 [transactionMutex] 提供：加载段与点击对子段都
     * 必须在 Mutex 内执行，通道只负责把它们送到同一执行域。
     */
    private val serialDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * 跨挂起互斥：[snapshot] 的加载段、[recordClick] 全段（upsert
     * →落点→节流裁剪）与 [noteUpsert] 的落点段都在本锁内执行，
     * 任一段在 DAO 挂起期间不允许另一段插入。
     *
     * 锁序纪律：本锁恒为最外层；既有 [lock]（synchronized）只护
     * 代次校验与缓存合并的短临界区，只允许出现在本锁内层（经
     * [landIfCurrent]/[finishLoad]）或单独出现（[invalidate]），
     * 绝不反向嵌套。[invalidate] 刻意不取本锁：它由非挂起调用方
     * （备份还原）同步调用，靠推进代次使飞行中的加载/落点在恢
     * 复后自检作废，无需与本锁互斥。
     */
    private val transactionMutex = Mutex()

    /**
     * [noteUpsert] 落点的投递作用域：noteUpsert 本身是同步签名
     * （写入端在 DAO 落库后同段代码内调用、不能挂起），落点改为
     * 投递到 [serialDispatcher] 异步执行。落点捕获调用时的代次，
     * 执行时若代次已变（期间发生过 invalidate）直接丢弃——那次点
     * 击所属的表已被整表替换/裁剪，其计数不得复活进新缓存，后续
     * 加载会按新表读数重建。异常兜底只记日志：偏好计数是尽力而
     * 为的写，不能赔上调用方进程。
     */
    private val landingScope = CoroutineScope(
        SupervisorJob() + serialDispatcher +
            CoroutineExceptionHandler { _, e ->
                Timber.w(e, "CandidatePreferCache landing failed")
            }
    )

    /**
     * 返回 text → click_count。首次调用在本类串行通道上全量加载
     * （读库与回写合并同段执行，不与点击对子交错）。
     */
    suspend fun snapshot(context: Context): Map<String, Int> {
        if (loaded) return cache
        return loadSnapshot(AppDatabase.getInstance(context).candidatePreferDao())
    }

    /**
     * [snapshot] 的加载段本体（DAO 注入形态，供单测以假 DAO 驱动
     * 与 [recordClick] 的真实交错）。整段在 [transactionMutex] 内：
     * 读库中途即使挂起，点击对子也不能插入——读数要么整体在对子
     * 之前，要么在对子之后，不存在「读数已含点击、落点再 +1」的
     * 中间态。
     */
    internal suspend fun loadSnapshot(dao: CandidatePreferDao): Map<String, Int> {
        return withContext(serialDispatcher) {
            transactionMutex.withLock {
                // 锁外的一次 loaded 检查只是快速路径：进锁后先复查，
                // 排队期间已由另一段完成加载时直接返回，不重复读库
                if (loaded) return@withLock cache
                val gen = generation          // 加载前记下代次
                val loadedMap = runCatching {
                    dao.getAllCounts().associate { it.text to it.click_count }
                }.getOrElse {
                    Timber.e(it, "candidate prefer cache load failed")
                    null
                }
                // 加载失败不能置 loaded：否则本进程内偏好永远为空且不再重试
                finishLoad(loadedMap, gen)
                cache
            }
        }
    }

    /** 偏好表容量上限（随生产写路径自 RimeEngine 收编至此，值不变）。 */
    private const val PREFER_LIMIT = 5000

    /** 每多少次点击检查一次容量（节流，避免每次选词都跑 COUNT）。 */
    private const val PRUNE_CHECK_INTERVAL = 200

    /** 只在 [serialDispatcher] 通道内读写，无需同步。 */
    private var recordClicksSincePruneCheck = 0

    /**
     * 生产写路径：一次选词点击的落库与计数落点。
     *
     * 整段在 [serialDispatcher] 上、[transactionMutex] 内执行：
     * dao.upsert 成功后在同一段内把计数落点合并进缓存，随后做节
     * 流容量检查（超限裁剪并 [invalidate]——本次落点若已合并会
     * 随之清掉，与裁剪后的 DB 在下次 [snapshot] 重建时对齐，不
     * 单独留账）。裁剪段一并纳入本锁：它读写的是同一张偏好表与
     * 缓存代次，若留在锁外，其 prune/invalidate 与加载段的交错
     * 还要再证一遍；纳入后整段对加载段原子，结论直接随对子成
     * 立，且裁剪本身不另写缓存计数、无独立双计面。异常由调用方
     * 按其既有口径吸收（写入是尽力而为，失败只丢这一次偏好）。
     */
    suspend fun recordClick(text: String, dao: CandidatePreferDao) {
        withContext(serialDispatcher) {
            transactionMutex.withLock {
                val gen = generation
                // 隐私：不把光标前文存入 context 列——该列从未被排序/
                // 重排读取，却会随云备份与 .jbk 备份带出用户输入片段
                //（口径随写路径自 RimeEngine 迁入，保持不变）
                dao.upsert(text, "")
                // 落点紧随 upsert 先行：裁剪段的 count/prune 若抛异常
                // （盘满/库忙），不得吞掉这次已落库点击的计数（旧实现
                // noteUpsert 在裁剪之前投递，落点不丢，此序保持之）
                landIfCurrent(text, gen)
                // 偏好表容量上限：节流检查，超限时裁剪并让缓存重载
                //（保持两者一致；刚落的点若随 invalidate 清掉，下次
                // snapshot 按裁剪后的 DB 重建对齐，本就如此）。裁剪是
                // 尽力而为的维护动作，失败只记日志、不冒泡连累点击主
                // 路径——异常在本段内吸收（取消除外）
                try {
                    if (++recordClicksSincePruneCheck >= PRUNE_CHECK_INTERVAL) {
                        recordClicksSincePruneCheck = 0
                        if (dao.count() > PREFER_LIMIT) {
                            dao.pruneToLimit(PREFER_LIMIT)
                            invalidate()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Failed to prune candidate preference table")
                }
            }
        }
    }

    /**
     * 代次未变时把一次点击计数合并进缓存。代次校验与计数同在
     * lock 内：与 [invalidate] 的清缓存/推进代次互斥，防「校验通
     * 过后被清、随后又把计数写回空缓存」的交错。代次已变（期间
     * 发生过还原/裁剪）时直接丢弃——这次点击所属的表已被整表替
     * 换，其计数不得复活进新缓存，后续加载按新表读数重建。
     */
    private fun landIfCurrent(text: String, gen: Int) {
        synchronized(lock) {
            if (gen == generation) {
                cache.merge(text, 1, Int::plus)
            }
        }
    }

    /**
     * 加载收尾：在代次未变时把 DB 快照合并进缓存并置 loaded。
     * [loadedMap] 为 null 表示加载失败：不合并、不置 loaded，下次
     * [snapshot] 重新加载（失败语义与原实现一致）；失败前已落点
     * 的直写计数保留在缓存里供进程内继续使用。
     */
    internal fun finishLoad(loadedMap: Map<String, Int>?, gen: Int) {
        synchronized(lock) {
            if (loadedMap != null && !loaded && gen == generation) {
                mergeLoadedSnapshot(cache, loadedMap)
                loaded = true
            }
        }
    }

    /**
     * 落点原语：把一次已落库点击的计数投递到串行通道异步合并。
     * 生产写路径走 [recordClick]（upsert 与落点同段完成）；本入口
     * 保留给测试直接驱动落点时序，以及任何无法走 [recordClick]
     * 的调用方——注意单独调用它不构成「upsert → 落点」对子的串
     * 行保证（见 [serialDispatcher] 的不变量说明）。
     */
    fun noteUpsert(text: String) {
        val gen = generation
        landingScope.launch {
            // 落点段同在跨挂起互斥内：与加载段、点击对子段统一口径，
            // 不留一条能在对子挂起窗口插入的旁路落点
            transactionMutex.withLock {
                landIfCurrent(text, gen)
            }
        }
    }

    /**
     * 偏好表被整表替换/裁剪时调用（备份还原、容量裁剪）：清缓存并
     * 推进代次，使正在后台加载的旧数据回写时被 [snapshot] 的代次
     * 校验丢弃——否则旧数据会被 merge 回来并置 loaded，还原/裁剪
     * 在内存层等于白做、进程重启前候选一直按旧偏好排序。
     * 代次推进同时作废尚未执行的 [noteUpsert] 落点（其计数属于
     * 旧表），不需要、也不会再叠加到新一轮加载的读数上。
     */
    fun invalidate() {
        synchronized(lock) {
            generation++
            cache.clear()
            loaded = false
        }
    }

    /** 测试用：当前缓存内容副本。 */
    internal fun cacheCopy(): Map<String, Int> = HashMap(cache)

    /**
     * 测试用：等已投递的增量落点全部执行完。落点与本调用同在
     * [serialDispatcher] 上 FIFO 排队，本段执行到时先于它投递的
     * 落点必然已执行。
     */
    internal suspend fun flushLandings() {
        withContext(serialDispatcher) { }
    }
}

/**
 * 加载回写合并（纯函数，供单测直接验证合并语义）：对 DB 快照命中
 * 键取「DB 值与缓存现值（落点直写计数）的较大者」；只在缓存中、
 * 不在 DB 快照里的新键不动；只在 DB 快照里的键按 DB 值落缓存。
 *
 * 纯 maxOf 之所以精确，前提是读库点与「upsert＋落点」对子已由
 * 跨挂起互斥严格排序（生产写路径 [CandidatePreferCache.recordClick]
 * 与加载段同在 transactionMutex 内成对完成，见其锁说明）：
 * 命中键的点击要么全部在读数里（DB 值即真值、且 ≥ 缓存里对应
 * 的落点计数），要么全部不在读数里（DB 值是点击前的存量，落点
 * 在合并之后才执行、随后直加到缓存上）。不存在「一部分在读数
 * 里、一部分只记在别处」的中间态，故不需要增量叠加。
 */
internal fun mergeLoadedSnapshot(
    cache: ConcurrentHashMap<String, Int>,
    loadedMap: Map<String, Int>,
) {
    loadedMap.forEach { (k, v) ->
        cache.merge(k, v) { old, new -> maxOf(old, new) }
    }
}
