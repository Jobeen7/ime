package com.jobeen.ime.skin.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对端 5 图等价形式的内容核对（浅色实测值；渲染本身走真机验收，
 * XML 资源由构建期 aapt 校验）。
 */
class SkinImageEquivalentsTest {

    @Test
    fun shapeColorsMatchSampledPeerImages() {
        assertEquals(0xFFD0D3DA.toInt(), SkinImageEquivalents.floatBackGray)
        assertEquals(0xFFFFFFFF.toInt(), SkinImageEquivalents.toolbarCircleNormal)
        assertEquals(0xFFD0D4DB.toInt(), SkinImageEquivalents.toolbarCircleHighlight)
    }

    @Test
    fun cnenIsTwoLineBlackText() {
        val lines = TextIconDrawable.cnen().lines
        assertEquals(listOf("中", "EN"), lines.map { it.text })
        assertEquals(
            listOf(0xFF000000.toInt(), 0xFF000000.toInt()),
            lines.map { it.color },
        )
    }

    @Test
    fun encnIsSingleGreenCharacter() {
        val lines = TextIconDrawable.encn().lines
        assertEquals(listOf("英"), lines.map { it.text })
        assertEquals(0xFF0FBC7A.toInt(), lines.single().color)
        assertEquals(0xFF0FBC7A.toInt(), SkinImageEquivalents.encnText)
        assertEquals(0xFF1BC886.toInt(), SkinImageEquivalents.wxiconGreen)
    }
}
