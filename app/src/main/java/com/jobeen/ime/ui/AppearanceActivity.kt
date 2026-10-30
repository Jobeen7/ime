// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jobeen.ime.ui.screen.AppearanceScreen
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.ImeTheme

/**
 * 外观页 Activity：主题状态由本页持有（改动即时重组本页；
 * 其它设置页下次创建时读取新值，与现行各页“创建时读一次”一致）。
 */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var mode by androidx.compose.runtime.remember {
                mutableStateOf(AppAppearance.getMode(this))
            }
            val systemDark = isSystemInDarkTheme()
            ImeTheme(
                themeMode = AppAppearance.themeModeFor(mode, systemDark),
                dynamicColor = AppAppearance.useDynamicColorFor(
                    mode,
                    android.os.Build.VERSION.SDK_INT,
                ),
            ) {
                AppearanceScreen(
                    mode = mode,
                    onModeChange = { newMode ->
                        AppAppearance.setMode(this, newMode)
                        mode = newMode
                    },
                    onBack = { finish() },
                )
            }
        }
    }
}
