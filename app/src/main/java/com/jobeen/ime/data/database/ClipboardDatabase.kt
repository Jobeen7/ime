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
@Database(entities = [ClipboardRecord::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class ClipboardDatabase : RoomDatabase() {

    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: ClipboardDatabase? = null

        fun getInstance(context: Context): ClipboardDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipboardDatabase::class.java,
                    "clipboard_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
