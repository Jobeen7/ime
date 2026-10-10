package com.jobeen.ime.base.speech

import com.jobeen.ime.base.util.InputFieldPolicy
import com.jobeen.ime.data.manager.CandidateManager
import com.jobeen.ime.input.ImeInputMethodService
import timber.log.Timber

/**
 * 语音纠错沉淀会话的纯逻辑部分（无 Android 依赖，可单测）：
 * 段定位、武装重试/过期判定、编辑下的锚点维护，以及段内编辑
 * 区间向原文坐标的回映射。
 */
object VoiceCorrectionSessionLogic {

    /**
     * 一次段内编辑在「当时的演化段」相对坐标下的记录：[start] 起的
     * [oldLength] 个字被替换成了 [newLength] 个字。配对时要把历次
     * 编辑区间逐一回映射到上屏原文坐标（见 [originalEditRanges]）。
     */
    data class SegmentEdit(
        val start: Int,
        val oldLength: Int,
        val newLength: Int,
    )

    /** 锚点维护结果：段在新窗口中的起始位置与演化快照。 */
    data class Anchor(
        val segmentStart: Int,
        val segmentText: String,
    )

    /**
     * 定位刚提交的段：常态下光标正落在段尾（窗口前文以段结尾）；
     * 否则退而求其次在前文里找最后一次出现。都找不到返回 null。
     */
    fun locateSegment(windowText: String, cursorPos: Int, committedText: String): Int? {
        val before = windowText.substring(0, cursorPos)
        if (before.endsWith(committedText)) {
            return cursorPos - committedText.length
        }
        val idx = before.lastIndexOf(committedText)
        return if (idx >= 0) idx else null
    }

    /** 上屏记录是否还在补武装重试窗口内。 */
    fun canRetryArm(commitAt: Long, now: Long, retryMs: Long): Boolean =
        now - commitAt <= retryMs

    /** 配对窗口是否已过期。 */
    fun isExpired(armedAt: Long, now: Long, expireMs: Long): Boolean =
        now - armedAt > expireMs

    /**
     * 一次窗口文本变更下维护段锚点：
     * - 段前编辑：段整体平移；
     * - 段后编辑：段不受影响；
     * - 段内编辑：只更新演化快照（配对用的原文不动）；
     * - 跨界变更：能在新窗口里原样找到段就重新锚定，否则返回 null
     *   表示放弃跟踪。
     */
    fun updateAnchor(
        segmentStart: Int,
        segmentText: String,
        change: VoiceCorrectionLearner.Change,
        newWindowText: String,
    ): Anchor? {
        val segEnd = segmentStart + segmentText.length
        val changeEnd = change.start + change.oldText.length
        return when {
            changeEnd <= segmentStart -> Anchor(
                segmentStart + change.newText.length - change.oldText.length,
                segmentText,
            )
            change.start >= segEnd -> Anchor(segmentStart, segmentText)
            change.start >= segmentStart && changeEnd <= segEnd -> {
                val rel = change.start - segmentStart
                Anchor(
                    segmentStart,
                    segmentText.replaceRange(
                        rel, rel + change.oldText.length, change.newText
                    ),
                )
            }
            else -> {
                val idx = newWindowText.indexOf(segmentText)
                if (idx >= 0) Anchor(idx, segmentText) else null
            }
        }
    }

    /**
     * 判断一次变更是否构成对段内原文的编辑（只有编辑才产生配对位置
     * 信号）。纯插入（oldText 为空）一律不算：在段边界处接着打字是
     * 续写新内容，不是修改本段——旧实现把段尾纯插入也判为段内编辑，
     * 用户在语音句后打一个新词就会与段尾按位置配对误学成词对
     * （如句尾「开会」后打「不会」被学成 开会→不会）。插入仍会经
     * updateAnchor 更新演化快照的位置跟踪，只是不产生学习信号。
     */
    fun isSegmentInternalChange(
        segmentStart: Int,
        segmentText: String,
        change: VoiceCorrectionLearner.Change,
    ): Boolean {
        if (change.oldText.isEmpty()) return false
        val segEnd = segmentStart + segmentText.length
        val changeEnd = change.start + change.oldText.length
        return change.start >= segmentStart && changeEnd <= segEnd
    }

    /**
     * 把一个点从「某次编辑之后」的演化坐标回映射到原文坐标：
     * 逆序撤销 [priorEdits] 的每一次替换。点恰落在某次新文本内部
     * 时没有原文对应位置，近似归到该次编辑的起点。
     */
    fun mapPointToOriginal(point: Int, priorEdits: List<SegmentEdit>): Int {
        var p = point
        for (edit in priorEdits.asReversed()) {
            p = when {
                p < edit.start -> p
                p >= edit.start + edit.newLength -> p - edit.newLength + edit.oldLength
                else -> edit.start
            }
        }
        return p
    }

