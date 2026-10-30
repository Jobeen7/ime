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
    fun formulaBaseName() {
        assertEquals(0xFFFFFFFF.toInt(), DerivationFormulas.eval("face", base))
    }

    @Test
    fun formulaDarken() {
        // 白色 darken 0.5 → 各通道 127
        assertEquals(0xFF7F7F7F.toInt(), DerivationFormulas.eval("darken(face, 0.5)", base))
    }

    @Test
    fun formulaLighten() {
        // 墨色 lighten 0.5 → 20 + (255-20)*0.5 = 137
        assertEquals(0xFF898989.toInt(), DerivationFormulas.eval("lighten(ink, 0.5)", base))
    }

    @Test
    fun formulaMix() {
        // 白 255 与墨 20 按 0.5 混合 → 137
        assertEquals(
            0xFF898989.toInt(),
            DerivationFormulas.eval("mix(face, ink, 0.5)", base),
        )
    }

    @Test
    fun formulaNested() {
        assertEquals(
            DerivationFormulas.eval("darken(face, 0.2)", base),
            DerivationFormulas.eval("mix(darken(face, 0.2), darken(face, 0.2), 0.7)", base),
        )
    }

    @Test
    fun formulaInvalidCasesReturnNull() {
        assertNull(DerivationFormulas.eval("unknown", base))
        assertNull(DerivationFormulas.eval("blur(face, 0.5)", base))
        assertNull(DerivationFormulas.eval("darken(face)", base))
        assertNull(DerivationFormulas.eval("darken(face, 1.5)", base))
        assertNull(DerivationFormulas.eval("darken(face, 0.5", base))
        assertNull(DerivationFormulas.eval("darken(face, 0.5) extra", base))
        assertNull(DerivationFormulas.eval("", base))
        assertNull(DerivationFormulas.eval("mix(face, ink)", base))
        assertNull(DerivationFormulas.eval("darken(face, abc)", base))
    }

    @Test
    fun formulaAlphaPreservedByDarkenLighten() {
        val translucent = mapOf("face" to 0x80FFFFFF.toInt())
        assertEquals(0x807F7F7F.toInt(), DerivationFormulas.eval("darken(face, 0.5)", translucent))
    }

    @Test
    fun textSizeLimits() {
        assertEquals(true, KeyTextSizeLimits.isValid(8f))
        assertEquals(true, KeyTextSizeLimits.isValid(40f))
        assertEquals(false, KeyTextSizeLimits.isValid(7.9f))
        assertEquals(false, KeyTextSizeLimits.isValid(40.1f))
        assertEquals(false, KeyTextSizeLimits.isValid(Float.NaN))
    }

    @Test
    fun geometryRanges() {
        assertEquals(0.5f..2.0f, KeyGeometryOverride.WIDTH_RANGE)
        assertEquals(0.5f..1.0f, KeyGeometryOverride.HEIGHT_RANGE)
        assertEquals(0f..1f, KeyGeometryOverride.CENTER_RANGE)
        assertEquals(0f..20f, KeyGeometryOverride.INSET_RANGE)
        assertEquals(true, KeyGeometryOverride().isEmpty)
        assertEquals(
            false,
            KeyGeometryOverride(widthFactor = 1.5f).isEmpty,
        )
    }

    @Test
    fun dynamicTextSources() {
        assertEquals(
            DynamicTextSource.CANDIDATE_FIRST,
            DynamicTextSource.byJsonName("candidateFirst"),
        )
        assertEquals(DynamicTextSource.COMPOSING, DynamicTextSource.byJsonName("composing"))
        assertNull(DynamicTextSource.byJsonName("clipboard"))
    }

    @Test
    fun foregroundLayerLimit() {
        assertEquals(4, KeyForegroundLayer.MAX_LAYERS)
        assertEquals(16, KeyForegroundLayer.MAX_TEXT_LENGTH)
    }
}
