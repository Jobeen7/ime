// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.discoverSchemes
import com.jobeen.ime.data.manager.effectiveEnabledIds
import com.jobeen.ime.data.manager.parseDefaultYamlSchemaList
import com.jobeen.ime.data.manager.parseSchemaFile
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 磁盘方案发现与启用表回落链的纯逻辑测试。 */
class SchemeDiskTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("scheme-disk-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeSchemaFile(dir: File, fileName: String, body: String): File {
        val file = File(dir, fileName)
        file.writeText(body)
        return file
    }

    @Test
    fun parseSchemaFile_readsFiveKeys() {
        runWithTempDir { root ->
            val file = writeSchemaFile(
                root, "wanxiang_t9.schema.yaml",
                "schema:\n" +
                    "  schema_id: wanxiang_t9\n" +
                    "  name: 万象九键\n" +
                    "  layout: T9\n" +
                    "  punctuation: FullWidth\n" +
                    "  kind: PinYin\n"
            )
            val schema = parseSchemaFile(file)
            assertEquals("wanxiang_t9", schema?.id)
            assertEquals("万象九键", schema?.name)
            assertEquals("T9", schema?.layout)
            assertEquals("FullWidth", schema?.punctuation)
            assertEquals("PinYin", schema?.kind)
        }
    }

    @Test
    fun parseSchemaFile_missingKeysAreEmptyAndIdFallsBackToFileName() {
        runWithTempDir { root ->
            val file = writeSchemaFile(
                root, "foo.schema.yaml",
                "schema:\n" +
                    "  name: Foo\n"
            )
            val schema = parseSchemaFile(file)
            assertEquals("foo", schema?.id)
            assertEquals("Foo", schema?.name)
            assertEquals("", schema?.layout)
            assertEquals("", schema?.punctuation)
            assertEquals("", schema?.kind)
        }
    }

    @Test
    fun parseSchemaFile_t9IdWithoutLayoutFallsBackToT9() {
        runWithTempDir { root ->
            val file = writeSchemaFile(
                root, "wanxiang_t9i.schema.yaml",
                "schema:\n" +
                    "  schema_id: wanxiang_t9i\n" +
                    "  name: 万象九键\n"
            )
            val schema = parseSchemaFile(file)
            assertEquals("wanxiang_t9i", schema?.id)
            assertEquals("T9", schema?.layout)
        }
    }

    @Test
    fun parseSchemaFile_explicitLayoutWinsOverT9Fallback() {
        runWithTempDir { root ->
            val file = writeSchemaFile(
                root, "custom_t9.schema.yaml",
                "schema:\n" +
                    "  schema_id: custom_t9\n" +
                    "  name: Custom\n" +
                    "  layout: Qwerty\n"
            )
            val schema = parseSchemaFile(file)
            assertEquals("Qwerty", schema?.layout)
        }
    }

    @Test
    fun parseSchemaFile_t9FallbackUsesWholeSegments() {
        runWithTempDir { root ->
            // t9/t9i 自成一段 → 九宫格；t9 非整段 → 全键盘。
            // 旧 contains 口径会把后四组误判成九宫格；t9i 须保持九宫格
            // （它是内置九键方案，与 t9 同口径）。
            val cases = mapOf(
                "t9" to "T9",
                "wanxiang_t9" to "T9",
                "wanxiang_t9i" to "T9",
                "wanxiang_abbrev_t9" to "T9",
                "wanxiang_phrase_t9" to "T9",
                "t9_pinyin" to "T9",
                "pinyin_t9like" to "",
                "my_t9x" to "",
                "at9" to "",
                "wanxiang_lite" to "",
                "wanxiang_english" to "",
            )
            for ((id, expected) in cases) {
                val file = writeSchemaFile(
                    root, "$id.schema.yaml",
                    "schema:\n" +
                        "  schema_id: $id\n" +
                        "  name: $id\n",
                )
                assertEquals(id, expected, parseSchemaFile(file)?.layout)
            }
        }
    }

    @Test
    fun parseSchemaFile_noSchemaSectionReturnsNull() {
        runWithTempDir { root ->
            val file = writeSchemaFile(
                root, "foo.schema.yaml",
                "patch:\n" +
                    "  schema_list:\n" +
                    "    - foo\n"
            )
            assertNull(parseSchemaFile(file))
        }
    }

    @Test
    fun discoverSchemes_userOverridesSharedSortedAndNoRecursion() {
        runWithTempDir { root ->
            val shared = File(root, "shared").apply { mkdirs() }
            val user = File(root, "user").apply { mkdirs() }
            writeSchemaFile(
                shared, "a.schema.yaml",
                "schema:\n  schema_id: a\n  name: SharedA\n"
            )
            writeSchemaFile(
                shared, "b.schema.yaml",
                "schema:\n  schema_id: b\n  name: SharedB\n"
            )
            writeSchemaFile(
                user, "b.schema.yaml",
                "schema:\n  schema_id: b\n  name: UserB\n"
            )
            val sub = File(user, "sub").apply { mkdirs() }
            writeSchemaFile(
                sub, "c.schema.yaml",
                "schema:\n  schema_id: c\n  name: SubC\n"
            )
            val found = discoverSchemes(user, shared)
            assertEquals(listOf("a", "b"), found.map { it.id })
            assertEquals("UserB", found.first { it.id == "b" }.name)
        }
    }

    @Test
    fun parseDefaultYamlSchemaList_takesSchemaItemsStopsAtTopLevel() {
        val text = "schema_list:\n" +
            "  - schema: wanxiang_t9\n" +
            "  - schema: wanxiang_lite\n" +
            "other_top:\n" +
            "  - schema: no\n"
        assertEquals(
            listOf("wanxiang_t9", "wanxiang_lite"),
            parseDefaultYamlSchemaList(text)
        )
    }

    @Test
    fun parseDefaultYamlSchemaList_plainItemsNotTaken() {
        val text = "schema_list:\n" +
            "  - wanxiang_t9\n"
        assertEquals(emptyList<String>(), parseDefaultYamlSchemaList(text))
    }

    @Test
    fun effectiveEnabledIds_fallsBackThroughThreeTiers() {
        runWithTempDir { root ->
            val dir = File(root, "dir").apply { mkdirs() }
            val shared = File(root, "shared").apply { mkdirs() }
            val diskIds = listOf("a", "b", "c")
            // 三档皆空 → 全体。
            assertEquals(diskIds, effectiveEnabledIds(dir, shared, diskIds))
            // 第三档 shared default.yaml。
            File(shared, "default.yaml").writeText(
                "schema_list:\n  - schema: c\n"
            )
            assertEquals(listOf("c"), effectiveEnabledIds(dir, shared, diskIds))
            // 第二档目录 default.yaml 优先于 shared。
            File(dir, "default.yaml").writeText(
                "schema_list:\n  - schema: b\n"
            )
            assertEquals(listOf("b"), effectiveEnabledIds(dir, shared, diskIds))
            // 第一档 custom 优先于目录 default.yaml，且交集过滤掉不在磁盘的 id。
            File(dir, "default.custom.yaml").writeText(
                "# Written by Jime scheme settings.\n" +
                    "patch:\n" +
                    "  schema_list:\n" +
                    "    - missing\n" +
                    "    - a\n"
            )
            assertEquals(listOf("a"), effectiveEnabledIds(dir, shared, diskIds))
        }
    }

    @Test
    fun effectiveEnabledIds_missingDirsDoNotThrow() {
        runWithTempDir { root ->
            val diskIds = listOf("a", "b")
            assertEquals(
                diskIds,
                effectiveEnabledIds(File(root, "no-dir"), File(root, "no-shared"), diskIds)
            )
        }
    }
}
