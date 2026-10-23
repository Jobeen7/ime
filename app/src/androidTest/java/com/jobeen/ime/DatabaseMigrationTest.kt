package com.jobeen.ime

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jobeen.ime.data.database.AppDatabase
import com.jobeen.ime.data.database.ClipboardDatabase
import com.jobeen.ime.data.database.ClipboardRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 迁移仪器测试（§7.4）：ClipboardDatabase 3→4（清软删行 →
 * 按 text 去重 → 建 text 唯一索引）与 AppDatabase 12→14（经 13 的
 * 剪贴板索引迁移、到 14 DROP 剪贴板残表，清点 D20）。
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
 * 不触碰生产数据。前提：本测试跑在 debug 构建上，debug 带
 * applicationIdSuffix ".debug"、与正式版是不同包名，测试进程的
 * 私有目录与正式版完全隔离——connectedAndroidTest 结束后的卸载
 * 也只卸 debug 包，故在装有正式版的日常机上跑不会删掉真实的
 * 常用语、选词偏好与剪贴板。若去掉 debug 的包名后缀，此结论
 * 不再成立。
 *
 * 本机未跑（需真机/模拟器）；CI 的 android-test.yml 为手动触发，
 * 已改用 google_apis/x86_64/API 31 转译镜像，但至今尚未实际运行，
 * 本测试尚未在 CI 执行过。
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
    fun appDatabase_migrates12To14_dropsClipboardTable() {
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
        // v14（清点 D20）：剪贴板残表整表已 DROP，其余三表照常在
        val tables = HashSet<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        assertTrue("clipboard_records" !in tables)
        assertTrue("candidate_sortings" in tables)
        assertTrue("candidate_prefers" in tables)
        assertTrue("phrase_records" in tables)
    }

    /**
     * 存量限长去重规格（ClipboardDao.dedupeOversizedByTruncatedKey）：
     * 长度恰等于上限的行与超限行同前缀时，截断后必然同 text——保留
     * 口径落败的一方必须在去重阶段被删（等长行也要作删除目标），
     * 否则截断 UPDATE 撞 text 唯一索引整条失败、迁移永不收敛。
     * 两组对称场景：超限行置顶占优（留超限行、截断后文本与等长行
     * 相同）与等长行置顶占优（留等长行、超限行被删）。直接开当前
     * 版本库走生产 DAO，不经迁移链。本机未跑（需真机/模拟器）。
     */
    @Test
    fun clipboardDao_dedupeOversizedByTruncatedKey_exactLimitRowCompetes() {
        val name = "clipboard_dedupe_test"
        context.deleteDatabase(name)
        val roomDb = ClipboardDatabase.createWithName(context, name)
        try {
            val dao = roomDb.clipboardDao()
            runBlocking {
                // 组一：等长行未置顶（ts 更新也压不过置顶），超限行置顶占优
                dao.insert(ClipboardRecord(text = "abcdefghij", timestamp = 100))
                dao.insert(ClipboardRecord(text = "abcdefghijXYZ", timestamp = 50, pinned = true))
                // 组二：等长行置顶占优，超限行未置顶（ts 再新也落败）
                dao.insert(ClipboardRecord(text = "klmnopqrst", timestamp = 50, pinned = true))
                dao.insert(ClipboardRecord(text = "klmnopqrstXYZ", timestamp = 999))
                dao.dedupeOversizedByTruncatedKey(10)
                dao.truncateOversizedTexts(10)
                val rows = dao.getAllRaw()
                assertEquals(2, rows.size)
                val byText = rows.associateBy { it.text }
                // 两组各只剩一行、无重复文本、截断未抛唯一约束
                assertEquals(setOf("abcdefghij", "klmnopqrst"), byText.keys)
                // 组一留的是置顶的超限行（截断后与等长行同文本）
                assertTrue(byText.getValue("abcdefghij").pinned)
                assertEquals(50L, byText.getValue("abcdefghij").timestamp)
                // 组二留的是置顶的等长行本身
                assertTrue(byText.getValue("klmnopqrst").pinned)
                assertEquals(50L, byText.getValue("klmnopqrst").timestamp)
            }
        } finally {
            roomDb.close()
            context.deleteDatabase(name)
        }
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
