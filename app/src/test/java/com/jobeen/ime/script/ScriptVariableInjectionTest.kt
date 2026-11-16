// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.script

import com.jobeen.ime.script.ScriptVariableInjection.SkipReason
import com.jobeen.ime.script.ScriptVariableInjection.VariableEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ScriptVariable → `$key` 注入准备单测（纯函数，不碰 Room 与引擎）。 */
class ScriptVariableInjectionTest {

    private fun entry(key: String, value: String = "v", isSecure: Boolean = false) =
        VariableEntry(key, value, isSecure)

    @Test
    fun prepare_validEntries_passThrough() {
        val prepared = ScriptVariableInjection.prepare(
            listOf(entry("apiUrl", "https://api.example.com"), entry("name", "Jime")),
        )
        assertEquals(
            mapOf("apiUrl" to "https://api.example.com", "name" to "Jime"),
            prepared.values,
        )
        assertTrue(prepared.skipped.isEmpty())
    }

    @Test
    fun prepare_secureVariables_skippedNeverInjected() {
        val prepared = ScriptVariableInjection.prepare(
            listOf(entry("token", "s3cr3t", isSecure = true), entry("ok", "yes")),
        )
        assertEquals(mapOf("ok" to "yes"), prepared.values)
        assertEquals(
            listOf(ScriptVariableInjection.SkippedVariable("token", SkipReason.SECURE)),
            prepared.skipped,
        )
        // 跳过日志不含敏感值。
        assertFalse(ScriptVariableInjection.skipLogLines(prepared).joinToString().contains("s3cr3t"))
    }

    @Test
    fun prepare_invalidKeys_skipped() {
        val prepared = ScriptVariableInjection.prepare(
            listOf(
                entry(""),
                entry("1abc"),
                entry("bad key"),
                entry("bad-key"),
                entry("good_key"),
            ),
        )
        assertEquals(mapOf("good_key" to "v"), prepared.values)
        assertEquals(
            listOf(
                ScriptVariableInjection.SkippedVariable("", SkipReason.INVALID_KEY),
                ScriptVariableInjection.SkippedVariable("1abc", SkipReason.INVALID_KEY),
                ScriptVariableInjection.SkippedVariable("bad key", SkipReason.INVALID_KEY),
                ScriptVariableInjection.SkippedVariable("bad-key", SkipReason.INVALID_KEY),
            ),
            prepared.skipped,
        )
    }

    @Test
    fun prepare_reservedKeys_skipped_inputsAndHostApis() {
        val prepared = ScriptVariableInjection.prepare(
            listOf(
                entry("searchText", "x"),
                entry("pasteboardContent", "x"),
                entry("log", "x"),
                entry("http", "x"),
                entry("openURL", "x"),
                entry("output", "x"),
                entry("free", "yes"),
            ),
        )
        assertEquals(mapOf("free" to "yes"), prepared.values)
        assertTrue(prepared.skipped.all { it.reason == SkipReason.RESERVED_KEY })
        assertEquals(6, prepared.skipped.size)
    }

    @Test
    fun prepare_duplicateKey_firstWins() {
        val prepared = ScriptVariableInjection.prepare(
            listOf(entry("k", "first"), entry("k", "second")),
        )
        assertEquals(mapOf("k" to "first"), prepared.values)
        assertEquals(
            listOf(ScriptVariableInjection.SkippedVariable("k", SkipReason.DUPLICATE)),
            prepared.skipped,
        )
    }

    @Test
    fun prepare_valueTooLong_skipped() {
        val prepared = ScriptVariableInjection.prepare(
            listOf(entry("big", "x".repeat(ScriptVariableInjection.MAX_VALUE_CHARS + 1))),
        )
        assertTrue(prepared.values.isEmpty())
        assertEquals(SkipReason.VALUE_TOO_LONG, prepared.skipped.single().reason)
    }

    @Test
    fun prepare_tooManyVariables_tailSkipped() {
        val entries = (1..(ScriptVariableInjection.MAX_VARIABLES + 2)).map { entry("k$it") }
        val prepared = ScriptVariableInjection.prepare(entries)
        assertEquals(ScriptVariableInjection.MAX_VARIABLES, prepared.values.size)
        assertEquals(2, prepared.skipped.size)
        assertTrue(prepared.skipped.all { it.reason == SkipReason.TOO_MANY })
    }

    @Test
    fun prepare_unicodeKeyAllowed() {
        assertTrue(ScriptVariableInjection.isValidKey("名字"))
        assertFalse(ScriptVariableInjection.isValidKey("a b"))
    }

    @Test
    fun skipLogLine_formatAndReasonText() {
        assertEquals(
            "跳过变量 \$token：敏感变量不注入",
            ScriptVariableInjection.skipLogLine("token", SkipReason.SECURE),
        )
        assertEquals(
            "跳过变量 \$bad key：变量名非法",
            ScriptVariableInjection.skipLogLine("bad key", SkipReason.INVALID_KEY),
        )
        assertEquals(
            listOf("跳过变量 \$a：变量名与内置全局冲突"),
            ScriptVariableInjection.skipLogLines(
                ScriptVariableInjection.PreparedVariables(
                    emptyMap(),
                    listOf(ScriptVariableInjection.SkippedVariable("a", SkipReason.RESERVED_KEY)),
                ),
            ),
        )
    }
}
