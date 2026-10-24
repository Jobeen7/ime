package com.jobeen.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.SchemaSettingsScreen
import com.jobeen.ime.ui.theme.ImeTheme

class SchemaSettingsActivity : ComponentActivity() {

    companion object {
        /** RIME 页“方案列表管理”入口：直接以管理页打开，不经过 … 菜单。 */
        const val EXTRA_OPEN_MANAGE = "open_manage"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode = remember {
                mutableIntStateOf(KeyboardManager.Theme.getMode(this))
            }
            ImeTheme(themeMode = themeMode.intValue) {
                SchemaSettingsScreen(
                    onBack = { finish() },
                    openManage = intent.getBooleanExtra(EXTRA_OPEN_MANAGE, false),
                )
            }
        }
    }

}
