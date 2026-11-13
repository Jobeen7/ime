// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.jobeen.ime.R
import com.jobeen.ime.data.database.CandidatePrefer
import com.jobeen.ime.data.database.CandidateSorting
import com.jobeen.ime.data.database.ClipboardGroup
import com.jobeen.ime.data.database.ClipboardRecord
import com.jobeen.ime.data.database.Converters
import com.jobeen.ime.data.database.PhraseRecord
import java.io.File

/**
 * 跨库一致快照（D-M1，fcitx `SqliteSnapshots.kt` 形状）：
 * 另开一条原生 SQLite 连接，ATTACH 两个 Room 库文件，在一个
 * 事务（beginTransactionNonExclusive，WAL 下读到同一时点）内
 * 读出五张表，事务外再对两库各跑 `PRAGMA quick_check(1)` 校验。
 * 读出的行与各 DAO 的全量查询口径逐字一致（WHERE／ORDER BY 照抄），
 * 只是执行位置从两个 Room 事务换成了一个跨库事务。
 *
 * 注意：
 * - 调用方须先调 `AppDatabase.getInstance`／`ClipboardDatabase.getInstance`
 *   打开过库（迁移已跑完），否则裸读旧版库文件会因缺列失败；
 *   任一库文件不存在时该库各表按空表处理（新装无数据）。
 * - 绝不在本连接上改 journal_mode（“never change the live Room
 *   connection's WAL mode”，fcitx 原文口径）：只读，不动库配置。
 * - 需要真 Context＋真库文件，本地 JVM 单测跑不通
 *   （android.database.sqlite 只在真机／Robolectric 下可用，
 *   项目禁 Robolectric）——主机只验编译，真机验收（备份一次＋
 *   还原一次）。SQL 与列名已按实体／迁移逐项核对，见各查询注释。
 */
