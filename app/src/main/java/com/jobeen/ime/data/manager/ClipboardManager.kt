package com.jobeen.ime.data.manager

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.room.withTransaction
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.ClipboardDatabase
import com.jobeen.ime.data.database.ClipboardDao
import com.jobeen.ime.data.database.ClipboardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.jobeen.ime.base.util.WeakProperty
import com.jobeen.ime.base.util.appScope
import timber.log.Timber

object ClipboardManager {

    // 弱引用后备：注册方（KawaiiPanel）销毁后槽位自动失效，避免单例强持面板及其视图树
    var onNewEntry: ((Entry) -> Unit)? by WeakProperty()
    var onContentChanged: (() -> Unit)? by WeakProperty()

    private const val PREFS_NAME = "clipboard_settings"
    private const val KEY_MAX_ENTRIES = "max_entries"
    private const val KEY_RETENTION_DAYS = "retention_days"

    private const val DEFAULT_MAX_ENTRIES = 100
    private const val DEFAULT_RETENTION_DAYS = 30

    /**
     * 单条文本长度上限：超大行（约 2MB 起）读出时会抛 SQLiteBlobTooBigException，
     * 一行就能毒化整张历史表（列表恒空、备份导出失败），写入侧一律截断。
     */
    const val MAX_TEXT_LENGTH = 100_000

    // 物理删除后防「已删条目复活」的抑制集合：见 noteRemovedTexts
    private const val KEY_REMOVED_TEXT_HASHES = "removed_text_hashes_v2"
    private const val KEY_REMOVED_LATEST_TEXT = "removed_latest_text"
    private const val MAX_REMOVED_HASHES = 200
    private const val KEY_TEXT_TRUNCATED = "clipboard_text_truncated_v1"

    // ── 设置读写 ──

