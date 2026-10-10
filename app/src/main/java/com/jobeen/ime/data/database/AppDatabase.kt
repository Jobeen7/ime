package com.jobeen.ime.data.database

import android.content.Context
import androidx.core.content.edit
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CandidateSorting::class, ClipboardRecord::class, CandidatePrefer::class, PhraseRecord::class], version = 10, exportSchema = true)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun candidateSortingDao(): CandidateSortingDao

    abstract fun clipboardDao(): ClipboardDao

    abstract fun candidatePreferDao(): CandidatePreferDao

    abstract fun phraseDao(): PhraseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ime_database"
                )                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10).addCallback(VACUUM_CALLBACK).build().also { INSTANCE = it }
                // 注意：不要加 fallbackToDestructiveMigration() —— 漏写 Migration 时宁可启动崩溃（fail-fast，
                // 发布前真机测试会先暴露），也不要静默清空用户的剪贴板/常用语/选词偏好。每次 bump version
                // 都必须写 Migration（无结构变更时写空迁移，见 MIGRATION_7_8）。
            }
        }

        private const val MAINTENANCE_PREFS = "db_maintenance"
        private const val KEY_VACUUM_V10_DONE = "vacuum_after_v10_done"
        private const val KEY_VACUUM_CLIP_DONE = "vacuum_after_clipboard_split_done"

        /**
         * 剪贴板拆库迁移完成后补跑的一次性 VACUUM：旧剪贴板行在迁移时
         * 已从主库整表删除，但全文还留在主库文件的空闲页与 WAL 里，
         * 而 onOpen 的一次性 VACUUM 在启动时（迁移发生之前）就已跑完
         * 并置了标记，擦不到这批残留。迁移完成点单独补跑一次
         * （VACUUM 后再截断 WAL），成功落标记、失败下次迁移检查重试。
         * 在调用方的后台线程执行。
         */
        fun vacuumAfterClipboardSplit() {
            val prefs = com.jobeen.ime.base.util.appContext
                .getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_VACUUM_CLIP_DONE, false)) return
            runCatching {
                val db = getInstance(com.jobeen.ime.base.util.appContext)
                    .openHelper.writableDatabase
                db.execSQL("VACUUM")
                db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                prefs.edit { putBoolean(KEY_VACUUM_CLIP_DONE, true) }
            }.onFailure {
                timber.log.Timber.w(it, "VACUUM after clipboard split failed; will retry")
            }
        }

        /**
         * 一次性 VACUUM：v10 迁移只把 candidate_prefers.context 的值清空，
         * 旧输入片段仍留在数据库空闲页中（迁移在事务内、无法执行 VACUUM）。
         * 库打开后（不在事务中）补跑一次 VACUUM 重建文件彻底擦除，成功后
         * 落标记，之后每次打开只是一次偏好读检查。
         */
        private val VACUUM_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                val prefs = com.jobeen.ime.base.util.appContext
                    .getSharedPreferences(MAINTENANCE_PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_VACUUM_V10_DONE, false)) return
                runCatching {
                    db.execSQL("VACUUM")
                    // VACUUM 重建主文件后，旧页还可能残留在 WAL 文件里，一并截断
                    db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                }.onSuccess {
                    prefs.edit().putBoolean(KEY_VACUUM_V10_DONE, true).apply()
                }
            }
        }

        private val MIGRATION_1_2: Migration = Migration(
            startVersion = 1,
            endVersion = 2,
        ) { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `clipboard_records` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`cloud` INTEGER NOT NULL)"
            )
        }

        private val MIGRATION_2_3: Migration = Migration(
            startVersion = 2,
            endVersion = 3,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_3_4: Migration = Migration(
            startVersion = 3,
            endVersion = 4,
        ) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `candidate_prefers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `text` TEXT NOT NULL,
                    `context` TEXT NOT NULL,
                    `click_count` INTEGER NOT NULL DEFAULT 1,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """
            )
        }

        private val MIGRATION_4_5: Migration = Migration(
            startVersion = 4,
            endVersion = 5,
        ) { db ->
            // 主键从自增 id 改为 text：同结构搬数据，不要 DROP 后重建白白丢掉用户选词偏好
            db.execSQL(
                """
                CREATE TABLE `candidate_prefers_new` (
                    `text` TEXT NOT NULL PRIMARY KEY,
                    `context` TEXT NOT NULL,
                    `click_count` INTEGER NOT NULL DEFAULT 1,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """
            )
            db.execSQL(
                """
                INSERT INTO `candidate_prefers_new` (text, context, click_count, created_at, updated_at)
                SELECT text,
                    (SELECT context FROM candidate_prefers o2
                     WHERE o2.text = o.text ORDER BY updated_at DESC LIMIT 1),
                    SUM(click_count), MIN(created_at), MAX(updated_at)
                FROM candidate_prefers o GROUP BY text
                """
            )
            db.execSQL("DROP TABLE IF EXISTS `candidate_prefers`")
            db.execSQL("ALTER TABLE `candidate_prefers_new` RENAME TO `candidate_prefers`")
        }

        private val MIGRATION_5_6: Migration = Migration(
            startVersion = 5,
            endVersion = 6,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_6_7: Migration = Migration(
            startVersion = 6,
            endVersion = 7,
        ) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `phrase_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `text` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """
            )
        }

        // v8 仅随包名改版 bump，无结构变更；空迁移避免 fallbackToDestructiveMigration 清库
        private val MIGRATION_7_8: Migration = Migration(
            startVersion = 7,
            endVersion = 8,
        ) { _ -> }

        // v9：与 ClipboardDatabase v2 同步——ClipboardRecord 实体共用，旧主库里的
        // clipboard_records 残表也要补 pinned 列，否则实体 schema 校验不过（fail-fast 崩）
        private val MIGRATION_8_9: Migration = Migration(
            startVersion = 8,
            endVersion = 9,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
        }

        // v10：清空历史遗留的 candidate_prefers.context（旧版本存了光标前 20 字的输入片段，
        // 该列从未被读取）。结构不变，只擦内容；列保留以兼容旧备份与 Room 实体。
        // 注意：SQLite 空闲页不会被立即覆写，彻底擦除需另行 VACUUM。
        private val MIGRATION_9_10: Migration = Migration(
            startVersion = 9,
            endVersion = 10,
        ) { db ->
            db.execSQL("UPDATE `candidate_prefers` SET `context` = ''")
        }
    }
}