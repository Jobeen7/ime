package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** G1 单键模型纯函数：派生自定义公式、字号区间、几何区间。 */
class KeyModelG1Test {

    private val base = mapOf(
        "face" to 0xFFFFFFFF.toInt(),
        "pressed" to 0xFFEEEEEE.toInt(),
        "bed" to 0xFFF5F5F5.toInt(),
        "ink" to 0xFF141414.toInt(),
        "accent" to 0xFF9C824A.toInt(),
        "hint" to 0xFF999999.toInt(),
    )










    @Test
    fun dynamicTextSources() {
        assertEquals(
            DynamicTextSource.CANDIDATE_FIRST,
            DynamicTextSource.byJsonName("candidateFirst"),
        )
        assertEquals(DynamicTextSource.COMPOSING, DynamicTextSource.byJsonName("composing"))
        assertNull(DynamicTextSource.byJsonName("clipboard"))
    }

}