    fun getMaxEntries(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_ENTRIES, DEFAULT_MAX_ENTRIES)
    }

    fun setMaxEntries(context: Context, max: Int) {
        val value = max.coerceIn(20, 500)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_MAX_ENTRIES, value)
        }
        // 上限调低后立即清理存量：旧实现只在下一次新增条目时才顺带 trimExcess，
        // 不再复制新内容的话超限条目会一直留着，设置看起来不生效
        val app = context.applicationContext
        appScope.launch(Dispatchers.IO) {
            val trimmed = db(app) { db ->
                db.withTransaction {
                    val dao = db.clipboardDao()
                    val before = dao.count()
                    trimExcess(dao, value)
                    dao.count() < before
                }
            } ?: false
            if (trimmed) withContext(Dispatchers.Main) { onContentChanged?.invoke() }
        }
    }

    fun getRetentionDays(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
    }

    fun setRetentionDays(context: Context, days: Int) {
        val value = days.coerceIn(1, 365)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_RETENTION_DAYS, value)
        }
    }

    // ── 历史记录 ──

    data class Entry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val cloud: Boolean = false,
        val pinned: Boolean = false,
    )

    suspend fun getEntries(context: Context): List<Entry> = db(context) { db ->
        val cutoff = System.currentTimeMillis() - getRetentionDays(context) * 86400000L
        db.clipboardDao().getAllActiveSince(cutoff)
            .map { Entry(it.text, it.timestamp, it.cloud, it.pinned) }
    } ?: emptyList()

    suspend fun setPinned(context: Context, text: String, pinned: Boolean) {
        db(context) { db -> db.clipboardDao().setPinnedByText(text, pinned) }
    }

    suspend fun addEntry(context: Context, text: String, notify: Boolean = true) {
        if (text.isBlank()) return
        // 限长：见 MAX_TEXT_LENGTH，超限文本截断后再走全部后续逻辑
        val clipped = text.take(MAX_TEXT_LENGTH)
        // 真正的新内容入库后，旧删除的防复活抑制随之失效（剪贴板已换了内容）
        if (!isRemovedTextSuppressed(context, clipped)) clearRemovedTextSuppressions(context)
        val now = System.currentTimeMillis()
        val retentionCutoff = now - getRetentionDays(context) * 86400000L
        // 事务串行化"检查→删除→插入→裁剪"：并发写入时旧实现会重复插入，
        // 且与 clearAll 交错时条目会在清空后复活
        val isNew = db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                val existed = dao.existsByText(clipped)
                // 同文本重入（再次复制）会删旧行重插，置顶状态要保留下来
                val wasPinned = dao.pinnedByText(clipped) ?: false
                dao.deleteByText(clipped)
                dao.insert(
                    ClipboardRecord(text = clipped, timestamp = now, cloud = false, pinned = wasPinned)
                )
                trimExcess(dao, getMaxEntries(context))
                dao.deleteOlderThan(retentionCutoff)
                dao.purgeDeletedOlderThan(retentionCutoff)
                !existed
            }
        } ?: return
        if (notify && isNew) onNewEntry?.invoke(Entry(clipped, now))
    }

    private suspend fun trimExcess(dao: ClipboardDao, limit: Int) {
        val excess = dao.count() - limit
        if (excess > 0) dao.deleteOldest(excess)
    }

    suspend fun clearAll(context: Context) {
        val now = System.currentTimeMillis()
        db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                // 物理清空前把全部条目记入防复活抑制（替代软删墓碑行的作用）
                noteRemovedTexts(context, dao.getAllActive().map { it.text })
                dao.deleteAllRaw()
            }
        }
        lastCopyText = null
        lastCopyTimestamp = 0L
        clearTimestamp = now
    }

    suspend fun removeEntry(context: Context, text: String) {
        noteRemovedTexts(context, listOf(text))
        db(context) { db -> db.clipboardDao().deleteByText(text) }
    }

    /** 多选批量删除（物理删，与单条删除一致）。 */
    suspend fun removeEntries(context: Context, texts: Collection<String>) {
        if (texts.isEmpty()) return
        noteRemovedTexts(context, texts)
        db(context) { db -> db.clipboardDao().deleteByTexts(texts.toList()) }
    }

    /**
     * 删除改物理删后替代软删墓碑的防复活机制：软删时代，被删条目以 deleted
     * 行留在表里，checkCurrentClipboard 靠它判定「系统剪贴板内容没变、不是
     * 新记录」；物理删后记录消失，补偿检查会把仍在系统剪贴板里的同一文本当
     * 新内容重新入库。这里把被删文本的哈希记进 prefs 集合（只存哈希不存
     * 原文，上限 [MAX_REMOVED_HASHES] 条），命中即不算新记录；真正的新文本
     * 入库时由 addEntry 整表清除（剪贴板已换内容，旧抑制不再需要）。
     *
     * 旧实现只记一条「最新被删文本」且仅当删的是最新行才记：连删多条时后删
     * 的把先删的顶掉，而系统剪贴板里仍是先删那条，于是先删的冒回来、后删
     * 的不冒——必须按集合记、且每条被删文本都记。
     */
    private fun noteRemovedTexts(context: Context, texts: Collection<String>) {
        if (texts.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fresh = texts.map { textHash(it) }
        val merged = (fresh + removedTextHashes(prefs)).distinct().take(MAX_REMOVED_HASHES)
        prefs.edit {
            putString(KEY_REMOVED_TEXT_HASHES, merged.joinToString("\n"))
            remove(KEY_REMOVED_LATEST_TEXT)
        }
    }

    private fun removedTextHashes(
        prefs: android.content.SharedPreferences,
    ): List<String> {
        val stored = prefs.getString(KEY_REMOVED_TEXT_HASHES, null)
        if (stored != null) return stored.split("\n").filter { it.isNotEmpty() }
        // 一次性迁移旧版单值抑制：折算成哈希并入集合口径
        val legacy = prefs.getString(KEY_REMOVED_LATEST_TEXT, null) ?: return emptyList()
        return listOf(textHash(legacy))
    }

    private fun isRemovedTextSuppressed(context: Context, text: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return textHash(text) in removedTextHashes(prefs)
    }

    private fun clearRemovedTextSuppressions(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_REMOVED_TEXT_HASHES)
            remove(KEY_REMOVED_LATEST_TEXT)
        }
    }

    private fun textHash(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * 编辑条目文本：改写后刷新时间戳排到最前，置顶状态随行保留。
     * 新文本若已存在另一条记录，先删那条再改写，避免同文本两行；
     * 合并口径为任一行置顶则结果置顶——删目标行前先读其置顶状态（同 addEntry
     * 的先读后恢复），改写后恢复，避免目标行置顶在合并时静默丢失。
     * 事务保证检查与改写不被并发插入打断。
     */
    suspend fun updateEntry(context: Context, oldText: String, newText: String) {
        val clean = newText.trim()
        if (clean.isEmpty() || clean == oldText) return
        db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                val targetPinned = dao.pinnedByText(clean) ?: false
                if (dao.existsByText(clean)) dao.deleteByText(clean)
                dao.updateTextByText(oldText, clean, System.currentTimeMillis())
                if (targetPinned) dao.setPinnedByText(clean, true)
            }
        }
    }

    // ── 系统剪切板监听 ──

    private fun clipText(clip: ClipData, context: Context): String? {
        val item = clip.getItemAt(0) ?: return null
        val text = item.text?.toString() ?: return null
        if (text.isBlank()) return null
        if (text.any { it.code in 0..31 && it != '\t' && it != '\n' && it != '\r' }) return null
        return text
    }

    private fun clipTimestamp(clip: ClipData): Long =
        // getTimestamp() 自 API 26 起可用（旧实现误按 33 拦截，低版本永远拿不到时间戳）
        if (Build.VERSION.SDK_INT >= 26) clip.description?.timestamp?.takeIf { it > 0 } ?: -1L
        else -1L

    /**
     * 隐私：被标记为敏感的剪贴板内容（如密码管理器复制的密码）不入库、不弹粘贴提示。
     *
     * 标记由**复制来源 App** 写进 ClipDescription 的 extras，与设备系统版本无关：
     * extras 的 getExtras() 自 API 24 起可用，常量 [ClipDescription.EXTRA_IS_SENSITIVE]
     * 到 API 33 才公开，低版本用同一字符串键读取即可。旧实现在 API<31 直接返回
     * false，把来源 App 在旧系统上写的标记也一并丢弃了。
     */
    private fun isSensitiveClip(clip: ClipData): Boolean {
        val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE
        else "android.content.extra.IS_SENSITIVE"
        return clip.description?.extras?.getBoolean(key) == true
    }

    /**
     * 检查系统剪贴板，有新内容则入库。返回 true 表示有新条目。
     * suspend：在 IO 线程做查询，调用方负责把回调切回主线程。
     */
    suspend fun checkCurrentClipboard(context: Context): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        // 读剪贴板可能抛 SecurityException（如后台限制/OEM 行为），不能让监听协程崩掉
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return false
        if (clip.itemCount == 0) return false
        // 隐私：先判敏感标记——敏感内容连文本都不读，不进历史、不弹提示
        if (isSensitiveClip(clip)) return false
        val text = clipText(clip, context) ?: return false

        val ts = clipTimestamp(clip)
        if (ts > 0) lastClipTimestamp = ts
        lastText = text

        // 与“最新一条记录（含软删除）”比对：若仍与系统剪贴板内容一致，
        // 则视为没有新记录，避免重复插入。删除已改物理删，原墓碑行的
        // 作用由 prefs 里的防复活抑制集合接替（见 noteRemovedTexts）。
        // 两项判断合并进一次 db{} 往返（每次剪贴板变化都走这里）
        val (latest, exists) = db(context) { db ->
            db.clipboardDao().getLatestIncludingDeleted() to db.clipboardDao().existsByText(text)
        } ?: (null to true)
        if (latest?.text == text) return false
        if (isRemovedTextSuppressed(context, text)) return false

        val isNew = !exists
        if (isNew) {
            lastCopyText = text
            lastCopyTimestamp = System.currentTimeMillis()
            addEntry(context, text, notify = false)
            return true
        }
        return false
    }

    /**
     * 事件驱动监听：系统剪贴板变化时实时回调，不再定时轮询。
     * 注册后主动检查一次，补偿进程不存活期间的变化。
     */
    fun startMonitoring(context: Context) {
        stopMonitoring(context)
        val app = context.applicationContext
        val cm =
            app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val listener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
            appScope.launch(Dispatchers.IO) {
                val changed = checkCurrentClipboard(app)
                if (changed) {
                    withContext(Dispatchers.Main) {
                        onContentChanged?.invoke()
                    }
                }
            }
        }
        clipListener = listener
        cm.addPrimaryClipChangedListener(listener)
        // 补偿进程不存活期间的变化
        appScope.launch(Dispatchers.IO) {
            val changed = checkCurrentClipboard(app)
            if (changed) {
                withContext(Dispatchers.Main) {
                    onContentChanged?.invoke()
                }
            }
        }
    }

    fun stopMonitoring(context: Context) {
        val listener = clipListener ?: return
        clipListener = null
        runCatching {
            val cm = context.applicationContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.removePrimaryClipChangedListener(listener)
        }
    }

    private var clipListener: android.content.ClipboardManager.OnPrimaryClipChangedListener? = null

    @Volatile private var lastText: String = ""
    @Volatile private var lastClipTimestamp: Long = -1L

    @Volatile var lastCopyText: String? = null
        private set

    @Volatile var lastCopyTimestamp: Long = 0L
        private set

    @Volatile var clearTimestamp: Long = 0L
        private set

    private const val KEY_DB_MIGRATED = "clipboard_db_migrated_v2"

    // 迁移首跑串行锁：db() 的调用方都在 IO 多线程上，并发首跑时多个调用方
    // 会同时看到「未迁移」并重复执行迁移体（各自按同一快照补行、互相重复
    // 插入）。进程内只允许一个执行，其余等它完成后再读标记直接跳过；
    // 迁移失败不置标记，下一个调用方仍可重试，语义不变。
    private val migrationMutex = Mutex()

    /**
     * 一次性把剪贴板历史从 ime_database 迁到独立的 clipboard_database（拆库原因见
     * ClipboardDatabase 注释）。v2 起改为幂等合并：按 (text, timestamp) 自然键
     * 只补新库缺失的行（旧实现以 count()==0 判完成，中断后新库非空但缺行会被
     * 当成已迁移、静默丢行；且留有主键冲突让后续每次操作都重跑失败迁移的
     * 可能）。补行在单个事务内完成；确认后清掉旧库残留行——旧表继续躺在
     * ime_database 里会随云备份/换机迁移外带，拆库的隐私隔离只做一半。
     * 整个过程可重入：任何一步失败下次调用会继续收敛到同一终态。
     */
    private suspend fun migrateFromAppDatabase(context: Context, target: ClipboardDatabase) {
        val settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (settings.getBoolean(KEY_DB_MIGRATED, false)) return
        runCatching {
            val dao = target.clipboardDao()
            val oldDao = AppDatabase.getInstance(context).clipboardDao()
            val oldRows = oldDao.getAllRaw()
            if (oldRows.isNotEmpty()) {
                val existing = dao.getAllRaw().map { it.text to it.timestamp }.toHashSet()
                val missing = oldRows.filter { (it.text to it.timestamp) !in existing }
                if (missing.isNotEmpty()) {
                    target.withTransaction {
                        // id 清零走自增，避免与新库已有行主键冲突
                        missing.forEach { dao.insert(it.copy(id = 0)) }
                    }
                    Timber.i("Clipboard history migrated: ${missing.size} rows")
                }
                oldDao.deleteAllRaw()
            }
            settings.edit().putBoolean(KEY_DB_MIGRATED, true).apply()
        }.onFailure { Timber.w(it, "Clipboard history migration failed; will retry") }
    }

    /** 一次性存量限长：把库中已有的超限行在 SQL 内截断（见 DAO 注释）。 */
    private suspend fun truncateOversizedTextsOnce(context: Context, target: ClipboardDatabase) {
        val settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (settings.getBoolean(KEY_TEXT_TRUNCATED, false)) return
        runCatching {
            target.clipboardDao().truncateOversizedTexts(MAX_TEXT_LENGTH)
            settings.edit().putBoolean(KEY_TEXT_TRUNCATED, true).apply()
        }.onFailure { Timber.w(it, "Clipboard oversized-text truncation failed; will retry") }
    }

    private suspend fun <T> db(context: Context, block: suspend (ClipboardDatabase) -> T): T? =
        try {
            withContext(Dispatchers.IO) {
                val db = ClipboardDatabase.getInstance(context)
                migrationMutex.withLock { migrateFromAppDatabase(context, db) }
                truncateOversizedTextsOnce(context, db)
                block(db)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是失败：必须透传，否则结构化并发的取消信号被吞，
            // 已取消的协程会继续跑完 block 之外的逻辑
            throw e
        } catch (e: Exception) {
            Timber.e(e, "clipboard database operation failed")
            null
        }
}
