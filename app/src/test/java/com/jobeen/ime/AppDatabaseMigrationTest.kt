// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import androidx.room.migration.Migration
import com.jobeen.ime.data.database.AppDatabase
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AppDatabase 迁移链单测（WO52 6.1 v14→15；9.1 v15→16 的断言随 9.1 提交补）。
 *
 * 主机无 Room 运行环境（禁 Robolectric），故分两层验证：
 * 1. 本单测：Room 编译期生成的 schema JSON（app/schemas 下，用
 *    kotlinx.serialization 解析——org.json 在主机单测里是桩）、
 *    迁移链注册（反射读 AppDatabase 的静态 Migration 字段起止版本；
 *    companion 的 private val 编译后落在外层类的静态字段上）；
 * 2. 真实 SQLite 端到端：见 ~/workspace/system/wo52-g3-migration-check.py，
 *    以 sqlite3 从 14.json 建 v14 库、跑迁移 SQL、验数据不丢＋
 *    新结构与 15.json 一致（结果记入回报与日志；9.1 再续 15→16 一段）。
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
    fun databaseVersion_is15() {
        assertEquals(15, schemaDb(15)["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun migrationChain_covers14to15() {
        val pairs = migrationPairs()
        assertTrue("迁移链应含 14→15（WO52 6.1），实际：$pairs", pairs.contains(14 to 15))
        // 链连续：1→15 每一步都有迁移（无断链、无跳号）
        for (v in 1 until 15) {
            assertTrue("迁移链缺 $v→${v + 1}", pairs.contains(v to v + 1))
        }
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
}