internal class RoomSnapshotReader(
    private val appDbFile: File,
    private val clipDbFile: File,
) {
    data class RoomSnapshot(
        val clipboard: List<ClipboardRecord>,
        val groups: List<ClipboardGroup>,
        val phrases: List<PhraseRecord>,
        val prefers: List<CandidatePrefer>,
        val sortings: List<CandidateSorting>,
    )

    fun read(): RoomSnapshot {
        val appExists = appDbFile.isFile
        val clipExists = clipDbFile.isFile
        if (!appExists && !clipExists) {
            return RoomSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        // 主库取现存的一方；另一方 ATTACH（不存在即该库各表为空）。
        val mainFile = if (appExists) appDbFile else clipDbFile
        val attachFile = if (appExists) clipDbFile else null
        val attachClip = appExists && clipExists
        val db = try {
            SQLiteDatabase.openDatabase(
                mainFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
        } catch (e: Exception) {
            throw BackupManager.BackupException(backupString(R.string.backup_read_db_failed, e.message ?: backupString(R.string.backup_db_open_failed)))
        }
        try {
            if (attachClip) {
                try {
                    db.execSQL(
                        "ATTACH DATABASE '${attachFile!!.absolutePath.replace("'", "''")}' AS clip",
                    )
                } catch (e: Exception) {
                    throw BackupManager.BackupException(backupString(R.string.backup_read_db_failed, e.message ?: backupString(R.string.backup_db_attach_failed)))
                }
            }
            // 主库 schema 名：ATTACH 后主库仍叫 main，剪贴板库叫 clip；
            // 只开剪贴板库时主库即剪贴板库（clip 别名不存在），查询
            // 按下面 clipSchema／appSchema 两个前缀拼。
            val clipSchema = if (appExists) "clip" else "main"
            val appSchema = if (appExists) "main" else "clip"
            val snapshot = try {
                db.beginTransactionNonExclusive()
                try {
                    RoomSnapshot(
                        clipboard = if (clipExists) readClipboard(db, clipSchema) else emptyList(),
                        groups = if (clipExists) readGroups(db, clipSchema) else emptyList(),
                        phrases = if (appExists) readPhrases(db, appSchema) else emptyList(),
                        prefers = if (appExists) readPrefers(db, appSchema) else emptyList(),
                        sortings = if (appExists) readSortings(db, appSchema) else emptyList(),
                    ).also { db.setTransactionSuccessful() }
                } finally {
                    db.endTransaction()
                }
            } catch (e: BackupManager.BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupManager.BackupException(backupString(R.string.backup_read_db_failed, e.message ?: backupString(R.string.backup_db_read_failed)))
            }
            // 事务外校验两库完整性：任一库 first 行不是 "ok" 即损坏。
            if (appExists) checkQuickCheck(db, "main", backupString(R.string.backup_db_main))
            if (clipExists) checkQuickCheck(db, clipSchema, backupString(R.string.backup_db_clipboard))
            return snapshot
        } finally {
            runCatching { if (attachClip) db.execSQL("DETACH DATABASE clip") }
            runCatching { db.close() }
        }
    }

    private fun checkQuickCheck(db: SQLiteDatabase, schema: String, label: String) {
        val result = try {
            db.rawQuery("PRAGMA $schema.quick_check(1)", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            throw BackupManager.BackupException(backupString(R.string.backup_read_db_failed, e.message ?: backupString(R.string.backup_db_check_failed, label)))
        }
        if (result != "ok") {
            throw BackupManager.BackupException(backupString(R.string.backup_db_integrity_failed, label, result ?: backupString(R.string.backup_no_result)))
        }
    }

    // 以下五条查询与各 DAO 全量查询逐字一致（表名／列名／WHERE／ORDER BY）：
    // ClipboardDao.getAllActive / getAllGroups、PhraseDao.getAll、
    // CandidatePreferDao.getAllFull、CandidateSortingDao.getAllFull。

    private fun readClipboard(db: SQLiteDatabase, schema: String): List<ClipboardRecord> {
        val out = ArrayList<ClipboardRecord>()
        db.rawQuery(
            "SELECT id, text, timestamp, cloud, deleted, deletedAt, pinned, groupId, sortOrder " +
                "FROM $schema.clipboard_records WHERE deleted = 0",
            null,
        ).use { cursor ->
            val id = cursor.getColumnIndexOrThrow("id")
            val text = cursor.getColumnIndexOrThrow("text")
            val timestamp = cursor.getColumnIndexOrThrow("timestamp")
            val cloud = cursor.getColumnIndexOrThrow("cloud")
            val deleted = cursor.getColumnIndexOrThrow("deleted")
            val deletedAt = cursor.getColumnIndexOrThrow("deletedAt")
            val pinned = cursor.getColumnIndexOrThrow("pinned")
            val groupId = cursor.getColumnIndexOrThrow("groupId")
            val sortOrder = cursor.getColumnIndexOrThrow("sortOrder")
            while (cursor.moveToNext()) {
                out += ClipboardRecord(
                    id = cursor.getLong(id),
                    text = cursor.getString(text) ?: "",
                    timestamp = cursor.getLong(timestamp),
                    cloud = cursor.getInt(cloud) != 0,
                    deleted = cursor.getInt(deleted) != 0,
                    deletedAt = cursor.getLong(deletedAt),
                    pinned = cursor.getInt(pinned) != 0,
                    groupId = cursor.getLong(groupId),
                    sortOrder = cursor.getLong(sortOrder),
                )
            }
        }
        return out
    }

    private fun readGroups(db: SQLiteDatabase, schema: String): List<ClipboardGroup> {
        val out = ArrayList<ClipboardGroup>()
        db.rawQuery(
            "SELECT id, name, sortOrder, createdAt FROM $schema.clipboard_groups " +
                "ORDER BY sortOrder ASC, id ASC",
            null,
        ).use { cursor ->
            val id = cursor.getColumnIndexOrThrow("id")
            val name = cursor.getColumnIndexOrThrow("name")
            val sortOrder = cursor.getColumnIndexOrThrow("sortOrder")
            val createdAt = cursor.getColumnIndexOrThrow("createdAt")
            while (cursor.moveToNext()) {
                out += ClipboardGroup(
                    id = cursor.getLong(id),
                    name = cursor.getString(name) ?: "",
                    sortOrder = cursor.getLong(sortOrder),
                    createdAt = cursor.getLong(createdAt),
                )
            }
        }
        return out
    }

    private fun readPhrases(db: SQLiteDatabase, schema: String): List<PhraseRecord> {
        val out = ArrayList<PhraseRecord>()
        db.rawQuery(
            "SELECT id, text, label, createdAt, sortOrder, pinned FROM $schema.phrase_records " +
                "ORDER BY pinned DESC, sortOrder ASC, createdAt DESC",
            null,
        ).use { cursor ->
            val id = cursor.getColumnIndexOrThrow("id")
            val text = cursor.getColumnIndexOrThrow("text")
            val label = cursor.getColumnIndexOrThrow("label")
            val createdAt = cursor.getColumnIndexOrThrow("createdAt")
            val sortOrder = cursor.getColumnIndexOrThrow("sortOrder")
            val pinned = cursor.getColumnIndexOrThrow("pinned")
            while (cursor.moveToNext()) {
                out += PhraseRecord(
                    id = cursor.getLong(id),
                    text = cursor.getString(text) ?: "",
                    label = cursor.getString(label) ?: "",
                    createdAt = cursor.getLong(createdAt),
                    sortOrder = cursor.getLong(sortOrder),
                    pinned = cursor.getInt(pinned) != 0,
                )
            }
        }
        return out
    }

    private fun readPrefers(db: SQLiteDatabase, schema: String): List<CandidatePrefer> {
        val out = ArrayList<CandidatePrefer>()
        db.rawQuery(
            "SELECT text, context, click_count, created_at, updated_at " +
                "FROM $schema.candidate_prefers",
            null,
        ).use { cursor ->
            val text = cursor.getColumnIndexOrThrow("text")
            val context = cursor.getColumnIndexOrThrow("context")
            val count = cursor.getColumnIndexOrThrow("click_count")
            val createdAt = cursor.getColumnIndexOrThrow("created_at")
            val updatedAt = cursor.getColumnIndexOrThrow("updated_at")
            while (cursor.moveToNext()) {
                out += CandidatePrefer(
                    text = cursor.getString(text) ?: "",
                    context = cursor.getString(context) ?: "",
                    count = cursor.getInt(count),
                    createdAt = cursor.getLong(createdAt),
                    updatedAt = cursor.getLong(updatedAt),
                )
            }
        }
        return out
    }

    private fun readSortings(db: SQLiteDatabase, schema: String): List<CandidateSorting> {
        val converters = Converters()
        val out = ArrayList<CandidateSorting>()
        db.rawQuery(
            "SELECT sorting_key, candidateIds FROM $schema.candidate_sorting_v2",
            null,
        ).use { cursor ->
            val key = cursor.getColumnIndexOrThrow("sorting_key")
            val candidateIds = cursor.getColumnIndexOrThrow("candidateIds")
            while (cursor.moveToNext()) {
                out += CandidateSorting(
                    key = cursor.getString(key) ?: "",
                    // 与 Converters.toIntList 同一口径：逗号分隔，非数字段丢弃
                    candidateIds = converters.toIntList(cursor.getString(candidateIds) ?: ""),
                )
            }
        }
        return out
    }
}
