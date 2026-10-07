package com.jobeen.ime.input.panel

import android.view.View
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage

interface IPanel {
    val view: View
    var recording: Boolean
    fun setCandidates(list: List<EngineMessage.Candidate>, hasMore: Boolean = false)
    /** 分页补取的追加页并入当前候选列表；total 为引擎侧确切总数（-1=未知） */
    fun appendCandidates(list: List<EngineMessage.Candidate>, total: Int) {}
    fun refreshTheme()
    fun onFinishInputView(finishingInput: Boolean)
    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>)
    fun exitAddPhraseMode()

    /** 剪贴板搜索态截获引擎上屏文本：返回 true 表示已消费（不写入目标应用）。 */
    fun interceptCommit(text: String): Boolean = false

    /** 剪贴板搜索态截获退格：返回 true 表示已消费（不下发引擎删字）。 */
    fun handleClipSearchBackspace(isComposing: Boolean): Boolean = false
}
