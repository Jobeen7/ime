package com.jobeen.ime.skin.library

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.skin.model.SkinIssue
import com.jobeen.ime.skin.model.SkinJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 第三组库页改版：颜色模式、迷你预览调色、开发屏检视纯函数的单测。 */
class SkinLibraryUiTest {

    private fun scheme(): KeyboardColors.ColorScheme =
        KeyboardColors.ColorScheme(
            keyBackground = 0xFF111111.toInt(),
            keyPressed = 0xFF222222.toInt(),
            keyBorderStroke = 0xFF333333.toInt(),
            specialKeyBackground = 0xFF444444.toInt(),
            specialKeyPressed = 0xFF555555.toInt(),
            specialKeyBorderStroke = 0xFF666666.toInt(),
            accentKeyBackground = 0xFF777777.toInt(),
            accentKeyPressed = 0xFF888888.toInt(),
            accentKeyBorderStroke = 0xFF999999.toInt(),
            keyText = 0xFFAAAAAA.toInt(),
            specialKeyText = 0xFFBBBBBB.toInt(),
            accentKeyText = 0xFFCCCCCC.toInt(),
            altText = 0xFFDDDDDD.toInt(),
            background = 0xFFEEEEEE.toInt(),
            panel = KeyboardColors.ColorScheme.PanelColors(
                background = 0xFF101010.toInt(),
                toolbarText = 0xFF202020.toInt(),
                toolbarActived = 0xFF303030.toInt(),
                toolbarIcon = 0xFF404040.toInt(),
                candidateBackground = 0xFF505050.toInt(),
                candidateText = 0xFF606060.toInt(),
                candidateIndex = 0xFF707070.toInt(),
                candidateDivider = 0xFF808080.toInt(),
                toolbarPressed = 0xFF909090.toInt(),
            ),
            pinner = KeyboardColors.ColorScheme.PinnerColors(
                background = 0xFF111111.toInt(),
                textColor = 0xFFCCCCCC.toInt(),
                secondaryTextColor = 0xFFDDDDDD.toInt(),
            ),
        )

    @Test
    fun colorModeParseDefaultsToSystem() {
        assertEquals(SkinColorMode.SYSTEM, SkinColorMode.parse(null))
        assertEquals(SkinColorMode.SYSTEM, SkinColorMode.parse("bogus"))
        assertEquals(SkinColorMode.LIGHT, SkinColorMode.parse("light"))
        assertEquals(SkinColorMode.DARK, SkinColorMode.parse("dark"))
        for (mode in SkinColorMode.entries) {
            assertEquals(mode, SkinColorMode.parse(mode.stored))
        }
    }

    @Test
    fun colorModeThemeModeMappingMatchesThemeConstants() {
        // 取值与 KeyboardManager.Theme 的 MODE_SYSTEM/LIGHT/DARK（0/1/2）一致，
        // ImeTheme 据此分支持续浅色/深色/跟随系统。
        assertEquals(0, SkinColorMode.SYSTEM.toThemeMode())
        assertEquals(1, SkinColorMode.LIGHT.toThemeMode())
        assertEquals(2, SkinColorMode.DARK.toThemeMode())
    }

    @Test
    fun colorModeEffectiveDarkMatrix() {
        assertEquals(true, SkinColorMode.SYSTEM.effectiveDark(true))
        assertEquals(false, SkinColorMode.SYSTEM.effectiveDark(false))
        assertEquals(false, SkinColorMode.LIGHT.effectiveDark(true))
        assertEquals(true, SkinColorMode.DARK.effectiveDark(false))
    }

    @Test
    fun miniPreviewPaletteMapsSchemeFields() {
        val palette = miniPreviewPalette(scheme())
        assertEquals(androidx.compose.ui.graphics.Color(0xFFEEEEEE.toInt()), palette.background)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF505050.toInt()), palette.candidateBackground)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF606060.toInt()), palette.candidateText)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF111111.toInt()), palette.key)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF444444.toInt()), palette.special)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF777777.toInt()), palette.accent)
    }

    @Test
    fun miniPreviewRowsShape() {
        assertEquals(3, MINI_PREVIEW_ROWS.size)
        for (row in MINI_PREVIEW_ROWS) {
            assertEquals(4, row.size)
            assertTrue(row.contains(MiniKeyKind.SPECIAL))
            assertTrue(row.contains(MiniKeyKind.ACCENT))
        }
    }

    @Test
    fun devPrettyPrintTruncatesLongJson() {
        val tree = SkinJson.parseObject(
            """{"meta": {"name": "${"x".repeat(300)}"}}""",
        )!!
        val pretty = SkinDevInspector.prettyPrint(tree, maxChars = 50)
        assertTrue(pretty.contains("已截断"))
        assertTrue(pretty.length < 300)
        val short = SkinDevInspector.prettyPrint(
            SkinJson.parseObject("""{"a": 1}""")!!,
        )
        assertTrue(short.contains("\"a\""))
        assertTrue(!short.contains("已截断"))
    }

    @Test
    fun devTailLinesKeepsLastLines() {
        val text = (1..10).joinToString("\n") { "line$it" }
        assertEquals(listOf("line8", "line9", "line10"), SkinDevInspector.tailLines(text, 3))
        assertTrue(SkinDevInspector.tailLines("").isEmpty())
    }

    @Test
    fun devValidationSummary() {
        assertEquals(
            "已拒绝：format_version_unsupported:9",
            SkinDevInspector.validationSummary(false, emptyList(), "format_version_unsupported:9"),
        )
        assertEquals(
            "有效，无回填提示",
            SkinDevInspector.validationSummary(true, emptyList(), null),
        )
        assertEquals(
            "有效，2 项回填提示",
            SkinDevInspector.validationSummary(
                true,
                listOf(
                    SkinIssue("a", SkinIssue.Kind.FIELD_FALLBACK, "x"),
                    SkinIssue("b", SkinIssue.Kind.INFO, "y"),
                ),
                null,
            ),
        )
    }
}
