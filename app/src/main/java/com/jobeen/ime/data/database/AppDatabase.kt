package com.jobeen.ime.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(entities = [CandidateSorting::class, ClipboardRecord::class, CandidatePrefer::class, PhraseRecord::class], version = 8, exportSchema = false)
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
                )                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { INSTANCE = it }
                // 注意：不要加 fallbackToDestructiveMigration() —— 漏写 Migration 时宁可启动崩溃（fail-fast，
                // 发布前真机测试会先暴露），也不要静默清空用户的剪贴板/常用语/选词偏好。每次 bump version
                // 都必须写 Migration（无结构变更时写空迁移，见 MIGRATION_7_8）。
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
            db.execSQL("DROP TABLE IF EXISTS `candidate_prefers`")
            db.execSQL(
                """
                CREATE TABLE `candidate_prefers` (
                    `text` TEXT NOT NULL PRIMARY KEY,
                    `context` TEXT NOT NULL,
                    `click_count` INTEGER NOT NULL DEFAULT 1,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """
            )
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
    }
}