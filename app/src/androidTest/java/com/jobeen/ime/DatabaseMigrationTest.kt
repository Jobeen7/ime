package com.jobeen.ime

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.ClipboardDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移仪器测试（§7.4）：ClipboardDatabase 3→4 与 AppDatabase
 * 12→13（两处都是「清软删行 → 按 text 去重 → 建 text 唯一索引」）。
 *
 * 造旧版库不用 MigrationTestHelper（room-testing 是额外构件，构建
 * 环境未必拉得到）：按 app/schemas 里导出的旧版 createSql 手工建库
 * 并写入旧版 identity 到 room_master_table（与 MigrationTestHelper
 * 的造法等价），再经**生产构建器**（createWithName 的真实迁移链）
 * 开库断言。两库的 Migration 都是 companion 私有成员，测试拿不到、
 * 无法走 runMigrationsAndValidate；经生产路径开库时 Room 自身会做
 * identity 与 TableInfo 校验，迁移产出的索引与实体不一致会直接
 * 抛错，等价于 schema 验证。
 *
 * 使用测试专用库名（ime_database_test / clipboard_database_test），
 * 不触碰生产数据：在装有正式版的日常机上跑也不会删掉真实的
 * 常用语、选词偏好与剪贴板。
 *
 * 本机未跑（需真机/模拟器）；CI 以 android-test.yml 手动触发运行。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun clipboardDatabase_migrates3To4() {
        createOldDatabase(
            name = CLIPBOARD_DB_NAME,
            version = 3,
            identityHash = "a56076266282b2fb9498caa3557db1e9",
            createSqls = listOf(CLIPBOARD_RECORDS_SQL),
        )
        val db = ClipboardDatabase.createWithName(context, CLIPBOARD_DB_NAME)
            .openHelper.writableDatabase
        assertClipboardMigrated(db)
    }

    @Test
    fun appDatabase_migrates12To13() {
        createOldDatabase(
            name = IME_DB_NAME,
            version = 12,
            identityHash = "b92e705d0b16e5ceb322460de325a766",
            createSqls = listOf(
                CANDIDATE_SORTING_SQL,
                CLIPBOARD_RECORDS_SQL,
                CANDIDATE_PREFERS_SQL,
                PHRASE_RECORDS_SQL,
            ),
        )
        val db = AppDatabase.createWithName(context, IME_DB_NAME)
            .openHelper.writableDatabase
        assertClipboardMigrated(db)
    }

    /** 按旧版 schema 建库、写旧版 identity、灌种子后关闭，等生产构建器接手迁移。 */
    private fun createOldDatabase(
        name: String,
        version: Int,
        identityHash: String,
        createSqls: List<String>,
    ) {
        context.deleteDatabase(name)
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createSqls.forEach { db.execSQL(it) }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS room_master_table " +
                        "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                )
                db.execSQL(
                    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                        "VALUES (42, '$identityHash')"
                )
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build()
        )
        helper.writableDatabase.use { db -> seedClipboardRows(db) }
        helper.close()
    }

    /**
     * 存量种子（v3/v12 的 clipboard_records 列集相同）：
     * - `dup`：普通行 + 置顶行 + 一条**软删的**置顶更新行——软删行必须
     *   先被清掉，既不能存活也不能在去重里「胜出」，最终留置顶行 id=2；
     * - `dup2`：同未置顶，留 timestamp 最新 id=5；
     * - `dup3`：pinned/timestamp 全同，留 id 最大 id=7；
     * - `gone`：纯软删行，应被清；
     * - `solo`：无冲突行，原样保留。
     */
    private fun seedClipboardRows(db: SupportSQLiteDatabase) {
        fun insert(id: Long, text: String, ts: Long, deleted: Int, pinned: Int) {
            db.execSQL(
                "INSERT INTO clipboard_records " +
                    "(id, text, timestamp, cloud, deleted, deletedAt, pinned, sortOrder) " +
                    "VALUES ($id, '$text', $ts, 0, $deleted, 0, $pinned, 0)"
            )
        }
        insert(1, "dup", ts = 100, deleted = 0, pinned = 0)
        insert(2, "dup", ts = 50, deleted = 0, pinned = 1)
        insert(3, "dup", ts = 999, deleted = 1, pinned = 1)
        insert(4, "dup2", ts = 100, deleted = 0, pinned = 0)
        insert(5, "dup2", ts = 200, deleted = 0, pinned = 0)
        insert(6, "dup3", ts = 100, deleted = 0, pinned = 0)
        insert(7, "dup3", ts = 100, deleted = 0, pinned = 0)
        insert(8, "gone", ts = 100, deleted = 1, pinned = 0)
        insert(9, "solo", ts = 300, deleted = 0, pinned = 0)
    }

    private fun assertClipboardMigrated(db: SupportSQLiteDatabase) {
        // 唯一索引存在且确为 UNIQUE
        db.query(
            "SELECT sql FROM sqlite_master " +
                "WHERE type = 'index' AND name = 'index_clipboard_records_text'"
        ).use { cursor ->
            assertTrue("unique index missing", cursor.moveToFirst())
            val sql = cursor.getString(0) ?: ""
            assertTrue("index is not UNIQUE: $sql", sql.contains("UNIQUE"))
        }
        // 软删行已整表清除
        db.query("SELECT COUNT(*) FROM clipboard_records WHERE deleted = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        // 同 text 仅剩口径内最佳一行
        val survivors = mutableMapOf<String, Triple<Long, Int, Long>>()
        db.query("SELECT text, id, pinned, timestamp FROM clipboard_records").use { cursor ->
            while (cursor.moveToNext()) {
                survivors[cursor.getString(0)] =
                    Triple(cursor.getLong(1), cursor.getInt(2), cursor.getLong(3))
            }
        }
        assertEquals(
            mapOf(
                "dup" to Triple(2L, 1, 50L),
                "dup2" to Triple(5L, 0, 200L),
                "dup3" to Triple(7L, 0, 100L),
                "solo" to Triple(9L, 0, 300L),
            ),
            survivors,
        )
    }

    private companion object {
        const val CLIPBOARD_DB_NAME = "clipboard_database_test"
        const val IME_DB_NAME = "ime_database_test"

        // 以下建表 SQL 逐字摘自 app/schemas 导出（占位符已替换为表名）：
        // ClipboardDatabase/3.json 与 AppDatabase/12.json 的 clipboard_records；
        // 其余三张摘自 AppDatabase/12.json。
        const val CLIPBOARD_RECORDS_SQL =
            "CREATE TABLE IF NOT EXISTS `clipboard_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `cloud` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `deletedAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)"
        const val CANDIDATE_SORTING_SQL =
            "CREATE TABLE IF NOT EXISTS `candidate_sorting_v2` (`sorting_key` TEXT NOT NULL, `candidateIds` TEXT NOT NULL, PRIMARY KEY(`sorting_key`))"
        const val CANDIDATE_PREFERS_SQL =
            "CREATE TABLE IF NOT EXISTS `candidate_prefers` (`text` TEXT NOT NULL, `click_count` INTEGER NOT NULL, `context` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`text`))"
        const val PHRASE_RECORDS_SQL =
            "CREATE TABLE IF NOT EXISTS `phrase_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `text` TEXT NOT NULL, `label` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `pinned` INTEGER NOT NULL)"
    }
}
