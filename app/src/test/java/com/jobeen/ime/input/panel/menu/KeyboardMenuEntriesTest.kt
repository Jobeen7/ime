// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.menu

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘菜单页入口注册表（重点候选 8-10）：唯一性、分页、工具栏池全覆盖、部署入口。 */
class KeyboardMenuEntriesTest {

    @Test
    fun idsUniqueAndPagesBounded() {
        val ids = KeyboardMenuEntries.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(KeyboardMenuEntries.PAGES.isNotEmpty())
        for (page in KeyboardMenuEntries.PAGES) {
            assertTrue(page.isNotEmpty())
            assertTrue(page.size <= 8)
        }
        assertEquals(3, KeyboardMenuEntries.PAGES.size)
    }

    @Test
    fun toolbarPoolFullyCovered() {
        val actions = KeyboardMenuEntries.ALL.map { it.action }.toSet()
        for (poolItem in ToolbarConfig.POOL) {
            assertTrue(
                "pool ${poolItem.id} missing from menu",
                poolItem.action in actions,
            )
        }
    }

    @Test
    fun settingsAndDeployEntriesPresent() {
        val byId = KeyboardMenuEntries.ALL.associateBy { it.id }
        assertEquals(PanelAction.Settings, byId.getValue("settings").action)
        assertEquals(PanelAction.SchemaSettings, byId.getValue("schema_settings").action)
        assertEquals(PanelAction.About, byId.getValue("about").action)
        assertEquals(PanelAction.ReloadEngine, byId.getValue("reload_engine").action)
        assertEquals(PanelAction.DeploySync, byId.getValue("deploy_sync").action)
        assertEquals(PanelAction.SelectAll, byId.getValue("select_all").action)
        assertEquals(PanelAction.Copy, byId.getValue("copy").action)
        assertEquals(PanelAction.Paste, byId.getValue("paste").action)
        assertEquals(PanelAction.Undo, byId.getValue("undo").action)
        assertEquals(PanelAction.Redo, byId.getValue("redo").action)
    }
}
