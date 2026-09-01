package com.ninthsoft.ime.input

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

    private fun recordHistory() {
        val snap = Snapshot(editable.toString(), cursor)
        if (undoStack.lastOrNull() == snap) return
        undoStack.addLast(snap)
        while (undoStack.size > 200) undoStack.removeFirst()
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
        restoreSnapshot(undoStack.removeLast())
        return true
    }

    fun redo(): Boolean {
        if (redoStack.isEmpty()) return false
        undoStack.addLast(Snapshot(editable.toString(), cursor))
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

    private fun selStart(): Int {
        val s = Selection.getSelectionStart(editable)
        return if (s < 0) editable.length else s
    }

    private fun selEnd(): Int {
        val e = Selection.getSelectionEnd(editable)
        return if (e < 0) editable.length else e
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
        recordHistory()
        val s = selStart()
        val e = selEnd()
        val start = max(0, s - inLength)
        val end = min(editable.length, e + outLength)
        editable.removeSpan(composingSpan)
        editable.delete(start, end)
        Selection.setSelection(editable, start.coerceAtMost(editable.length))
        selectionAnchor = null
        selectionCursor = null
        fireChange()
        return true
    }

    override fun deleteSurroundingTextInCodePoints(
        inLength: Int,
        outLength: Int,
    ): Boolean = deleteSurroundingText(inLength, outLength)

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        recordHistory()
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
                (cursor - 1).coerceAtLeast(0)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!extending && selStart() != selEnd()) {
                selEnd()
            } else {
                (cursor + 1).coerceAtMost(editable.length)
            }
            KeyEvent.KEYCODE_MOVE_HOME -> 0
            KeyEvent.KEYCODE_MOVE_END -> editable.length
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> moveVertically(
                cursor, event.keyCode == KeyEvent.KEYCODE_DPAD_UP
            )
            KeyEvent.KEYCODE_DEL -> {
                deleteSurroundingText(1, 0)
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
        return (targetStart + column).coerceAtMost(targetEnd)
    }
}
