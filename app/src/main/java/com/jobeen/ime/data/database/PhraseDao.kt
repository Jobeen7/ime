package com.jobeen.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface PhraseDao {

    @Insert
    suspend fun insert(record: PhraseRecord): Long

    @Insert
    suspend fun insertAll(records: List<PhraseRecord>)

    @Update
    suspend fun update(record: PhraseRecord): Int

    @Query("SELECT * FROM phrase_records ORDER BY pinned DESC, sortOrder ASC, createdAt DESC")
    suspend fun getAll(): List<PhraseRecord>

    /**
     * 自动排序（WO52 6.1，04 §2.3 `phrase_auto_sort_info`“最近使用的
     * 排在前面”）：置顶优先，其余按最近使用在前、未用过的按新建在前；
     * 手动 sortOrder 在此口径下不参与排序。
     */
    @Query("SELECT * FROM phrase_records ORDER BY pinned DESC, lastUsedAt DESC, createdAt DESC")
    suspend fun getAllAutoSorted(): List<PhraseRecord>

    /**
     * 输入码精确查询（WO52 6.1，04 §2.2 `il3.java` 口径）：
     * `WHERE shortcut = ? ORDER BY isFavorite DESC, sort ASC, date DESC`
     * （Jime 字段名：pinned/sortOrder/createdAt）。同码多条全返回，
     * 由候选注入侧按序拼到候选前。
     */
    @Query("SELECT * FROM phrase_records WHERE shortcut = :code ORDER BY pinned DESC, sortOrder ASC, createdAt DESC")
    suspend fun getByShortcut(code: String): List<PhraseRecord>

    @Query("UPDATE phrase_records SET lastUsedAt = :at WHERE id = :id")
    suspend fun setLastUsedById(id: Long, at: Long): Int

    @Query("UPDATE phrase_records SET pinned = :pinned WHERE id = :id")
    suspend fun setPinnedById(id: Long, pinned: Boolean): Int

    @Query("SELECT MIN(sortOrder) FROM phrase_records")
    suspend fun minSortOrder(): Long?

    @Query("UPDATE phrase_records SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrderById(id: Long, order: Long)

    @Query("SELECT * FROM phrase_records WHERE id = :id")
    suspend fun getById(id: Long): PhraseRecord?

    @Query("DELETE FROM phrase_records WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM phrase_records")
    suspend fun deleteAll(): Int

    /**
     * 搜索字段（WO52 6.1，04 §2.3 `tl3.java` 口径）：title/content/
     * shortcut/category/tags（Jime 字段名：label/text/shortcut/
     * category/tags）。isFavorite 不进 LIKE（布尔列无文本可比）。
     */
    @Query("SELECT * FROM phrase_records WHERE label LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR shortcut LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' ORDER BY pinned DESC, sortOrder ASC, createdAt DESC")
    suspend fun search(query: String): List<PhraseRecord>
}
