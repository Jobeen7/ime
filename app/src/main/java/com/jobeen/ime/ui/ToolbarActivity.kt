// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.ToolbarScreen

/** 工具栏编辑页宿主（与键盘设置页同主题口径）。 */
class ToolbarActivity : ImeActivity() {
    override val useEdgeToEdge = false

    @Composable
    override fun Content() {
        ToolbarScreen(onBack = { finish() })
    }
}
