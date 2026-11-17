// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import androidx.room.migration.Migration
import com.jobeen.ime.data.database.AppDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * C-L-27：AppDatabase 迁移真执行——主机单测按 hand-roll 套路，
 * 从 Room schema JSON 建旧版库、逐段跑各 [Migration.migrate]（真
 * SQLite 引擎，见 [CliSupportSQLiteDatabase]），断言新表结构＋
 * 旧数据不丢＋种子落盘。
 *
 * 范围：schema JSON 只导出到 v10（app/schemas 下 10–18.json），
 * 故执行链从 v10 起跑（10→19 共 9 段）；1→10 无 JSON 建库，仍由
 * [AppDatabaseMigrationTest.migrationChain_covers14to19] 的反射
 * 链连续性覆盖。仓库外脚本
 * ~/workspace/system/wo52-g6-migration-check.py 只作补充，不再是
 * 唯一端到端证据。
 *
 * 脚本功能删除后：16→17 与 18→19 均为空迁移（只保链连续／只升
 * 版）；10→19 全链不再新建脚本四表，18→19 后旧库里的四表成孤表。
 * AI apiToken 加密：19→20 同为空迁移（列名／类型不变），见
 * [migrate_19to20_aiTokenPlaintextPreserved]——旧明文行原样保留，
 * 形态转换由 AiRepository 在首次访问时做（主机无 Keystore，迁移
 * 本身不断言加密）。
 */
