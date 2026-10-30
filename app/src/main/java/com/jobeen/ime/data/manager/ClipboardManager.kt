package com.jobeen.ime.data.manager

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.room.withTransaction
import com.jobeen.ime.base.util.DigestUtil
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
     * 置顶条数上限：置顶行不计入保留上限（见 trimExcess）、单条又可
     * 达 [MAX_TEXT_LENGTH]，没有独立上限时置顶可绕过全部容量约束
     * 无限堆积库文件。达到上限后新的置顶被拒（[setPinned] 回
     * [PinResult.Limit]），取消置顶与已置顶条目的存续不受影响。
     * 公开可见：置顶上限提示文案用它填 %d，避免数字两处各写一份。
     */
    const val MAX_PINNED = 100

    /**
     * 单条文本长度上限：超大行（约 2MB 起）读出时会抛 SQLiteBlobTooBigException，
     * 一行就能毒化整张历史表（列表恒空、备份导出失败），写入侧一律截断。
     */
    const val MAX_TEXT_LENGTH = 100_000

    // 物理删除后防「已删条目复活」的抑制集合：见 noteRemovedTexts
    private const val KEY_REMOVED_TEXT_HASHES = "removed_text_hashes_v2"
    private const val MAX_REMOVED_HASHES = 200

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

    /** 剪贴板监听开关（默认开）：关 = 不注册系统剪贴板监听、不做补偿检查，历史保留 */
    fun isListeningEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("clipboard.listening", true)
    }

    fun setListeningEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("clipboard.listening", enabled)
        }
        if (enabled) startMonitoring(context) else stopMonitoring(context)
    }

    /**
     * 去重开关（默认开）：开 = 重复复制静默忽略；关 = 重复复制把该条
     * 顶到最前并刷新时间。同一文本仍只留一行（text 唯一约束，不做表结构迁移）。
     */
    fun isDedupEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("clipboard.dedup", true)
    }

    fun setDedupEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("clipboard.dedup", enabled)
        }
    }

    /** 粘贴输出规则（默认原样） */
    enum class OutputRule { AS_IS, TRIM, NEWLINE_TO_SPACE }

    fun getOutputRule(context: Context): OutputRule {
        return when (
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("clipboard.output_rule", "as_is")
        ) {
            "trim" -> OutputRule.TRIM
            "newline_to_space" -> OutputRule.NEWLINE_TO_SPACE
            else -> OutputRule.AS_IS
        }
    }

    fun setOutputRule(context: Context, rule: OutputRule) {
        val value = when (rule) {
            OutputRule.AS_IS -> "as_is"
            OutputRule.TRIM -> "trim"
            OutputRule.NEWLINE_TO_SPACE -> "newline_to_space"
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("clipboard.output_rule", value)
        }
    }

    /** 粘贴后返回键盘（默认关）：开 = 点历史条目上屏后收起剪贴板面板回键盘 */
    fun isReturnAfterPasteEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean("clipboard.return_after_paste", false)
    }

    fun setReturnAfterPasteEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("clipboard.return_after_paste", enabled)
        }
    }

    /** 粘贴输出变换（纯函数）：原样／去首尾空白／换行转空格（其余内容不动） */
    fun applyOutputRule(text: String, rule: OutputRule): String = when (rule) {
        OutputRule.AS_IS -> text
        OutputRule.TRIM -> text.trim()
        OutputRule.NEWLINE_TO_SPACE -> text.replace("\r\n", " ")
            .replace('\n', ' ').replace('\r', ' ')
    }

    // ── 历史记录 ──

    data class Entry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val cloud: Boolean = false,
        val pinned: Boolean = false,
    )

    /**
     * 读条目，失败与真空分开：数据库操作失败（库损坏/迁移异常等）
     * 返回 null，「真无条目」返回空表。旧实现失败一律吞成 emptyList，
     * 面板会把「读不出来」显示成「剪贴板被清空」的假象、还可能据此
     * 覆盖现有列表。消费方拿到 null 应保留现有数据/给出提示，
     * 不要用空列表覆盖。
     */
    suspend fun getEntriesOrNull(context: Context): List<Entry>? = db(context) { db ->
        val cutoff = System.currentTimeMillis() - getRetentionDays(context) * 86400000L
        db.clipboardDao().getAllActiveSince(cutoff)
            .map { Entry(it.text, it.timestamp, it.cloud, it.pinned) }
    }

    /** 旧契约兼容：失败按真空返回。不关心成败的调用方用，新消费方用 [getEntriesOrNull]。 */
    suspend fun getEntries(context: Context): List<Entry> =
        getEntriesOrNull(context) ?: emptyList()

    /** [setPinned] 的结果三态：落库成功 / 达上限被拒 / 数据库失败。 */
    enum class PinResult { Ok, Limit, Error }

    /**
     * 置顶或取消置顶。旧契约只回 Boolean：达上限被拒与数据库失败
     * 都是 false，调用方只能一律提示「已达上限」（与实际原因不符），
     * 且取消置顶失败被当成无事发生、完全没有反馈。现在三态分开：
     * [PinResult.Limit] 仅出现在置顶方向（取消置顶永远放行），
     * [PinResult.Error] 是 db 操作失败（db() 返回 null）。
     */
    suspend fun setPinned(context: Context, text: String, pinned: Boolean): PinResult {
        val result = db(context) { db ->
            val dao = db.clipboardDao()
            if (pinned && dao.pinnedByText(text) != true &&
                dao.countPinned() >= MAX_PINNED
            ) {
                // 已置顶条目重复置顶是幂等操作、不占新名额（上面按
                // pinnedByText 放行）；未置顶条目在置顶数已达上限时
                // 拒绝，不落库。取消置顶永远放行
                false
            } else {
                dao.setPinnedByText(text, pinned)
                true
            }
        }
        return when (result) {
            null -> PinResult.Error
            true -> PinResult.Ok
            false -> PinResult.Limit
        }
    }

    /**
     * @return 条目已落库（或空文本无需落库）为 true；数据库操作失败
     * 为 false——旧实现静默 return，监听侧会冒充「有新条目」触发无谓刷新。
     */
    suspend fun addEntry(context: Context, text: String, notify: Boolean = true): Boolean {
        if (text.isBlank()) return true
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
        } ?: return false
        if (notify && isNew) onNewEntry?.invoke(Entry(clipped, now))
        return true
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
    /** @return 落库成功为 true；数据库操作失败为 false（重排未生效，调用方可提示/保持原序）。 */
    suspend fun applyOrder(context: Context, orderedTexts: List<String>): Boolean =
        db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                // 只取排序重排需要的五列投影：旧实现整行读全文，
                // 单条可达 10 万字、500 条时内存峰值数十 MB（见
                // ClipboardOrderRow）。排序/合并口径与全量读完全一致
                val rows = dao.getAllActiveOrderRows()
                val byText = rows.associateBy { it.text }
                val current = rows.sortedWith(
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
        } != null

    /** @return 清空落库成功为 true；数据库操作失败为 false。 */
    suspend fun clearAll(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val ok = db(context) { db ->
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
        } != null
        lastCopyText = null
        lastCopyTimestamp = 0L
        clearTimestamp = now
        return ok
    }

    /** @return 删除落库成功为 true；数据库操作失败为 false。 */
    suspend fun removeEntry(context: Context, text: String): Boolean {
        noteRemovedTexts(context, listOf(text))
        return db(context) { db -> db.clipboardDao().deleteByText(text) } != null
    }

    /**
     * 多选批量删除（物理删，与单条删除一致）。
     * @return 删除落库成功为 true；数据库操作失败为 false。
     */
    suspend fun removeEntries(context: Context, texts: Collection<String>): Boolean {
        if (texts.isEmpty()) return true
        noteRemovedTexts(context, texts)
        return db(context) { db -> db.clipboardDao().deleteByTexts(texts.toList()) } != null
    }

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
     * 放行判据只有监听事件一条（清点 S6 定论：时间戳兜底与最长有效期
     * 两层已删——它们让「删后重复制同一文本」在进程死亡等边角下被继
     * 续抑制最长 7 天、表现为复制了不进历史；进程死亡期间错过的复制
     * 事件不再补判，靠下一次真实复制事件放行）。存量行曾是「哈希:删
     * 除时刻」格式：读取时只取哈希段，时刻自此不存在。
     */
    private fun noteRemovedTexts(context: Context, texts: Collection<String>) {
        if (texts.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val fresh = texts.map { textHash(it) }
        val merged = (fresh + removedSuppressionHashes(prefs))
            .distinct()
            .take(MAX_REMOVED_HASHES)
        prefs.edit {
            putString(KEY_REMOVED_TEXT_HASHES, merged.joinToString("\n"))
        }
    }

    private fun removedSuppressionHashes(
        prefs: android.content.SharedPreferences,
    ): List<String> {
        val stored = prefs.getString(KEY_REMOVED_TEXT_HASHES, null) ?: return emptyList()
        // 存量行可能是旧格式「哈希:删除时刻」：只取哈希段（S6 后时刻层退场）
        return stored.split("\n").filter { it.isNotEmpty() }
            .map { it.substringBefore(':') }
    }

    /** 该文本是否命中防复活抑制；命中返回其哈希，未命中返回 null。 */
    private fun removedSuppressionHash(context: Context, text: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hash = textHash(text)
        return hash.takeIf { it in removedSuppressionHashes(prefs) }
    }

    /** 放行一条抑制（被删文本已被重新复制）：只摘命中条，其余抑制原样保留。 */
    private fun liftRemovedSuppression(context: Context, hash: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remaining = removedSuppressionHashes(prefs).filter { it != hash }
        prefs.edit {
            if (remaining.isEmpty()) {
                remove(KEY_REMOVED_TEXT_HASHES)
            } else {
                putString(KEY_REMOVED_TEXT_HASHES, remaining.joinToString("\n"))
            }
        }
    }

    private fun textHash(text: String): String =
        DigestUtil.sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * 编辑条目文本：改写后刷新时间戳排到最前，置顶状态随行保留。
     * 新文本若已存在另一条记录，先删那条再改写，避免同文本两行；
     * 合并口径为任一行置顶则结果置顶——删目标行前先读其置顶状态（同 addEntry
     * 的先读后恢复），改写后恢复，避免目标行置顶在合并时静默丢失。
     * 事务保证检查与改写不被并发插入打断。
     *
     * @return 编辑落库成功为 true；数据库操作失败为 false。空结果/
     * 未改动按无需落库处理、返回 true。
     */
    suspend fun updateEntry(context: Context, oldText: String, newText: String): Boolean {
        // 与写入侧统一限长（见 MAX_TEXT_LENGTH）：编辑结果超限同样截断，
        // 否则改写出的超限行照样能毒化整张历史表
        val clean = newText.trim().take(MAX_TEXT_LENGTH)
        if (clean.isEmpty() || clean == oldText) return true
        return db(context) { db ->
            db.withTransaction {
                val dao = db.clipboardDao()
                val targetPinned = dao.pinnedByText(clean) ?: false
                if (dao.existsByText(clean)) dao.deleteByText(clean)
                // 排到最前靠改排序序号（与 addEntry 新条目同口径，取
                // 当前最小值减一）：列表排序键是 sortOrder 在前、
                // timestamp 在后，只刷新时间戳条目原地不动
                val now = System.currentTimeMillis()
                val frontOrder = (dao.minSortOrder() ?: 0L) - 1L
                val updated = dao.updateTextByText(oldText, clean, now, frontOrder)
                if (updated == 0) {
                    // 旧行已被并发路径删除（编辑框打开期间新复制触发
                    // 裁剪/保留期清理把正在编辑的条目删了）：改写命中
                    // 0 行、而新文本行已在上方被删——旧实现到此为止，
                    // 新旧内容双双静默消失。同事务补插一行兜底，
                    // 置顶/排序口径与改写命中时一致
                    dao.insert(
                        ClipboardRecord(
                            text = clean, timestamp = now,
                            pinned = targetPinned, sortOrder = frontOrder,
                        )
                    )
                }
                if (targetPinned) dao.setPinnedByText(clean, true)
            }
        } != null
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
        // 监听关 = 全口径停采：面板打开时的补偿检查同样不采（只留历史）
        if (!isListeningEnabled(context)) return false
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
            // 放行判据只有一条（清点 S6 定论）：本次检查由剪贴板变更监听
            // 事件触发——删除动作本身不改系统剪贴板、不会产生新事件，
            // 事件到达且内容哈希仍等于被删文本，只可能是删除之后又被
            // 复制了同一文本。命中放行时只摘除该条抑制并走正常入库，
            // 其余条目的抑制不受影响；未放行即继续抑制、不算新记录。
            val suppressionHash = removedSuppressionHash(context, text)
            val dedup = isDedupEnabled(context)
            if (suppressionHash != null) {
                if (!fromClipChangedEvent) return@withLock false
                liftRemovedSuppression(context, suppressionHash)
            } else if (latest?.text == text) {
                // 去重关：重复复制不再静默忽略，顶到最前并刷新时间（仍只留一行）
                if (!dedup) return@withLock addEntry(context, text, notify = true)
                return@withLock false
            }

            val isNew = !exists
            if (isNew) {
                lastCopyText = text
                lastCopyTimestamp = System.currentTimeMillis()
                // 提示条状态（重点候选 8-5）：新文本即喂提示态（只读抽取，
                // 绝不回写历史）；去重／抑制分支不更新（仍是同一文本）。
                promptState.onClipboardText(text)
                // 入库失败不再冒充「有新条目」：内容仍在系统剪贴板里，
                // 下次变化事件/补偿检查会再试；冒充成功只会让面板
                // 白刷新一次、还把监听侧的成败口径搅浑
                return@withLock addEntry(context, text, notify = false)
            }
            // 已存在但非最新行：去重关时同样顶到最前
            if (!dedup) return@withLock addEntry(context, text, notify = true)
            false
        }
    }

    /**
     * 事件驱动监听：系统剪贴板变化时实时回调，不再定时轮询。
     * 注册后主动检查一次，补偿进程不存活期间的变化。
     */
    fun startMonitoring(context: Context) {
        stopMonitoring(context)
        // 监听关时不注册、不补偿（设置开关切换时由 setListeningEnabled 直调启停）
        if (!isListeningEnabled(context)) return
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
        // 补偿进程不存活期间的变化（不冒充监听事件：防复活抑制的放行
        // 只认监听事件，补偿检查命中的抑制条目继续抑制，见 S6）
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

    /** 剪贴板提示条状态（重点候选 8-5）：提示条 UI（待接）读取本态显示。 */
    val promptState = ClipboardPromptState()

    @Volatile var lastCopyTimestamp: Long = 0L
        private set

    @Volatile var clearTimestamp: Long = 0L
        private set

    private suspend fun <T> db(context: Context, block: suspend (ClipboardDatabase) -> T): T? =
        try {
            withContext(Dispatchers.IO) {
                val db = ClipboardDatabase.getInstance(context)
                // 拆库后主库空闲页的一次性物理擦除（清点 S10 定论
                // 收敛形态：同步 VACUUM 一次＋一个完成标记，见
                // AppDatabase.vacuumAfterClipboardSplitOnce）。拆库
                // 迁移本体已随清点 Q2 删除——现行设备迁移早已完成，
                // 这里只剩把当年删行留在主库文件里的全文擦掉这一件
                AppDatabase.vacuumAfterClipboardSplitOnce()
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
