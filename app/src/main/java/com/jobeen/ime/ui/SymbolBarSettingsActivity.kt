// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.SymbolBarSettingsScreen

/** 自定义符号栏设置页宿主（与工具栏页同主题口径）。 */
class SymbolBarSettingsActivity : ImeActivity() {
    override val useEdgeToEdge = false

    @Composable
    override fun Content() {
        SymbolBarSettingsScreen(onBack = { finish() })
    }
}
