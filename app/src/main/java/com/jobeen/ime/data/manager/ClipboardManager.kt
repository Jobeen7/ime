package com.jobeen.ime.data.manager

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.ClipboardDao
import com.jobeen.ime.data.database.ClipboardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jobeen.ime.base.util.appScope
import timber.log.Timber

object ClipboardManager {

    var onNewEntry: ((Entry) -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null

    private const val PREFS_NAME = "clipboard_settings"
    private const val KEY_MAX_ENTRIES = "max_entries"
    private const val KEY_RETENTION_DAYS = "retention_days"

    private const val DEFAULT_MAX_ENTRIES = 100
    private const val DEFAULT_RETENTION_DAYS = 30

    // ── 设置读写 ──

    fun getMaxEntries(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_ENTRIES, DEFAULT_MAX_ENTRIES)
    }

    fun setMaxEntries(context: Context, max: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_MAX_ENTRIES, max.coerceIn(20, 500))
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
    )

    suspend fun getEntries(context: Context): List<Entry> = db(context) { db ->
        val cutoff = System.currentTimeMillis() - getRetentionDays(context) * 86400000L
        db.clipboardDao().getAllActiveSince(cutoff).map { Entry(it.text, it.timestamp, it.cloud) }
    } ?: emptyList()

    suspend fun addEntry(context: Context, text: String, notify: Boolean = true) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        val retentionCutoff = now - getRetentionDays(context) * 86400000L
        val isNew = db(context) { db ->
            val dao = db.clipboardDao()
            val existed = dao.existsByText(text)
            dao.deleteByText(text)
            dao.insert(ClipboardRecord(text = text, timestamp = now, cloud = false))
            trimExcess(dao, getMaxEntries(context))
            dao.deleteOlderThan(retentionCutoff)
            dao.purgeDeletedOlderThan(retentionCutoff)
            !existed
        } ?: return
        if (notify && isNew) onNewEntry?.invoke(Entry(text, now))
    }

    private suspend fun trimExcess(dao: ClipboardDao, limit: Int) {
        val excess = dao.count() - limit
        if (excess > 0) dao.deleteOldest(excess)
    }

    suspend fun clearAll(context: Context) {
        val now = System.currentTimeMillis()
        val retentionCutoff = now - getRetentionDays(context) * 86400000L
        db(context) { db ->
            db.clipboardDao().softDeleteAll(now)
            db.clipboardDao().purgeDeletedOlderThan(retentionCutoff)
        }
        lastCopyText = null
        lastCopyTimestamp = 0L
        clearTimestamp = now
    }

    suspend fun removeEntry(context: Context, text: String) {
        db(context) { db -> db.clipboardDao().softDeleteByText(text, System.currentTimeMillis()) }
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
        if (Build.VERSION.SDK_INT >= 33) clip.description?.timestamp?.takeIf { it > 0 } ?: -1L
        else -1L

    /**
     * 隐私：系统标记为敏感的剪贴板内容（如密码管理器复制的密码，
     * [ClipDescription.EXTRA_IS_SENSITIVE]，API 33+）不入库、不弹粘贴提示。
     */
    private fun isSensitiveClip(clip: ClipData): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return clip.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
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
        val text = clipText(clip, context) ?: return false

        // 隐私：敏感内容不进历史、不弹提示
        if (isSensitiveClip(clip)) return false

        val ts = clipTimestamp(clip)
        if (ts > 0) lastClipTimestamp = ts
        lastText = text

        // 与“最新一条记录（含软删除）”比对：清空后该记录变为软删除状态，
        // 若仍与系统剪贴板内容一致，则视为没有新记录，避免重复插入。
        val latest = db(context) { db -> db.clipboardDao().getLatestIncludingDeleted() }
        if (latest?.text == text) return false

        val isNew = db(context) { db -> !db.clipboardDao().existsByText(text) } ?: false
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

    private suspend fun <T> db(context: Context, block: suspend (com.jobeen.ime.data.database.AppDatabase) -> T): T? =
        try {
            withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                block(db)
            }
        } catch (e: Exception) {
            Timber.e(e, "clipboard database operation failed")
            null
        }
}
