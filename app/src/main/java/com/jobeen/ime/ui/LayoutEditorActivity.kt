package com.jobeen.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.LayoutEditorScreen
import com.jobeen.ime.ui.theme.ImeTheme

/**
 * 自创布局编辑器（皮肤 v3 Batch 3）：layoutId 编辑已有布局或恢复
 * 其草稿；无参 = 新建布局（id 在编辑器内预生成）。
 */
class LayoutEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val layoutId = intent.getStringExtra(EXTRA_LAYOUT_ID)
        setContent {
            ImeTheme(themeMode = KeyboardManager.Theme.getMode(this)) {
                LayoutEditorScreen(
                    layoutId = layoutId,
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_LAYOUT_ID = "layout_id"

        fun intentForLayout(context: Context, layoutId: String): Intent =
            Intent(context, LayoutEditorActivity::class.java)
                .putExtra(EXTRA_LAYOUT_ID, layoutId)

        fun intentForNew(context: Context): Intent =
            Intent(context, LayoutEditorActivity::class.java)
    }
}
