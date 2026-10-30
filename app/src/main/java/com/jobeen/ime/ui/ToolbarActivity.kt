// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.jobeen.ime.ui.screen.ToolbarScreen
import com.jobeen.ime.ui.theme.AppAppearance
import com.jobeen.ime.ui.theme.AppAppearanceMode
import com.jobeen.ime.ui.theme.ImeTheme

/** 工具栏编辑页宿主（与键盘设置页同主题口径）。 */
class ToolbarActivity : ComponentActivity() {
    // 返回即刷新：创建时记下的外观模式；onResume 比对，变了才重建。
    private var createdAppearanceMode: AppAppearanceMode? = null
    /**
     * 返回即刷新：从外观页返回时，若应用外观模式已变则重建本页，
     * 主题立即生效；模式未变不重建；重建后新模式即记录值，不循环。
     */
    override fun onResume() {
        super.onResume()
        val recordedAppearanceMode = createdAppearanceMode
        if (recordedAppearanceMode != null &&
            AppAppearance.appearanceModeChanged(recordedAppearanceMode, AppAppearance.getMode(this))
        ) {
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createdAppearanceMode = AppAppearance.getMode(this)
        setContent {
            val themeMode = remember { mutableIntStateOf(AppAppearance.themeMode(this)) }
            ImeTheme(themeMode = themeMode.intValue, dynamicColor = AppAppearance.useDynamicColor(this)) {
                ToolbarScreen(onBack = { finish() })
            }
        }
    }
}
