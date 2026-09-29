package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.data.database.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 选词偏好常驻内存缓存。
 *
 * 每次出候选都要查偏好表做重排/联想加权；表是"写少读多"（只在上屏选词时 upsert），
 * 因此首次按需全量加载进内存，之后读走内存、写时同步更新内存，省掉每轮候选一次 DB 查询。
 */
object CandidatePreferCache {

    @Volatile
    private var cache: Map<String, Int>? = null
    private val lock = Any()

    /**
     * 返回 text → click_count 全量快照。首次调用在 IO 线程全量加载。
     */
    suspend fun snapshot(context: Context): Map<String, Int> {
        cache?.let { return it }
        return withContext(Dispatchers.IO) {
            val loaded = runCatching {
                AppDatabase.getInstance(context).candidatePreferDao().getAllCounts()
                    .associate { it.text to it.click_count }
            }.getOrElse {
                Timber.e(it, "candidate prefer cache load failed")
                emptyMap()
            }
            synchronized(lock) {
                if (cache == null) cache = loaded
                cache!!
            }
        }
    }

    /**
     * 与 [com.jobeen.ime.data.database.CandidatePreferDao.upsert] 配对调用：
     * DB 写入成功后同步更新内存，保持一致。
     */
    fun noteUpsert(text: String) {
        synchronized(lock) {
            val cur = cache ?: return
            val m = HashMap(cur)
            m[text] = (m[text] ?: 0) + 1
            cache = m
        }
    }

    /** 偏好表被清空时调用（当前无清空入口，预留）。 */
    fun invalidate() {
        synchronized(lock) { cache = null }
    }
}
