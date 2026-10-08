package com.jobeen.ime.base.speech

import com.jobeen.ime.base.util.InputFieldPolicy
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.input.ImeInputMethodService
import timber.log.Timber

/**
 * 语音纠错沉淀的会话跟踪：一次语音文本上屏后武装，跟踪这段文本在
 * 编辑器里的当前位置与内容；用户此间打字上屏的词段经
 * [VoiceCorrectionLearner.decidePairing] 与段内片段配对，学成
 * （错形 → 正形）词对供下次语音定稿自动纠正。
 *
 * 观察借输入法服务现有的选区探针节奏（onUpdateSelection → 24ms
 * 合并），只做窗口文本快照与段位置维护，不解读编辑本身——教学
 * 信号只认「亲手打出的词段」，改述、删改等编辑不会被误学。
 *
 * 保守原则：定位不准、配对歧义、形态不合规一律不学。只跟踪真实
 * 编辑器（常用语桥接模式不武装）、非密码框；繁体输出模式下编辑器
 * 里是繁体字、与简体词表对不上，本版只纠错不沉淀。观察自上屏起
 * 5 分钟失效，输入视图结束即解除。
 */
object VoiceCorrectionSession {

    private const val WINDOW_BEFORE = 400
    private const val WINDOW_AFTER = 100
    private const val EXPIRE_MS = 5 * 60 * 1000L

    private val lock = Any()

    private class Pending(
        val store: VoiceCorrectionStore,
        var windowText: String,
        var segmentStart: Int,
        var segmentText: String,
        var applied: List<AppliedCorrection>,
        val armedAt: Long,
    )

    @Volatile
    private var pending: Pending? = null

    /**
     * 语音定稿上屏后调用。[committedText] 是纠错后的简体文本（编辑器
     * 里显示的可能是其繁体形态，故繁体模式直接不武装）。
     */
    fun arm(
        service: ImeInputMethodService,
        store: VoiceCorrectionStore,
        committedText: String,
        applied: List<AppliedCorrection>,
    ) {
        synchronized(lock) {
            pending = null
            if (committedText.isBlank()) return
            if (service.phraseAddBridgeActive) return
            if (InputFieldPolicy.isPasswordField(service.currentInputEditorInfo)) return
            if (CandidateManager.isTraditionalChineseEnabled(service)) return
            val window = readWindow(service) ?: return
            val segStart = locateSegment(window, committedText) ?: return
            pending = Pending(
                store = store,
                windowText = window.text,
                segmentStart = segStart,
                segmentText = committedText,
                applied = applied,
                armedAt = System.currentTimeMillis(),
            )
            Timber.d(
                "VoiceCorrect arm seg at %d len %d applied %d",
                segStart, committedText.length, applied.size
            )
        }
    }

    /** 输入法服务的探针节奏回调：维护段的位置与内容快照。 */
    fun onInputChanged(service: ImeInputMethodService) {
        synchronized(lock) {
            val p = pending ?: return
            if (System.currentTimeMillis() - p.armedAt > EXPIRE_MS || service.phraseAddBridgeActive) {
                pending = null
                return
            }
            val window = readWindow(service) ?: return
            val newWindow = window.text
            if (newWindow == p.windowText) return
            val change = VoiceCorrectionLearner.extractChange(p.windowText, newWindow) ?: return
            val segStart = p.segmentStart
            val segEnd = segStart + p.segmentText.length
            val changeEnd = change.start + change.oldText.length
            when {
                changeEnd <= segStart -> {
                    // 段前编辑：段整体平移
                    val delta = change.newText.length - change.oldText.length
                    p.segmentStart = segStart + delta
                    p.windowText = newWindow
                }
                change.start >= segEnd -> {
                    // 段后编辑：段不受影响
                    p.windowText = newWindow
                }
                change.start >= segStart && changeEnd <= segEnd -> {
                    // 段内编辑：更新段内容快照与纠正记录坐标
                    val rel = change.start - segStart
                    p.segmentText = p.segmentText.replaceRange(
                        rel, rel + change.oldText.length, change.newText
                    )
                    val delta = change.newText.length - change.oldText.length
                    p.applied = p.applied.mapNotNull { a ->
                        val aEnd = a.start + a.rightForm.length
                        when {
                            aEnd <= rel -> a
                            a.start >= rel + change.oldText.length -> a.copy(start = a.start + delta)
                            else -> null
                        }
                    }
                    p.windowText = newWindow
                }
                else -> {
                    // 跨界变更：能在新窗口里原样找到段就重新锚定，否则放弃
                    val idx = newWindow.indexOf(p.segmentText)
                    if (idx >= 0) {
                        p.windowText = newWindow
                        p.segmentStart = idx
                    } else {
                        pending = null
                    }
                }
            }
        }
    }

    /**
     * 打字上屏词段回调（与搭配学习同一入口，仅打字路径）：把词段与
     * 跟踪中的语音段配对，命中则按 [VoiceCorrectionLearner] 的裁定
     * 学入词对或停用被否决的纠正，并同步段快照。
     */
    fun onTypedWord(word: String) {
        synchronized(lock) {
            val p = pending ?: return
            if (System.currentTimeMillis() - p.armedAt > EXPIRE_MS) {
                pending = null
                return
            }
            val pairing = VoiceCorrectionLearner.decidePairing(p.segmentText, p.applied, word)
                ?: return
            when (val action = pairing.action) {
                is VoiceCorrectionLearner.PairingAction.Learn -> {
                    p.store.learn(action.wrongForm, action.rightForm)
                    Timber.d("VoiceCorrect learn %s -> %s", action.wrongForm, action.rightForm)
                }
                is VoiceCorrectionLearner.PairingAction.Disable -> {
                    p.store.disable(action.rightForm)
                    Timber.d("VoiceCorrect disable %s", action.rightForm)
                }
                is VoiceCorrectionLearner.PairingAction.LearnAndDisable -> {
                    p.store.learn(action.wrongForm, action.rightForm)
                    p.store.disable(action.disableRightForm)
                    Timber.d(
                        "VoiceCorrect learn %s -> %s, disable %s",
                        action.wrongForm, action.rightForm, action.disableRightForm
                    )
                }
            }
            // 账面同步：该跨度已变成他打出的正形；命中的纠正记录失效
            p.segmentText = p.segmentText.replaceRange(
                pairing.start, pairing.start + pairing.span.length, word
            )
            p.applied = p.applied.filterNot {
                it.start == pairing.start && it.rightForm == pairing.span
            }
        }
    }

    fun disarm() {
        synchronized(lock) { pending = null }
    }

    private class Window(val text: String, val cursorPos: Int)

    private fun readWindow(service: ImeInputMethodService): Window? {
        val ic = service.currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(WINDOW_BEFORE, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(WINDOW_AFTER, 0)?.toString().orEmpty()
        return Window(before + after, before.length)
    }

    /**
     * 定位刚提交的段：常态下光标正落在段尾（窗口前文以段结尾）；
     * 否则退而求其次在前文里找最后一次出现。都找不到不武装。
     */
    private fun locateSegment(window: Window, committedText: String): Int? {
        val before = window.text.substring(0, window.cursorPos)
        if (before.endsWith(committedText)) {
            return window.cursorPos - committedText.length
        }
        val idx = before.lastIndexOf(committedText)
        return if (idx >= 0) idx else null
    }
}
