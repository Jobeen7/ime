package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 8 位 16 进制色双口径钉住：对端 RRGGBBAA（alpha 在末尾）与
 * Jime 自有字段 AARRGGBB（alpha 在开头）绝不可混用；转换器
 * 用 [convertPeerHexToJimeHex] 移位。
 */
class HexColorTest {

    @Test
    fun peerParserReadsTrailingAlpha() {
        assertEquals(0xFF112233.toInt(), parsePeerColor("#112233"))
        // alpha 01 = 1/255，近乎全透明（不是不透明，也不是 0 的同义反复：0x01 ≠ 0x00）。
        val nearlyTransparent = parsePeerColor("#11223301")
        assertEquals(0x01, (nearlyTransparent?.ushr(24) ?: 0) and 0xFF)
        assertEquals(0x112233, nearlyTransparent?.and(0x00FFFFFF))
        assertEquals(0xFF112233.toInt(), parsePeerColor("#112233FF"))
        assertEquals(0x00112233, parsePeerColor("#11223300"))
    }

    @Test
    fun peerParserRejectsIllegalForms() {
        assertNull(parsePeerColor("#FFF"))
        assertNull(parsePeerColor("#GGGGGG"))
        assertNull(parsePeerColor("112233"))
        assertNull(parsePeerColor("#1122334455"))
        assertNull(parsePeerColor("#11223"))
        assertNull(parsePeerColor("#1122334"))
    }

    @Test
    fun jimeParserReadsLeadingAlpha() {
        assertEquals(0xFF112233.toInt(), SkinJson.parseColor("#112233"))
        assertEquals(0x01112233, SkinJson.parseColor("#01112233"))
        // 对照：同一字面量经两者解析的 alpha 字节不同——口径绝不可混用。
        assertEquals(0x44, (parsePeerColor("#11223344")?.ushr(24) ?: 0) and 0xFF)
        assertEquals(0x11, (SkinJson.parseColor("#11223344")?.ushr(24) ?: 0) and 0xFF)
    }

    @Test
    fun peerToJimeConversionMovesAlphaToFront() {
        assertEquals("#44112233", convertPeerHexToJimeHex("#11223344"))
        assertEquals("#112233", convertPeerHexToJimeHex("#112233"))
        assertEquals("#44112233", convertPeerHexToJimeHex("11223344"))
        assertNull(convertPeerHexToJimeHex("#11223"))
        assertNull(convertPeerHexToJimeHex("#GGGGGG"))
        assertNull(convertPeerHexToJimeHex(""))
        // 往返一致：转换后再经 Jime 口径解析 = 对端口径解析。
        val peer = "#A0B0C0D0"
        assertEquals(
            parsePeerColor(peer),
            convertPeerHexToJimeHex(peer)?.let { SkinJson.parseColor(it) },
        )
    }
}
