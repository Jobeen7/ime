package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 8 位 16 进制色双口径钉住：对端 RRGGBBAA（alpha 在末尾）与
 * Jime 自有字段 AARRGGBB（alpha 在开头）绝不可混用；转换器
 * 用 [convertPeerHexToJimeHex] 移位。
 */
class HexColorTest {

    @Test
    fun jimeParserReadsLeadingAlpha() {
        assertEquals(0xFF112233.toInt(), SkinJson.parseColor("#112233"))
        assertEquals(0x01112233, SkinJson.parseColor("#01112233"))
        assertEquals(0x11, (SkinJson.parseColor("#11223344")?.ushr(24) ?: 0) and 0xFF)
    }

}
