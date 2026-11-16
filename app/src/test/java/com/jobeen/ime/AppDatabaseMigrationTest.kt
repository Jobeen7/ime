// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import androidx.room.migration.Migration
import com.jobeen.ime.data.database.AppDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppDatabase 迁移链单测（WO52 6.1 v14→15；9.1 v15→16；L-33 v17→18；
 * 脚本功能删除：v16→17 与 v18→19 为空迁移，库版本现为 19）。
 *
 * 本文件只验静态面：Room 编译期生成的 schema JSON（app/schemas
 * 下，用 kotlinx.serialization 解析——org.json 在主机单测里是桩）、
 * 迁移链注册（反射读 AppDatabase 的静态 Migration 字段起止版本；
 * companion 的 private val 编译后落在外层类的静态字段上）。
 * 真执行（从 schema JSON 建旧版库、逐段跑 Migration.migrate()、
 * 断言新结构＋旧数据不丢＋种子落盘）见
 * [AppDatabaseMigrationExecTest]（C-L-27，sqlite3 CLI 真引擎）；
 * 仓库外脚本 ~/workspace/system/wo52-g6-migration-check.py 只作补充。
 */
class AppDatabaseMigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun schemaDb(version: Int): JsonObject {
        // 单测工作目录即模块目录（app/）；向上逐级找 schemas 目录兜底。
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

    private fun migrationPairs(): Set<Pair<Int, Int>> {
        return AppDatabase::class.java.declaredFields
            .filter { Migration::class.java.isAssignableFrom(it.type) }
            .map {
                it.isAccessible = true
                val m = it.get(null) as Migration
                m.startVersion to m.endVersion
            }.toSet()
    }

