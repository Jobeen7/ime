package com.jobeen.ime.input.panel

import android.view.View
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage

interface IPanel {
    val view: View
    var recording: Boolean
    fun setCandidates(
        list: List<EngineMessage.Candidate>,
        hasMore: Boolean = false,
        highlightedIndex: Int = 0,
        pageNo: Int = 0,
        isLastPage: Boolean = false,
        pagingMode: com.jobeen.ime.engine.CandidatePagingMode =
            com.jobeen.ime.engine.CandidatePagingMode.SWIPE,
    )
    /** 分页补取的追加页并入当前候选列表；total 为引擎侧确切总数（-1=未知） */
    fun appendCandidates(list: List<EngineMessage.Candidate>, total: Int) {}
    fun refreshTheme()
    fun onFinishInputView(finishingInput: Boolean)
    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>)
    fun exitAddPhraseMode()

    /** 粘贴后返回键盘（默认空实现）：剪贴板态 → Idle，其余态不动 */
    fun returnToKeyboard() {}

    /** WO52 5.4 锁定：操作后刷新剪贴板列表、面板停留（默认空实现）。 */
    fun refreshClipboardList() {}

    /** 剪贴板搜索态是否激活（窗口布局要据此拉高窗口、插入结果列表区）。 */
    val clipSearchActive: Boolean get() = false

    /** 剪贴板条目编辑态是否激活（与搜索态同窗口形态：拉高窗口+键盘改道输入）。 */
    val clipEditActive: Boolean get() = false

    /**
     * 剪贴板文本输入态（搜索/编辑）截获引擎上屏文本：
     * 返回 true 表示已消费（不写入目标应用）。
     */
    fun interceptCommit(text: String): Boolean = false

    /**
     * 剪贴板文本输入态（搜索/编辑）截获退格：
     * 返回 true 表示已消费（不下发引擎删字）。
     */
    fun handleClipSearchBackspace(isComposing: Boolean): Boolean = false
}
