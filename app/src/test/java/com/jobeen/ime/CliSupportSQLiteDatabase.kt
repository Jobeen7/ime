// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import android.content.ContentValues
import android.database.Cursor
import android.database.SQLException
import android.database.sqlite.SQLiteTransactionListener
import android.os.CancellationSignal
import android.util.Pair
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume

/**
 * C-L-27：主机 SQLite 执行器——以系统 `sqlite3` CLI 为引擎的
 * [SupportSQLiteDatabase]，供迁移测试真执行 [androidx.room.migration.Migration.migrate]。
 *
 * 背景：主机单测无 Room 运行环境（禁 Robolectric；room-testing
 * 不在镜像里），且 sqlite-jdbc 不在本地镜像／Gradle 缓存里——
 * 不引新依赖，改用本机已有的 sqlite3 二进制（/usr/bin/sqlite3，
 * GitHub ubuntu  runner 亦自带）。每个 execSQL 即一次 CLI 调用
 * （自动提交、无跨语句事务——迁移本身逐条 DDL，结构与数据断言
 * 不受影响）；外键约束按 SQLite 缺省为关（与 Room 迁移时关外键
 * 同口径，DDL／种子插入不因外键顺序失败）。
 *
 * 仅 execSQL／版本读写走真执行；query／insert／update／delete／
 * compileStatement 不被迁移调用，一律抛 UnsupportedOperationException。
 * 校验读数（表结构／行数）走本类的 CLI 直读 helper，不经 Cursor。
 */
class CliSupportSQLiteDatabase(private val dbFile: File) : SupportSQLiteDatabase {

