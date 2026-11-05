// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.key

import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.diag.DiagLogCore
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逐键面色诊断（2026-11-05 真机灰诊断行）：取色优先级与回落
 * 原因纯判定全分支＋诊断行确实落盘（DEBUG 门控开时才落盘）。
 */
class KeyFaceDiagTest {

    private val cores = mutableListOf<DiagLogCore>()

    @After
    fun tearDown() {
        cores.forEach { runCatching { it.close() } }
        cores.clear()
        DiagLog.setCoreForTest(null)
    }

    private fun readAllLines(dir: File): List<String> =
        dir.listFiles { f -> DiagLogCore.isLogFileName(f.name) }
            ?.sortedBy { it.name }
            ?.flatMap { it.readLines() }
            ?: emptyList()

    @Test
    fun resolve_runtimeOverrideWins() {
        val info = KeyFaceDiag.resolve(
            keyLabel = "Q",
            skinId = "skin-a", generation = 3, keyStylesEmpty = false,
            overrideColor = 0xFFFF0000.toInt(),
            skinFaceColor = 0xFF00FF00.toInt(),
            variantDefaultColor = 0xFF0000FF.toInt(),
        )
        assertEquals(KeyFaceDiag.KeyFaceSource.RUNTIME_OVERRIDE, info.source)
        assertEquals(0xFFFF0000.toInt(), info.faceColor)
        assertEquals("#FFFF0000", info.faceHex)
        assertNull(info.fallback)
    }

    @Test
    fun resolve_namedStyleHit() {
        val info = KeyFaceDiag.resolve(
            keyLabel = "Q",
            skinId = "skin-a", generation = 3, keyStylesEmpty = false,
            overrideColor = null,
            skinFaceColor = 0xFF112233.toInt(),
            variantDefaultColor = 0xFF0000FF.toInt(),
        )
        assertEquals(KeyFaceDiag.KeyFaceSource.NAMED_STYLE, info.source)
        assertEquals("#FF112233", info.faceHex)
        assertNull(info.fallback)
    }

    @Test
    fun resolve_noSnapshotFallback() {
        val info = KeyFaceDiag.resolve(
            keyLabel = "Q",
            skinId = null, generation = null, keyStylesEmpty = true,
            overrideColor = null,
            skinFaceColor = null,
            variantDefaultColor = 0xFF888888.toInt(),
        )
        assertEquals(KeyFaceDiag.KeyFaceSource.VARIANT_DEFAULT, info.source)
        assertEquals(KeyFaceDiag.KeyFaceFallback.NO_SNAPSHOT, info.fallback)
        assertEquals("#FF888888", info.faceHex)
    }

    @Test
    fun resolve_noKeyStylesFallback() {
        val info = KeyFaceDiag.resolve(
            keyLabel = "Q",
            skinId = "skin-a", generation = 3, keyStylesEmpty = true,
            overrideColor = null,
            skinFaceColor = null,
            variantDefaultColor = 0xFF888888.toInt(),
        )
        assertEquals(KeyFaceDiag.KeyFaceFallback.NO_KEY_STYLES, info.fallback)
    }

    @Test
    fun resolve_noStyleForKeyFallback() {
        // 快照有样式表，但本键未解析出面色：回落默认、原因记 no-style-for-key。
        val info = KeyFaceDiag.resolve(
            keyLabel = "Q",
            skinId = "skin-a", generation = 3, keyStylesEmpty = false,
            overrideColor = null,
            skinFaceColor = null,
            variantDefaultColor = 0xFF888888.toInt(),
        )
        assertEquals(KeyFaceDiag.KeyFaceFallback.NO_STYLE_FOR_KEY, info.fallback)
    }

    @Test
    fun log_writesKeyFaceLineWhenDebugEnabled() {
        val dir = Files.createTempDirectory("keyface-diag").toFile()
        val core = DiagLogCore(dir, debugEnabled = { true })
        cores += core
        DiagLog.setCoreForTest(core)

        val info = KeyFaceDiag.resolve(
            keyLabel = "Q",
            skinId = "skin-a", generation = 3, keyStylesEmpty = false,
            overrideColor = null,
            skinFaceColor = null,
            variantDefaultColor = 0xFF888888.toInt(),
        )
        KeyFaceDiag.log(info)
        core.flush()

        val lines = readAllLines(dir)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("DEBUG [keyface] key_face"))
        assertTrue(lines[0].contains("skin=skin-a"))
        assertTrue(lines[0].contains("gen=3"))
        assertTrue(lines[0].contains("key=Q"))
        assertTrue(lines[0].contains("source=variant_default"))
        assertTrue(lines[0].contains("face=#FF888888"))
        assertTrue(lines[0].contains("reason=no_style_for_key"))
    }

    @Test
    fun log_droppedWhenDebugDisabled() {
        val dir = Files.createTempDirectory("keyface-diag-off").toFile()
        val core = DiagLogCore(dir, debugEnabled = { false })
        cores += core
        DiagLog.setCoreForTest(core)

        val info = KeyFaceDiag.resolve(
            keyLabel = "Q",
            skinId = "skin-a", generation = 3, keyStylesEmpty = false,
            overrideColor = null,
            skinFaceColor = 0xFF112233.toInt(),
            variantDefaultColor = 0xFF888888.toInt(),
        )
        KeyFaceDiag.log(info)
        core.flush()

        assertTrue(readAllLines(dir).isEmpty())
    }
}
