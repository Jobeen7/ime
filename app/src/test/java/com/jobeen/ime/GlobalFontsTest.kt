// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.data.keyboard.theme.FontChoice
import com.jobeen.ime.data.keyboard.theme.FontRole
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

    // ---- 导入字体删除管理（施工单 9 特征 2，纯函数） ----

    @Test
    fun deletableFileNameAcceptsFourExtensions() {
        assertTrue(GlobalFonts.isDeletableFileName("abcdef123456.ttf"))
        assertTrue(GlobalFonts.isDeletableFileName("abcdef123456.otf"))
        assertTrue(GlobalFonts.isDeletableFileName("abcdef123456.ttc"))
        assertTrue(GlobalFonts.isDeletableFileName("abcdef123456.otc"))
        assertTrue(GlobalFonts.isDeletableFileName("MyFont.TTF"))
    }

    @Test
    fun deletableFileNameRejectsTraversalAndBadExt() {
        assertTrue(!GlobalFonts.isDeletableFileName(""))
        assertTrue(!GlobalFonts.isDeletableFileName("abc.txt"))
        assertTrue(!GlobalFonts.isDeletableFileName("noext"))
        assertTrue(!GlobalFonts.isDeletableFileName(".ttf"))
        assertTrue(!GlobalFonts.isDeletableFileName("../secret.ttf"))
        assertTrue(!GlobalFonts.isDeletableFileName("sub/dir.ttf"))
        assertTrue(!GlobalFonts.isDeletableFileName("a\\b.ttf"))
        assertTrue(!GlobalFonts.isDeletableFileName("a..ttf"))
    }

    @Test
    fun purgeReferencesResetsOnlyReferencingRoles() {
        val choices = mapOf(
            FontRole.KEY to FontChoice.Imported("abcdef123456.ttf"),
            FontRole.CANDIDATE to FontChoice.Imported("abcdef123456.ttf"),
            FontRole.POPUP to FontChoice.System("serif"),
            FontRole.TOOLBAR to FontChoice.Follow,
        )
        val (updated, reset) = GlobalFonts.purgeReferences(choices, "abcdef123456.ttf")
        assertEquals(listOf(FontRole.KEY, FontRole.CANDIDATE), reset)
        assertEquals(FontChoice.Follow, updated[FontRole.KEY])
        assertEquals(FontChoice.Follow, updated[FontRole.CANDIDATE])
        assertEquals(FontChoice.System("serif"), updated[FontRole.POPUP])
        assertEquals(FontChoice.Follow, updated[FontRole.TOOLBAR])
    }

    @Test
    fun purgeReferencesWithNoMatchLeavesMapUntouched() {
        val choices = mapOf(
            FontRole.KEY to FontChoice.Imported("abcdef123456.ttf"),
            FontRole.CANDIDATE to FontChoice.Follow,
        )
        val (updated, reset) = GlobalFonts.purgeReferences(choices, "000000000000.otf")
        assertTrue(reset.isEmpty())
        assertEquals(choices, updated)
    }
}