    companion object {
        /** sqlite3 不在 PATH 时跳过（不红）：执行环境须自带 sqlite3。 */
        fun assumeSqlite3Available() {
            val ok = runCatching {
                ProcessBuilder("sqlite3", "--version")
                    .redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS)
            }.getOrDefault(false)
            Assume.assumeTrue("本机无 sqlite3 二进制，跳过迁移执行测试", ok)
        }
    }

    private var inTx = false

    /** 执行一条 SQL（无绑定参数）；失败抛 [SQLException]（含 stderr）。 */
    private fun runSql(sql: String, json: Boolean = false): String {
        // SQL 走 stdin 传 UTF-8 字节——不走 argv：测试 worker JVM 在
        // C locale 下 argv 编码会把中文写成 ?（曾致种子名撞唯一索引）。
        val cmd = mutableListOf("sqlite3")
        if (json) cmd += "-json"
        cmd += dbFile.absolutePath
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        proc.outputStream.use { it.write(sql.toByteArray(Charsets.UTF_8)) }
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
        if (!proc.waitFor(30, TimeUnit.SECONDS) || proc.exitValue() != 0) {
            throw SQLException("sqlite3 执行失败（exit=${proc.exitValue()}）: $err; SQL: ${sql.take(200)}")
        }
        return out
    }

    /** 绑定参数转 SQL 字面量（字符串单引号加倍；? 只认引号外的占位符）。 */
    private fun bind(sql: String, args: Array<out Any?>): String {
        val out = StringBuilder()
        var argIndex = 0
        var inString = false
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            if (c == '\'') {
                if (inString && i + 1 < sql.length && sql[i + 1] == '\'') {
                    out.append("''")
                    i += 2
                    continue
                }
                inString = !inString
                out.append(c)
                i++
                continue
            }
            if (c == '?' && !inString) {
                require(argIndex < args.size) { "绑定参数不足：SQL 需多于 ${args.size} 个" }
                out.append(literal(args[argIndex++]))
                i++
                continue
            }
            out.append(c)
            i++
        }
        require(argIndex == args.size) { "绑定参数多余：SQL 只有 $argIndex 个占位符，给了 ${args.size} 个" }
        return out.toString()
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "NULL"
        is String -> "'" + value.replace("'", "''") + "'"
        is Number -> value.toString()
        is Boolean -> if (value) "1" else "0"
        is ByteArray -> "X'" + value.joinToString("") { "%02x".format(it) } + "'"
        else -> "'" + value.toString().replace("'", "''") + "'"
    }

    // ---- 校验读数（测试直读，不经 Cursor） ----

    /** SELECT 首行首列（无行回 null）。 */
    fun scalar(sql: String): String? {
        val out = runSql(sql).trim()
        return out.ifEmpty { null }
    }

    /** SELECT 结果行（-json 解析；每行一 Map）。 */
    fun rows(sql: String): List<Map<String, String?>> {
        val out = runSql(sql, json = true).trim()
        if (out.isEmpty()) return emptyList()
        return Json.parseToJsonElement(out).jsonArray.map { el ->
            el.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull }
        }
    }

    /** 表的建表 SQL（sqlite_master.sql），无表回 null。 */
    fun tableSql(table: String): String? =
        scalar("SELECT sql FROM sqlite_master WHERE type='table' AND name='${table.replace("'", "''")}';")

    /** 表的列名（PRAGMA table_info 顺序）。 */
    fun columns(table: String): List<String> =
        rows("PRAGMA table_info(`$table`);").mapNotNull { it["name"] }

    /** 表名集合（不含 sqlite_sequence 等内部表）。 */
    fun tableNames(): Set<String> =
        rows("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")
            .mapNotNull { it["name"] }.toSet()

    /** 索引名集合。 */
    fun indexNames(): Set<String> =
        rows("SELECT name FROM sqlite_master WHERE type='index' AND sql IS NOT NULL;")
            .mapNotNull { it["name"] }.toSet()

    fun count(table: String): Long =
        scalar("SELECT COUNT(*) FROM `$table`;")?.toLongOrNull() ?: 0L

    // ---- SupportSQLiteDatabase：迁移实际用到的 ----

    override fun execSQL(sql: String) {
        runSql(sql)
    }

    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        runSql(bind(sql, bindArgs))
    }

    override var version: Int
        get() = scalar("PRAGMA user_version;")?.toIntOrNull() ?: 0
        set(value) {
            runSql("PRAGMA user_version=$value;")
        }

    override val path: String get() = dbFile.absolutePath

    override val isOpen: Boolean get() = true

    override val isReadOnly: Boolean get() = false

    override fun needUpgrade(newVersion: Int): Boolean = version < newVersion

    override val isDatabaseIntegrityOk: Boolean get() = scalar("PRAGMA integrity_check;") == "ok"

    override fun beginTransaction() {
        inTx = true
    }

    override fun beginTransactionNonExclusive() {
        inTx = true
    }

    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) {
        inTx = true
    }

    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) {
        inTx = true
    }

    override fun endTransaction() {
        inTx = false
    }

    override fun setTransactionSuccessful() = Unit

    override fun inTransaction(): Boolean = inTx

    override val isDbLockedByCurrentThread: Boolean get() = false

    override fun yieldIfContendedSafely(): Boolean = false

    override fun yieldIfContendedSafely(sleepAfterYieldDelayMillis: Long): Boolean = false

    override val attachedDbs: List<Pair<String, String>> get() = emptyList()

    override val maximumSize: Long get() = 0L

    override fun setMaximumSize(numBytes: Long): Long = 0L

    override var pageSize: Long
        get() = 0L
        set(value) = Unit

    override fun enableWriteAheadLogging(): Boolean = false

    override fun disableWriteAheadLogging() = Unit

    override val isWriteAheadLoggingEnabled: Boolean get() = false

    override fun setLocale(locale: Locale) = Unit

    override fun setMaxSqlCacheSize(cacheSize: Int) = Unit

    override fun setForeignKeyConstraintsEnabled(enable: Boolean) = Unit

    override fun close() = Unit

    // ---- 迁移不调用的，一律抛（误用即失败，不静默） ----

    override fun query(sql: String): Cursor = throw UnsupportedOperationException("CLI 库不支持 query")
    override fun query(sql: String, bindArgs: Array<out Any?>): Cursor =
        throw UnsupportedOperationException("CLI 库不支持 query")

    override fun query(query: SupportSQLiteQuery): Cursor =
        throw UnsupportedOperationException("CLI 库不支持 query")

    override fun query(query: SupportSQLiteQuery, signal: CancellationSignal?): Cursor =
        throw UnsupportedOperationException("CLI 库不支持 query")

    override fun compileStatement(sql: String): SupportSQLiteStatement =
        throw UnsupportedOperationException("CLI 库不支持 compileStatement")

    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long =
        throw UnsupportedOperationException("CLI 库不支持 insert")

    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int =
        throw UnsupportedOperationException("CLI 库不支持 delete")

    override fun update(
        table: String,
        conflictAlgorithm: Int,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<out Any?>?,
    ): Int = throw UnsupportedOperationException("CLI 库不支持 update")
}
