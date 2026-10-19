package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.data.database.AppDatabase
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 选词偏好常驻内存缓存。
 *
 * 每次出候选都要查偏好表做重排/联想加权；表是"写少读多"（只在上屏选词时 upsert），
 * 因此首次按需全量加载进内存，之后读走内存、写时同步更新内存，省掉每轮候选一次 DB 查询。
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
     * 加载窗口增量账本（§3.6）：一次加载在飞期间，[noteUpsert] 的
     * 增量除直写缓存外再记一份到这里。原先回写只用 maxOf 合并：
     * 窗口内对「DB 里已有键」的点击先以 1 为基数写进缓存，回写时
     * maxOf(DB 值, 窗口计数) 会把这部分增量吃掉（DB 读数是窗口开始
     * 前的快照、不含这些点击），缓存比 DB 低，直到进程重启才纠正。
     * 改为回写时对命中键用「DB 值 + 窗口增量」，窗口增量不丢。
     *
     * 账本生命周期与单次加载绑死：[finishLoad] 在任何收尾路径
     * （成功合并 / 代次丢弃 / 加载失败）都结清账本与在飞标记——
     * 结清时未被消费的增量，其点击的 DB 写入都已落地，下一次加载
     * 的 DB 读数会包含它们，再叠加就会重复计数。
     */
    private val pendingDelta = ConcurrentHashMap<String, Int>()

    /** 是否有一次 [snapshot] 加载在飞（[noteUpsert] 据此决定是否记账）。 */
    @Volatile
    private var loadInFlight = false

    /**
     * 返回 text → click_count。首次调用在 IO 线程全量加载。
     */
    suspend fun snapshot(context: Context): Map<String, Int> {
        if (loaded) return cache
        val gen = generation          // 加载前记下代次
        markLoadStarted()
        return withContext(Dispatchers.IO) {
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

    /** 标记一次加载开始在飞（[snapshot] 在发起 IO 读之前调用）。 */
    internal fun markLoadStarted() {
        loadInFlight = true
    }

    /**
     * 加载收尾：结清窗口账本，并在代次未变时把 DB 快照合并进缓存。
     * [loadedMap] 为 null 表示加载失败：不合并、不置 loaded，下次
     * [snapshot] 重新加载（失败语义与原实现一致）。
     */
    internal fun finishLoad(loadedMap: Map<String, Int>?, gen: Int) {
        synchronized(lock) {
            val delta = HashMap(pendingDelta)
            pendingDelta.clear()
            loadInFlight = false
            if (loadedMap != null && !loaded && gen == generation) {
                mergeLoadedSnapshot(cache, loadedMap, delta)
                loaded = true
            }
        }
    }

    /**
     * 与 [com.jobeen.ime.data.database.CandidatePreferDao.upsert] 配对调用：
     * DB 写入成功后同步更新内存，保持一致。加载在飞期间同时记入
     * 窗口账本，供 [finishLoad] 回写时叠加（见 [pendingDelta]）。
     */
    fun noteUpsert(text: String) {
        cache.merge(text, 1, Int::plus)
        if (loadInFlight) pendingDelta.merge(text, 1, Int::plus)
    }

    /**
     * 偏好表被整表替换/裁剪时调用（备份还原、容量裁剪）：清缓存并
     * 推进代次，使正在后台加载的旧数据回写时被 [snapshot] 的代次
     * 校验丢弃——否则旧数据会被 merge 回来并置 loaded，还原/裁剪
     * 在内存层等于白做、进程重启前候选一直按旧偏好排序。
     * 窗口账本一并结清：在飞加载注定被代次校验丢弃，其后新一轮
     * 加载的 DB 读数会包含这些点击，不需要旧账本再叠加。
     */
    fun invalidate() {
        synchronized(lock) {
            generation++
            cache.clear()
            pendingDelta.clear()
            loadInFlight = false
            loaded = false
        }
    }

    /** 测试用：当前缓存内容副本。 */
    internal fun cacheCopy(): Map<String, Int> = HashMap(cache)
}

/**
 * 加载回写合并（纯函数，供单测直接验证时序语义）：
 * - DB 快照命中键：取「DB 值 + 窗口增量」与缓存现值（窗口内直写
 *   计数）的较大者——增量账本为空时退化为原先的 maxOf 语义；
 * - 只在窗口账本/缓存中、不在 DB 快照里的新键：不动，缓存中的
 *   直写计数即其全部计数；
 * - 只在 DB 快照里的键：按 DB 值落缓存。
 */
internal fun mergeLoadedSnapshot(
    cache: ConcurrentHashMap<String, Int>,
    loadedMap: Map<String, Int>,
    delta: Map<String, Int>,
) {
    loadedMap.forEach { (k, v) ->
        val target = v + (delta[k] ?: 0)
        cache.merge(k, target) { old, new -> maxOf(old, new) }
    }
}
