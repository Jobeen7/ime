// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.jobeen.ime.input.ClearedTextLedger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 清空字段头失效：清空入口只探前文，光标在字段头时后文一字不删。
 *
 * 测的是抽出来的清文函数（RimeEngine.clearCommittedText，伴生对象
 * 内函数）与入口三探针／组字分流判定，跑在可变字段桩
 * [FieldFakeInputConnection] 上，不依赖真机。协程／分发
 * （clearInternal 内的 sendJob → actions.send）与前文缓存失效
 * 不在单测内，走编译＋回归测试。
 *
 * 桩按平台 deleteSurroundingText 语义实现：删
 * [起点-前长, 终点+后长] 整段（含选区）；commitText 替换选区。
 */
class ClearCommittedTextTest {

    @After
    fun tearDown() {
        ClearedTextLedger.clearAll()
    }

    // ---- 字段头：后文删净（P2 核心回归） ----

    @Test
    fun fieldHeadWithTextAfter_deletesAfter() {
        val ic = FieldFakeInputConnection("abc", cursor = 0)
        assertTrue(RimeEngine.hasClearableText(ic))
        assertTrue(RimeEngine.clearCommittedText(ic))
        assertEquals("", ic.fieldText())
        assertEquals(1, ClearedTextLedger.size())
        assertEquals("abc" to "", ClearedTextLedger.peek())
        // 第一轮探到前文为空即停（无删除调用），只第二轮删后文
        assertEquals(1, ic.deleteCalls)
    }

    // ---- 字段中间：两侧都删 ----

    @Test
    fun middleCursor_deletesBothSides() {
        val ic = FieldFakeInputConnection("abcdef", cursor = 3)
        assertTrue(RimeEngine.clearCommittedText(ic))
        assertEquals("", ic.fieldText())
        assertEquals("abcdef" to "", ClearedTextLedger.peek())
        assertEquals(2, ic.deleteCalls)
    }

    // ---- 字段末尾：只删前文（回归现有行为） ----

    @Test
    fun fieldEnd_deletesBeforeOnly() {
        val ic = FieldFakeInputConnection("abc", cursor = 3)
        assertTrue(RimeEngine.clearCommittedText(ic))
        assertEquals("", ic.fieldText())
        assertEquals("abc" to "", ClearedTextLedger.peek())
        assertEquals(1, ic.deleteCalls)
    }

    // ---- 字段头有选区／全选：选区被删 ----

    @Test
    fun fieldHeadFullSelection_deletesSelection() {
        val ic = FieldFakeInputConnection("abcdef", cursor = 0, selectionEnd = 6)
        // 前文与后文都为空：只靠选区探针进入口
        assertTrue(RimeEngine.hasClearableText(ic))
        assertTrue(RimeEngine.clearCommittedText(ic))
        assertEquals("", ic.fieldText())
        assertEquals(1, ic.commitCalls)
        assertEquals("abcdef" to "", ClearedTextLedger.peek())
    }

    @Test
    fun fieldHeadPartialSelection_deletesSelectionAndAfter() {
        val ic = FieldFakeInputConnection("abcdef", cursor = 0, selectionEnd = 2)
        assertTrue(RimeEngine.clearCommittedText(ic))
        assertEquals("", ic.fieldText())
        assertEquals("abcdef" to "", ClearedTextLedger.peek())
    }

    @Test
    fun middleSelection_deletesSelectionAndSides() {
        val ic = FieldFakeInputConnection("abcdef", cursor = 2, selectionEnd = 4)
        assertTrue(RimeEngine.clearCommittedText(ic))
        assertEquals("", ic.fieldText())
        assertEquals("abcdef" to "", ClearedTextLedger.peek())
    }

    // ---- 空字段：无操作、无入账 ----

