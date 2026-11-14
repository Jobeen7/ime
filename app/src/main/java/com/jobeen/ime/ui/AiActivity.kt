// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.AiSettingsScreen

/** 命令面板管理页宿主（WO52 9.1，设置一级入口 `ai`）。 */
class AiActivity : ImeActivity() {

    @Composable
    override fun Content() {
        AiSettingsScreen(
            onBack = { finish() },
        )
    }
}
