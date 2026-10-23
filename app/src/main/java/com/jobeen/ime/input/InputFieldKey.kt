package com.jobeen.ime.input

import android.view.inputmethod.EditorInfo

/**
 * 输入框身份快照（纯数据）：onStartInputView 的 restarting=true 只
 * 说明宿主在同一 View 上重启输入（restartInput），不保证还是同一
 * 个逻辑输入框——WebView 内不同 <input> 之间切换焦点正是走这条
 * 路、且不经过 onFinishInputView。故以核心四项（包名、字段 id、
 * 输入类型、imeOptions）比对判定「同框重启」还是「换框」：任一
 * 不同即按换框处理（推进预测代次、作废在途联想）。
 *
 * 第十六/十七批曾纳入五项尽力而为的扩展字段（autofillId、
 * fieldName、hintText、label、privateImeOptions）＋两层判变口径，
 * 为区分核心四项全同的 WebView 多 <input> 场景；但其字段在
 * WebView 里常为 null、autofillId 仅 API 36+ 可读，分层自身还制
 * 造过同框动态文案误判换框、误杀在途语音的反向故障，而目标场
 * 景在全部真机记录中从未复现。已按清点 D6 定论整层拆除：代价
 * 是核心四项全同的多框切换失去区分线索，接受此代价换判定口径
 * 回到单一事实源。
 *
 * 本类自第十四批起自引擎抽出共享：引擎（联想代次）与 IMS（语
 * 音纪元的字段身份投递）共用同一份字段口径，避免两处各表。
 */
internal data class InputFieldKey(
    val packageName: String?,
    val fieldId: Int,
    val inputType: Int,
    val imeOptions: Int,
) {
    companion object {
        fun of(info: EditorInfo?): InputFieldKey? = info?.let {
            InputFieldKey(
                packageName = it.packageName,
                fieldId = it.fieldId,
                inputType = it.inputType,
                imeOptions = it.imeOptions,
            )
        }
    }
}

/**
 * restarting 重启时是否实为换框：任一侧快照缺失（首次启动等）不
 * 判换框；核心四项任一不同即换框。
 */
internal fun isInputFieldChanged(previous: InputFieldKey?, current: InputFieldKey?): Boolean {
    if (previous == null || current == null) return false
    return previous.packageName != current.packageName ||
        previous.fieldId != current.fieldId ||
        previous.inputType != current.inputType ||
        previous.imeOptions != current.imeOptions
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
