// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.menu

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.input.panel.toolbar.ToolbarConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 键盘菜单页入口注册表（重点候选 8-10）：唯一性、分页、工具栏池覆盖（除已删 6 项）、设置入口。 */
class KeyboardMenuEntriesTest {

    // 2026-10-31 用户定：菜单第 3 页 6 项删除，下列三处期望随规格改（报告中标明）：
    // 页数 3→2、工具栏池覆盖排除已删 6 项、已删 6 项断言由存在改为不存在。
    private val removedIds = setOf("select_all", "copy", "paste", "undo", "redo", "deploy_sync")

    @Test
    fun idsUniqueAndPagesBounded() {
        val ids = KeyboardMenuEntries.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(KeyboardMenuEntries.PAGES.isNotEmpty())
        for (page in KeyboardMenuEntries.PAGES) {
            assertTrue(page.isNotEmpty())
            assertTrue(page.size <= 8)
        }
        assertEquals(2, KeyboardMenuEntries.PAGES.size)
    }

    @Test
    fun toolbarPoolCoveredExceptRemoved() {
        val actions = KeyboardMenuEntries.ALL.map { it.action }.toSet()
        val ids = KeyboardMenuEntries.ALL.map { it.id }.toSet()
        for (poolItem in ToolbarConfig.POOL) {
            if (poolItem.id in removedIds) {
                assertTrue("removed ${poolItem.id} must not be in menu", poolItem.id !in ids)
            } else {
                assertTrue(
                    "pool ${poolItem.id} missing from menu",
                    poolItem.action in actions,
                )
            }
        }
    }

    @Test
    fun settingsEntriesPresentAndRemovedAbsent() {
        val byId = KeyboardMenuEntries.ALL.associateBy { it.id }
        assertEquals(PanelAction.Settings, byId.getValue("settings").action)
        assertEquals(PanelAction.SchemaSettings, byId.getValue("schema_settings").action)
        assertEquals(PanelAction.About, byId.getValue("about").action)
        assertEquals(PanelAction.ReloadEngine, byId.getValue("reload_engine").action)
        for (id in removedIds) {
            assertTrue("removed $id must not be in menu", id !in byId)
        }
    }
}
