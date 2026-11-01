package com.jobeen.ime.skin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 集合约束（模型侧）：前景文字属性、styleName 取值、悬空空白、成对、颜色、非穿透键。 */
class CollectionConstraintsTest {

    @Test
    fun styleNameCellResolvesBackgroundAndForegroundOnly() {
        val styles = mapOf(
            "cell" to StyleNameStyle(
                background = 0xFF112233.toInt(),
                foreground = ForegroundTextAttrs(
                    textSizeDp = 16f,
                    textWeight = CandidateFontWeight.MEDIUM,
                    textColor = 0xFF445566.toInt(),
                ),
            ),
        )
        val resolved = CollectionConstraints.resolveStyleNameCell("cell", styles)
        assertEquals(0xFF112233.toInt(), resolved?.background)
        assertEquals(16f, resolved?.foreground?.textSizeDp)
        assertEquals(CandidateFontWeight.MEDIUM, resolved?.foreground?.textWeight)
    }

    @Test
    fun emptyStyleNameIsDefaultNotBlank() {
        val resolved = CollectionConstraints.resolveStyleNameCell("", emptyMap())
        assertTrue(resolved != null)
        assertNull(resolved?.background)
        assertNull(resolved?.foreground)
    }

    @Test
    fun danglingStyleRefIsSilentBlank() {
        // 悬空引用返回 null（空白），不抛错；集合差集列出悬空名。
        assertNull(CollectionConstraints.resolveStyleNameCell("ghost", emptyMap()))
        assertEquals(
            setOf("ghost"),
            CollectionConstraints.findDanglingRefs(setOf("a", "ghost"), setOf("a")),
        )
        assertTrue(
            CollectionConstraints.findDanglingRefs(setOf("a"), setOf("a")).isEmpty(),
        )
    }

    @Test
    fun unpairedThemeIsBlankOnSwitch() {
        val light = StyleNameStyle(background = 0xFFFFFFFF.toInt())
        assertNull(CollectionConstraints.themeStyleFor(light, null, isDark = true))
        assertEquals(light, CollectionConstraints.themeStyleFor(light, null, isDark = false))
        assertNull(CollectionConstraints.themeStyleFor(null, null, isDark = false))
    }

    @Test
    fun illegalColorsFailSilently() {
        // 只接受 #RRGGBB / #RRGGBBAA；其余一律 null（不拒包不抛错）。
        assertNull(parsePeerColor("#FFF"))
        assertNull(parsePeerColor("#GGGGGG"))
        assertNull(parsePeerColor("112233"))
        assertNull(parsePeerColor("#1122334455"))
        assertEquals(0xFF112233.toInt(), parsePeerColor("#112233"))
        // 8 位末尾为透明度：01 近乎全透明（1/255），不是不透明。
        val nearlyTransparent = parsePeerColor("#11223301")
        assertEquals(0x01, (nearlyTransparent?.ushr(24) ?: 0) and 0xFF)
        assertEquals(0x112233, nearlyTransparent?.and(0x00FFFFFF))
        // 皮肤字段解析口径同义：非法记 issue 回 null（由调用方回落）。
        assertNull(SkinJson.parseColor("#12"))
    }

    @Test
    fun nonPenetratingKeysMustBeInline() {
        // type/maxColumns/maxRows/colorLocation/shadowOpacity 只写
        // 基底即失效；本层补写后不再违规。
        assertEquals(
            setOf("maxColumns", "type", "shadowOpacity"),
            MergeKeyPolicy.findBaseOnlyNonPenetrating(
                setOf("maxColumns", "type", "shadowOpacity", "fontSize"),
                setOf("fontSize"),
            ),
        )
        assertTrue(
            MergeKeyPolicy.findBaseOnlyNonPenetrating(
                setOf("maxColumns", "type"),
                setOf("maxColumns", "type"),
            ).isEmpty(),
        )
        // 非穿透表外的键（如 fontSize）只写基底不违规。
        assertTrue(
            MergeKeyPolicy.findBaseOnlyNonPenetrating(
                setOf("fontSize"),
                emptySet(),
            ).isEmpty(),
        )
    }
}
