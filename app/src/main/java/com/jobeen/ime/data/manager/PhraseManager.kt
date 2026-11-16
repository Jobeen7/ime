package com.jobeen.ime.data.manager

import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.room.withTransaction
import com.jobeen.ime.R
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.PhraseRecord
import com.jobeen.ime.data.database.PhraseVariable
import com.jobeen.ime.data.database.PhraseVariables
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

    /**
     * 常用语输入码开关（WO52 6.1，04 §2.2 `phrase_shortcut_mode`“常用语
     * 输入码”／`isPhraseShortcutEnabled`）：开时完整匹配输入码才在候选
     * 前显示常用语；关时不显示输入码候选，保留已设置的输入码。
     * 默认开——单子定的输入码行为出厂即生效，关是用户的显式选择。
     */
    const val KEY_SHORTCUT_ENABLED = "isPhraseShortcutEnabled"

    /**
     * 自动排序开关（WO52 6.1，04 §2.3 `isPhraseAutoSortEnabled`）：
     * 开时最近使用的排在前面（`phrase_auto_sort_info`）；关时按手动
     * 拖动顺序。默认关——保原有行为（现行列表即手动顺序）。
     */
    const val KEY_AUTO_SORT_ENABLED = "isPhraseAutoSortEnabled"

    fun isShortcutEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHORTCUT_ENABLED, true)

    fun setShortcutEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_SHORTCUT_ENABLED, enabled) }
    }

    fun isAutoSortEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SORT_ENABLED, false)

    fun setAutoSortEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_AUTO_SORT_ENABLED, enabled) }
    }

    /**
     * 输入码校验（WO52 6.1，04 §2.2 报错字符串原文口径）：
     * 可留空；非空须为 2–10 位英文字母 a–z。返回 null＝合法，
     * 否则返回应显示的报错字符串资源 id（调用方 resolve，L-43）。
     */
    @StringRes
    fun validateShortcut(code: String): Int? {
        if (code.isEmpty()) return null
        if (code.length !in 2..10) return R.string.phrase_shortcut_length_error
        if (!code.all { it in 'a'..'z' }) return R.string.phrase_shortcut_characters_error
        return null
    }

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
        val shortcut: String = "",
        val category: String = "",
        val tags: String = "",
        val variables: List<PhraseVariable> = emptyList(),
        val lastUsedAt: Long = 0,
    )

    private fun PhraseRecord.toPhrase(): Phrase =
        Phrase(
            id, text, label, createdAt, sortOrder, pinned,
            shortcut, category, tags, PhraseVariables.decode(variables), lastUsedAt,
        )

    suspend fun getAll(context: Context): List<Phrase> = db(context) { db ->
        ensureSeeded(context, db)
        val dao = db.phraseDao()
        val records = if (isAutoSortEnabled(context)) dao.getAllAutoSorted() else dao.getAll()
        records.map { it.toPhrase() }
    } ?: emptyList()

    suspend fun getById(context: Context, id: Long): Phrase? = db(context) { db ->
        // 播种不只走 getAll（D-L7）：首次访问若是按 id 取，默认常用语
        // 同样须已播种，否则 id 指向的默认条目查不到。
        ensureSeeded(context, db)
        db.phraseDao().getById(id)?.toPhrase()
    }

    suspend fun search(context: Context, query: String): List<Phrase> = db(context) { db ->
        // 播种不只走 getAll（D-L7）：首次访问若是搜索，默认常用语
        // 须已播种，否则搜“谢谢”无结果。
        ensureSeeded(context, db)
        db.phraseDao().search(query).map { it.toPhrase() }
    } ?: emptyList()

    /** 输入码归一化：小写＋去掉多音节 preedit 的空格／' 分隔（B-PH-1）。 */
    fun normalizeShortcutCode(code: String): String =
        code.lowercase().replace(" ", "").replace("'", "")

    /**
     * 空码判定（纯函数，可单测）：归一化后为空即不查库——空码查库会
     * 命中全部 shortcut="" 的种子默认常用语（2026-11-16 debug
     * 问题 3）。与 validateShortcut 正交：空串是合法输入码，但
     * 查库无意义。
     */
    internal fun isEmptyShortcutCode(code: String): Boolean =
        normalizeShortcutCode(code).isEmpty()

    /** 输入码精确查询（WO52 6.1）：开关关着或输入码非法时直接返回空，不查库。 */
    suspend fun getByShortcut(context: Context, code: String): List<Phrase> {
        if (!isShortcutEnabled(context)) return emptyList()
        // 空码（无组字／仅分隔符）直接返回空，不查库：空串是合法输入码
        // （validateShortcut 不动——它判的是用户输入的合法性），但空
        // 码查库会命中全部 shortcut="" 的种子默认常用语，导致没打字
        // 候选栏却出词（2026-11-16 debug 问题 3）。
        if (isEmptyShortcutCode(code)) return emptyList()
        // 多音节 preedit 含空格／' 分隔（全拼 "ni hao"、简拼 "d z"）：
        // 匹配前去掉分隔符再校验（"ni hao"→"nihao"，单音节行为不变；B-PH-1）。
        val normalized = normalizeShortcutCode(code)
        if (validateShortcut(normalized) != null) return emptyList()
        return db(context) { db ->
            ensureSeeded(context, db)
            db.phraseDao().getByShortcut(normalized).map { it.toPhrase() }
        } ?: emptyList()
    }

    /** 最近使用打点（自动排序的排序键；上屏成功后由调用方记一次）。 */
    suspend fun markUsed(context: Context, id: Long) {
        db(context) { db ->
            db.phraseDao().setLastUsedById(id, System.currentTimeMillis())
        }
    }

    suspend fun setPinned(context: Context, id: Long, pinned: Boolean) {
        val rows = db(context) { db -> db.phraseDao().setPinnedById(id, pinned) } ?: 0
        if (rows > 0) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
    }

    suspend fun insert(
        context: Context,
        text: String,
        label: String,
        shortcut: String = "",
        category: String = "",
        tags: String = "",
        variables: List<PhraseVariable> = emptyList(),
    ): Long {
        val t = text.trim()
        if (t.isEmpty()) return -1
        // 非法输入码不落库（编辑页已先行校验，此处是第二道关）
        if (validateShortcut(shortcut) != null) return -1
        val l = label.trim().ifEmpty { t.takeCodePoints(12) }
        val id = db(context) { db ->
            // 播种不只走 getAll（D-L7）：首次访问若是新增，默认常用语
            // 须先播种，否则用户的新增排在默认条目之前、默认条目随后
            // 才由 getAll 补种，顺序与“新建在前”的语义脱节。
            ensureSeeded(context, db)
            // 「读最小序号→插入」必须同事务：并发新增时两条会算出
            // 同一 sortOrder、次序不稳定（与剪贴板侧 addEntry 同口径）
            db.withTransaction {
                db.phraseDao().insert(
                    PhraseRecord(
                        text = t, label = l, createdAt = System.currentTimeMillis(),
                        // 新常用语排到最前（与此前「新建在前」的顺序语义一致）
                        sortOrder = (db.phraseDao().minSortOrder() ?: 0L) - 1L,
                        shortcut = shortcut,
                        category = category.trim(),
                        tags = tags.trim(),
                        variables = PhraseVariables.encode(variables),
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
        if (validateShortcut(phrase.shortcut) != null) return
        val l = phrase.label.trim().ifEmpty { t.takeCodePoints(12) }
        // 只有真的改到行（且数据库操作没失败）才通知刷新：
        // 旧实现无条件发回调，失败/目标不存在时 UI 也照常当成功处理。
        // sortOrder/pinned/lastUsedAt 必须回读保留：整行 @Update 会把它们重置为默认值
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
                        shortcut = phrase.shortcut,
                        category = phrase.category.trim(),
                        tags = phrase.tags.trim(),
                        variables = PhraseVariables.encode(phrase.variables),
                        lastUsedAt = existing?.lastUsedAt ?: phrase.lastUsedAt,
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
            // D-P2（WO36）：这是全仓唯一的真 Room＋prefs 配对写（WO34
            // 已核实）：Room 先提交、prefs 后标记。旧实现用 apply()
            // 异步落盘，flush 挂在 QueuedWork 里跨过备份结算点时，
            // 备份会读到“Room 已播种、prefs 仍未标记”的撕裂对。改
            // 同步 commit()：本函数只在 [db] 的 Dispatchers.IO 内
            // 跑（见下），同步写一个小 prefs 文件无 ANR 风险；返回
            // 即 Room 与 prefs 双双落盘，配对窗口闭合。commit 失败
            // （返回 false）时 seeded 仍为 false，下次调用重进本
            // 函数：表已非空故跳过插入、只重标 seeded，自愈，不丢数。
            if (!prefs.edit().putBoolean(KEY_SEEDED, true).commit()) {
                Timber.w("phrase seeded flag commit failed, will retry on next access")
            }
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
