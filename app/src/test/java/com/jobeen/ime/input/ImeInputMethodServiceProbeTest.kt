// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 1 字符探针合并（I-M6）与取消选择的选区解析（I-L4）：
 * null 不是空文本；读不到选区时回落快照。
 */
class ImeInputMethodServiceProbeTest {

    @Test
    fun mergeProbePrefersNonEmptySide() {
        assertEquals("a", mergeProbeResults("a", null))
        assertEquals("b", mergeProbeResults(null, "b"))
        assertEquals("a", mergeProbeResults("a", "b"))
        assertEquals("b", mergeProbeResults("", "b"))
        assertEquals("a", mergeProbeResults("a", ""))
    }

    @Test
    fun mergeProbeEmptyOnlyWhenBothSidesConfirmEmpty() {
        assertEquals("", mergeProbeResults("", ""))
    }

    @Test
    fun mergeProbeNullIsUnknownNotEmpty() {
        // 任一路返回 null 且无一路证明非空：内容未知，返回 null。
        assertNull(mergeProbeResults(null, null))
        assertNull(mergeProbeResults("", null))
        assertNull(mergeProbeResults(null, ""))
    }

    @Test
    fun selectionFromExtractedKeepsValidSelection() {
        assertEquals(3 to 5, selectionFromExtracted(3, 5))
        assertEquals(5 to 5, selectionFromExtracted(5, 5))
        // 反向选区不归一化：折叠判定由调用方按原值做。
        assertEquals(5 to 3, selectionFromExtracted(5, 3))
        assertEquals(0 to 0, selectionFromExtracted(0, 0))
    }

    @Test
    fun selectionFromExtractedRejectsNegativeEndpoints() {
        assertNull(selectionFromExtracted(-1, 5))
        assertNull(selectionFromExtracted(3, -1))
        assertNull(selectionFromExtracted(-1, -1))
    }
}