    @Test
    fun emptyField_isNoop() {
        val ic = FieldFakeInputConnection("", cursor = 0)
        assertFalse(RimeEngine.hasClearableText(ic))
        assertFalse(RimeEngine.clearCommittedText(ic))
        assertEquals(0, ic.deleteCalls)
        assertEquals(0, ic.commitCalls)
        assertEquals(0, ClearedTextLedger.size())
    }

    // ---- 组字中：走 Action.Reset 分支（分流判定） ----

    @Test
    fun composing_goesToReset() {
        assertTrue(RimeEngine.shouldResetComposing("ni"))
        assertFalse(RimeEngine.shouldResetComposing(""))
        assertFalse(RimeEngine.shouldResetComposing(null))
    }

    // ---- deleteSurroundingText 中途返回 false：停在该处、账本只记已删部分 ----

    @Test
    fun deleteFailsMidway_stopsAndRecordsOnlyDeleted() {
        val ic = FieldFakeInputConnection(
            "x".repeat(2500), cursor = 2500, failDeleteFromCall = 2,
        )
        assertTrue(RimeEngine.clearCommittedText(ic))
        // 第一轮第一段（末尾 1000 字）已删，第二段删除失败即停
        assertEquals("x".repeat(1500), ic.fieldText())
        assertEquals(2, ic.deleteCalls)
        assertEquals(1, ClearedTextLedger.size())
        assertEquals("x".repeat(1000), ClearedTextLedger.peek()?.first)
    }
}

/**
 * 可变字段桩（平台语义）：deleteSurroundingText 删
 * [起点-前长, 终点+后长] 整段（含选区），删后光标落在删除起点；
 * commitText 以给定文本替换选区。其余方法返回固定默认值，
 * 不触碰 Android 框架实现。
 */
private class FieldFakeInputConnection(
    initialText: String,
    cursor: Int,
    selectionEnd: Int = cursor,
    private val failDeleteFromCall: Int = Int.MAX_VALUE,
) : InputConnection {
    val text = StringBuilder(initialText)
    var selStart: Int = cursor
        private set
    var selEnd: Int = selectionEnd
        private set
    var deleteCalls = 0
        private set
    var commitCalls = 0
        private set

    fun fieldText(): String = text.toString()

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        text.substring(maxOf(0, selStart - n), selStart)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        text.substring(selEnd, minOf(text.length, selEnd + n))

    override fun getSelectedText(flags: Int): CharSequence? {
        if (selStart == selEnd) return ""
        return text.substring(minOf(selStart, selEnd), maxOf(selStart, selEnd))
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        deleteCalls++
        if (deleteCalls >= failDeleteFromCall) return false
        if (beforeLength < 0 || afterLength < 0) return false
        val start = maxOf(0, selStart - beforeLength)
        val end = minOf(text.length, selEnd + afterLength)
        if (start >= end) return true
        text.delete(start, end)
        selStart = start
        selEnd = start
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        commitCalls++
        val s = minOf(selStart, selEnd)
        val e = maxOf(selStart, selEnd)
        this.text.replace(s, e, text.toString())
        selStart = s + text.length
        selEnd = selStart
        return true
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun beginBatchEdit(): Boolean = true
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun closeConnection() = Unit
    override fun commitCompletion(text: CompletionInfo): Boolean = false
    override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
    override fun commitCorrection(info: CorrectionInfo): Boolean = false
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
    override fun endBatchEdit(): Boolean = true
    override fun finishComposingText(): Boolean = false
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getHandler(): Handler? = null
    override fun performContextMenuAction(id: Int): Boolean = false
    override fun performEditorAction(editorAction: Int): Boolean = false
    override fun performPrivateCommand(action: String, data: Bundle): Boolean = false
    override fun reportFullscreenMode(enabled: Boolean): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun sendKeyEvent(event: KeyEvent): Boolean = false
    override fun setComposingRegion(start: Int, end: Int): Boolean = false
    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean = false
    override fun setSelection(start: Int, end: Int): Boolean = false
}