    /**
     * 历次段内编辑各自在原文坐标下的变更区间。第 i 次编辑发生时
     * 的坐标系已经历前 i-1 次编辑，用 [mapPointToOriginal] 按其
     * 之前的编辑逐一回映射起止点得到。
     */
    fun originalEditRanges(edits: List<SegmentEdit>): List<VoiceCorrectionLearner.EditRange> =
        edits.mapIndexed { i, edit ->
            val prior = edits.subList(0, i)
            val start = mapPointToOriginal(edit.start, prior)
            val end = mapPointToOriginal(edit.start + edit.oldLength, prior)
            VoiceCorrectionLearner.EditRange(start, maxOf(end, start))
        }
}

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
 * 配对还须有位置信号：只有与段内实际编辑区间（回映射到原文
 * 坐标）重叠或紧邻的原文片段才参与配对，段里其他位置长得再像
 * 也不是用户改的；学成一条后其原文区间即被消费，窗口内不得对
 * 同一区间重复配对。段内编辑由 [VoiceCorrectionSessionLogic]
 * 记录并回映射，跨界编辑无法定位到原文区间、保守不产生信号。
 *
 * 武装（在编辑器窗口里定位刚上屏的段）可能因编辑器尚未落账
 * 提交而失败：上屏记录保留 [ARM_RETRY_MS]，期间每次选区探针
 * 都重试定位，落账后即可补上。
 *
 * 观察借输入法服务现有的选区探针节奏（onUpdateSelection →
 * 24ms 合并）。保守原则：定位不准、配对歧义、形态不合规一律
 * 不学。只跟踪真实编辑器（常用语桥接模式不武装）、非密码框；
 * 繁体输出模式下编辑器里是繁体字、与简体词表对不上，本版只
 * 纠错不沉淀。纠错总开关关闭时不武装、不沉淀。配对窗口自上屏
 * 起 5 分钟，输入视图结束即解除。
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
    ) {
        /** 历次段内编辑（演化段相对坐标，发生顺序），配对时回映射到原文坐标。 */
        val segmentEdits = ArrayList<VoiceCorrectionSessionLogic.SegmentEdit>()

        /** 已学成词对消费掉的原文区间，后续词段不得再对这些区间配对。 */
        val consumedRanges = ArrayList<VoiceCorrectionLearner.EditRange>()
    }

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
            if (!store.enabled) return
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
        if (!VoiceCorrectionSessionLogic.canRetryArm(
                record.at, System.currentTimeMillis(), ARM_RETRY_MS
            )
        ) {
            lastCommit = null
            return
        }
        if (!record.store.enabled) {
            lastCommit = null
            return
        }
        if (service.phraseAddBridgeActive) return
        val window = readWindow(service) ?: return
        val segStart = VoiceCorrectionSessionLogic.locateSegment(
            window.text, window.cursorPos, record.text
        ) ?: return
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
            if (VoiceCorrectionSessionLogic.isExpired(
                    p.armedAt, System.currentTimeMillis(), EXPIRE_MS
                ) || !p.store.enabled || service.phraseAddBridgeActive
            ) {
                pending = null
                return
            }
            val window = readWindow(service) ?: return
            val newWindow = window.text
            if (newWindow == p.windowText) return
            val change = VoiceCorrectionLearner.extractChange(p.windowText, newWindow) ?: return
            // 段内编辑先记下位置信号（相对旧锚点的演化坐标），再维护锚点
            val internal = VoiceCorrectionSessionLogic.isSegmentInternalChange(
                p.segmentStart, p.segmentText, change
            )
            val anchor = VoiceCorrectionSessionLogic.updateAnchor(
                p.segmentStart, p.segmentText, change, newWindow
            )
            if (anchor == null) {
                pending = null
                return
            }
            if (internal) {
                p.segmentEdits += VoiceCorrectionSessionLogic.SegmentEdit(
                    start = change.start - p.segmentStart,
                    oldLength = change.oldText.length,
                    newLength = change.newText.length,
                )
            }
            p.segmentStart = anchor.segmentStart
            p.segmentText = anchor.segmentText
            p.windowText = newWindow
        }
    }

    /**
     * 打字上屏词段回调（与搭配学习同一入口，仅打字路径）：词段与
     * 上屏原文配对——用户删错词、打正形时，错形只在原文里还在，
     * 对着演化快照永远配不上。配对只在段内实际编辑区间（回映射
     * 到原文坐标）附近进行，且已消费区间不再参与。命中则按
     * [VoiceCorrectionLearner] 的裁定学入词对或停用被否决的纠正，
     * 并立即消费命中的原文区间。
     */
    fun onTypedWord(word: String) {
        synchronized(lock) {
            val p = pending ?: return
            if (VoiceCorrectionSessionLogic.isExpired(
                    p.armedAt, System.currentTimeMillis(), EXPIRE_MS
                ) || !p.store.enabled
            ) {
                pending = null
                return
            }
            val editRanges = VoiceCorrectionSessionLogic.originalEditRanges(p.segmentEdits)
            if (editRanges.isEmpty()) return // 没有观察到段内编辑：无位置信号，不配对
            val pairing = VoiceCorrectionLearner.decidePairing(
                p.originalText, p.originalApplied, word,
                editRanges = editRanges,
                consumedRanges = p.consumedRanges,
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
            // 学成（或停用裁定）后立即消费该原文区间，窗口内不再重复配对
            p.consumedRanges += pairing.spanRange
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
        // 优先一次 getExtractedText 拿全文+选区（1 次 IPC）：此前
        // before/after 两次取文本、再叠加服务层自己的 1 字探针，
        // 武装期内每次输入变化要跨进程往返 3–4 次。部分编辑器不
        // 实现 getExtractedText（返回 null），回退原两次取文本。
        val extracted = runCatching {
            ic.getExtractedText(
                android.view.inputmethod.ExtractedTextRequest().apply {
                    hintMaxChars = WINDOW_BEFORE + WINDOW_AFTER
                    hintMaxLines = -1
                },
                0,
            )
        }.getOrNull()
        val extractedText = extracted?.text?.toString()
        if (extracted != null && extractedText != null) {
            val cursor = extracted.selectionStart.coerceIn(0, extractedText.length)
            val start = (cursor - WINDOW_BEFORE).coerceAtLeast(0)
            val end = (cursor + WINDOW_AFTER).coerceAtMost(extractedText.length)
            return Window(extractedText.substring(start, end), cursor - start)
        }
        val before = ic.getTextBeforeCursor(WINDOW_BEFORE, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(WINDOW_AFTER, 0)?.toString().orEmpty()
        return Window(before + after, before.length)
    }
}
