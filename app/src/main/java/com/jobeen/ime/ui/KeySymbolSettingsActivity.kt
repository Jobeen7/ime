// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.KeySymbolSettingsScreen

/** 按键符号设置页宿主（与自定义符号栏页同主题口径）。 */
class KeySymbolSettingsActivity : ImeActivity() {
    override val useEdgeToEdge = false

    @Composable
    override fun Content() {
        KeySymbolSettingsScreen(onBack = { finish() })
    }
}
