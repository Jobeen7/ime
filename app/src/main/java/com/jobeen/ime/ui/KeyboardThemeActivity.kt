// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.remember
import com.jobeen.ime.ui.screen.BuiltinThemeScreen
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.ImeTheme

/** 键盘皮肤页 Activity（与 AppearanceActivity 同构：只承托页面，不持状态）。 */
class KeyboardThemeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mode = remember { AppAppearance.getMode(this) }
            val systemDark = isSystemInDarkTheme()
            ImeTheme(
                themeMode = AppAppearance.themeModeFor(mode, systemDark),
                dynamicColor = AppAppearance.useDynamicColorFor(
                    mode,
                    android.os.Build.VERSION.SDK_INT,
                ),
            ) {
                BuiltinThemeScreen(onBack = { finish() })
            }
        }
    }
}
