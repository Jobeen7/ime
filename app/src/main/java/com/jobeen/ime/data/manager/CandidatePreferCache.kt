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
    private var generation = 0
    private val lock = Any()

    /**
     * 返回 text → click_count。首次调用在 IO 线程全量加载。
     */
    suspend fun snapshot(context: Context): Map<String, Int> {
        if (loaded) return cache
        val gen = generation          // 加载前记下代次
        return withContext(Dispatchers.IO) {
            val loadedMap = runCatching {
                AppDatabase.getInstance(context).candidatePreferDao().getAllCounts()
                    .associate { it.text to it.click_count }
            }.getOrElse {
                Timber.e(it, "candidate prefer cache load failed")
                null
            }
            // 加载失败不能置 loaded：否则本进程内偏好永远为空且不再重试
            if (loadedMap != null) {
                synchronized(lock) {
                    // 加载期间被 invalidate 过：结果已过期，丢弃，下次重新加载
                    if (!loaded && gen == generation) {
                        // 用 maxOf 合并：加载期间若有 noteUpsert 先写入，不丢增量
                        loadedMap.forEach { (k, v) -> cache.merge(k, v, ::maxOf) }
                        loaded = true
                    }
                }
            }
            cache
        }
    }

    /**
     * 与 [com.jobeen.ime.data.database.CandidatePreferDao.upsert] 配对调用：
     * DB 写入成功后同步更新内存，保持一致。
     */
    fun noteUpsert(text: String) {
        cache.merge(text, 1, Int::plus)
    }

    /**
     * 偏好表被整表替换/裁剪时调用（备份还原、容量裁剪）：清缓存并
     * 推进代次，使正在后台加载的旧数据回写时被 [snapshot] 的代次
     * 校验丢弃——否则旧数据会被 merge 回来并置 loaded，还原/裁剪
     * 在内存层等于白做、进程重启前候选一直按旧偏好排序。
     */
    fun invalidate() {
        synchronized(lock) {
            generation++
            cache.clear()
            loaded = false
        }
    }
}
