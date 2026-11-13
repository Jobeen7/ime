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
 * 新窗口复位门（WO32，LatinIME 公式）：`!restarting || inputTypeChanged`。
 *
 * LatinIME（LatinIME.java:988）`isDifferentTextField = !restarting ||
 * inputTypeChanged`：`restarting` 是框架契约——切到新输入框必为
 * false（新会话），同框重开才为 true。Gboard 照此判定换框（它不
 * 复位语言，Jime 只照搬检测方法、不照搬策略）。
 *
 * inputTypeChanged 是当前 [EditorInfo.inputType] 与上次应用值
 * 的全等比对（整型逐位相等，不拆 class/variation）。上次应用值
 * 缺失（进程内首次输入开始）时按变化处理，与 LatinIME 的
 * mInputEditorInfo 为空时 inputTypeChanged=true 一致。
 *
 * 本函数不比包名／字段 id／imeOptions：微信各会话共用同一布局
 * 与字段 id，四项全同，旧门（四项比对）在此恒为 false、
 * resetToFirstSchema() 一次也没跑——正是 WO12 复位失效的根因。
 * 纯决策，可单测。
 */
internal fun shouldResetWindowOnStartInput(
    restarting: Boolean,
    inputType: Int?,
    lastAppliedInputType: Int?,
): Boolean {
    if (!restarting) return true
    if (inputType == null) return false
    return lastAppliedInputType == null || lastAppliedInputType != inputType
}

/**
 * 新窗口复位门的待投递状态（WO32）：门在 onStartInput 算（该入口
 * 每会话必调、restarting 可信），复位在 onStartInputView 链路
 * 执行（窗口弹出时才有可复位的窗口；键盘未显示时框架只调
 * onStartInput、不调 onStartInputView，见 IMS 注释）。
 *
 * 三坑：
 * - 转屏：转屏第一次 onStartInput 的 restarting=true 且 inputType
 *   不变 → 门为 false，pending 不置位，不误复位；pending 只由门
 *   为真时置位、永不由门为假时清零（转屏不能把先前换框攒下的
 *   pending 抹掉）。
 * - 同框隐藏再点开：框架上 served view 没变，checkFocusInternalLocked
 *   直接返回，不产生新 startInput，pending 保持 false，复位不误
 *   触发；onStartInputView 自身的 restarting 在此路径上不可信
 *   （同框收起再打开该回调 restarting=false），故门不在该层重算。
 * - onStartInputView 不一定被调：门不依赖它，只由它消费 pending。
 *
 * 旧 W17-6 的展示字段比对门（shouldConsumePendingFieldChange）
 * 已随本门删除：它拿展示字段与上次已展示字段的四项全等比对，
 * 微信切聊天框时两边全同、恒不消费，正是旧门失效的另一半；
 * 跨会话残留 pending 改由 onFinishInput 的 [clearPending] 兜底。
 *
 * 仅主线程（IMS 回调线程）使用，与旧 fieldChangedPending 同口径，
 * 无需同步。
 */
internal class WindowResetGate {
    private var lastAppliedInputType: Int? = null
    private var pending: Boolean = false

    /**
     * onStartInput 到达时算门并滚动上次应用值；门为真时置位待投递。
     * 返回本次算得的门值（供取证日志）。
     */
    fun noteStartInput(restarting: Boolean, inputType: Int?): Boolean {
        val gate = shouldResetWindowOnStartInput(restarting, inputType, lastAppliedInputType)
        if (inputType != null) lastAppliedInputType = inputType
        if (gate) pending = true
        return gate
    }

    /** onStartInputView 消费一次待投递；无待投递时返回 false。 */
    fun consumePending(): Boolean {
        val value = pending
        pending = false
        return value
    }

    /** 会话结束（onFinishInput）清零残留待投递。 */
    fun clearPending() {
        pending = false
    }
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
