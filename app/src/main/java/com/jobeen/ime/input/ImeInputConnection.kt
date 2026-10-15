package com.jobeen.ime.input

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.Spanned
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.collections.ArrayDeque

/**
 * 桥接模式下使用的虚拟输入连接：所有提交/删除/语音输出都写入内部 Editable，
 * 而不是直接提交到真实编辑器。AddPhraseLayerView 读取 [text]/[cursor] 进行绘制。
 */
class ImeInputConnection(private val context: Context? = null) : InputConnection {
    companion object {
        const val MAX_LENGTH = 1000

        /** undo 快照总字符上限（与条数上限双钳，防快照全文拷贝无界累积） */
        const val MAX_UNDO_TOTAL_CHARS = 200_000

        /**
         * 码点数 → UTF-16 单元数换算（纯函数）：以 [selStart]/[selEnd]
         * 为原点，前方按 [beforeCp] 个码点、后方按 [afterCp] 个码点行走，
         * 返回两侧实际跨过的 UTF-16 单元数。代理对（emoji 等）一个码点
         * 占 2 个单元，与 deleteSurroundingText 的字符口径对齐。
         *
         * 先用 codePointCount 数清两端可用码点数并钳制请求量，再调
         * offsetByCodePoints 行走——后者越界会抛 IndexOutOfBounds，
         * 钳制后必然落回文本内；选区端点本身也先钳回 [0, length]。
         */
        internal fun codePointsToUtf16Counts(
            text: CharSequence,
            selStart: Int,
            selEnd: Int,
            beforeCp: Int,
            afterCp: Int,
        ): Pair<Int, Int> {
            val length = text.length
            val start = selStart.coerceIn(0, length)
            val end = selEnd.coerceIn(start, length)
            val availBefore = Character.codePointCount(text, 0, start)
            val walkBefore = beforeCp.coerceIn(0, availBefore)
            val newStart = Character.offsetByCodePoints(text, start, -walkBefore)
            val availAfter = Character.codePointCount(text, end, length)
            val walkAfter = afterCp.coerceIn(0, availAfter)
            val newEnd = Character.offsetByCodePoints(text, end, walkAfter)
            return (start - newStart) to (newEnd - end)
        }
    }

    private val editable: Editable = Editable.Factory.getInstance().newEditable("")
    private val composingSpan = Any()
    var onChange: (() -> Unit)? = null
    private val changeListeners = mutableListOf<() -> Unit>()
    private val handler = Handler(Looper.getMainLooper())

    private data class Snapshot(val text: String, val cursor: Int)
    private val undoStack = ArrayDeque<Snapshot>()
    private val redoStack = ArrayDeque<Snapshot>()
    private var selectionAnchor: Int? = null
    private var selectionCursor: Int? = null

    // undo 栈总字符数增量维护（此前每次入栈都 sumOf 全栈遍历）
    private var undoTotalChars = 0

    private fun recordHistory() {
        val snap = Snapshot(editable.toString(), cursor)
        if (undoStack.lastOrNull() == snap) return
        undoStack.addLast(snap)
        undoTotalChars += snap.text.length
        // 双上限：条数 + 总字符数（每份快照都是全文拷贝，只钳条数时
        // 总量随单份长度线性膨胀）
        while (undoStack.size > 200 || (undoTotalChars > MAX_UNDO_TOTAL_CHARS && undoStack.size > 1)) {
            undoTotalChars -= undoStack.removeFirst().text.length
        }
        redoStack.clear()
    }

    private fun restoreSnapshot(s: Snapshot) {
        editable.removeSpan(composingSpan)
        editable.replace(0, editable.length, s.text)
        Selection.setSelection(editable, s.cursor.coerceIn(0, editable.length))
        selectionAnchor = null
        selectionCursor = null
        fireChange()
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        redoStack.addLast(Snapshot(editable.toString(), cursor))
        val snap = undoStack.removeLast()
        undoTotalChars -= snap.text.length
        restoreSnapshot(snap)
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        val snap = Snapshot(editable.toString(), cursor)
        undoStack.addLast(snap)
        undoTotalChars += snap.text.length
        restoreSnapshot(redoStack.removeLast())
        return true
    }

