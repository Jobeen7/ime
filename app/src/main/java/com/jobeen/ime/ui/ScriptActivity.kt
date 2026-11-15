// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.ScriptScreen

/** 脚本管理页宿主（WO52 8.1，设置一级入口 `script`）。 */
class ScriptActivity : ImeActivity() {

    @Composable
    override fun Content() {
        ScriptScreen(
            onBack = { finish() },
        )
    }
}
