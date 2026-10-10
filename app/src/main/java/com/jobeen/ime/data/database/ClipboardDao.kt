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

    /**
     * 存量限长（一次性）：超限行直接在 SQL 内截断，不把大文本读进内存
     * （超 CursorWindow 的行用普通查询读出会抛 SQLiteBlobTooBigException）。
     */
    @Query("UPDATE clipboard_records SET text = substr(text, 1, :limit) WHERE length(text) > :limit")
    suspend fun truncateOversizedTexts(limit: Int)

    /** 迁移完成并核对后清掉旧库残留行（旧表在 ime_database 里会随云备份外带） */
    @Query("DELETE FROM clipboard_records")
    suspend fun deleteAllRaw()

    // 置顶项不受保留期过滤（pinned=1 直接入列），排序置顶在前、组内时间倒序
    @Query("SELECT * FROM clipboard_records WHERE deleted = 0 AND (pinned = 1 OR timestamp >= :cutoff) ORDER BY pinned DESC, timestamp DESC")
    suspend fun getAllActiveSince(cutoff: Long): List<ClipboardRecord>

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

    /** 编辑条目：把旧文本改写为新文本并刷新时间戳（排到最前）；置顶状态随行保留 */
    @Query("UPDATE clipboard_records SET text = :newText, timestamp = :ts WHERE text = :oldText AND deleted = 0")
    suspend fun updateTextByText(oldText: String, newText: String, ts: Long)

    @Query("UPDATE clipboard_records SET deleted = 1, deletedAt = :ts WHERE text = :text AND deleted = 0")
    suspend fun softDeleteByText(text: String, ts: Long)

    /** 多选批量删除：与单条删除同为软删 */
    @Query("UPDATE clipboard_records SET deleted = 1, deletedAt = :ts WHERE text IN (:texts) AND deleted = 0")
    suspend fun softDeleteByTexts(texts: List<String>, ts: Long)

    @Query("UPDATE clipboard_records SET deleted = 1, deletedAt = :ts WHERE deleted = 0")
    suspend fun softDeleteAll(ts: Long)

    @Query("DELETE FROM clipboard_records WHERE text = :text")
    suspend fun deleteByText(text: String)

    /** 多选批量删除（物理删，与单条删除一致） */
    @Query("DELETE FROM clipboard_records WHERE text IN (:texts)")
    suspend fun deleteByTexts(texts: List<String>)

    // 只淘汰未置顶有效行中最旧的；软删除行由 purgeDeletedOlderThan 按保留期清理
    @Query("DELETE FROM clipboard_records WHERE id IN (SELECT id FROM clipboard_records WHERE deleted = 0 AND pinned = 0 ORDER BY timestamp ASC LIMIT :n)")
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
