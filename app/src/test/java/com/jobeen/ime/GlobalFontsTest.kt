// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.keyboard.theme.FontChoice
import com.jobeen.ime.data.keyboard.theme.GlobalFonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全局字体分层纯逻辑：皮肤字族＞全局选择＞系统默认，未知回落默认。 */
class GlobalFontsTest {

    @Test
    fun skinFamilyBeatsGlobalChoice() {
        val source = GlobalFonts.effectiveSource(
            "serif",
            FontChoice.System("monospace"),
        )
        assertTrue(source is GlobalFonts.EffectiveSource.Skin)
        assertEquals("serif", (source as GlobalFonts.EffectiveSource.Skin).family)
    }

    @Test
    fun unknownSkinFamilyFallsThroughToGlobal() {
        val source = GlobalFonts.effectiveSource(
            "not-a-real-family",
            FontChoice.System("sans-serif"),
        )
        assertTrue(source is GlobalFonts.EffectiveSource.Global)
        assertEquals("sans-serif", (source as GlobalFonts.EffectiveSource.Global).family)
    }

    @Test
    fun noSkinNoGlobalYieldsDefault() {
        val source = GlobalFonts.effectiveSource(null, FontChoice.Follow)
        assertTrue(source is GlobalFonts.EffectiveSource.Default)
    }

    @Test
    fun defaultKeywordSkinYieldsGlobal() {
        val source = GlobalFonts.effectiveSource(
            "default",
            FontChoice.Imported("abc123def456.ttf"),
        )
        assertTrue(source is GlobalFonts.EffectiveSource.GlobalFile)
        assertEquals(
            "abc123def456.ttf",
            (source as GlobalFonts.EffectiveSource.GlobalFile).fileId,
        )
    }

    @Test
    fun choiceParseStoreRoundTrip() {
        val choices = listOf(
            FontChoice.Follow,
            FontChoice.System("sans-serif"),
            FontChoice.System("serif"),
            FontChoice.System("monospace"),
            FontChoice.Imported("abc123def456.otf"),
        )
        for (choice in choices) {
            assertEquals(choice, FontChoice.parse(FontChoice.store(choice)))
        }
    }

    @Test
    fun unknownStoredValueParsesAsFollow() {
        assertEquals(FontChoice.Follow, FontChoice.parse("bogus"))
        assertEquals(FontChoice.Follow, FontChoice.parse("file:"))
        assertEquals(FontChoice.Follow, FontChoice.parse(null))
    }
}
