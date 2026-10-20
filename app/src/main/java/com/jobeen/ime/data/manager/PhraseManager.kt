package com.jobeen.ime.data.manager

import android.content.Context
import androidx.room.withTransaction
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.PhraseRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jobeen.ime.base.util.WeakProperty
import com.jobeen.ime.base.util.appScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * 取前 [n] 个字素簇（用户感知的一个「字」）。普通 [String.take] 按
 * UTF-16 码元计数会劈开代理对；只按码点截仍会劈开多码点的字素——
 * ZWJ 组合 emoji（如家庭组合是 5 个码点拼一个字素）、肤色修饰符
 * 序列（基础 emoji＋修饰符 2 个码点）截一半都会在标签里显示成
 * 散开/坏掉的图形。用 BreakIterator 的字符边界（即字素簇边界）
 * 截断。常用语标签的生成口径（三处：新增传参、insert/update
 * 兜底）统一走这里，函数名沿用旧名（调用方按「取前 n 个字」理解）。
 */
fun String.takeCodePoints(n: Int): String {
    if (n <= 0) return ""
    if (codePointCount(0, length) <= n) return this
    val iterator = java.text.BreakIterator.getCharacterInstance()
    iterator.setText(this)
    var count = 0
    var end = iterator.next()
    while (end != java.text.BreakIterator.DONE) {
        count++
        if (count >= n) return substring(0, end)
        end = iterator.next()
    }
    return this
}

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
        val sortOrder: Long = 0,
        val pinned: Boolean = false,
    )

    suspend fun getAll(context: Context): List<Phrase> = db(context) { db ->
        ensureSeeded(context, db)
        db.phraseDao().getAll().map { Phrase(it.id, it.text, it.label, it.createdAt, it.sortOrder, it.pinned) }
    } ?: emptyList()

    suspend fun getById(context: Context, id: Long): Phrase? = db(context) { db ->
        db.phraseDao().getById(id)?.let { Phrase(it.id, it.text, it.label, it.createdAt, it.sortOrder, it.pinned) }
    }

    suspend fun search(context: Context, query: String): List<Phrase> = db(context) { db ->
        db.phraseDao().search(query).map { Phrase(it.id, it.text, it.label, it.createdAt, it.sortOrder, it.pinned) }
    } ?: emptyList()

    suspend fun setPinned(context: Context, id: Long, pinned: Boolean) {
        val rows = db(context) { db -> db.phraseDao().setPinnedById(id, pinned) } ?: 0
        if (rows > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
    }

    suspend fun insert(context: Context, text: String, label: String): Long {
        val t = text.trim()
        if (t.isEmpty()) return -1
        val l = label.trim().ifEmpty { t.takeCodePoints(12) }
        val id = db(context) { db ->
            // 「读最小序号→插入」必须同事务：并发新增时两条会算出
            // 同一 sortOrder、次序不稳定（与剪贴板侧 addEntry 同口径）
            db.withTransaction {
                db.phraseDao().insert(
                    PhraseRecord(
                        text = t, label = l, createdAt = System.currentTimeMillis(),
                        // 新常用语排到最前（与此前「新建在前」的顺序语义一致）
                        sortOrder = (db.phraseDao().minSortOrder() ?: 0L) - 1L,
                    )
                )
            }
        } ?: -1
        if (id > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
        return id
    }

    suspend fun update(context: Context, phrase: Phrase) {
        val t = phrase.text.trim()
        if (t.isEmpty()) return
        val l = phrase.label.trim().ifEmpty { t.takeCodePoints(12) }
        // 只有真的改到行（且数据库操作没失败）才通知刷新：
        // 旧实现无条件发回调，失败/目标不存在时 UI 也照常当成功处理。
        // sortOrder/pinned 必须回读保留：整行 @Update 会把它们重置为默认值
        val rows = db(context) { db ->
            // 「回读保留字段→整行回写」必须同事务：与置顶切换并发时，
            // 事务外的旧快照会把刚改的置顶状态覆盖回去
            db.withTransaction {
                val existing = db.phraseDao().getById(phrase.id)
                db.phraseDao().update(
                    PhraseRecord(
                        id = phrase.id, text = t, label = l, createdAt = phrase.createdAt,
                        sortOrder = existing?.sortOrder ?: phrase.sortOrder,
                        pinned = existing?.pinned ?: phrase.pinned,
                    )
                )
            }
        } ?: 0
        if (rows > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
    }

    /**
     * 拖动排序落库：与剪贴板同一槽位保持合并——显示中的条目在原槽位
     * 集合内按面板新序重排，未显示的原位不动，最后统一重编号，避免
     * 只给子集编号时新序号与未更新条目的旧序号交错。
     */
    suspend fun applyOrder(context: Context, orderedIds: List<Long>) {
        db(context) { db ->
            db.withTransaction {
                val dao = db.phraseDao()
                val all = dao.getAll()
                val byId = all.associateBy { it.id }
                val shownQueue = orderedIds.mapNotNull { byId[it] }
                val sequence = slotPreservingReorder(all, shownQueue) { it.id }
                sequence.forEachIndexed { index, record ->
                    if (record.sortOrder != index.toLong()) {
                        dao.setSortOrderById(record.id, index.toLong())
                    }
                }
            }
        }
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
                // 事务内播种：中途中断不能留下「缺条 + 已标记 seeded」的永久缺口
                db.withTransaction {
                    defaults.forEachIndexed { index, (text, label) ->
                        val created = now - index * 1000
                        dao.insert(
                            PhraseRecord(
                                text = text, label = label, createdAt = created,
                                sortOrder = -created,
                            )
                        )
                    }
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "phrase database operation failed")
            null
        }
}
