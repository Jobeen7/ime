// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.KeyboardMenuSettingsScreen

/** 键盘菜单管理页宿主（WO52 12-⑨，设置一级入口 `keyboardMenu`）。 */
class KeyboardMenuSettingsActivity : ImeActivity() {
    override val useEdgeToEdge = false

    @Composable
    override fun Content() {
        KeyboardMenuSettingsScreen(onBack = { finish() })
    }
}