class AppDatabaseMigrationExecTest {

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        CliSupportSQLiteDatabase.assumeSqlite3Available()
        tempDir = java.nio.file.Files.createTempDirectory("migration-exec-test").toFile()
    }

    private fun schemaDb(version: Int): JsonObject {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "schemas/com.jobeen.ime.data.database.AppDatabase/$version.json")
            if (candidate.isFile) {
                return json.parseToJsonElement(candidate.readText()).jsonObject["database"]!!.jsonObject
            }
            val appCandidate = File(dir, "app/schemas/com.jobeen.ime.data.database.AppDatabase/$version.json")
            if (appCandidate.isFile) {
                return json.parseToJsonElement(appCandidate.readText()).jsonObject["database"]!!.jsonObject
            }
            dir = dir.parentFile
        }
        throw AssertionError("找不到 AppDatabase $version.json（Room schema 未生成？先跑一次编译）")
    }

    private fun entities(db: JsonObject): List<JsonObject> =
        db["entities"]!!.jsonArray.map { it.jsonObject }

    /** 迁移对象（反射读 AppDatabase 的静态 Migration 字段，按起止版本排）。 */
    private fun migrations(): List<Migration> =
        AppDatabase::class.java.declaredFields
            .filter { Migration::class.java.isAssignableFrom(it.type) }
            .map {
                it.isAccessible = true
                it.get(null) as Migration
            }.sortedBy { it.startVersion }

    /** 按 schema JSON 建 version 版旧库（createSql 的 ${TABLE_NAME} 换真表名）。 */
    private fun buildDbAtVersion(version: Int): CliSupportSQLiteDatabase {
        val dbFile = File(tempDir, "v$version.db")
        val db = CliSupportSQLiteDatabase(dbFile)
        for (entity in entities(schemaDb(version))) {
            val table = entity["tableName"]!!.jsonPrimitive.content
            val createSql = entity["createSql"]!!.jsonPrimitive.content
                .replace("\${TABLE_NAME}", table)
            db.execSQL(createSql)
            entity["indices"]?.jsonArray?.forEach { indexEl ->
                val index = indexEl.jsonObject
                val indexSql = index["createSql"]!!.jsonPrimitive.content
                    .replace("\${TABLE_NAME}", table)
                db.execSQL(indexSql)
            }
        }
        db.version = version
        return db
    }

    /** 逐段跑 fromVersion→toVersion 的全部迁移（真执行）。 */
    private fun migrate(db: CliSupportSQLiteDatabase, fromVersion: Int, toVersion: Int) {
        var current = fromVersion
        val all = migrations()
        while (current < toVersion) {
            val m = all.singleOrNull { it.startVersion == current }
                ?: throw AssertionError("缺迁移 $current→${current + 1}")
            m.migrate(db)
            current = m.endVersion
        }
        assertEquals(toVersion, current)
    }

    /**
     * 断言库结构与 version 版 schema JSON 一致：表名／逐列结构
     * （PRAGMA table_info 对 JSON fields：列名／类型／非空／默认
     * 值／主键）／索引名。建表 SQL 文本只比 [freshTables]（本次迁
     * 移新建的表）——ALTER 过的表 SQLite 会改写存储文本（如旧列
     * DEFAULT 0 在新版 schema 里已无），结构以逐列比对为准。
     */
    private fun assertSchemaMatches(
        db: CliSupportSQLiteDatabase,
        version: Int,
        freshTables: Set<String> = emptySet(),
        // 已退役的表：从期望表集合里排除（迁移后库里不应有这些表，
        // 但 version 版 schema JSON 仍含它们，如脚本四表在 18.json）。
        // 按 "Script" 前缀排除，不在源码里逐个写表名。
        excludeRetiredScriptTables: Boolean = false,
    ) {
        val expected = entities(schemaDb(version)).associateBy {
            it["tableName"]!!.jsonPrimitive.content
        }.filterKeys { !excludeRetiredScriptTables || !it.startsWith("Script") }
        assertEquals(expected.keys, db.tableNames())
        for ((table, entity) in expected) {
            val fields = entity["fields"]!!.jsonArray.map { it.jsonObject }
            val pkCols = entity["primaryKey"]!!.jsonObject["columnNames"]!!.jsonArray
                .map { it.jsonPrimitive.content }.toSet()
            val actual = db.rows("PRAGMA table_info(`$table`);")
            assertEquals("$table 列数", fields.size, actual.size)
            fields.forEachIndexed { i, f ->
                val row = actual[i]
                val column = f["columnName"]!!.jsonPrimitive.content
                assertEquals("$table.$column 列名", column, row["name"])
                assertEquals(
                    "$table.$column 类型",
                    f["affinity"]!!.jsonPrimitive.content, row["type"],
                )
                val notNull = f["notNull"]?.jsonPrimitive?.boolean ?: false
                assertEquals("$table.$column 非空", if (notNull) "1" else "0", row["notnull"])
                val expectedDefault = f["defaultValue"]?.jsonPrimitive?.content
                if (expectedDefault != null) {
                    assertEquals(
                        "$table.$column 默认值",
                        expectedDefault, row["dflt_value"],
                    )
                }
                // JSON 未声明默认值时不比——旧版建表留下的 DEFAULT（如
                // v10 的 sortOrder DEFAULT 0）是 SQLite 迁移不改写
                // 的历史残余，Room 运行时总写全列，行为无影响。
                val inPk = column in pkCols
                val actualPk = (row["pk"]?.toIntOrNull() ?: 0) > 0
                assertEquals("$table.$column 主键", inPk, actualPk)
            }
            if (table in freshTables) {
                val expectedSql = entity["createSql"]!!.jsonPrimitive.content
                    .replace("\${TABLE_NAME}", table)
                assertEquals(
                    "$table 建表 SQL",
                    normalizeSql(expectedSql),
                    normalizeSql(db.tableSql(table) ?: ""),
                )
            }
        }
        val expectedIndices = expected.values.flatMap { entity ->
            entity["indices"]?.jsonArray?.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }.orEmpty()
        }.toSet()
        assertEquals(expectedIndices, db.indexNames())
    }

    private fun normalizeSql(sql: String): String =
        sql.trim().replace(Regex("\\s+"), " ")
            // SQLite 存建表 SQL 时去掉 IF NOT EXISTS（只比实质定义）。
            .replace("IF NOT EXISTS ", "")
            // ALTER TABLE RENAME 后 SQLite 把存储的建表 SQL 里表名
            // 的反引号改写成双引号（列名仍是反引号；L-33 迁移即此
            // 形），归一化后只比实质定义。
            .replace("\"", "`")

    @Test
    fun migrate_10to19_fullChain_executesEachSegmentAndPreservesData() {
        val db = buildDbAtVersion(10)
        // 种子旧数据：常用语一行（v10 的 phrase_records 只有
        // id／text／label／createdAt 四列）＋偏好一行。
        db.execSQL(
            "INSERT INTO `phrase_records` (`text`,`label`,`createdAt`) " +
                "VALUES ('旧词','旧标签',1700000000000)",
        )
        db.execSQL(
            "INSERT INTO `candidate_prefers` (`text`,`click_count`,`context`,`created_at`,`updated_at`) " +
                "VALUES ('测试',3,'ctx',1700000000000,1700000000000)",
        )
        migrate(db, 10, 19)
        // 新表（AI 七表）由本次迁移链新建，建表 SQL 逐字比对；
        // 旧表（ALTER 过的 phrase_records 等）只比逐列结构。
        // 脚本四表已退役：18.json 仍含它们，此处排除比对，且
        // 迁移后库里不应出现（16→17 已改为空迁移）。
        assertSchemaMatches(
            db, 18,
            freshTables = setOf(
                "AIProvider", "AIProviderModel", "AISystemPrompt", "AIUserPrompt",
                "AIInstruction", "AIConversation", "AIMessage",
            ),
            excludeRetiredScriptTables = true,
        )
        assertTrue("脚本四表不应再建", db.tableNames().none { it.startsWith("Script") })
        // 旧数据不丢（13→14 只清 clipboard_records，常用语／偏好不动）。
        assertEquals(
            "旧词",
            db.scalar("SELECT `text` FROM `phrase_records` WHERE `label`='旧标签';"),
        )
        assertEquals(
            "3",
            db.scalar("SELECT `click_count` FROM `candidate_prefers` WHERE `text`='测试';"),
        )
        assertTrue("13→14 应清掉 clipboard_records", "clipboard_records" !in db.tableNames())
        // 播种落盘：AI 服务商。
        assertTrue(db.count("AIProvider") >= 1)
        assertEquals(
            "DeepSeek",
            db.scalar(
                "SELECT `name` FROM `AIProvider` " +
                    "WHERE `id`='00000000-0000-4000-8000-000000000101';",
            ),
        )
        assertTrue(db.isDatabaseIntegrityOk)
    }

    @Test
    fun migrate_14to15_addsPhraseColumnsAndIndex_preservesRows() {
        val db = buildDbAtVersion(14)
        db.execSQL(
            "INSERT INTO `phrase_records` (`text`,`label`,`createdAt`,`sortOrder`,`pinned`) " +
                "VALUES ('旧词','旧标签',1700000000000,1,0)",
        )
        migrate(db, 14, 15)
        val columns = db.columns("phrase_records")
        assertTrue(columns.containsAll(listOf("shortcut", "category", "tags", "variables", "lastUsedAt")))
        assertTrue("index_phrase_records_shortcut" in db.indexNames())
        assertEquals(
            "旧词",
            db.scalar("SELECT `text` FROM `phrase_records` WHERE `label`='旧标签';"),
        )
        // 新列默认值（NOT NULL DEFAULT ''／0；空串须用 rows() 读，
        // scalar() 分不清空串与无行）。
        val row = db.rows(
            "SELECT `shortcut`, `lastUsedAt` FROM `phrase_records` WHERE `label`='旧标签';",
        ).single()
        assertEquals("", row["shortcut"])
        assertEquals("0", row["lastUsedAt"])
    }

    @Test
    fun migrate_15to16_createsAiTablesAndSeeds_deferredFkInSql() {
        val db = buildDbAtVersion(15)
        migrate(db, 15, 16)
        val aiTables = setOf(
            "AIProvider", "AIProviderModel", "AISystemPrompt", "AIUserPrompt",
            "AIInstruction", "AIConversation", "AIMessage",
        )
        assertTrue(db.tableNames().containsAll(aiTables))
        assertTrue(db.count("AIProvider") >= 1)
        assertEquals(5L, db.count("AISystemPrompt"))
        assertEquals(3L, db.count("AIUserPrompt"))
        assertEquals(4L, db.count("AIInstruction"))
        // 外键 deferral 与 schema JSON 建表 SQL 一致（L-33 口径：
        // AIProvider／AIProviderModel 为 deferred；AIMessage 在 v18
        // 才改 deferred，此处 16.json 仍为即时）。
        assertSchemaMatches(
            db, 16,
            freshTables = setOf(
                "AIProvider", "AIProviderModel", "AISystemPrompt", "AIUserPrompt",
                "AIInstruction", "AIConversation", "AIMessage",
            ),
        )
        val providerSql = db.tableSql("AIProvider") ?: ""
        assertTrue(providerSql.contains("DEFERRABLE INITIALLY DEFERRED"))
        val modelSql = db.tableSql("AIProviderModel") ?: ""
        assertTrue(modelSql.contains("DEFERRABLE INITIALLY DEFERRED"))
    }

    @Test
    fun migrate_16to17_isEmpty_noScriptTables() {
        // 脚本功能删除：16→17 已改为空迁移（只保链连续），不再建
        // 脚本四表、不播种；16.json 建出的旧库直升后也不应出现脚本表。
        val db = buildDbAtVersion(16)
        migrate(db, 16, 17)
        assertTrue(db.tableNames().none { it.startsWith("Script") })
        assertTrue(db.isDatabaseIntegrityOk)
    }

    @Test
    fun migrate_18to19_scriptTablesBecomeOrphan() {
        // 18→19 为空迁移（只升版、更新 Room 身份哈希）：既有库里
        // 的脚本四表物理上仍在（孤表，无代码再读写），迁移不断言
        // 删除；Room 校验只看实体表，打开不受影响。
        val db = buildDbAtVersion(18)
        migrate(db, 18, 19)
        assertEquals(4, db.tableNames().count { it.startsWith("Script") })
        assertTrue(db.isDatabaseIntegrityOk)
    }

    @Test
    fun migrate_19to20_aiTokenPlaintextPreserved() {
        // 2.4：19→20 为空迁移（apiToken 列名／类型不变，只改落盘
        // 形态）：v19 库里的旧明文 token 行必须原样保留——迁移本身
        // 不丢配置；加密回写由 AiRepository 在首次访问时做（主机无
        // Keystore，此处不断言密文，只断旧值一字不动＋结构与 20.json
        // 一致）。真机端到端（读出明文＋落盘变密文）见仪器测试
        // DatabaseMigrationTest 的 19→20 一例。
        val db = buildDbAtVersion(19)
        db.execSQL(
            "INSERT INTO `AIProvider` (`id`,`name`,`type`,`sort`,`isDefault`," +
                "`isEnabled`,`isBuiltIn`,`builtInCanBeDelete`,`baseURL`,`apiPath`," +
                "`apiToken`,`requestTimeout`,`maxOutputTokens`,`contextWindow`) " +
                "VALUES ('prov-1','DeepSeek','openai',0,1,1,1,1," +
                "'https://api.deepseek.com','v1','sk-legacy-token',60.0,0,64000)",
        )
        migrate(db, 19, 20)
        assertEquals(
            "sk-legacy-token",
            db.scalar("SELECT `apiToken` FROM `AIProvider` WHERE `id`='prov-1';"),
        )
        assertSchemaMatches(db, 20)
        assertTrue(db.isDatabaseIntegrityOk)
    }

    @Test
    fun migrate_17to18_rebuildsAiMessageDeferred_preservesRows() {
        val db = buildDbAtVersion(17)
        db.execSQL(
            "INSERT INTO `AIConversation` (`id`,`title`,`createdAt`,`updatedAt`,`isTitleUserEdited`) " +
                "VALUES ('conv-1','会话',1700000000000,1700000000000,0)",
        )
        db.execSQL(
            // tokenEstimate／isSuperseded 是 NOT NULL 且无默认值
            // （Kotlin 构造默认值不进建表 SQL），插入须显式给 0。
            "INSERT INTO `AIMessage` (`id`,`conversationId`,`role`,`status`,`sequence`,`createdAt`,`content`,`tokenEstimate`,`isSuperseded`) " +
                "VALUES ('msg-1','conv-1','user','complete',1,1700000000000,'你好',0,0)",
        )
        migrate(db, 17, 18)
        // 旧数据不丢（整表重建只换外键定义，行原样复制）。
        assertEquals(
            "你好",
            db.scalar("SELECT `content` FROM `AIMessage` WHERE `id`='msg-1';"),
        )
        // 外键改 deferred：建表 SQL 与 18.json 逐字一致（含后缀）。
        val messageSql = db.tableSql("AIMessage") ?: ""
        assertTrue(messageSql.contains("DEFERRABLE INITIALLY DEFERRED"))
        assertSchemaMatches(db, 18, freshTables = setOf("AIMessage"))
        assertTrue(db.isDatabaseIntegrityOk)
    }
}
