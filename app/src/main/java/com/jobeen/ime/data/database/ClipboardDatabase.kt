package com.jobeen.ime.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 剪贴板历史独立数据库（文件 clipboard_database）。
 *
 * 从 ime_database 拆出的原因：云备份规则只能按文件排除。剪贴板历史含用户复制过的
 * 敏感文本，必须排除在系统云备份之外；而常用语/选词偏好等留在 ime_database 照常备份。
 * 旧数据由 ClipboardManager 在首次访问时从 ime_database 一次性迁入。
 */
@Database(entities = [ClipboardRecord::class], version = 4, exportSchema = true)
@TypeConverters(Converters::class)
abstract class ClipboardDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: ClipboardDatabase? = null

        /** v2：新增 pinned（置顶）列，默认 0。 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE clipboard_records ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** v3：新增 sortOrder（拖动排序）列，回填 -timestamp 使既有顺序不变。 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE clipboard_records ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE clipboard_records SET sortOrder = -timestamp")
            }
        }

        /**
         * v4：text 唯一索引。建索引前必须先收敛存量，否则同 text 重复行
         * 会让 CREATE UNIQUE INDEX 直接失败、迁移永不完成：
         * 1. 软删行整表物理清除——与现行 purge 口径一致（删除已改物理删，
         *    墓碑行靠 ClipboardManager.purgeLegacyDeletedOnce 在首访时清）；
         *    提前到迁移内是因为唯一索引覆盖全表行，墓碑行与有效行同
         *    text 同样撞约束，不清建不了索引；
         * 2. 有效行按 text 去重：删掉「存在严格更优行」的行，保留口径与
         *    dedupeClipboardByText 严格一致（pinned 优先、timestamp
         *    最新、id 最大）。每组的最大行永不满足删除条件，去重结果
         *    与求值顺序无关；
         * 3. 建唯一索引（名称与 Room 按实体 @Index 生成的保持一致）。
         *
         * 注意：ClipboardRecord 实体被 AppDatabase 共用，ime_database 里
         * 的同名旧表也须配套建此索引（AppDatabase 侧版本 bump 与迁移
         * 不在本文件，随实体变更一并处理）。
         */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM clipboard_records WHERE deleted = 1")
                db.execSQL(
                    "DELETE FROM clipboard_records WHERE EXISTS (" +
                        "SELECT 1 FROM clipboard_records other " +
                        "WHERE other.text = clipboard_records.text AND (" +
                        "other.pinned > clipboard_records.pinned OR " +
                        "(other.pinned = clipboard_records.pinned AND other.timestamp > clipboard_records.timestamp) OR " +
                        "(other.pinned = clipboard_records.pinned AND other.timestamp = clipboard_records.timestamp AND other.id > clipboard_records.id)))"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_clipboard_records_text` ON `clipboard_records` (`text`)"
                )
            }
        }

        fun getInstance(context: Context): ClipboardDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext, "clipboard_database")
                    .also { INSTANCE = it }
            }
        }

        /**
         * 以指定库名新建实例（不走单例缓存）：迁移仪器测试以测试专用
         * 库名走同一条迁移链验证，避免在真机上触碰生产库文件。
         * 生产代码只用 [getInstance]。
         */
        fun createWithName(context: Context, name: String): ClipboardDatabase =
            build(context.applicationContext, name)

        private fun build(context: Context, name: String): ClipboardDatabase =
            Room.databaseBuilder(context, ClipboardDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
