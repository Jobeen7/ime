package com.jobeen.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.event.KeyEvent
import com.jobeen.ime.input.EnterField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface IEngine {
    fun initialize(context: Context)
    fun finalize()
    fun processKey(service: InputMethodService, key: KeyEvent): Unit?

    /**
     * 回车键精简决策入口（2026-11-03）：软回车键与注入的回车键
     * 都走这里，不再经 processKey 的状态键旧路径。字段属性在
     * 分发时点由 EditorInfo 映射（多行／不要换行标志／动作），
     * 组字中由实现方在串行 job 执行时点现读原始输入判定。
     * 缺省空实现（非 Rime 引擎无此通道时不崩溃）。
     */
    fun processEnter(
        service: InputMethodService,
        field: EnterField,
        isLongPress: Boolean,
        hasDedicatedNewlineKey: Boolean,
    ) {}
    fun selectCandidate(candidate: EngineMessage.Candidate)
    /** 分页补取下一页候选（首屏只取少量，UI 滚动到底时请求）；无更多时应为空操作 */
    fun loadMoreCandidates() {}
    /**
     * 候选翻页（WO52 1.1 双模式）：滑动模式向前走批量追加、向后空操作
     * （网格自行上滚）；native 模式走 changeCandidatePage；浮动候选窗
     * 激活时强制 native。缺省空实现（非 Rime 引擎无此通道时不崩溃）。
     */
    fun pageCandidates(backward: Boolean) {}
    /**
     * 候选高亮（WO52 1.1）：下标口径按模式分派（见 CandidatePagingPolicy
     * .highlightOnCurrentPage：native 模式页内下标、滑动模式全局下标）。
     * 缺省空实现。
     */
    fun highlightCandidate(index: Int) {}
    /**
     * 浮动候选窗是否激活（WO52 1.4）：激活时 pageCandidates 强制走
     * native 分页。缺省 false（无浮动窗的引擎恒走开关模式）。
     */
    fun isFloatingCandidateActive(): Boolean = false
    suspend fun schemasList(): List<EngineMessage.Schema>
    fun clear(service: InputMethodService)
    fun resetComposition()
    /** 移动光标：direction < 0 左移，> 0 右移 */
    fun moveCursor(service: InputMethodService, direction: Int)
    fun selectSchema(schemaId: String)
    fun selectCandidatePinYin(pinYin: CandidatePinYin)
    fun segement()
    fun undo(service: InputMethodService)
    fun redo(service: InputMethodService)
    fun commit(text: String)
    /**
     * 向前删除（deleteForward 动作入口）：组字中删编码右侧、
     * 否则删选区／光标右侧（表情整体），右侧无物时空操作。
     * 缺省空实现（非 Rime 引擎无此通道时不崩溃）。
     */
    fun deleteForward(service: InputMethodService) {}
    /**
     * RIME 布尔选项翻转（rimeOption 动作入口）：引擎侧异步
     * 读现值后取反写回；三态以上选项不适用；不持久化。
     * 缺省空实现。
     */
    fun toggleRuntimeOption(option: String) {}
    /**
     * 关闭联想（sendKeyEvent 前置）：取消在途联想、清联想
     * 可见态并以空候选帧通知 UI 收起；不碰组字与文档文本。
     * 缺省空实现。
     */
    fun dismissPredictions() {}
    /**
     * 成对提交（2026-10-28 截图 6 项）：提交 pair（如 "()"），
     * 落屏后光标回退到两符号之间。默认实现退化为普通提交
     * （成对文本照常上屏，仅光标留在闭符号之后，不丢字）；
     * RimeEngine 覆写为有序成对路径。
     */
    fun commitPair(pair: String, opener: String) {
        commit(pair)
    }
    fun resortCandidates(candidates: List<EngineMessage.Candidate>): Unit?
    fun deleteCandidate(index: Int): Unit?
    fun predict(commit: String = "")
    fun reload()
    fun onStartInputView(ic: InputConnection, info: EditorInfo, restarting: Boolean = false)
    fun onFinishInputView()
    fun onInputCleared()
    /** 光标/选区变化：与光标位置相关的缓存（如光标前文本）应失效 */
    fun onSelectionChanged()
    fun observeMessages(scope: CoroutineScope, onMessage: suspend (EngineMessage) -> Unit): Job
}
