package com.jobeen.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jobeen.ime.data.manager.KeyboardManager
import com.jobeen.ime.ui.screen.SkinEditorScreen
import com.jobeen.ime.ui.theme.ImeTheme

/**
 * 皮肤编辑器（Batch 1 全局外观面）：入口参数——
 * skinId 编辑已有皮肤或恢复其草稿；presetId / legacyId 以预设 /
 * 旧主题为起点新建；皆无则空白新建。
 */
class SkinEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val skinId = intent.getStringExtra(EXTRA_SKIN_ID)
        val presetId = intent.getStringExtra(EXTRA_PRESET_ID)
        val legacyId = intent.getStringExtra(EXTRA_LEGACY_ID)
        setContent {
            ImeTheme(themeMode = KeyboardManager.Theme.getMode(this)) {
                SkinEditorScreen(
                    skinId = skinId,
                    presetId = presetId,
                    legacyId = legacyId,
                    onClose = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_SKIN_ID = "skin_id"
        const val EXTRA_PRESET_ID = "preset_id"
        const val EXTRA_LEGACY_ID = "legacy_id"

        fun intentForSkin(context: Context, skinId: String): Intent =
            Intent(context, SkinEditorActivity::class.java)
                .putExtra(EXTRA_SKIN_ID, skinId)

        fun intentForPreset(context: Context, presetId: String): Intent =
            Intent(context, SkinEditorActivity::class.java)
                .putExtra(EXTRA_PRESET_ID, presetId)

        fun intentForLegacy(context: Context, legacyId: String): Intent =
            Intent(context, SkinEditorActivity::class.java)
                .putExtra(EXTRA_LEGACY_ID, legacyId)

        fun intentForBlank(context: Context): Intent =
            Intent(context, SkinEditorActivity::class.java)
    }
}
