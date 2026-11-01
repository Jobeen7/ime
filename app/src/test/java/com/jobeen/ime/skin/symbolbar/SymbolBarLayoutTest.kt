package com.jobeen.ime.skin.symbolbar

import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 符号栏布局纯函数：10 格顺序、气泡顺序与位移选格。 */
class SymbolBarLayoutTest {

    private fun pos(action: SkinAction? = SkinAction.Command(WhitelistAction.SYMBOL, "x")) =
        SymbolBarPosition(action, null, null)

    @Test
    fun cellsOrderSwapsEndsOnly() {
        assertEquals(
            listOf(
                SymbolBarCell.Close,
                SymbolBarCell.Symbol(0),
                SymbolBarCell.Symbol(1),
                SymbolBarCell.Symbol(2),
                SymbolBarCell.Symbol(3),
                SymbolBarCell.Symbol(4),
                SymbolBarCell.Symbol(5),
                SymbolBarCell.Symbol(6),
                SymbolBarCell.Symbol(7),
                SymbolBarCell.TypeSwitch,
            ),
            symbolBarCells(true),
        )
        assertEquals(
            listOf(
                SymbolBarCell.TypeSwitch,
                SymbolBarCell.Symbol(0),
                SymbolBarCell.Symbol(1),
                SymbolBarCell.Symbol(2),
                SymbolBarCell.Symbol(3),
                SymbolBarCell.Symbol(4),
                SymbolBarCell.Symbol(5),
                SymbolBarCell.Symbol(6),
                SymbolBarCell.Symbol(7),
                SymbolBarCell.Close,
            ),
            symbolBarCells(false),
        )
    }

    @Test
    fun openDirectionSplitsAtFourthSymbol() {
        assertTrue((0 until 4).all { symbolOpensRight(it) })
        assertTrue((4 until 8).none { symbolOpensRight(it) })
    }

    @Test
    fun bubbleOrderClockwiseAndMirrored() {
        val key = SymbolBarKey(
            center = pos(),
            topLeft = pos(SkinAction.Command(WhitelistAction.SYMBOL, "TL")),
            topRight = pos(SkinAction.Command(WhitelistAction.SYMBOL, "TR")),
            bottomLeft = pos(SkinAction.Command(WhitelistAction.SYMBOL, "BL")),
            bottomRight = pos(SkinAction.Command(WhitelistAction.SYMBOL, "BR")),
        )
        val ordered = key.orderedCorners().map { actionLiteral(it.action) }
        assertEquals(listOf("TL", "TR", "BR", "BL"), ordered)
        assertEquals(
            listOf("TL", "TR", "BR", "BL"),
            bubbleDisplayOrder(key.orderedCorners(), true).map { actionLiteral(it.action) },
        )
        assertEquals(
            listOf("BL", "BR", "TR", "TL"),
            bubbleDisplayOrder(key.orderedCorners(), false).map { actionLiteral(it.action) },
        )
        // 空角跳过，不留空格。
        val sparse = SymbolBarKey(
            center = pos(),
            topRight = pos(SkinAction.Command(WhitelistAction.SYMBOL, "TR")),
            bottomLeft = pos(SkinAction.Command(WhitelistAction.SYMBOL, "BL")),
        )
        assertEquals(
            listOf("TR", "BL"),
            sparse.orderedCorners().map { actionLiteral(it.action) },
        )
    }

    @Test
    fun bubbleIndexByDisplacement() {
        assertNull(bubbleIndexForDisplacement(10f, 100f, 0, true))
        assertEquals(0, bubbleIndexForDisplacement(0f, 100f, 4, true))
        assertEquals(0, bubbleIndexForDisplacement(99f, 100f, 4, true))
        assertEquals(1, bubbleIndexForDisplacement(100f, 100f, 4, true))
        assertEquals(3, bubbleIndexForDisplacement(9999f, 100f, 4, true))
        // 反方向位移停在最近格。
        assertEquals(0, bubbleIndexForDisplacement(-50f, 100f, 4, true))
        // 向左开：负位移计格。
        assertEquals(2, bubbleIndexForDisplacement(-250f, 100f, 4, false))
        assertEquals(0, bubbleIndexForDisplacement(50f, 100f, 4, false))
    }
}
