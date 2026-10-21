package com.jobeen.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CandidatePreferDao {

    @Query("SELECT * FROM candidate_prefers WHERE text = :text LIMIT 1")
    suspend fun get(text: String): CandidatePrefer?

    @Query("SELECT * FROM candidate_prefers WHERE text IN (:texts)")
    suspend fun getAllByTextIn(texts: List<String>): List<CandidatePrefer>

    @Query("SELECT text, click_count FROM candidate_prefers")
    suspend fun getAllCounts(): List<PreferCount>

    data class PreferCount(val text: String, val click_count: Int)

    /**
     * 一次点击的偏好累计（公开签名与语义不变）。
     *
     * 原实现用 `INSERT ... ON CONFLICT DO UPDATE`（UPSERT 语法，
     * SQLite 3.24+ 才有）：minSdk 24 的平台 SQLite 在部分受支持
     * 设备上低于 3.24，该语句直接语法错误、偏好永远写不进（调
     * 用方只记日志，表现为学不到）。改用全版本兼容的两语句事务：
     * 先 INSERT OR IGNORE 以 click_count=0 建行，再 UPDATE 累加
     * ——首次调用净结果与原 SQL 一致（count=1、context 与
     * created_at/updated_at 均为本次值），重复调用递增并刷新
     * context/updated_at、created_at 不动。两条子语句仅供本事
     * 务方法调用，不得单独使用（单独调 increment 会丢首次建行，
     * 单独调 insert 落的 0 计数不是有效偏好）。
     */
    @Transaction
    suspend fun upsert(text: String, context: String, now: Long = System.currentTimeMillis()) {
        insertOrIgnoreZero(text, context, now)
        incrementExisting(text, context, now)
    }

    /** 仅供 [upsert] 事务调用：行不存在时以 0 计数建行，已存在则不动。 */
    @Query(
        """
        INSERT OR IGNORE INTO candidate_prefers (text, context, click_count, created_at, updated_at)
        VALUES (:text, :context, 0, :now, :now)
        """
    )
    suspend fun insertOrIgnoreZero(text: String, context: String, now: Long)

    /** 仅供 [upsert] 事务调用：对已存在的行累加计数并刷新 context/时间。 */
    @Query(
        """
        UPDATE candidate_prefers SET
            click_count = click_count + 1,
            context = :context,
            updated_at = :now
        WHERE text = :text
        """
    )
    suspend fun incrementExisting(text: String, context: String, now: Long)

    @Query("SELECT COUNT(*) FROM candidate_prefers")
    suspend fun count(): Int

    // 备份/还原专用：全量读取、整表替换（还原流程先 deleteAll 再 insertAll）
    @Query("SELECT * FROM candidate_prefers")
    suspend fun getAllFull(): List<CandidatePrefer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CandidatePrefer>)

    @Query("DELETE FROM candidate_prefers")
    suspend fun deleteAll()

    // 容量上限：只保留点击量最高（同量取最近更新）的前 limit 条，其余删除。
    // 偏好表无上限时长年使用会无限膨胀，拖慢全量加载与重排。
    @Query(
        """
        DELETE FROM candidate_prefers WHERE text NOT IN (
            SELECT text FROM candidate_prefers
            ORDER BY click_count DESC, updated_at DESC LIMIT :limit
        )
        """
    )
    suspend fun pruneToLimit(limit: Int)
}
