// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.HintSymbolNode
import com.jobeen.ime.skin.model.HintSymbolsGridStyle
import com.jobeen.ime.skin.model.HintSymbolsStyle
import com.jobeen.ime.skin.model.KeyStyleRole
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 长按符号面板背景样式解析（面板级两背景走现有样式表）。 */
class HintSymbolsResolverTest {

    private val scheme = KeyboardColors.ColorScheme(
        keyBackground = 1, keyPressed = 2, keyBorderStroke = 3,
        specialKeyBackground = 4, specialKeyPressed = 5, specialKeyBorderStroke = 6,
        accentKeyBackground = 7, accentKeyPressed = 8, accentKeyBorderStroke = 9,
        keyText = 10, specialKeyText = 11, accentKeyText = 12, altText = 13,
        background = 14, cornerRadius = 5f, keyHMargin = 3f, keyVMargin = 4f,
        panel = KeyboardColors.ColorScheme.PanelColors(
            background = 20, toolbarText = 21, toolbarActived = 22, toolbarIcon = 23,
            candidateBackground = 24, candidateText = 25, candidateIndex = 26,
            candidateDivider = 27, toolbarPressed = 28,
        ),
        pinner = KeyboardColors.ColorScheme.PinnerColors(
            background = 30, textColor = 31, secondaryTextColor = 32,
        ),
        toastBackground = 40, toastText = 41,
    )

    @Test
    fun backgroundsResolveAgainstStyleTable() {
        val keyStyles = mapOf(
            "panelBg" to mapOf(KeyStyleRole.FACE to "#FF112233"),
            "selectedBg" to mapOf(KeyStyleRole.FACE to "accentKeyBackground"),
        )
        val style = HintSymbolsStyle(
            symbolStyles = listOf("sym1"),
            backgroundStyle = "panelBg",
            selectedBackgroundStyle = "selectedBg",
        )
        val resolved = HintSymbolsResolver.resolveBackgrounds(style, keyStyles, scheme)
        assertEquals(0xFF112233.toInt(), resolved.background?.face)
        assertEquals(7, resolved.selectedBackground?.face)
    }

    @Test
    fun unknownBackgroundNamesResolveToNull() {
        val style = HintSymbolsStyle(
            symbolStyles = listOf("sym1"),
            backgroundStyle = "missing",
        )
        val resolved = HintSymbolsResolver.resolveBackgrounds(
            style, emptyMap(), scheme,
        )
        assertNull(resolved.background)
        assertNull(resolved.selectedBackground)
    }

    // ---- 长按面板（旧版单行）决议 ----

    private val testToRuntime: (SkinAction) -> KeyboardAction = { action ->
        when (action) {
            is SkinAction.Text -> KeyboardAction.CommitAction(action.value)
            else -> KeyboardAction.NoOpAction
        }
    }

    private fun legacyPanelFixture(): HintSymbolsResolver.LegacyPanel? {
        val styles = mapOf(
            "legacy1" to HintSymbolsStyle(
                symbolStyles = listOf("a", "dangling", "b"),
                selectedIndex = 2,
            ),
        )
        val nodes = mapOf(
            "a" to HintSymbolNode("A!", SkinAction.Text("a")),
            "b" to HintSymbolNode(null, SkinAction.Command(WhitelistAction.SYMBOL, "β")),
        )
        return HintSymbolsResolver.resolveLegacyPanel(
            "legacy1", styles, nodes, testToRuntime,
        )
    }

    @Test
    fun legacyPanelSkipsDanglingNamesAndClampsInitialIndex() {
        val panel = legacyPanelFixture()!!
        assertEquals(listOf("a", "b"), panel.styleNames)
        assertEquals(
            listOf(KeyboardAction.CommitAction("a"), KeyboardAction.NoOpAction),
            panel.actions,
        )
        // a 显式文本优先；b 无文本时回落符号字面值；初始选中 2 夹取到过滤后末位 1
        assertEquals(listOf("A!", "β"), panel.labels)
        assertEquals(1, panel.initialIndex)
    }

