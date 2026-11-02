package com.jobeen.ime.skin.symbolbar

import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 符号栏配置解析（指南「出错时会怎样」表全口径）与动作转换。 */
class SymbolBarConfigTest {

    private fun parse(yaml: String): SymbolBarConfig? =
        SymbolBarConfigParser.parse(SymbolBarYaml.parse(yaml))

    private fun guideConfig(): SymbolBarConfig = parse(
        """
        closeButtonOnLeft: true
        height: 44
        cellInsets: { left: 4, right: 4, top: 4, bottom: 4 }
        backgroundColor: { light: "#EEEEEE", dark: "#222222" }
        longPressHintColor: "#888888"
        type:
          - MD
          - 数学
        panels:
          MD:
            - center:
                action: { symbol: "," }
                text: ","
              topLeft:
                action: { symbol: "、" }
              topRight:
                action: { symbol: "+" }
              bottomLeft:
                action: { symbol: "；" }
              bottomRight:
                action: { symbol: "：" }
          旧类型:
            - center:
                action: { symbol: "x" }
        """.trimIndent(),
    )!!

    @Test
    fun guideExampleParsesFully() {
        val config = guideConfig()
        assertTrue(config.closeButtonOnLeft)
        assertEquals(44, config.heightDp)
        assertEquals(CellInsetsDp(4f, 4f, 4f, 4f), config.cellInsetsDp)
        assertEquals(LightDark(0xFFEEEEEE.toInt(), 0xFF222222.toInt()), config.background)
        assertEquals(LightDark(0xFF888888.toInt(), 0xFF888888.toInt()), config.longPressHintColor)
        assertEquals(listOf("MD", "数学"), config.types)
        val key = config.panelFor("MD").keys[0]
        assertEquals(SkinAction.Command(WhitelistAction.SYMBOL, ","), key.center.action)
        assertEquals(",", key.center.text)
        assertEquals(",", key.center.display().first)
        assertEquals(
            listOf("、", "+", "：", "；"),
            key.orderedCorners().map { actionLiteral(it.action) },
        )
        assertFalse(key.showsLongPressHint)
        // 不在 type 列表中的面板被保留。
        assertEquals("x", actionLiteral(config.panelFor("旧类型").keys[0].center.action))
        // 缺面板的类型 8 格全空白。
        assertTrue(config.panelFor("数学").keys.all { it.center.action == null })
    }

    @Test
    fun invalidConfigsReturnNull() {
        assertNull(parse("closeButtonOnLeft: true\n"))
        assertNull(parse("type: []\n"))
        assertNull(parse("type: MD\n"))
        assertNull(parse("not a map"))
    }

    @Test
    fun panelsNonMapFallsBackToBlank() {
        val config = parse("type:\n  - MD\npanels: 42\n")!!
        assertTrue(config.panelFor("MD").keys.all { it.center.action == null })
    }

    @Test
    fun shortPanelFillsBlanksAndLongPanelTruncates() {
        val config = parse(
            """
            type:
              - A
              - B
            panels:
              A:
                - center:
                    action: { symbol: "1" }
              B:
                - center:
                    action: { symbol: "1" }
                - center:
                    action: { symbol: "2" }
                - center:
                    action: { symbol: "3" }
                - center:
                    action: { symbol: "4" }
                - center:
                    action: { symbol: "5" }
                - center:
                    action: { symbol: "6" }
                - center:
                    action: { symbol: "7" }
                - center:
                    action: { symbol: "8" }
                - center:
                    action: { symbol: "9" }
            """.trimIndent(),
        )!!
        assertEquals(8, config.panelFor("A").keys.size)
        assertEquals("1", actionLiteral(config.panelFor("A").keys[0].center.action))
        assertTrue(config.panelFor("A").keys.drop(1).all { it.center.action == null })
        assertEquals("8", actionLiteral(config.panelFor("B").keys[7].center.action))
    }

    @Test
    fun heightAndInsetsFallBackPerSlot() {
        val config = parse(
            """
            type:
              - MD
            height: 999
            cellInsets: { left: -1, right: abc, top: 2 }
            """.trimIndent(),
        )!!
        assertEquals(44, config.heightDp)
        assertEquals(CellInsetsDp(4f, 4f, 2f, 4f), config.cellInsetsDp)
        val zero = parse("type:\n  - MD\nheight: 0\n")!!
        assertEquals(44, zero.heightDp)
        val ok = parse("type:\n  - MD\nheight: 200\n")!!
        assertEquals(200, ok.heightDp)
    }

    @Test
    fun illegalColorsFallBackToSlotDefaults() {
        val config = parse(
            """
            type:
              - MD
            longPressHintColor: "#123"
            panels:
              MD:
                - center:
                    action: { symbol: "x" }
                  normalBackgroundColor: "red"
                  gridBackgroundColor: { light: "#FFFFFF", dark: "nope" }
            """.trimIndent(),
        )!!
        assertEquals(LightDark(0xFF888888.toInt(), 0xFF888888.toInt()), config.longPressHintColor)
        val key = config.panelFor("MD").keys[0]
        assertEquals(LightDark(0x00000000, 0x00000000), key.normalBackground)
        assertEquals(LightDark(0xFFEEEEEE.toInt(), 0xFF222222.toInt()), key.gridBackground)
        assertEquals(0xFF112233.toInt(), SymbolBarConfigParser.parseHex("#112233"))
        assertNull(SymbolBarConfigParser.parseHex("#123"))
        assertNull(SymbolBarConfigParser.parseHex("red"))
        assertNull(SymbolBarConfigParser.parseHex("#GGGGGG"))
    }

