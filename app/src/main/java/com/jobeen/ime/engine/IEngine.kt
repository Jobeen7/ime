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

    /**
     * 向前删除（定稿四.4）：组字中无操作；非组字时删选区或
     * 光标后一个字素簇。绝不经按键通道发 Delete、绝不回退退格。
     */
    fun forwardDelete(service: InputMethodService)
    fun commit(text: String)
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
