// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.skin.model.SkinAction
import com.jobeen.ime.skin.model.WhitelistAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 工具栏通知动作映射（接线轮 R1）：只取与面板动作精确对应的项。 */
class ToolbarNotificationActionTest {

    @Test
    fun mappedActions() {
        assertEquals(
            PanelAction.Undo,
            ToolbarNotificationAction.panelActionFor(
                SkinAction.Command(WhitelistAction.UNDO),
            ),
        )
        assertEquals(
            PanelAction.Redo,
            ToolbarNotificationAction.panelActionFor(
                SkinAction.Command(WhitelistAction.REDO),
            ),
        )
        assertEquals(
            PanelAction.CloseKeyboard,
            ToolbarNotificationAction.panelActionFor(
                SkinAction.Command(WhitelistAction.HIDE_KEYBOARD),
            ),
        )
    }

    @Test
    fun unmappedActionsStayPut() {
        assertNull(
            ToolbarNotificationAction.panelActionFor(
                SkinAction.Command(WhitelistAction.CAPS),
            ),
        )
        assertNull(
            ToolbarNotificationAction.panelActionFor(
                SkinAction.Command(WhitelistAction.SWITCH_LAYOUT, "T9"),
            ),
        )
        assertNull(ToolbarNotificationAction.panelActionFor(SkinAction.Text("x")))
        assertNull(ToolbarNotificationAction.panelActionFor(null))
    }
}
