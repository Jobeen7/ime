// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.base.util.ToastUtil
import timber.log.Timber

/**
 * App 内置皮肤文件编辑器入口（W19-7 填充实现）：
 *
 * W19-6 panel 板的「按键」「微调」两钮经此进入——前者打开
 * 当前皮肤的布局文件，后者打开 skin.json；编辑器未落成前
 * 两入口只记日志＋提示，不伪造界面、不静默吞掉点按。
 * W19-7 在此接真编辑器页（校验＋重载口径见施工单 19）。
 */
object SkinEditorEntry {

    /** 打开当前皮肤的布局文件（W19-7：编辑器页）。 */
    fun openLayouts(context: Context) {
        Timber.w("skin editor not ready: openLayouts")
        ToastUtil.showToast(context, context.getString(R.string.skin_editor_not_ready))
    }

    /** 打开当前皮肤的 skin.json（W19-7：编辑器页）。 */
    fun openSkinJson(context: Context) {
        Timber.w("skin editor not ready: openSkinJson")
        ToastUtil.showToast(context, context.getString(R.string.skin_editor_not_ready))
    }
}