    private fun composingRange(): Pair<Int, Int>? {
        val s = editable.getSpanStart(composingSpan)
        val e = editable.getSpanEnd(composingSpan)
        if (s < 0 || e < 0) return null
        return s to e
    }

    private fun fitInsert(selStart: Int, selEnd: Int, text: CharSequence?): CharSequence {
        val incoming = text ?: ""
        val room = MAX_LENGTH - (editable.length - (selEnd - selStart))
        return if (room <= 0) "" else incoming.subSequence(0, min(incoming.length, room))
    }

    private fun replaceAndSpan(
        text: CharSequence?,
        useComposing: Boolean,
    ) {
        val incoming = text ?: ""
        val (s, e) = composingRange() ?: (selStart() to selEnd())
        editable.removeSpan(composingSpan)
        val fitted = fitInsert(s, e, incoming)
        editable.replace(s, e, fitted)
        val spanEnd = s + fitted.length
        if (useComposing && fitted.isNotEmpty()) {
            editable.setSpan(
                composingSpan,
                s,
                spanEnd,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        val pos = (s + fitted.length).coerceIn(0, editable.length)
        Selection.setSelection(editable, pos)
        selectionAnchor = null
        selectionCursor = null
        fireChange()
    }

    val text: String
        get() = editable.toString()

    val cursor: Int
        get() {
            val s = Selection.getSelectionStart(editable)
            return if (s < 0) editable.length else s
        }

    val selection: Pair<Int, Int>
        get() = selStart() to selEnd()

    /** 当前未上屏（composing）文本区间，无则返回 null */
    val composingRange: Pair<Int, Int>?
        get() {
            val s = editable.getSpanStart(composingSpan)
            val e = editable.getSpanEnd(composingSpan)
            if (s < 0 || e <= s) return null
            return s to e
        }

    fun clear() {
        editable.removeSpan(composingSpan)
        editable.clear()
        undoStack.clear()
        redoStack.clear()
        // 计数与栈同清：不清零会跨会话（每次进入桥接编辑都调 clear）
        // 单调残留，逼近上限后 recordHistory 把撤销栈裁到只剩 1 条，
        // 多步撤销长期劣化为单步
        undoTotalChars = 0
        selectionAnchor = null
        selectionCursor = null
        fireChange()
    }

    private fun fireChange() {
        handler.post {
            onChange?.invoke()
            changeListeners.toList().forEach { it() }
        }
    }

    fun addOnChangeListener(listener: () -> Unit) {
        if (!changeListeners.contains(listener)) changeListeners += listener
    }

    fun removeOnChangeListener(listener: () -> Unit) {
        changeListeners -= listener
    }

    /**
     * 选区区间左端。Shift+方向键扩展出的反向选区里 Selection 的 start 在
     * end 之后，这里一律归一化为 min，保证 replace/delete 等按 [start, end)
     * 区间操作不越界抛 IOOBE；选区方向由 [selectionAnchor]/[selectionCursor]
     * 单独记录，不靠 start/end 的顺序表达。
     */
    private fun selStart(): Int {
        val s = Selection.getSelectionStart(editable)
        val e = Selection.getSelectionEnd(editable)
        return if (s < 0 || e < 0) editable.length else min(s, e)
    }

    /** 选区区间右端，口径见 [selStart]。 */
    private fun selEnd(): Int {
        val s = Selection.getSelectionStart(editable)
        val e = Selection.getSelectionEnd(editable)
        return if (s < 0 || e < 0) editable.length else max(s, e)
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val s = selStart()
        val start = (s - n).coerceAtLeast(0)
        return editable.subSequence(start, s)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val e = selEnd()
        val end = e + n.coerceAtMost(editable.length - e)
        return editable.subSequence(e, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val s = selStart()
        val e = selEnd()
        if (s == e) return ""
        return editable.subSequence(min(s, e), max(s, e))
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun getExtractedText(
        request: ExtractedTextRequest?,
        flags: Int,
    ): ExtractedText? = null

    override fun deleteSurroundingText(inLength: Int, outLength: Int): Boolean {
        // 系统契约里长度为负无意义：先钳为 0 与契约对齐——负值会让
        // 区间端点反转，无选区分支里 start > end 时 Editable.delete
        // 直接抛 IndexOutOfBounds
        val inLen = inLength.coerceAtLeast(0)
        val outLen = outLength.coerceAtLeast(0)
        recordHistory()
        val s = selStart()
        val e = selEnd()
        editable.removeSpan(composingSpan)
        if (s != e) {
            // 有选区时只删选区两侧的环绕文本、选区本身保留：旧实现
            // 把 [s-in, e+out) 整段删掉，deleteSurroundingText(1, 0)
            // 这类「删光标前 1 字」的调用会连选区带前面一字一起吃掉，
            // 与调用方意图不符（删选区应由 commitText("")/明确的
            // 选区删除路径完成）
            val afterStart = min(editable.length, e + outLen)
            if (afterStart > e) editable.delete(e, afterStart)
            val beforeStart = max(0, s - inLen)
            if (s > beforeStart) editable.delete(beforeStart, s)
            Selection.setSelection(
                editable,
                beforeStart.coerceAtMost(editable.length),
                (e - (s - beforeStart)).coerceAtMost(editable.length),
            )
        } else {
            val start = max(0, s - inLen)
            val end = min(editable.length, e + outLen)
            editable.delete(start, end)
            Selection.setSelection(editable, start.coerceAtMost(editable.length))
        }
        selectionAnchor = null
        selectionCursor = null
        fireChange()
        return true
    }

    override fun deleteSurroundingTextInCodePoints(
        inLength: Int,
        outLength: Int,
    ): Boolean {
        // 接口语义是码点数：先以当前选区两端为原点把码点数换算成
        // UTF-16 单元数，再委托既有 deleteSurroundingText——旧实现把
        // 码点数直接当字符数转发，选区两侧有代理对时会少删或劈字；
        // 换算后 span/组字清理与选区保留口径全部沿用既有实现不变
        val (before, after) = codePointsToUtf16Counts(
            editable, selStart(), selEnd(), inLength, outLength,
        )
        return deleteSurroundingText(before, after)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        // 组字中间态不入 undo 栈：桥接编辑时每键一次全文快照纯属浪费，
        // 撤销的粒度应该是已提交/已删除的文本（commit/delete 仍记录）
        replaceAndSpan(text, useComposing = true)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        editable.removeSpan(composingSpan)
        if (end > start) {
            editable.setSpan(
                composingSpan,
                start,
                end,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
        }
        fireChange()
        return true
    }

    override fun finishComposingText(): Boolean {
        editable.removeSpan(composingSpan)
        fireChange()
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        recordHistory()
        replaceAndSpan(text, useComposing = false)
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false

    override fun setSelection(start: Int, end: Int): Boolean {
        Selection.setSelection(
            editable,
            start.coerceIn(0, editable.length),
            end.coerceIn(0, editable.length),
        )
        selectionAnchor = null
        selectionCursor = null
        fireChange()
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean = false

    override fun performContextMenuAction(id: Int): Boolean {
        val start = min(selStart(), selEnd())
        val end = max(selStart(), selEnd())
        when (id) {
            android.R.id.selectAll -> return setSelection(0, editable.length)
            android.R.id.copy -> {
                if (start == end) return false
                clipboard()?.setPrimaryClip(ClipData.newPlainText("text", editable.subSequence(start, end)))
                return true
            }
            android.R.id.cut -> {
                if (start == end) return false
                clipboard()?.setPrimaryClip(ClipData.newPlainText("text", editable.subSequence(start, end)))
                recordHistory()
                editable.delete(start, end)
                editable.removeSpan(composingSpan)
                Selection.setSelection(editable, start)
                fireChange()
                return true
            }
            android.R.id.paste -> {
                val clip = clipboard()?.primaryClip?.getItemAt(0)?.coerceToText(context ?: return false)
                if (clip != null) {
                    recordHistory()
                    replaceAndSpan(clip, useComposing = false)
                    return true
                }
            }
        }
        return false
    }

    override fun beginBatchEdit(): Boolean = true

    override fun endBatchEdit(): Boolean = true

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.keyCode == KeyEvent.KEYCODE_Z && event.isCtrlPressed) {
            if (event.isShiftPressed) redo() else undo()
            return true
        }
        val extending = event.isShiftPressed
        val cursor = selectionCursor ?: if (extending) selEnd() else selStart()
        if (extending && selectionAnchor == null) selectionAnchor = selStart()
        val target = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!extending && selStart() != selEnd()) {
                selStart()
            } else {
                // 按码点步进：cursor ± 1 是 UTF-16 单元口径，会让光标
                // 停在代理对（emoji）中间；步长经既有换算工具按码点
                // 数出单元数（与 deleteSurroundingTextInCodePoints 同源）
                val (units, _) = codePointsToUtf16Counts(editable, cursor, cursor, 1, 0)
                (cursor - units).coerceAtLeast(0)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!extending && selStart() != selEnd()) {
                selEnd()
            } else {
                val (_, units) = codePointsToUtf16Counts(editable, cursor, cursor, 0, 1)
                (cursor + units).coerceAtMost(editable.length)
            }
            KeyEvent.KEYCODE_MOVE_HOME -> 0
            KeyEvent.KEYCODE_MOVE_END -> editable.length
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> moveVertically(
                cursor, event.keyCode == KeyEvent.KEYCODE_DPAD_UP
            )
            KeyEvent.KEYCODE_DEL -> {
                // 按码点删：deleteSurroundingText(1, 0) 只删 1 个
                // UTF-16 单元，emoji 会被劈成孤立代理项残留下来
                deleteSurroundingTextInCodePoints(1, 0)
                return true
            }
            else -> return false
        }
        if (extending) {
            Selection.setSelection(editable, selectionAnchor ?: cursor, target)
            selectionCursor = target
        } else {
            Selection.setSelection(editable, target)
            selectionAnchor = null
            selectionCursor = null
        }
        fireChange()
        return true
    }

    override fun clearMetaKeyStates(states: Int): Boolean = false

    override fun reportFullscreenMode(state: Boolean): Boolean = false

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    override fun getHandler(): Handler? = null

    override fun closeConnection() {}

    override fun commitContent(
        inputContentInfo: InputContentInfo,
        flags: Int,
        opts: Bundle?,
    ): Boolean = false

    private fun clipboard(): ClipboardManager? =
        context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    private fun moveVertically(position: Int, up: Boolean): Int {
        val lineStart = editable.toString().lastIndexOf('\n', position - 1) + 1
        val column = position - lineStart
        val targetStart = if (up) {
            if (lineStart == 0) return 0
            editable.toString().lastIndexOf('\n', lineStart - 2) + 1
        } else {
            val next = editable.toString().indexOf('\n', position)
            if (next < 0) return editable.length
            next + 1
        }
        val targetEnd = editable.toString().indexOf('\n', targetStart)
            .takeIf { it >= 0 } ?: editable.length
        // 列偏移是 UTF-16 单元口径：目标行同列前有代理对时会正好落
        // 在一对代理项中间，对齐回该码点起点再返回
        return snapToCodePointBoundary((targetStart + column).coerceAtMost(targetEnd))
    }

    /** 把 UTF-16 下标对齐到码点边界：落在代理对中间时退到该码点起点 */
    private fun snapToCodePointBoundary(index: Int): Int {
        if (index in 1 until editable.length &&
            Character.isLowSurrogate(editable[index]) &&
            Character.isHighSurrogate(editable[index - 1])
        ) {
            return index - 1
        }
        return index
    }
}
