// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.ui.screen.SkinEditorScreen
import com.jobeen.ime.ui.screen.VisualSkinEditorScreen
import com.jobeen.ime.ui.theme.ImeTheme

/**
 * App 内置皮肤文件编辑器（W19-7）：编辑当前生效皮肤的 skin.json／布局文件。
 *
 * 两种模式：默认文本编辑器；EXTRA_VISUAL 时走可视化皮肤编辑器
 * （VisualSkinEditorScreen），其“高级／查看 JSON”入口再起一个文本模式
 * 本 Activity（返回栈回可视化页）。
 */
class SkinEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialFilePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val visual = intent.getBooleanExtra(EXTRA_VISUAL, false)
        val skinId = intent.getStringExtra(EXTRA_SKIN_ID)
        setContent {
            ImeTheme {
                if (visual) {
                    VisualSkinEditorScreen(
                        skinId = skinId,
                        onBack = { finish() },
                        onOpenJsonEditor = { filePath ->
                            runCatching {
                                startActivity(intent(this, filePath))
                            }
                        },
                    )
                } else {
                    SkinEditorScreen(
                        initialFilePath = initialFilePath,
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_VISUAL = "visual"
        const val EXTRA_SKIN_ID = "skin_id"

        fun intent(context: Context, filePath: String): Intent =
            Intent(context, SkinEditorActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
            }

        fun visualIntent(context: Context, skinId: String?): Intent =
            Intent(context, SkinEditorActivity::class.java).apply {
                putExtra(EXTRA_VISUAL, true)
                if (skinId != null) putExtra(EXTRA_SKIN_ID, skinId)
            }
    }
}