    @Test
    fun databaseVersion_is18() {
        assertEquals(18, schemaDb(18)["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun databaseVersion_is19() {
        // @Database 注解是 CLASS 保留（只供编译期用），反射读不到
        // ——原实现恒得 null；现行库版本改读 Room 生成的 schema，
        // 与本文件其余 schema 断言同机制。
        assertEquals(19, schemaDb(19)["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun migrationChain_covers14to19() {
        val pairs = migrationPairs()
        assertTrue("迁移链应含 14→15（WO52 6.1），实际：$pairs", pairs.contains(14 to 15))
        assertTrue("迁移链应含 15→16（WO52 9.1），实际：$pairs", pairs.contains(15 to 16))
        assertTrue("迁移链应含 16→17（脚本删除后为空迁移），实际：$pairs", pairs.contains(16 to 17))
        assertTrue("迁移链应含 17→18（L-33），实际：$pairs", pairs.contains(17 to 18))
        assertTrue("迁移链应含 18→19（脚本功能删除，空迁移），实际：$pairs", pairs.contains(18 to 19))
        // 链连续：1→19 每一步都有迁移（无断链、无跳号）
        for (v in 1 until 19) {
            assertTrue("迁移链缺 $v→${v + 1}", pairs.contains(v to v + 1))
        }
    }

    @Test
    fun schema18_aiMessageFkDeferred() {
        // L-33：AIMessage 外键改 deferred（本版 Room 的 schema JSON
        // 不序列化 deferred 字段，改断建表 SQL 含 DEFERRABLE
        // INITIALLY DEFERRED，与 schema16 的 provider／model 同口径）。
        // 其余面（字段、索引、外键指向与级联）与 v17 一字不动。
        val tables = entities(schemaDb(18)).associateBy {
            it["tableName"]!!.jsonPrimitive.content
        }
        val messageSql = tables["AIMessage"]!!["createSql"]!!.jsonPrimitive.content
        assertTrue(messageSql.contains("DEFERRABLE INITIALLY DEFERRED"))
        val fields = tables["AIMessage"]!!["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertEquals(entities(schemaDb(17)).first {
            it["tableName"]!!.jsonPrimitive.content == "AIMessage"
        }["fields"]!!.jsonArray.map {
            it.jsonObject["columnName"]!!.jsonPrimitive.content
        }, fields)
        val messageFk = tables["AIMessage"]!!["foreignKeys"]!!.jsonArray.single().jsonObject
        assertEquals("AIConversation", messageFk["table"]!!.jsonPrimitive.content)
        assertEquals("CASCADE", messageFk["onDelete"]!!.jsonPrimitive.content)
    }

    @Test
    fun schema15_phraseHasNewColumnsAndShortcutIndex() {
        val phrase = entities(schemaDb(15)).first { it["tableName"]!!.jsonPrimitive.content == "phrase_records" }
        val fields = phrase["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()
        assertTrue(fields.contains("shortcut"))
        assertTrue(fields.contains("category"))
        assertTrue(fields.contains("tags"))
        assertTrue(fields.contains("variables"))
        assertTrue(fields.contains("lastUsedAt"))
        // 旧列一个不少（迁移不丢数据的前提：旧字段仍在表里）
        assertTrue(fields.containsAll(listOf("id", "text", "label", "createdAt", "sortOrder", "pinned")))
        val indices = phrase["indices"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(indices.contains("index_phrase_records_shortcut"))
    }

    @Test
    fun schema16_aiTablesPresentWithExactFields() {
        val tables = entities(schemaDb(16)).associateBy {
            it["tableName"]!!.jsonPrimitive.content
        }
        val expected = mapOf(
            "AIProvider" to listOf(
                "id", "name", "type", "sort", "isDefault", "isEnabled", "isBuiltIn",
                "builtInCanBeDelete", "baseURL", "apiPath", "apiToken", "requestTimeout",
                "maxOutputTokens", "contextWindow", "temperature", "presencePenalty",
                "frequencyPenalty", "customParametersJSON", "defaultModelId",
            ),
            "AIProviderModel" to listOf("id", "providerId", "displayName", "modelID", "sort", "isEnabled"),
            "AISystemPrompt" to listOf(
                "id", "name", "iconName", "purpose", "body", "generationOverridesJSON",
                "sort", "isDefault", "isEnabled", "isBuiltIn", "builtInCanBeDelete",
            ),
            "AIUserPrompt" to listOf(
                "id", "name", "iconName", "purpose", "templateBody",
                "sort", "isDefault", "isEnabled", "isBuiltIn", "builtInCanBeDelete",
            ),
            "AIInstruction" to listOf(
                "id", "name", "detail", "systemPromptID", "userPromptID", "providerID", "sort",
            ),
            "AIConversation" to listOf(
                "id", "title", "createdAt", "updatedAt", "isTitleUserEdited",
                "systemPromptID", "providerID", "modelID", "snapshotJSON",
            ),
            "AIMessage" to listOf(
                "id", "conversationId", "role", "status", "sequence", "createdAt",
                "originalInput", "renderedRequest", "userPromptName", "content",
                "tokenEstimate", "usageJSON", "errorCategory", "requestID",
                "regenerationOfID", "isSuperseded", "configSnapshotJSON",
            ),
        )
        for ((table, fields) in expected) {
            val entity = tables[table]
            assertTrue("缺 AI 表 $table", entity != null)
            val actual = entity!!["fields"]!!.jsonArray
                .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
            assertEquals("$table 字段须与单子一字不差", fields, actual)
        }
        // AIMessage 原表无 updatedAt（单子里的 updatedAt? 即此意），不要补
        val messageFields = tables["AIMessage"]!!["fields"]!!.jsonArray
            .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        assertTrue("AIMessage 不应有 updatedAt 列", !messageFields.contains("updatedAt"))
        // AIUserPrompt.templateBody 默认 '{{input}}'（原表 DEFAULT）
        val userPromptFields = tables["AIUserPrompt"]!!["fields"]!!.jsonArray
            .map { it.jsonObject }
        val templateBody = userPromptFields.first {
            it["columnName"]!!.jsonPrimitive.content == "templateBody"
        }
        assertEquals("'{{input}}'", templateBody["defaultValue"]!!.jsonPrimitive.content)
    }

    @Test
    fun schema16_aiIndicesAndForeignKeys() {
        val tables = entities(schemaDb(16)).associateBy {
            it["tableName"]!!.jsonPrimitive.content
        }
        fun indexNames(table: String): Set<String> =
            tables[table]!!["indices"]!!.jsonArray
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        fun uniqueIndexNames(table: String): Set<String> =
            tables[table]!!["indices"]!!.jsonArray
                .filter { it.jsonObject["unique"]!!.jsonPrimitive.boolean }
                .map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue(uniqueIndexNames("AISystemPrompt").contains("index_AISystemPrompt_name"))
        assertTrue(uniqueIndexNames("AIUserPrompt").contains("index_AIUserPrompt_name"))
        assertTrue(uniqueIndexNames("AIInstruction").contains("index_AIInstruction_name"))
        assertTrue(indexNames("AIProvider").contains("index_AIProvider_defaultModelId"))
        assertTrue(indexNames("AIProviderModel").contains("index_AIProviderModel_providerId"))
        assertTrue(indexNames("AIConversation").contains("index_AIConversation_updatedAt"))
        assertTrue(indexNames("AIMessage").containsAll(listOf(
            "index_AIMessage_conversationId", "index_AIMessage_sequence", "index_AIMessage_status",
        )))
        // 外键：AIMessage→AIConversation 级联删；AIProviderModel→AIProvider 级联删；
        // AIProvider.defaultModelId→AIProviderModel 删除置空（deferred）
        fun foreignKeys(table: String): List<JsonObject> =
            tables[table]!!["foreignKeys"]!!.jsonArray.map { it.jsonObject }
        val messageFk = foreignKeys("AIMessage").single()
        assertEquals("AIConversation", messageFk["table"]!!.jsonPrimitive.content)
        assertEquals("CASCADE", messageFk["onDelete"]!!.jsonPrimitive.content)
        val modelFk = foreignKeys("AIProviderModel").single()
        assertEquals("AIProvider", modelFk["table"]!!.jsonPrimitive.content)
        assertEquals("CASCADE", modelFk["onDelete"]!!.jsonPrimitive.content)
        val providerFk = foreignKeys("AIProvider").single()
        assertEquals("AIProviderModel", providerFk["table"]!!.jsonPrimitive.content)
        assertEquals("SET NULL", providerFk["onDelete"]!!.jsonPrimitive.content)
        // 外键须为 deferred（本版 Room 的 schema JSON 不序列化 deferred
        // 字段，改断建表 SQL 含 DEFERRABLE INITIALLY DEFERRED）
        val providerSql = tables["AIProvider"]!!["createSql"]!!.jsonPrimitive.content
        assertTrue(providerSql.contains("DEFERRABLE INITIALLY DEFERRED"))
        val modelSql = tables["AIProviderModel"]!!["createSql"]!!.jsonPrimitive.content
        assertTrue(modelSql.contains("DEFERRABLE INITIALLY DEFERRED"))
    }
}
