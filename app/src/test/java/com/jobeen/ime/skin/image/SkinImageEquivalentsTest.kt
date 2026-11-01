package com.jobeen.ime.skin.image

import com.jobeen.ime.skin.model.SpriteFrame
import com.jobeen.ime.skin.model.SpriteInsets
import com.jobeen.ime.skin.model.SpriteRect
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
    fun floatBackDescriptorDrivenValues() {
        // E4：固定 16dp 已废止——描述、深色变体、圆角系数皆有实测来源。
        assertEquals(0xFF474747.toInt(), SkinImageEquivalents.floatBackDarkGray)
        val descriptor = SkinImageEquivalents.floatBackDescriptor
        assertEquals("float_back", descriptor.normal.file)
        assertEquals("IMG1", descriptor.normal.image)
        assertEquals(descriptor.normal, descriptor.highlight)
        assertEquals(
            SpriteFrame(
                SpriteRect(0, 0, 130, 130),
                SpriteInsets(63, 63, 63, 63),
            ),
            SkinImageEquivalents.floatBackFrame,
        )
        // 实测圆弧 25px / 形体 80px = 0.3125；80px 形体复算回 25px。
        assertEquals(0.3125f, SkinImageEquivalents.FLOAT_BACK_ARC_FRACTION, 0.0001f)
        assertEquals(25f, SkinImageEquivalents.floatBackRadiusPx(80f), 0.001f)
        assertEquals(0f, SkinImageEquivalents.floatBackRadiusPx(0f), 0f)
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
