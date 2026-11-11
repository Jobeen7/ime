// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.Context
import android.content.Intent
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import com.jobeen.ime.ui.SkinEditorActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * App 内置皮肤文件编辑器入口（W19-7 已填成真实现）：
 *
 * W19-6 panel 板的「按键」「微调」两钮经此进入——前者打开
 * 当前皮肤的主布局文件，后者打开 skin.json。无生效皮肤时
 * 只提示，不伪造界面。编辑器页内完成校验＋重载（见
 * SkinEditorSaver），入口本身只做解析＋起页。
 */
object SkinEditorEntry {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 打开当前皮肤的主布局文件（panel「按键」钮）。 */
    fun openLayouts(context: Context) {
        scope.launch {
            val saver = SkinEditorSaver(context.applicationContext)
            val active = when (val resolved = saver.resolveActiveFiles()) {
                is ResolveActiveResult.Unavailable -> {
                    Timber.w("skin editor: resolve failed: ${resolved.reason}")
                    ToastUtil.showToast(context, context.getString(R.string.skin_editor_no_active_skin))
                    return@launch
                }
                is ResolveActiveResult.Ok -> resolved.files
            }
            // 回退链（问题 6）：主布局→首个绑定布局；无绑定布局不默认选全局首项。
            val file = active.files.firstOrNull { it.id == active.primaryLayoutId }
                ?: active.files.firstOrNull { it.kind == EditorFile.Kind.LAYOUT }
            if (file == null) {
                Timber.w("skin editor: no bound layout")
                ToastUtil.showToast(context, "未绑定布局")
                return@launch
            }
            openEditor(context, file.file.absolutePath)
        }
    }

    /** 打开当前皮肤的 skin.json（panel「微调」钮）。 */
    fun openSkinJson(context: Context) {
        scope.launch {
            val saver = SkinEditorSaver(context.applicationContext)
            val active = when (val resolved = saver.resolveActiveFiles()) {
                is ResolveActiveResult.Unavailable -> {
                    Timber.w("skin editor: resolve failed: ${resolved.reason}")
                    ToastUtil.showToast(context, context.getString(R.string.skin_editor_no_active_skin))
                    return@launch
                }
                is ResolveActiveResult.Ok -> resolved.files
            }
            val file = active.files.firstOrNull { it.kind == EditorFile.Kind.SKIN_JSON }
            if (file == null) {
                Timber.w("skin editor: no active skin for openSkinJson")
                ToastUtil.showToast(context, context.getString(R.string.skin_editor_no_active_skin))
                return@launch
            }
            openEditor(context, file.file.absolutePath)
        }
    }

    private fun openEditor(context: Context, filePath: String) {
        val intent = SkinEditorActivity.intent(context, filePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Timber.w(it, "skin editor: startActivity failed")
        }
    }
}
