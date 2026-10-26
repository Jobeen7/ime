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
                    "    - a\n" +
                    "    - b\n",
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
            assertTrue(text.contains("    - a\n    - b\n"))
            assertFalse(text.contains("    - old\n"))
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
                    "    - a\n" +
                    "    - b\n",
                custom.readText()
            )
        }
    }
}
