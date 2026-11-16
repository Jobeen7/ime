// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.T9QuickSymbolsSettingsScreen

/** T9 快捷符号设置页宿主（与符号栏设置页同主题口径）。 */
class T9QuickSymbolsSettingsActivity : ImeActivity() {
    override val useEdgeToEdge = false

    @Composable
    override fun Content() {
        T9QuickSymbolsSettingsScreen(onBack = { finish() })
    }
}
