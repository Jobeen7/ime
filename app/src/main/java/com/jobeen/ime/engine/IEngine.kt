package com.jobeen.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.jobeen.ime.engine.data.CandidatePinYin
import com.jobeen.ime.engine.data.EngineMessage
import com.jobeen.ime.engine.event.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface IEngine {
    fun initialize(context: Context)
    fun finalize()
    fun processKey(service: InputMethodService, key: KeyEvent): Unit?
    fun selectCandidate(candidate: EngineMessage.Candidate)
    /** 分页补取下一页候选（首屏只取少量，UI 滚动到底时请求）；无更多时应为空操作 */
    fun loadMoreCandidates() {}
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
