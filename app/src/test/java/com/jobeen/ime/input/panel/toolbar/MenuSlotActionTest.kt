// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.panel.toolbar

import com.jobeen.ime.input.panel.PanelAction
import com.jobeen.ime.skin.model.MenuAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** MENU 槽位动作（W19-6b）：皮肤 menuAction → MENU 钮动作的纯函数映射。 */
class MenuSlotActionTest {

    @Test
    fun menuOpensOriginalMenuByDefault() {
        assertEquals(PanelAction.SwitchKeyboard, menuSlotAction(MenuAction.MENU))
    }

    @Test
    fun panelOpensShortcutPanel() {
        assertEquals(PanelAction.ShortcutPanel, menuSlotAction(MenuAction.PANEL))
    }
}
