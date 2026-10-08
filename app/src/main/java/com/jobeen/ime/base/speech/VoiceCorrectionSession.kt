package com.jobeen.ime.base.speech

import com.jobeen.ime.base.util.InputFieldPolicy
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.input.ImeInputMethodService
import timber.log.Timber

/**
 * 语音纠错沉淀的会话跟踪：一次语音文本上屏后武装；用户此间打字
 * 上屏的词段与「上屏原文」配对，学成（错形 → 正形）词对供下次
 * 语音定稿自动纠正。
 *
 * 配对必须对着上屏原文（不可变快照），不能对着随编辑演化的段
 * 快照：用户修正专名的常态动作是先删错词、再打正形——删词那一
 * 步就会把错形从演化快照里抹掉，等正形打出时已无从配对（首版
 * 即栽于此，沉淀永远学不到）。演化快照只用于在编辑器窗口里维持
 * 段的锚点（段还在不在、大致在哪），不参与配对。
 *
 * 武装（在编辑器窗口里定位刚上屏的段）可能因编辑器尚未落账
 * 提交而失败：上屏记录保留 [ARM_RETRY_MS]，期间每次选区探针
 * 都重试定位，落账后即可补上。
 *
 * 观察借输入法服务现有的选区探针节奏（onUpdateSelection →
 * 24ms 合并）。保守原则：定位不准、配对歧义、形态不合规一律
 * 不学。只跟踪真实编辑器（常用语桥接模式不武装）、非密码框；
 * 繁体输出模式下编辑器里是繁体字、与简体词表对不上，本版只
 * 纠错不沉淀。配对窗口自上屏起 5 分钟，输入视图结束即解除。
 */
object VoiceCorrectionSession {

    private const val WINDOW_BEFORE = 400
    private const val WINDOW_AFTER = 100
    private const val EXPIRE_MS = 5 * 60 * 1000L
    private const val ARM_RETRY_MS = 30 * 1000L

    private val lock = Any()

    /** 刚上屏的记录：武装成功前保留，供探针重试定位。 */
    private class CommitRecord(
        val store: VoiceCorrectionStore,
        val text: String,
        val applied: List<AppliedCorrection>,
        val at: Long,
    )

    private class Pending(
        val store: VoiceCorrectionStore,
        var windowText: String,
        var segmentStart: Int,
        /** 演化快照：随编辑同步，仅用于锚定，不参与配对。 */
        var segmentText: String,
        /** 上屏原文：不可变，配对与自动纠正坐标都以它为准。 */
        val originalText: String,
        val originalApplied: List<AppliedCorrection>,
        val armedAt: Long,
    )

    @Volatile
    private var lastCommit: CommitRecord? = null

    @Volatile
    private var pending: Pending? = null

    /**
     * 语音定稿上屏后调用。[committedText] 是纠错后的简体文本（编辑器
     * 里显示的可能是其繁体形态，故繁体模式直接不留记录）。
     */
    fun arm(
        service: ImeInputMethodService,
        store: VoiceCorrectionStore,
        committedText: String,
        applied: List<AppliedCorrection>,
    ) {
        synchronized(lock) {
            pending = null
            lastCommit = null
            if (committedText.isBlank()) return
            if (service.phraseAddBridgeActive) return
            if (InputFieldPolicy.isPasswordField(service.currentInputEditorInfo)) return
            if (CandidateManager.isTraditionalChineseEnabled(service)) return
            lastCommit = CommitRecord(store, committedText, applied, System.currentTimeMillis())
            tryArmLocked(service)
        }
    }

    /** 尝试把上屏记录定位成跟踪段；编辑器还没落账时留待探针重试。 */
    private fun tryArmLocked(service: ImeInputMethodService) {
        val record = lastCommit ?: return
        if (System.currentTimeMillis() - record.at > ARM_RETRY_MS) {
            lastCommit = null
            return
        }
        if (service.phraseAddBridgeActive) return
        val window = readWindow(service) ?: return
        val segStart = locateSegment(window, record.text) ?: return
        pending = Pending(
            store = record.store,
            windowText = window.text,
            segmentStart = segStart,
            segmentText = record.text,
            originalText = record.text,
            originalApplied = record.applied,
            armedAt = System.currentTimeMillis(),
        )
        lastCommit = null
        Timber.d(
            "VoiceCorrect arm seg at %d len %d applied %d",
            segStart, record.text.length, record.applied.size
        )
    }

    /** 输入法服务的探针节奏回调：重试武装，并维护段的锚点快照。 */
    fun onInputChanged(service: ImeInputMethodService) {
        synchronized(lock) {
            if (pending == null) {
                tryArmLocked(service)
                return
            }
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
                    // 段内编辑：只更新锚定快照，配对用的原文不动
                    val rel = change.start - segStart
                    p.segmentText = p.segmentText.replaceRange(
                        rel, rel + change.oldText.length, change.newText
                    )
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
     * 打字上屏词段回调（与搭配学习同一入口，仅打字路径）：词段与
     * 上屏原文配对——用户删错词、打正形时，错形只在原文里还在，
     * 对着演化快照永远配不上。命中则按 [VoiceCorrectionLearner]
     * 的裁定学入词对或停用被否决的纠正。
     */
    fun onTypedWord(word: String) {
        synchronized(lock) {
            val p = pending ?: return
            if (System.currentTimeMillis() - p.armedAt > EXPIRE_MS) {
                pending = null
                return
            }
            val pairing = VoiceCorrectionLearner.decidePairing(
                p.originalText, p.originalApplied, word
            ) ?: return
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
        }
    }

    fun disarm() {
        synchronized(lock) {
            pending = null
            lastCommit = null
        }
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
