package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.data.database.AppDatabase
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
     * 加载与增量落点的串行通道（IO + limitedParallelism(1)，与写入
     * 端 RimeEngine 的 preferDbScope 同规格；preferDbScope 是
     * RimeEngine 的私有实例字段、本类够不到，故在本类内自建同规格
     * 通道收敛同一不变量）。
     *
     * 要保的不变量：读库（[snapshot] 的 getAllCounts）与写入端的
     * 「DAO upsert → [noteUpsert]」对子严格先后——读数要么整体在
     * 对子之前（点击不在读数里，随后由落点直加缓存），要么整体在
     * 对子之后（读数已含点击，落点更早已计入缓存、合并取 maxOf
     * 不重复）。旧实现读走 Dispatchers.IO 多线程、写走另一条通道，
     * 两边无先后关系，只能靠窗口增量账本（pendingDelta）事后对
     * 账，而账本本身在「点击先于读数落地」的交错下会双计（见
     * §3.6 复核）。现在读库与落点都收进本通道按 FIFO 串行执行：
     * 落点是 noteUpsert 投递到本通道的一段、读库与回写合并是
     * snapshot 的一段，同通道内两段不交错，账本整套删除、合并
     * 回归纯 maxOf 即精确（见 [mergeLoadedSnapshot]）。
     */
    private val serialDispatcher = Dispatchers.IO.limitedParallelism(1)

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
     * （读库与回写合并同段执行，不与 [noteUpsert] 落点交错）。
     */
    suspend fun snapshot(context: Context): Map<String, Int> {
        if (loaded) return cache
        val gen = generation          // 加载前记下代次
        return withContext(serialDispatcher) {
            val loadedMap = runCatching {
                AppDatabase.getInstance(context).candidatePreferDao().getAllCounts()
                    .associate { it.text to it.click_count }
            }.getOrElse {
                Timber.e(it, "candidate prefer cache load failed")
                null
            }
            // 加载失败不能置 loaded：否则本进程内偏好永远为空且不再重试
            finishLoad(loadedMap, gen)
            cache
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
     * 与 [com.jobeen.ime.data.database.CandidatePreferDao.upsert] 配对调用：
     * DB 写入成功后更新内存，保持一致。落点投递到串行通道异步
     * 执行（见 [landingScope]）：与加载同通道 FIFO 串行，是「读数
     * 与写入对子严格先后」不变量的落点一侧。
     */
    fun noteUpsert(text: String) {
        val gen = generation
        landingScope.launch {
            // 代次校验与计数同在 lock 内：与 invalidate 的清缓存/
            // 推进代次互斥，防「校验通过后被清、随后又把计数写回
            // 空缓存」的交错
            synchronized(lock) {
                if (gen == generation) {
                    cache.merge(text, 1, Int::plus)
                }
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
 * 纯 maxOf 之所以精确，前提是读库点与「upsert＋noteUpsert 落点」
 * 已由串行通道严格排序（见 [CandidatePreferCache] 的通道说明）：
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