    @Test
    fun badActionsBecomeBlankPositions() {
        val config = parse(
            """
            type:
              - MD
            panels:
              MD:
                - center:
                    action: { symbol: "" }
                  topLeft:
                    action: { shortcut: "#notReal" }
                  topRight:
                    action: { shortcut: "#toggleSymbolBar" }
                  bottomLeft:
                    action: "not a map"
                  bottomRight:
                    action: { snippet: "hi" }
            """.trimIndent(),
        )!!
        val key = config.panelFor("MD").keys[0]
        assertNull(key.center.action)
        assertNull(key.topLeft?.action)
        assertEquals(
            SkinAction.Command(WhitelistAction.HASH_TOGGLE_SYMBOL_BAR, null),
            key.topRight?.action,
        )
        assertNull(key.bottomLeft?.action)
        assertEquals(SkinAction.Snippet("hi"), key.bottomRight?.action)
        assertTrue(key.orderedCorners().map { it.action }.containsAll(
            listOf(
                SkinAction.Command(WhitelistAction.HASH_TOGGLE_SYMBOL_BAR, null),
                SkinAction.Snippet("hi"),
            ),
        ))
    }

    @Test
    fun displayFallbackIconTextLiteral() {
        val icon = SymbolBarPosition(action = null, text = "T", systemImageName = "xmark")
        val (glyph, isIcon) = icon.display()
        assertTrue(isIcon)
        assertTrue(glyph.isNotEmpty())
        val unknownIcon = SymbolBarPosition(action = null, text = "T", systemImageName = "no.such.icon")
        assertEquals("T" to false, unknownIcon.display())
        val literal = SymbolBarPosition(
            SkinAction.Command(WhitelistAction.SYMBOL, ","), null, null,
        )
        assertEquals("," to false, literal.display())
        val blank = SymbolBarPosition(null, null, null)
        assertEquals("" to false, blank.display())
    }

    @Test
    fun longPressHintOnlyWhenAllShownCornersHidden() {
        val hidden = SymbolBarKey(
            center = SymbolBarPosition(SkinAction.Command(WhitelistAction.SYMBOL, "x"), null, null),
            topLeft = SymbolBarPosition(
                SkinAction.Command(WhitelistAction.SYMBOL, "a"), null, null, showOnKey = false,
            ),
        )
        assertTrue(hidden.showsLongPressHint)
        val shown = SymbolBarKey(
            center = SymbolBarPosition(SkinAction.Command(WhitelistAction.SYMBOL, "x"), null, null),
            topLeft = SymbolBarPosition(
                SkinAction.Command(WhitelistAction.SYMBOL, "a"), null, null, showOnKey = true,
            ),
        )
        assertFalse(shown.showsLongPressHint)
        assertFalse(SymbolBarKey.BLANK.showsLongPressHint)
    }

    @Test
    fun resolveCurrentTypeFallsBackToFirst() {
        assertEquals("数学", resolveCurrentType(listOf("MD", "数学"), "数学"))
        assertEquals("MD", resolveCurrentType(listOf("MD", "数学"), "旧类型"))
        assertEquals("MD", resolveCurrentType(listOf("MD", "数学"), null))
    }

    @Test
    fun orientationChange_resetsOnlyOnDefinedChange() {
        // 方向值：0=未定义，1=竖屏，2=横屏，3=方形（Configuration.orientation）。
        assertTrue(shouldResetSymbolBarOnOrientationChange(1, 2))
        assertTrue(shouldResetSymbolBarOnOrientationChange(2, 1))
        assertTrue(shouldResetSymbolBarOnOrientationChange(1, 3))
        assertFalse(shouldResetSymbolBarOnOrientationChange(1, 1))
        assertFalse(shouldResetSymbolBarOnOrientationChange(0, 2))
        assertFalse(shouldResetSymbolBarOnOrientationChange(1, 0))
        assertFalse(shouldResetSymbolBarOnOrientationChange(0, 0))
    }

    @Test
    fun loaderMissingInvalidAndOk() {
        val dir = java.nio.file.Files.createTempDirectory("symbolbar-test").toFile()
        try {
            val missing = File(dir, "nope.yaml")
            assertTrue(SymbolBarConfigLoader.load(missing) is SymbolBarLoadResult.Missing)
            val bad = File(dir, "bad.yaml").apply { writeText("a: [unclosed\n") }
            assertTrue(SymbolBarConfigLoader.load(bad) is SymbolBarLoadResult.Invalid)
            val emptyTypes = File(dir, "empty.yaml").apply { writeText("type: []\n") }
            assertTrue(SymbolBarConfigLoader.load(emptyTypes) is SymbolBarLoadResult.Invalid)
            val ok = File(dir, "ok.yaml").apply { writeText("type:\n  - MD\n") }
            val result = SymbolBarConfigLoader.load(ok)
            assertTrue(result is SymbolBarLoadResult.Ok)
            assertEquals(listOf("MD"), (result as SymbolBarLoadResult.Ok).config.types)
        } finally {
            dir.deleteRecursively()
        }
    }
}
