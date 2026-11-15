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
    }


}