    @Test
    fun legacyPanelNullWhenStyleOrNodesMissing() {
        assertNull(
            HintSymbolsResolver.resolveLegacyPanel(
                null, emptyMap(), emptyMap(), testToRuntime,
            ),
        )
        assertNull(
            HintSymbolsResolver.resolveLegacyPanel(
                "missing",
                mapOf("legacy1" to HintSymbolsStyle(symbolStyles = listOf("a"))),
                mapOf("a" to HintSymbolNode(null, SkinAction.Text("a"))),
                testToRuntime,
            ),
        )
        assertNull(
            HintSymbolsResolver.resolveLegacyPanel(
                "legacy1",
                mapOf("legacy1" to HintSymbolsStyle(symbolStyles = listOf("dangling"))),
                emptyMap(),
                testToRuntime,
            ),
        )
    }

    // ---- 长按网格面板决议 ----

    @Test
    fun gridPanelResolvesCellsSkippingEmptyAndDangling() {
        val style = HintSymbolsGridStyle(
            symbolRows = listOf(listOf("a", null, "dangling"), listOf("b")),
        )
        val nodes = mapOf(
            "a" to HintSymbolNode("A!", SkinAction.Text("a")),
            "b" to HintSymbolNode(null, SkinAction.Command(WhitelistAction.SYMBOL, "β")),
        )
        val keyStyles = mapOf(
            "a" to mapOf(
                KeyStyleRole.TEXT to "keyText",
                KeyStyleRole.FACE to "#FF112233",
            ),
        )
        val panel = HintSymbolsResolver.resolveGridPanel(
            "grid1", mapOf("grid1" to style), nodes, keyStyles, scheme, testToRuntime,
        )!!
        assertEquals(2, panel.rows.size)
        val cellA = panel.rows[0][0]!!
        assertEquals(KeyboardAction.CommitAction("a"), cellA.action)
        assertEquals("A!", cellA.label)
        assertEquals(10, cellA.textColor)
        assertEquals(0xFF112233.toInt(), cellA.faceColor)
        assertNull(panel.rows[0][1])
        assertNull(panel.rows[0][2])
        val cellB = panel.rows[1][0]!!
        assertEquals(KeyboardAction.NoOpAction, cellB.action)
        assertEquals("β", cellB.label)
        assertNull(cellB.textColor)
        assertNull(cellB.faceColor)
    }

    @Test
    fun gridPanelNullWhenNameMissingOrAllEmpty() {
        assertNull(
            HintSymbolsResolver.resolveGridPanel(
                null, emptyMap(), emptyMap(), emptyMap(), scheme, testToRuntime,
            ),
        )
        assertNull(
            HintSymbolsResolver.resolveGridPanel(
                "missing",
                mapOf("grid1" to HintSymbolsGridStyle(symbolRows = listOf(listOf("a")))),
                mapOf("a" to HintSymbolNode(null, SkinAction.Text("a"))),
                emptyMap(), scheme, testToRuntime,
            ),
        )
        assertNull(
            HintSymbolsResolver.resolveGridPanel(
                "grid1",
                mapOf("grid1" to HintSymbolsGridStyle(symbolRows = listOf(listOf("dangling")))),
                emptyMap(), emptyMap(), scheme, testToRuntime,
            ),
        )
    }

    @Test
    fun gridBackgroundsResolveAgainstStyleTable() {
        val style = HintSymbolsGridStyle(
            symbolRows = listOf(listOf("a")),
            backgroundStyle = "panelBg",
            selectedBackgroundStyle = "selectedBg",
        )
        val keyStyles = mapOf(
            "panelBg" to mapOf(KeyStyleRole.FACE to "#FF112233"),
            "selectedBg" to mapOf(KeyStyleRole.FACE to "accentKeyBackground"),
        )
        val resolved = HintSymbolsResolver.resolveGridBackgrounds(style, keyStyles, scheme)
        assertEquals(0xFF112233.toInt(), resolved.background?.face)
        assertEquals(7, resolved.selectedBackground?.face)
    }

    @Test
    fun itemTextColorsResolveAgainstStyleTable() {
        val keyStyles = mapOf(
            "t1" to mapOf(KeyStyleRole.TEXT to "keyText"),
        )
        val colors = HintSymbolsResolver.resolveItemTextColors(
            listOf("t1", "missing"), keyStyles, scheme,
        )
        assertEquals(listOf(10, null), colors)
    }
}
