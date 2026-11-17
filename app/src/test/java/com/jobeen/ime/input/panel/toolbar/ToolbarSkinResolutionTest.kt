package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** 皮肤工具栏优先级抉择（皮肤 v3 死壳删除后恒走出厂默认分支）。 */
class ToolbarSkinResolutionTest {

    private val appActions = listOf(PanelAction.EmojiKeyboard, PanelAction.Copy)

    @Test
    fun resolveReturnsFallbackActions() {
        val resolved = ToolbarSkinResolution.resolve(appActions)
        assertFalse(resolved.hasSkinCenter)
        assertEquals(appActions, resolved.centerActions)
        assertEquals(emptyMap<String, String>(), resolved.centerIconAssets)
        assertEquals(emptyMap<String, String>(), resolved.centerIconSymbols)
        assertNull(resolved.expandIconAsset)
        assertNull(resolved.collapseIconAsset)
        assertNull(resolved.expandIconSymbol)
        assertNull(resolved.collapseIconSymbol)
    }

    @Test
    fun resolveEmptyFallbackStaysEmpty() {
        val resolved = ToolbarSkinResolution.resolve(emptyList())
        assertFalse(resolved.hasSkinCenter)
        assertEquals(emptyList<PanelAction>(), resolved.centerActions)
    }
}
