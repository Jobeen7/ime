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

    @Query("UPDATE clipboard_records SET deleted = 1, deletedAt = :ts WHERE text = :text AND deleted = 0")
    suspend fun softDeleteByText(text: String, ts: Long)

    @Query("UPDATE clipboard_records SET deleted = 1, deletedAt = :ts WHERE deleted = 0")
    suspend fun softDeleteAll(ts: Long)

    @Query("DELETE FROM clipboard_records WHERE text = :text")
    suspend fun deleteByText(text: String)

    // 只淘汰未置顶有效行中最旧的；软删除行由 purgeDeletedOlderThan 按保留期清理
    @Query("DELETE FROM clipboard_records WHERE id IN (SELECT id FROM clipboard_records WHERE deleted = 0 AND pinned = 0 ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    // 保留期清理豁免置顶行
    @Query("DELETE FROM clipboard_records WHERE timestamp < :cutoff AND pinned = 0")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM clipboard_records WHERE deleted = 1 AND deletedAt < :cutoff")
    suspend fun purgeDeletedOlderThan(cutoff: Long)
}
