package com.jobeen.ime.data.manager

import android.content.Context
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.PhraseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jobeen.ime.base.util.WeakProperty
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

object PhraseManager {

    private const val PREFS_NAME = "phrase_prefs"
    private const val KEY_SEEDED = "seeded"

    // 播种是"检查空表→插入→写标记"的非原子序列，并发 getAll 时必须串行，
    // 否则默认常用语会被重复插入
    private val seedMutex = Mutex()

    // 弱引用后备：注册方（KawaiiPanel）销毁后槽位自动失效，避免单例强持面板及其视图树
    var onContentChanged: (() -> Unit)? by WeakProperty()

    data class Phrase(
        val id: Long,
        val text: String,
        val label: String,
        val createdAt: Long,
    )

    suspend fun getAll(context: Context): List<Phrase> = db(context) { db ->
        ensureSeeded(context, db)
        db.phraseDao().getAll().map { Phrase(it.id, it.text, it.label, it.createdAt) }
    } ?: emptyList()

    suspend fun getById(context: Context, id: Long): Phrase? = db(context) { db ->
        db.phraseDao().getById(id)?.let { Phrase(it.id, it.text, it.label, it.createdAt) }
    }

    suspend fun search(context: Context, query: String): List<Phrase> = db(context) { db ->
        db.phraseDao().search(query).map { Phrase(it.id, it.text, it.label, it.createdAt) }
    } ?: emptyList()

    suspend fun insert(context: Context, text: String, label: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return -1
        val l = label.trim().ifEmpty { t.take(12) }
        val id = db(context) { db ->
            db.phraseDao().insert(PhraseRecord(text = t, label = l, createdAt = System.currentTimeMillis()))
        } ?: -1
        if (id > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
        return id
    }

    suspend fun update(context: Context, phrase: Phrase) {
        val t = phrase.text.trim()
        if (t.isEmpty()) return
        val l = phrase.label.trim().ifEmpty { t.take(12) }
        // 只有真的改到行（且数据库操作没失败）才通知刷新：
        // 旧实现无条件发回调，失败/目标不存在时 UI 也照常当成功处理
        val rows = db(context) { db ->
            db.phraseDao().update(
                PhraseRecord(id = phrase.id, text = t, label = l, createdAt = phrase.createdAt)
            )
        } ?: 0
        if (rows > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
    }

    suspend fun delete(context: Context, id: Long) {
        val rows = db(context) { db -> db.phraseDao().deleteById(id) } ?: 0
        if (rows > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
    }

    suspend fun deleteAll(context: Context) {
        val rows = db(context) { db -> db.phraseDao().deleteAll() } ?: 0
        if (rows > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
    }

    private suspend fun ensureSeeded(context: Context, db: AppDatabase) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        seedMutex.withLock {
            if (prefs.getBoolean(KEY_SEEDED, false)) return
            val dao = db.phraseDao()
            if (dao.getAll().isEmpty()) {
                val now = System.currentTimeMillis()
                val defaults = listOf(
                    "谢谢" to "谢谢",
                    "辛苦了" to "辛苦了",
                    "收到" to "收到",
                    "辛苦了，注意身体" to "关心",
                )
                defaults.forEachIndexed { index, (text, label) ->
                    dao.insert(PhraseRecord(text = text, label = label, createdAt = now - index * 1000))
                }
            }
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }
    }

    private suspend fun <T> db(context: Context, block: suspend (AppDatabase) -> T): T? =
        try {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                block(db)
            }
        } catch (e: Exception) {
            Timber.e(e, "phrase database operation failed")
            null
        }
}
