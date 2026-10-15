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

    /** 无剪贴板时间戳的旧系统上，防复活抑制的最长有效期（见 checkCurrentClipboard 判据③） */
    private const val SUPPRESSION_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val KEY_TEXT_TRUNCATED = "clipboard_text_truncated_v1"
    private const val KEY_LEGACY_DELETED_PURGED = "clipboard_legacy_deleted_purged_v1"

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
        // 防复活抑制不在这里动：抑制条目只许按条摘除（见 noteRemovedTexts），
        // 入库新内容不构成清除其余条目抑制的理由
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
                    ClipboardRecord(
                        text = clipped, timestamp = now, cloud = false, pinned = wasPinned,
                        // 新条目排到最前：取当前最小排序序号再减一
                        sortOrder = (dao.minSortOrder() ?: 0L) - 1L,
                    )
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
        // 上限只约束未置顶行：deleteOldest 本就只淘汰未置顶行，计数
        // 口径必须对齐——按总量算时置顶行白占名额，置顶数达到上限
        // 后每次新入库的未置顶行都会被立刻当作超限删掉
        val excess = dao.countUnpinned() - limit
        if (excess > 0) dao.deleteOldest(excess)
    }

    /**
     * 拖动排序落库：[orderedTexts] 是面板当前显示的条目顺序（置顶组在前）。
     * 槽位保持合并：取全量有效行的当前显示序，显示中的条目在它们原先
     * 占据的槽位集合内按面板新序重排，未显示的条目（保留期外的旧条目、
     * 拖动后落库前刚复制进来的新条目）原位不动，最后统一重编号。
     * 只按子集拼接会把未显示条目一律压到末尾——新条目刚进来就沉底，
     * 且两套序号交错后旧条目重回显示范围时位置错乱。
     */
    suspend fun applyOrder(context: Context, orderedTexts: List<String>) {
        db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                val all = dao.getAllActive()
                val byText = all.associateBy { it.text }
                val current = all.sortedWith(
                    compareBy({ !it.pinned }, { it.sortOrder }, { -it.timestamp })
                )
                val shownQueue = orderedTexts.mapNotNull { byText[it] }
                val sequence = slotPreservingReorder(current, shownQueue) { it.text }
                sequence.forEachIndexed { index, record ->
                    if (record.sortOrder != index.toLong()) {
                        dao.setSortOrderByText(record.text, index.toLong())
                    }
                }
            }
        }
    }

    suspend fun clearAll(context: Context) {
        val now = System.currentTimeMillis()
        db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                // 物理清空前把全部条目记入防复活抑制（替代软删墓碑行的作用）。
                // 抑制集上限 200 条：按时间倒序取最新 200 条截断，保证最新
                // 条目（含当前系统剪贴板内容）不被丢掉——乱序截断时可能
                // 恰好丢掉当前剪贴板那条，清空后它又复活回来。排序截断
                // 在 SQL 内完成、只取 text 列，不再全量读全文进内存
                noteRemovedTexts(
                    context,
                    dao.getRecentTexts(MAX_REMOVED_HASHES),
                )
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

    /** 一条防复活抑制记录：文本哈希 + 删除时刻（0 表示时刻未知，见 noteRemovedTexts）。 */
    private data class RemovedSuppression(val hash: String, val removedAt: Long)

    private fun RemovedSuppression.toStoredString(): String =
        if (removedAt > 0) "$hash:$removedAt" else hash

    /**
     * 删除改物理删后替代软删墓碑的防复活机制：软删时代，被删条目以 deleted
     * 行留在表里，checkCurrentClipboard 靠它判定「系统剪贴板内容没变、不是
     * 新记录」；物理删后记录消失，补偿检查会把仍在系统剪贴板里的同一文本当
     * 新内容重新入库。这里把被删文本的哈希记进 prefs 集合（只存哈希不存
     * 原文，上限 [MAX_REMOVED_HASHES] 条），命中即不算新记录。抑制条目只许
     * 按条摘除、没有任何整表清除路径：某条被删文本被证实重新复制时，才在
     * checkCurrentClipboard 里按其哈希摘掉那一条，其余条目的抑制原样保留
     * ——旧实现曾在新内容入库时整表清除，多删条目中其余条目的防复活保护
     * 会被一条文本的重新入库误伤失效。
     *
     * 旧实现只记一条「最新被删文本」且仅当删的是最新行才记：连删多条时后删
     * 的把先删的顶掉，而系统剪贴板里仍是先删那条，于是先删的冒回来、后删
     * 的不冒——必须按集合记、且每条被删文本都记。
     *
     * 每条抑制记录除哈希外还记删除时刻，供「被删文本被重新复制」时放行
     * （判定在 checkCurrentClipboard：监听事件为主判据、剪贴板时间戳为
     * 进程死亡期兜底）。prefs 序列化一行一条：新格式「哈希:删除时刻」，
     * 旧格式整行只有哈希、读出时删除时刻记 0（未知）——这类记录时间戳
     * 兜底不生效、按继续抑制处理，待下一次监听事件再放行。
     */
    private fun noteRemovedTexts(context: Context, texts: Collection<String>) {
        if (texts.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val fresh = texts.map { RemovedSuppression(textHash(it), now) }
        val merged = (fresh + removedSuppressions(prefs))
            .distinctBy { it.hash }
            .take(MAX_REMOVED_HASHES)
        prefs.edit {
            putString(KEY_REMOVED_TEXT_HASHES, merged.joinToString("\n") { it.toStoredString() })
            remove(KEY_REMOVED_LATEST_TEXT)
        }
    }

    private fun removedSuppressions(
        prefs: android.content.SharedPreferences,
    ): List<RemovedSuppression> {
        val stored = prefs.getString(KEY_REMOVED_TEXT_HASHES, null)
        if (stored != null) {
            return stored.split("\n").filter { it.isNotEmpty() }.map { line ->
                // 新格式带删除时刻；旧格式整行只有哈希，时刻记 0（未知）
                val sep = line.indexOf(':')
                if (sep > 0) {
                    RemovedSuppression(
                        line.substring(0, sep),
                        line.substring(sep + 1).toLongOrNull() ?: 0L,
                    )
                } else {
                    RemovedSuppression(line, 0L)
                }
            }
        }
        // 一次性迁移旧版单值抑制：折算成哈希并入集合口径（无删除时刻）
        val legacy = prefs.getString(KEY_REMOVED_LATEST_TEXT, null) ?: return emptyList()
        return listOf(RemovedSuppression(textHash(legacy), 0L))
    }

    private fun removedSuppression(context: Context, text: String): RemovedSuppression? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = textHash(text)
        return removedSuppressions(prefs).firstOrNull { it.hash == hash }
    }

    /** 放行一条抑制（被删文本已被重新复制）：只摘命中条，其余抑制原样保留。 */
    private fun liftRemovedSuppression(context: Context, hash: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remaining = removedSuppressions(prefs).filter { it.hash != hash }
        prefs.edit {
            if (remaining.isEmpty()) {
                remove(KEY_REMOVED_TEXT_HASHES)
            } else {
                putString(
                    KEY_REMOVED_TEXT_HASHES,
                    remaining.joinToString("\n") { it.toStoredString() },
                )
            }
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
        // 与写入侧统一限长（见 MAX_TEXT_LENGTH）：编辑结果超限同样截断，
        // 否则改写出的超限行照样能毒化整张历史表
        val clean = newText.trim().take(MAX_TEXT_LENGTH)
        if (clean.isEmpty() || clean == oldText) return
        db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                val targetPinned = dao.pinnedByText(clean) ?: false
                if (dao.existsByText(clean)) dao.deleteByText(clean)
                // 排到最前靠改排序序号（与 addEntry 新条目同口径，取
                // 当前最小值减一）：列表排序键是 sortOrder 在前、
                // timestamp 在后，只刷新时间戳条目原地不动
                dao.updateTextByText(
                    oldText, clean, System.currentTimeMillis(),
                    (dao.minSortOrder() ?: 0L) - 1L,
                )
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

    // 入库决策串行锁：监听回调与启动补偿（以及面板打开等其他来源）
    // 都会并发走 checkCurrentClipboard，「读最新行/判存在→判抑制→
    // 插入」若交错，快连复制时后到的内容可能先落库、顶部顺序倒置，
    // 同一文本还会被两路同时判为新条目。决策段统一过这把锁串行化
    private val intakeMutex = Mutex()

    /**
     * 检查系统剪贴板，有新内容则入库。返回 true 表示有新条目。
     * suspend：在 IO 线程做查询，调用方负责把回调切回主线程。
     *
     * [fromClipChangedEvent] 标记本次检查是否由系统剪贴板变更监听回调
     * 触发：它是「被删文本被重新复制」的放行主判据（见下方抑制判定），
     * 面板打开时的补偿检查等其他来源保持默认 false。
     */
    suspend fun checkCurrentClipboard(
        context: Context,
        fromClipChangedEvent: Boolean = false,
    ): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        // 读剪贴板可能抛 SecurityException（如后台限制/OEM 行为），不能让监听协程崩掉
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return false
        if (clip.itemCount == 0) return false
        // 隐私：先判敏感标记——敏感内容连文本都不读，不进历史、不弹提示
        if (isSensitiveClip(clip)) return false
        // 采集侧先按与入库侧同一上限截断，再参与后续全部比对与哈希：
        // 超限文本全文与 addEntry 截断后入库的文本永远对不上（最新行比对、
        // 抑制哈希判定双双失效），且哈希入参自此不超过 MAX_TEXT_LENGTH
        val text = (clipText(clip, context) ?: return false).take(MAX_TEXT_LENGTH)

        val ts = clipTimestamp(clip)
        if (ts > 0) lastClipTimestamp = ts
        lastText = text

        // 以下决策段（读最新行/判存在→判抑制→插入）过 intakeMutex
        // 串行化，见锁的注释
        return intakeMutex.withLock {
            // 与“最新一条记录（含软删除）”比对：若仍与系统剪贴板内容一致，
            // 则视为没有新记录，避免重复插入。删除已改物理删，原墓碑行的
            // 作用由 prefs 里的防复活抑制集合接替（见 noteRemovedTexts）。
            // 两项判断合并进一次 db{} 往返（每次剪贴板变化都走这里）
            val (latest, exists) = db(context) { db ->
                db.clipboardDao().getLatestIncludingDeleted() to db.clipboardDao().existsByText(text)
            } ?: (null to true)
            // 防复活抑制命中：先判「重新复制」放行，再判与最新行是否一致——
            // 已被物理删的文本不在有效行里，顺序反过来会让软删存量行把放行短路。
            // 放行判据（任一成立即视为删除之后的主动重新复制，只摘除该条
            // 抑制并走正常入库，其余条目的抑制不受影响）：
            // ① 主判据：本次检查由剪贴板变更监听事件触发——删除动作本身不改
            //    系统剪贴板、不会产生新事件，事件到达且内容哈希仍等于被删文本，
            //    只可能是删除之后又被复制了同一文本；
            // ② 时间戳兜底：进程死亡期间错过的复制事件靠 ClipDescription 时
            //    间戳补判，剪贴板设置时刻晚于删除时刻即放行；旧格式记录无删
            //    除时刻（removedAt=0）或剪贴板无时间戳（ts<=0）时不放行、继续抑制。
            val suppression = removedSuppression(context, text)
            if (suppression != null) {
                // ③ 无时间戳平台（API 26 以下恒无 ClipDescription 时间戳，
                // ② 永不成立）的兜底：抑制超过上限时长后失效。进程死亡
                // 期间的重新复制在这类系统上没有任何可判信号，无限期抑制
                // 会把「删掉后重新复制同一文本」永久吞掉；而删除后一周
                // 系统剪贴板仍是同一文本时，它实质就是用户当前的剪贴板
                // 内容，防复活的保护价值已消失。近期的防复活不受影响。
                val recopied = fromClipChangedEvent ||
                    (suppression.removedAt > 0L && ts > suppression.removedAt) ||
                    (ts <= 0 && suppression.removedAt > 0L &&
                        System.currentTimeMillis() - suppression.removedAt >
                        SUPPRESSION_MAX_AGE_MS)
                if (!recopied) return@withLock false
                liftRemovedSuppression(context, suppression.hash)
            } else if (latest?.text == text) {
                return@withLock false
            }

            val isNew = !exists
            if (isNew) {
                lastCopyText = text
                lastCopyTimestamp = System.currentTimeMillis()
                addEntry(context, text, notify = false)
                return@withLock true
            }
            false
        }
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
                // 来源标记供「重新复制」放行主判据使用（见 checkCurrentClipboard）
                val changed = checkCurrentClipboard(app, fromClipChangedEvent = true)
                if (changed) {
                    withContext(Dispatchers.Main) {
                        onContentChanged?.invoke()
                    }
                }
            }
        }
        clipListener = listener
        cm.addPrimaryClipChangedListener(listener)
        // 补偿进程不存活期间的变化（不冒充监听事件：删除后重复制的放行
        // 在这里靠剪贴板时间戳兜底判定）
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
     *
     * @return 迁移已完成（本次刚完成或此前已完成）为 true；任一步
     * 失败为 false——成败必须回给调用方：迁移没成时旧行还躺在主库
     * 里，此时派发拆库 VACUUM 会把「已擦除」标记错落下（此后再无
     * 路径补跑，删行后的空闲页全文永久留在主库文件里），备份侧
     * 直读新库则会静默漏掉旧行。
     */
    private suspend fun migrateFromAppDatabase(context: Context, target: ClipboardDatabase): Boolean {
        val settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (settings.getBoolean(KEY_DB_MIGRATED, false)) return true
        return runCatching {
            val dao = target.clipboardDao()
            val oldDao = AppDatabase.getInstance(context).clipboardDao()
            val oldRows = oldDao.getAllRaw()
            if (oldRows.isNotEmpty()) {
                val existing = dao.getAllRaw().map { it.text to it.timestamp }.toHashSet()
                // 软删行不搬运：那是已删除内容，搬进新库只会让墓碑行继续占位
                // 并被保留期清理前误当存量；下方 deleteAllRaw 仍会清掉旧库全部行
                val missing = oldRows.filter {
                    !it.deleted && (it.text to it.timestamp) !in existing
                }
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
            // 旧行全文此时还在 ime_database 的空闲页/WAL 里：补跑主库
            // VACUUM 才真正擦除（见 db() 中迁移检查之后的补跑点——
            // 已完成迁移的老用户在本函数开头就按标记返回，补跑点
            // 必须设在迁移体之外他们才走得到）
            true
        }.onFailure { Timber.w(it, "Clipboard history migration failed; will retry") }
            .getOrDefault(false)
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

    /**
     * 一次性清理软删存量行：删除已改为物理删，软删行只是旧版本留下
     * 的墓碑，此前靠 addEntry 时顺带按保留期淘汰——不新增条目就永
     * 远清不掉。这里在首次数据库访问时整表清完并落标记；防复活由
     * prefs 抑制集合负责，与这些行无关。
     */
    private suspend fun purgeLegacyDeletedOnce(context: Context, target: ClipboardDatabase) {
        val settings = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (settings.getBoolean(KEY_LEGACY_DELETED_PURGED, false)) return
        runCatching {
            val n = target.clipboardDao().purgeAllDeleted()
            if (n > 0) Timber.i("Purged %d legacy soft-deleted clipboard rows", n)
            settings.edit { putBoolean(KEY_LEGACY_DELETED_PURGED, true) }
        }.onFailure { Timber.w(it, "Legacy soft-deleted purge failed; will retry") }
    }

    /**
     * 确保拆库迁移已执行（备份导出/还原直接读写剪贴板库前调用）：
     * 迁移本体只在 [db] 的常规访问路径里懒触发，绕开它直读新库会
     * 在「升级后还没用过剪贴板」时读到空库（旧行还躺在主库里，
     * 备份的剪贴板段静默为空）。与 [db] 共用同一把迁移锁和同一
     * 个迁移实现，只是把触发点暴露出来，不复制迁移逻辑。
     *
     * @return 迁移已完成为 true、失败为 false（透传迁移体的成败，
     * 调用方据此决定是中止还是继续，不得再当作无条件成功）。
     */
    internal suspend fun ensureMigrated(context: Context): Boolean {
        val db = ClipboardDatabase.getInstance(context)
        return migrationMutex.withLock { migrateFromAppDatabase(context, db) }
    }

    // 拆库补跑 VACUUM 的进程内派发去重：db() 每次访问都尝试触发，
    // 已有一次在途时其余访问直接跳过，不排一串重复任务
    private val postSplitVacuumInFlight =
        java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 拆库补 VACUUM 的触发点（见 AppDatabase.vacuumAfterClipboardSplit）：
     * 只向应用级 IO 作用域派发一次、不内联执行——VACUUM 作用于主库
     * ime_database，要对它持排他锁数百毫秒，内联在 block 之前会把
     * 每次剪贴板操作都拖住；剪贴板数据已拆到独立库，两者不同库，
     * 异步并发执行不互斥。真正的去重/退避在 AppDatabase 侧（完成
     * 标记＋失败时间戳＋串行锁），这里只防同一时刻重复派发。
     */
    private fun schedulePostSplitVacuum() {
        if (!postSplitVacuumInFlight.compareAndSet(false, true)) return
        appScope.launch(Dispatchers.IO) {
            try {
                AppDatabase.vacuumAfterClipboardSplit()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Post-split VACUUM dispatch failed")
            } finally {
                postSplitVacuumInFlight.set(false)
            }
        }
    }

    private suspend fun <T> db(context: Context, block: suspend (ClipboardDatabase) -> T): T? =
        try {
            withContext(Dispatchers.IO) {
                val db = ClipboardDatabase.getInstance(context)
                val migrated = ensureMigrated(context)
                // 拆库补 VACUUM 的常规补跑点（触发逻辑见
                // schedulePostSplitVacuum）：此前它只在迁移体末尾被
                // 调用，而在本版本之前就完成拆库迁移的老用户会在
                // 迁移体开头按标记直接返回、永远走不到——旧剪贴板
                // 全文一直残留在会进云备份的主库空闲页里。放在每次
                // 数据库访问的迁移检查之后异步触发：老用户下次用
                // 剪贴板即补跑一次，此后只是标记检查。
                // 仅迁移已完成才派发：迁移失败时旧行还没删，VACUUM
                // 擦无可擦却会落下「已擦除」标记，之后迁移成功删行
                // 也再无路径补跑物理擦除（见 migrateFromAppDatabase
                // 的返回值说明）。迁移本身失败不拦本次剪贴板操作：
                // 新库照常可用、旧行下次访问继续收敛
                if (migrated) schedulePostSplitVacuum()
                truncateOversizedTextsOnce(context, db)
                purgeLegacyDeletedOnce(context, db)
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
