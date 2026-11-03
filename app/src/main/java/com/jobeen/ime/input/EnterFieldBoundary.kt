// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * EditorInfo 与纯决策 [EnterField] 之间的唯一边界映射。
 *
 * 纯决策函数 [EnterDecision] 本身不碰 Android 类型；所有 EditorInfo
 * 常量只在这里出现。无编辑框信息时按单行＋未声明动作计
 * （执行侧输入连接为空时发 DONE 即空操作，与旧口径的默认换行
 * 不同：空探针已退出回车决策，缺信息不再翻回换行）。
 */
object EnterFieldBoundary {

    fun fromEditorInfo(info: EditorInfo?): EnterField {
        if (info == null) {
            return EnterField(
                isMultiLine = false,
                noEnterAction = false,
                action = EnterFieldAction.UNSPECIFIED,
            )
        }
        return EnterField(
            isMultiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0,
            noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0,
            action = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_SEARCH -> EnterFieldAction.SEARCH
                EditorInfo.IME_ACTION_SEND -> EnterFieldAction.SEND
                EditorInfo.IME_ACTION_GO -> EnterFieldAction.GO
                EditorInfo.IME_ACTION_NEXT -> EnterFieldAction.NEXT
                EditorInfo.IME_ACTION_PREVIOUS -> EnterFieldAction.PREVIOUS
                EditorInfo.IME_ACTION_DONE -> EnterFieldAction.DONE
                else -> EnterFieldAction.UNSPECIFIED
            },
        )
    }

    fun toEditorAction(action: EnterFieldAction): Int = when (action) {
        EnterFieldAction.SEARCH -> EditorInfo.IME_ACTION_SEARCH
        EnterFieldAction.SEND -> EditorInfo.IME_ACTION_SEND
        EnterFieldAction.GO -> EditorInfo.IME_ACTION_GO
        EnterFieldAction.NEXT -> EditorInfo.IME_ACTION_NEXT
        EnterFieldAction.PREVIOUS -> EditorInfo.IME_ACTION_PREVIOUS
        EnterFieldAction.DONE -> EditorInfo.IME_ACTION_DONE
        EnterFieldAction.UNSPECIFIED -> EditorInfo.IME_ACTION_UNSPECIFIED
    }
}
