// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** 脚本（WO52 8.1）。 */
@Dao
interface ScriptItemDao {
    @Query("SELECT * FROM ScriptItem ORDER BY sort ASC, date ASC")
    suspend fun getAll(): List<ScriptItem>

    @Query("SELECT * FROM ScriptItem WHERE id = :id")
    suspend fun getById(id: Long): ScriptItem?

    @Query("SELECT * FROM ScriptItem WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): ScriptItem?

    @Query("SELECT * FROM ScriptItem WHERE isFavorite = 1 ORDER BY sort ASC, date ASC")
    suspend fun getFavorites(): List<ScriptItem>

    @Insert
    suspend fun insert(item: ScriptItem): Long

    @Insert
    suspend fun insertAll(items: List<ScriptItem>): List<Long>

    @Update
    suspend fun update(item: ScriptItem): Int

    @Query("DELETE FROM ScriptItem WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM ScriptItem")
    suspend fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM ScriptItem")
    suspend fun count(): Int
}

/** 脚本标签（WO52 8.1）。 */
@Dao
interface ScriptTagDao {
    @Query("SELECT * FROM ScriptTag ORDER BY date ASC")
    suspend fun getAll(): List<ScriptTag>

    @Query("SELECT * FROM ScriptTag WHERE id = :id")
    suspend fun getById(id: Long): ScriptTag?

    @Query("SELECT * FROM ScriptTag WHERE uuid = :uuid")
    suspend fun getByUuid(uuid: String): ScriptTag?

    @Insert
    suspend fun insert(tag: ScriptTag): Long

    @Update
    suspend fun update(tag: ScriptTag): Int

    @Query("DELETE FROM ScriptTag WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM ScriptTag")
    suspend fun count(): Int
}

/** 脚本↔标签交叉引用（WO52 8.1）。 */
@Dao
interface ScriptTagCrossRefDao {
    @Query("SELECT * FROM ScriptTagCrossRef WHERE scriptId = :scriptId")
    suspend fun getByScriptId(scriptId: Long): List<ScriptTagCrossRef>

    @Query("SELECT * FROM ScriptTagCrossRef WHERE tagId = :tagId")
    suspend fun getByTagId(tagId: Long): List<ScriptTagCrossRef>

    @Insert
    suspend fun insert(ref: ScriptTagCrossRef)

    @Query("DELETE FROM ScriptTagCrossRef WHERE scriptId = :scriptId AND tagId = :tagId")
    suspend fun delete(scriptId: Long, tagId: Long): Int

    @Query("DELETE FROM ScriptTagCrossRef WHERE scriptId = :scriptId")
    suspend fun deleteByScriptId(scriptId: Long): Int
}

/** 脚本变量（WO52 8.1）。 */
@Dao
interface ScriptVariableDao {
    @Query("SELECT * FROM ScriptVariable ORDER BY date ASC")
    suspend fun getAll(): List<ScriptVariable>

    @Query("SELECT * FROM ScriptVariable WHERE id = :id")
    suspend fun getById(id: Long): ScriptVariable?

    @Query("SELECT * FROM ScriptVariable WHERE `key` = :key")
    suspend fun getByKey(key: String): ScriptVariable?

    @Insert
    suspend fun insert(variable: ScriptVariable): Long

    @Update
    suspend fun update(variable: ScriptVariable): Int

    @Query("DELETE FROM ScriptVariable WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM ScriptVariable")
    suspend fun count(): Int
}
