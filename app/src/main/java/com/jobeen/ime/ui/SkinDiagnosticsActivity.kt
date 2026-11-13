// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import androidx.compose.runtime.Composable
import com.jobeen.ime.ui.screen.SkinDiagnosticsScreen

class SkinDiagnosticsActivity : ImeActivity() {
    @Composable
    override fun Content() {
        SkinDiagnosticsScreen(onBack = { finish() })
    }
}
