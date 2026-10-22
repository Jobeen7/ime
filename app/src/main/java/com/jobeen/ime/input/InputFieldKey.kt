package com.jobeen.ime.input

import android.os.Build
import android.view.inputmethod.EditorInfo

/**
 * 输入框身份快照（纯数据）：onStartInputView 的 restarting=true 只
 * 说明宿主在同一 View 上重启输入（restartInput），不保证还是同一
 * 个逻辑输入框——WebView 内不同 <input> 之间切换焦点正是走这条
 * 路、且不经过 onFinishInputView。故以关键字段比对判定「同框重
 * 启」还是「换框」：核心四项（包名、字段 id、输入类型、imeOptions）
 * 任一不同即按换框处理（推进预测代次、作废在途联想）。
 *
 * 第十六批起身份纳入五项尽力而为的扩展字段（autofillId、
 * fieldName、hintText、label、privateImeOptions）：同一 WebView 里
 * 多个同类型 <input> 的核心四项可完全重合，扩展字段是仅剩的区分
 * 线索（其中 autofillId 区分力最强，WebView 里其余几项常为 null）。
 * 扩展字段的判变语义是「两侧都有值且不同才算变」：同框的两次喂
 * 入（onStartInput 与 onStartInputView 背靠背）之间宿主可能只在
 * 一侧给出某项扩展字段，null↔有值的抖动若按换框处理会误杀在途
 * 语音/联想（S1 口径）；而真正的跨框切换里有区分力的字段两侧通
 * 常都有值（autofill 参与时 autofillId 必在两侧）。故扩展字段缺
 * 值只降低区分力、不产生误判，这也是「尽力而为」的确切含义。
 *
 * 本类自第十四批起自引擎抽出共享：引擎（联想代次）与 IMS（语
 * 音纪元的字段身份投递）共用同一份字段口径，避免两处各表。
 */
internal data class InputFieldKey(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int,
    /** 自动填充身份（API 30+ 的 EditorInfo.autofillId，定型为
     * 「viewId/autofillVirtualId」字符串）；低版本或宿主未参与
     * 自动填充时为 null。 */
    val autofillId: String? = null,
    val fieldName: String? = null,
    val hintText: String? = null,
    val label: String? = null,
    val privateImeOptions: String? = null,
) {
    companion object {
        fun of(info: EditorInfo?): InputFieldKey? = info?.let {
            InputFieldKey(
                packageName = it.packageName,
                fieldId = it.fieldId,
                inputType = it.inputType,
                imeOptions = it.imeOptions,
                autofillId = readAutofillId(it),
                fieldName = it.fieldName,
                // CharSequence 可能是可变/带 span 的实现，快照一律
                // 定型为 String 再比对，避免引用相等误判
                hintText = it.hintText?.toString(),
                label = it.label?.toString(),
                privateImeOptions = it.privateImeOptions,
            )
        }

        /** getAutofillId 及其 viewId/virtualId 按现行口径需 API 36+，低版本读不到即按无此线索处理；个别宿主实现可能抛错，同样按无此线索处理。 */
        private fun readAutofillId(info: EditorInfo): String? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
            return runCatching {
                info.autofillId?.let { "${it.viewId}/${it.autofillVirtualId}" }
            }.getOrNull()
        }
    }
}

/** 扩展字段判变：仅当两侧都有值且不同才算变（见 InputFieldKey 的类注释）。 */
private fun extendedFieldChanged(previous: String?, current: String?): Boolean =
    previous != null && current != null && previous != current

/**
 * restarting 重启时是否实为换框：任一侧快照缺失（首次启动等）不
 * 判换框；核心四项任一不同即换框；扩展字段按
 * [extendedFieldChanged] 的两侧有值口径参与。
 */
internal fun isInputFieldChanged(previous: InputFieldKey?, current: InputFieldKey?): Boolean {
    if (previous == null || current == null) return false
    if (
        previous.packageName != current.packageName ||
        previous.fieldId != current.fieldId ||
        previous.inputType != current.inputType ||
        previous.imeOptions != current.imeOptions
    ) {
        return true
    }
    return extendedFieldChanged(previous.autofillId, current.autofillId) ||
        extendedFieldChanged(previous.fieldName, current.fieldName) ||
        extendedFieldChanged(previous.hintText, current.hintText) ||
        extendedFieldChanged(previous.label, current.label) ||
        extendedFieldChanged(previous.privateImeOptions, current.privateImeOptions)
}

/**
 * 字段身份跟踪器：持有一份最近快照，每次输入开始（onStartInput /
 * onStartInputView）喂入本框身份，关键字段与上次不同（实为逻辑换
 * 框，无论宿主以 restarting 真假哪种形式投递）时返回 true，调用
 * 方据此投递换框作废。快照缺失（EditorInfo 为空）时不判换框、也
 * 不覆盖已有快照；同框 hide/show 与同框 restarting 喂入的身份与
 * 上次全同，恒返回 false，绝不误判。
 */
internal class InputFieldTracker {
    private var last: InputFieldKey? = null

    /** 喂入一次输入开始的字段身份；实为逻辑换框时返回 true。 */
    fun track(current: InputFieldKey?): Boolean {
        val changed = isInputFieldChanged(last, current)
        if (current != null) last = current
        return changed
    }
}
