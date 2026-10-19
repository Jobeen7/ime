package com.jobeen.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ClipboardDao {

    @Insert
    suspend fun insert(record: ClipboardRecord): Long

    @Insert
    suspend fun insertAll(records: List<ClipboardRecord>)

    /** 一次性迁移用：读出全部行（含软删除行），供搬到独立的 clipboard_database */
    @Query("SELECT * FROM clipboard_records")
    suspend fun getAllRaw(): List<ClipboardRecord>

    /** 备份导出用：只导有效行，历史软删除存量不进备份 */
    @Query("SELECT * FROM clipboard_records WHERE deleted = 0")
    suspend fun getAllActive(): List<ClipboardRecord>

    /** applyOrder 专用投影：排序重排只取五列，不整行读全文（见 ClipboardOrderRow） */
    @Query("SELECT id, text, pinned, sortOrder, timestamp FROM clipboard_records WHERE deleted = 0")
    suspend fun getAllActiveOrderRows(): List<ClipboardOrderRow>

    /**
     * 只取最新若干条的文本列：clearAll 构造防复活抑制集只需要文本本身，
     * 在 SQL 内排序截断，不再把全表全文读进内存再排序（单条文本可为
     * 用户复制的长文，全量读既费内存、极端时还有大行读出异常风险）
     */
    @Query("SELECT text FROM clipboard_records WHERE deleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentTexts(limit: Int): List<String>

    /**
     * 存量限长前置去重（必须在 [truncateOversizedTexts] 之前调用）：
     * 两条超限行若前 [limit] 字相同，截断后 text 必然同值、撞 text
     * 唯一索引，整条 UPDATE 语句失败（旧库迁移与新库一次性限长都
     * 会因此永久卡死）。先按截断键（substr(text, 1, limit)）删掉
     * 较差的一行，只留一行待截断；保留口径与 [dedupeClipboardByText]
     * 及 v4 迁移 SQL 严格一致：pinned 优先、timestamp 最新、id 最大。
     * 只删超限行，未超限的行不参与（其 text 与截断键的关系不归本
     * 方法处理）。
     */
    @Query(
        "DELETE FROM clipboard_records WHERE length(text) > :limit AND EXISTS (" +
            "SELECT 1 FROM clipboard_records o WHERE o.id <> clipboard_records.id " +
            "AND substr(o.text, 1, :limit) = substr(clipboard_records.text, 1, :limit) AND (" +
            "o.pinned > clipboard_records.pinned OR " +
            "(o.pinned = clipboard_records.pinned AND o.timestamp > clipboard_records.timestamp) OR " +
            "(o.pinned = clipboard_records.pinned AND o.timestamp = clipboard_records.timestamp AND o.id > clipboard_records.id)))"
    )
    suspend fun dedupeOversizedByTruncatedKey(limit: Int)

    /**
     * 存量限长（一次性）：超限行直接在 SQL 内截断，不把大文本读进内存
     * （超 CursorWindow 的行用普通查询读出会抛 SQLiteBlobTooBigException）。
     * 调用前必须先 [dedupeOversizedByTruncatedKey]，否则同前缀超限行
     * 截断后撞唯一索引、整条语句失败。
     */
    @Query("UPDATE clipboard_records SET text = substr(text, 1, :limit) WHERE length(text) > :limit")
    suspend fun truncateOversizedTexts(limit: Int)

    /** 迁移完成并核对后清掉旧库残留行（旧表在 ime_database 里会随云备份外带） */
    @Query("DELETE FROM clipboard_records")
    suspend fun deleteAllRaw()

    // 置顶项不受保留期过滤（pinned=1 直接入列），排序置顶在前、组内按
    // 手动排序序号（拖动排序写入；未排过时由 -timestamp 回填保持时间倒序）
    @Query("SELECT * FROM clipboard_records WHERE deleted = 0 AND (pinned = 1 OR timestamp >= :cutoff) ORDER BY pinned DESC, sortOrder ASC, timestamp DESC")
    suspend fun getAllActiveSince(cutoff: Long): List<ClipboardRecord>

    @Query("SELECT MIN(sortOrder) FROM clipboard_records WHERE deleted = 0")
    suspend fun minSortOrder(): Long?

    @Query("UPDATE clipboard_records SET sortOrder = :order WHERE text = :text AND deleted = 0")
    suspend fun setSortOrderByText(text: String, order: Long)

    @Query("UPDATE clipboard_records SET pinned = :pinned WHERE text = :text AND deleted = 0")
    suspend fun setPinnedByText(text: String, pinned: Boolean)

    @Query("SELECT pinned FROM clipboard_records WHERE text = :text AND deleted = 0 LIMIT 1")
    suspend fun pinnedByText(text: String): Boolean?

    @Query("SELECT * FROM clipboard_records WHERE deleted = 0 ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ClipboardRecord?

    @Query("SELECT EXISTS(SELECT 1 FROM clipboard_records WHERE text = :text AND deleted = 0 LIMIT 1)")
    suspend fun existsByText(text: String): Boolean

    @Query("SELECT * FROM clipboard_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestIncludingDeleted(): ClipboardRecord?

    // 只数有效行：软删除行会保留一段时间，计入上限会提前误删最旧的有效记录
    @Query("SELECT COUNT(*) FROM clipboard_records WHERE deleted = 0")
    suspend fun count(): Int

    /**
     * 只数未置顶的有效行：保留上限只约束未置顶行（见
     * ClipboardManager.trimExcess）——置顶行是用户显式保留的，
     * 占名额会把未置顶容量压成「上限－置顶数」，置顶数达到上限时
     * 新复制内容一入库就会被当作超限行立刻删掉
     */
    @Query("SELECT COUNT(*) FROM clipboard_records WHERE deleted = 0 AND pinned = 0")
    suspend fun countUnpinned(): Int

    /** 只数置顶的有效行：置顶条数上限的计数口径（见 ClipboardManager.MAX_PINNED） */
    @Query("SELECT COUNT(*) FROM clipboard_records WHERE deleted = 0 AND pinned = 1")
    suspend fun countPinned(): Int

    /**
     * 编辑条目：把旧文本改写为新文本，刷新时间戳并把排序序号设为
     * 当前最小值减一（排到最前——列表按 sortOrder 在 timestamp 之前
     * 排序，只刷新时间戳排不动）；置顶状态随行保留。
     * 返回受影响行数：调用方必须校验——旧行已被并发路径删除时
     * 命中 0 行，不补处理会让新旧内容双双静默丢失
     * （见 ClipboardManager.updateEntry 的补插兜底）。
     */
    @Query("UPDATE clipboard_records SET text = :newText, timestamp = :ts, sortOrder = :sortOrder WHERE text = :oldText AND deleted = 0")
    suspend fun updateTextByText(oldText: String, newText: String, ts: Long, sortOrder: Long): Int

    @Query("DELETE FROM clipboard_records WHERE text = :text")
    suspend fun deleteByText(text: String)

    /** 多选批量删除（物理删，与单条删除一致） */
    @Query("DELETE FROM clipboard_records WHERE text IN (:texts)")
    suspend fun deleteByTexts(texts: List<String>)

    // 只淘汰未置顶有效行；软删除行由 purgeDeletedOlderThan 按保留期清理。
    // 淘汰口径按显示序的末尾先删：显示序为 sortOrder 升序（越小越靠前），
    // 故 sortOrder 大的先淘汰——被用户拖到顶部的条目 sortOrder 小，
    // 不再因 timestamp 旧而被先删（旧口径纯按 timestamp，与显示序脱钩）；
    // sortOrder 相同（从未拖排的行同为一组）时按 timestamp 最旧先删
    @Query("DELETE FROM clipboard_records WHERE id IN (SELECT id FROM clipboard_records WHERE deleted = 0 AND pinned = 0 ORDER BY sortOrder DESC, timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    // 保留期清理豁免置顶行
    @Query("DELETE FROM clipboard_records WHERE timestamp < :cutoff AND pinned = 0")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM clipboard_records WHERE deleted = 1 AND deletedAt < :cutoff")
    suspend fun purgeDeletedOlderThan(cutoff: Long)

    /** 一次性清理全部软删行（物理删除切换前的存量墓碑），返回删除行数 */
    @Query("DELETE FROM clipboard_records WHERE deleted = 1")
    suspend fun purgeAllDeleted(): Int
}
