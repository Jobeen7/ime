package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.skin.model.SkinToolbarButtonSnapshot
import com.jobeen.ime.skin.model.SkinToolbarIconRef
import com.jobeen.ime.skin.model.SkinToolbarSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 皮肤工具栏优先级抉择（2026-10-27）：纯函数，渲染器只消费其结果。 */
class ToolbarSkinResolutionTest {

    private val appActions = listOf(PanelAction.EmojiKeyboard, PanelAction.Copy)

    private fun icon(id: String) = SkinToolbarIconRef(id, "a".repeat(64), "png")

    @Test
    fun skinCenterWinsOverAppCenterEntirely() {
        val resolved = ToolbarSkinResolution.resolve(
            skinToolbar = SkinToolbarSnapshot(
                present = true,
                buttons = listOf(
                    SkinToolbarButtonSnapshot("voice", icon("tb-voice")),
                    SkinToolbarButtonSnapshot("undo", null),
                ),
            ),
            fallbackActions = appActions,
        )
        assertTrue(resolved.hasSkinCenter)
        assertEquals(listOf(PanelAction.ToggleVoice, PanelAction.Undo), resolved.centerActions)
        assertEquals(mapOf("voice" to "tb-voice"), resolved.centerIconAssets)
    }

    @Test
    fun explicitEmptySkinCenterStaysEmpty() {
        val resolved = ToolbarSkinResolution.resolve(
            skinToolbar = SkinToolbarSnapshot(present = true, buttons = emptyList()),
            fallbackActions = appActions,
        )
        assertTrue(resolved.hasSkinCenter)
        assertEquals(emptyList<PanelAction>(), resolved.centerActions)
    }

    @Test
    fun undefinedSkinCenterFallsBackToAppActions() {
        val resolved = ToolbarSkinResolution.resolve(
            skinToolbar = SkinToolbarSnapshot(
                present = true, buttons = null, expandIcon = icon("tb-expand"),
            ),
            fallbackActions = appActions,
        )
        assertFalse(resolved.hasSkinCenter)
        assertEquals(appActions, resolved.centerActions)
        assertEquals(mapOf<String, String>(), resolved.centerIconAssets)
        assertEquals("tb-expand", resolved.expandIconAsset)
        assertNull(resolved.collapseIconAsset)
    }

    @Test
    fun noSkinToolbarFallsBackToAppFully() {
        val resolved = ToolbarSkinResolution.resolve(null, appActions)
        assertFalse(resolved.hasSkinCenter)
        assertEquals(appActions, resolved.centerActions)
        assertNull(resolved.expandIconAsset)
        assertNull(resolved.collapseIconAsset)
    }

    @Test
    fun unknownButtonIdsInSnapshotAreDroppedDefensively() {
        val resolved = ToolbarSkinResolution.resolve(
            skinToolbar = SkinToolbarSnapshot(
                present = true,
                buttons = listOf(
                    SkinToolbarButtonSnapshot("voice", null),
                    SkinToolbarButtonSnapshot("not-a-button", null),
                ),
            ),
            fallbackActions = appActions,
        )
        assertEquals(listOf(PanelAction.ToggleVoice), resolved.centerActions)
    }

    @Test
    fun skinSymbolsExposedForRegistryHookup() {
        val resolved = ToolbarSkinResolution.resolve(
            skinToolbar = SkinToolbarSnapshot(
                present = true,
                buttons = listOf(
                    SkinToolbarButtonSnapshot("voice", null, "mic"),
                    SkinToolbarButtonSnapshot("undo", icon("tb-undo")),
                ),
                expandSymbol = "menu",
            ),
            fallbackActions = appActions,
        )
        assertEquals(mapOf("voice" to "mic"), resolved.centerIconSymbols)
        assertEquals("menu", resolved.expandIconSymbol)
        assertNull(resolved.collapseIconSymbol)
        assertNull(resolved.expandIconAsset)
    }

    @Test
    fun edgeIconsPassThroughIndependently() {
        val resolved = ToolbarSkinResolution.resolve(
            skinToolbar = SkinToolbarSnapshot(
                present = true, buttons = null,
                expandIcon = icon("tb-expand"), collapseIcon = icon("tb-collapse"),
            ),
            fallbackActions = appActions,
        )
        assertEquals("tb-expand", resolved.expandIconAsset)
        assertEquals("tb-collapse", resolved.collapseIconAsset)
    }
}
