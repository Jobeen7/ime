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
    private val lock = Any()

    /**
     * 返回 text → click_count。首次调用在 IO 线程全量加载。
     */
    suspend fun snapshot(context: Context): Map<String, Int> {
        if (loaded) return cache
        return withContext(Dispatchers.IO) {
            val loadedMap = runCatching {
                AppDatabase.getInstance(context).candidatePreferDao().getAllCounts()
                    .associate { it.text to it.click_count }
            }.getOrElse {
                Timber.e(it, "candidate prefer cache load failed")
                emptyMap()
            }
            synchronized(lock) {
                if (!loaded) {
                    // 用 maxOf 合并：加载期间若有 noteUpsert 先写入，不丢增量
                    loadedMap.forEach { (k, v) -> cache.merge(k, v, ::maxOf) }
                    loaded = true
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

    /** 偏好表被清空时调用（当前无清空入口，预留）。 */
    fun invalidate() {
        synchronized(lock) {
            cache.clear()
            loaded = false
        }
    }
}
