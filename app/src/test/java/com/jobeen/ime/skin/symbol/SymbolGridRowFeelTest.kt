// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.symbol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 长按行格手感表（接线轮 R6）：BaseKeyboard 单行长按格按
 * 步距 itemStep、抖动半步、灵敏度 1.0 构造解析规格——
 * 锚定死区半步、每步一格、出界取消、回界恢复。
 * 注：负向精确半步边界（dx ≡ -23 mod 46）与旧 roundToInt
 * 差一格（旧式负向 tie 停留，新式按抖动契约 |dx| ≥ 抖动即
 * 换格、对称）；此为解析器契约本身，非接线偏差。
 */
class SymbolGridRowFeelTest {

    private fun spec() = SymbolGridSpec.create(
        cells = listOf("a", "b", "c", "d", "e"),
        columns = 5,
        anchorIndex = 2,
        defaultIndex = 2,
        cellSizePx = 46f,
        sensitivity = 1f,
        jitterPx = 23f,
    )!!

    @Test
    fun deadZoneAndSteps() {
        val s = spec()
        assertEquals(2, SymbolGridResolver.highlightFor(s, 0f, 0f))
        assertEquals(2, SymbolGridResolver.highlightFor(s, 22f, 0f))
        assertEquals(2, SymbolGridResolver.highlightFor(s, -22f, 0f))
        assertEquals(3, SymbolGridResolver.highlightFor(s, 23f, 0f))
        assertEquals(3, SymbolGridResolver.highlightFor(s, 68f, 0f))
        assertEquals(4, SymbolGridResolver.highlightFor(s, 69f, 0f))
        assertEquals(1, SymbolGridResolver.highlightFor(s, -23f, 0f))
        assertEquals(0, SymbolGridResolver.highlightFor(s, -69f, 0f))
    }

    @Test
    fun outOfBoundsCancelsAndReentryRestores() {
        val s = spec()
        assertNull(SymbolGridResolver.highlightFor(s, 115f, 0f))
        assertNull(SymbolGridResolver.highlightFor(s, -115f, 0f))
        // 回界内恢复：同一次拖动回到界内即再次命中格
        assertEquals(4, SymbolGridResolver.highlightFor(s, 114f, 0f))
        assertEquals(0, SymbolGridResolver.highlightFor(s, -114f, 0f))
    }

    @Test
    fun verticalDriftWouldCancelSoWiringPassesZero() {
        // 单行解析器对 dy 并不免疫（大纵向漂移 → 行出界 → 取消），
        // 故接线层固定传 dy=0：行格内纵向漂移永不取消选格。
        val s = spec()
        assertNull(SymbolGridResolver.highlightFor(s, 23f, 100f))
        assertEquals(3, SymbolGridResolver.highlightFor(s, 23f, 0f))
    }
}
