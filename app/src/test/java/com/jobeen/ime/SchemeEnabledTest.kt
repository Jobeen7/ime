// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.manager.SchemeEnabled
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录启用表写盘（tmp＋rename）的纯逻辑测试；不测 SharedPreferences。 */
class SchemeEnabledTest {

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("scheme-enabled-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writeEnabledCustomFile_createsNewFileWithHeader() {
        runWithTempDir { root ->
            val custom = File(File(root, "dir"), "default.custom.yaml")
            SchemeEnabled.writeEnabledCustomFile(custom, listOf("a", "b"))
            assertTrue(custom.isFile)
            assertEquals(
                "# Written by Jime scheme settings.\n" +
                    "patch:\n" +
                    "  schema_list:\n" +
                    "    - schema: a\n" +
                    "    - schema: b\n",
                custom.readText()
            )
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun writeEnabledCustomFile_preservesOtherPatchContent() {
        runWithTempDir { root ->
            val custom = File(root, "default.custom.yaml")
            custom.writeText(
                "patch:\n" +
                    "  something: x\n" +
                    "  schema_list:\n" +
                    "    - old\n"
            )
            SchemeEnabled.writeEnabledCustomFile(custom, listOf("a", "b"))
            val text = custom.readText()
            assertTrue(text.contains("  something: x\n"))
            assertTrue(text.contains("    - schema: a\n    - schema: b\n"))
            assertFalse(text.contains("    - old\n"))
            assertFalse(text.contains("    - schema: old\n"))
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun writeEnabledCustomFile_filtersInvalidIds() {
        runWithTempDir { root ->
            val custom = File(root, "default.custom.yaml")
            SchemeEnabled.writeEnabledCustomFile(
                custom, listOf("a", "a/b", "a b", "..", "b")
            )
            assertEquals(
                "# Written by Jime scheme settings.\n" +
                    "patch:\n" +
                    "  schema_list:\n" +
                    "    - schema: a\n" +
                    "    - schema: b\n",
                custom.readText()
            )
        }
    }

    @Test
    fun normalizeCustomFileToMapFormat_rewritesLegacyBareItems() {
        runWithTempDir { root ->
            val custom = File(root, "default.custom.yaml")
            custom.writeText(
                "patch:\n" +
                    "  something: x\n" +
                    "  schema_list:\n" +
                    "    - old_a\n" +
                    "    - old_b\n"
            )
            assertTrue(SchemeEnabled.normalizeCustomFileToMapFormat(custom))
            val text = custom.readText()
            assertTrue(text.contains("  something: x\n"))
            assertTrue(text.contains("    - schema: old_a\n    - schema: old_b\n"))
            assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") })
        }
    }

    @Test
    fun normalizeCustomFileToMapFormat_leavesMapFormatUntouched() {
        runWithTempDir { root ->
            val custom = File(root, "default.custom.yaml")
            val original =
                "patch:\n" +
                    "  schema_list:\n" +
                    "    - schema: a\n" +
                    "    - schema: b\n"
            custom.writeText(original)
            assertFalse(SchemeEnabled.normalizeCustomFileToMapFormat(custom))
            assertEquals(original, custom.readText())
        }
    }

    @Test
    fun writeEnabledCustomFile_concurrentWritesDoNotCollide() {
        runWithTempDir { root ->
            val custom = File(root, "default.custom.yaml")
            val errors = mutableListOf<Throwable>()
            val threads = (0 until 8).map { i ->
                Thread {
                    try {
                        SchemeEnabled.writeEnabledCustomFile(custom, listOf("s$i"))
                    } catch (t: Throwable) {
                        synchronized(errors) { errors.add(t) }
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            // 固定 .tmp 名时并发写必互踩（后写者删 tmp 会删先写者的 tmp，
            // rename 撞空）；带 nanoTime 后缀后各写各的 tmp，全成功。
            assertTrue(errors.isEmpty())
            // 最后写者胜出：文件是某一轮的完整启用表，不是撕裂的半截文本。
            val text = custom.readText()
            assertTrue(text.contains("schema_list:"))
            assertTrue(text.contains("    - schema: s"))
            assertTrue(root.walkTopDown().none { ".tmp" in it.name })
        }
    }
}
