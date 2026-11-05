// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上滑清字账本：登记口径、上限、作废语义，以及恢复前的锚点判定。
 *
 * 恢复本身分两层测：锚点判定抽成纯函数 [ClearedTextLedger.decideRestoreApply]
 * （与生产路径调用的是同一个函数）；tryRestore 的入账弹出语义用
 * 本文件内的 FakeInputConnection 测——Fake 的方法全是测试代码自身
 * 的实现，不调 Android 框架方法；真实编辑器连接的行为（部分实现
 * 在空字段上返回 null 还是空串等）仍属 device-verify，不由本文件证明。
 */
class ClearedTextLedgerTest {

    @After
    fun tearDown() {
        ClearedTextLedger.clearAll()
    }

    // ---- 登记口径 ----

    @Test
    fun record_keepsTextAndAnchorTail() {
        ClearedTextLedger.record("被清掉的文字", "删除点前文")
        assertEquals("被清掉的文字" to "删除点前文", ClearedTextLedger.peek())
        assertEquals(1, ClearedTextLedger.size())
    }

    @Test
    fun record_anchorTruncatedToAnchorChars() {
        // 锚点只留尾部 ANCHOR_CHARS 字：恢复判定是后缀判定，存全量
        // 前文只会让判定背上无关文本
        val longAnchor = "一二三四五六七八九十甲乙丙丁戊己庚辛"
        ClearedTextLedger.record("abc", longAnchor)
        val prefix = ClearedTextLedger.peek()?.second
        assertEquals(ClearedTextLedger.ANCHOR_CHARS, prefix?.length)
        assertEquals(longAnchor.takeLast(ClearedTextLedger.ANCHOR_CHARS), prefix)
    }

    @Test
    fun record_emptyTextIgnored() {
        ClearedTextLedger.record("", "前文")
        assertEquals(0, ClearedTextLedger.size())
        assertNull(ClearedTextLedger.peek())
    }

    @Test
    fun record_oversizeTextIgnored() {
        // 超长段不记（无法作为一次有意义的撤销，也不让账本常驻大字符串）
        ClearedTextLedger.record("x".repeat(4097), "前文")
        assertEquals(0, ClearedTextLedger.size())
    }

    @Test
    fun record_boundarySizeAccepted() {
        ClearedTextLedger.record("x".repeat(4096), "前文")
        assertEquals(1, ClearedTextLedger.size())
    }

    @Test
    fun record_capsAtMaxEntries() {
        repeat(15) { ClearedTextLedger.record("段$it", "前文") }
        assertEquals(10, ClearedTextLedger.size())
        // 最新一段仍在栈顶（旧段被挤出）
        assertEquals("段14", ClearedTextLedger.peek()?.first)
    }

    // ---- 作废语义 ----

    @Test
    fun invalidate_dropsAllEntries() {
        ClearedTextLedger.record("被清掉的文字", "前文")
        ClearedTextLedger.invalidate()
        assertEquals(0, ClearedTextLedger.size())
    }

    @Test
    fun clearAll_dropsAllEntries() {
        ClearedTextLedger.record("a", "前文")
        ClearedTextLedger.record("b", "前文")
        ClearedTextLedger.clearAll()
        assertEquals(0, ClearedTextLedger.size())
    }

    // ---- 恢复判定：删除后状态未被改动才接管 ----

    @Test
    fun restore_shortFieldExactPrefix_applies() {
        // 短锚点（未被截断＝删除点前全部前文）：窗口恰为锚点全长
        assertTrue(ClearedTextLedger.decideRestoreApply("你好", "你好", 3))
    }

    @Test
    fun restore_shortFieldTypedAfterClear_refuses() {
        // 清完又打了字：窗口尾部变成新打的字，锚点不再吻合
        assertFalse(ClearedTextLedger.decideRestoreApply("你好呀", "你好", 3))
    }

    @Test
    fun restore_emptyAnchorRequiresEmptyWindow() {
        // 删除点就在字段开头（锚点无从附着）：只认窗口全空
        assertTrue(ClearedTextLedger.decideRestoreApply("", "", 3))
        assertFalse(ClearedTextLedger.decideRestoreApply("别的内容", "", 4))
    }

    @Test
    fun restore_emptyAnchorAcceptsNullWindow() {
        // 全选清空后字段为空，部分实现的 getTextBeforeCursor 返回 null
        // 而非空串；空锚点下二者语义等价，必须都能接管，否则
        // 「全选→上滑清空→下滑找回」这条最常用路径永远恢复不上
        assertTrue(ClearedTextLedger.decideRestoreApply(null, "", 5))
    }

    @Test
    fun restore_fullAnchor_applies() {
        // 满锚点（前文不少于 16 字、锚点只是被截取的尾段）：
        // 窗口以锚点结尾且长度落在 [锚点长, 锚点长＋被清串长]
        val anchor = "一二三四五六七八九十甲乙丙丁戊己"
        assertTrue(ClearedTextLedger.decideRestoreApply("前文" + anchor, anchor, 17))
    }

    @Test
    fun restore_fullAnchorTailEdited_refuses() {
        val anchor = "一二三四五六七八九十甲乙丙丁戊己"
        assertFalse(
            ClearedTextLedger.decideRestoreApply(
                "一二三四五六七八九十甲乙丙丁戊改", anchor, 3,
            ),
        )
    }

    @Test
    fun restore_nullBefore_refuses() {
        assertFalse(ClearedTextLedger.decideRestoreApply(null, "你好", 3))
    }

    @Test
    fun restore_longWindowBeyondClearedLength_refuses() {
        // 窗口长于「锚点长＋被清串长」：说明清完之后又落了字，不接管
        val anchor = "一二三四五六七八九十甲乙丙丁戊己"
        assertFalse(
            ClearedTextLedger.decideRestoreApply(
                "前文" + anchor + "新打的字", anchor, 3,
            ),
        )
    }

    // ---- tryRestore：弹出语义（Fake 连接，不调框架方法） ----

    @Test
    fun tryRestore_successPopsEntryAndSecondRestoreIsNoop() {
        // 恢复后不再入任何账本：条目已弹出，第二次 tryRestore
        // 无账可接管（下滑落点在污染会话内即为空操作）
        ClearedTextLedger.record("被清文字", "前文")
        val ic = FakeInputConnection(beforeCursor = "前文")
        assertTrue(ClearedTextLedger.tryRestore(ic))
        assertEquals(listOf("被清文字"), ic.committed)
        assertEquals(0, ClearedTextLedger.size())
        assertFalse(ClearedTextLedger.tryRestore(ic))
    }

    @Test
    fun tryRestore_mismatchKeepsEntry() {
        ClearedTextLedger.record("被清文字", "前文")
        val ic = FakeInputConnection(beforeCursor = "前文呀")
        assertFalse(ClearedTextLedger.tryRestore(ic))
        assertTrue(ic.committed.isEmpty())
        assertEquals(1, ClearedTextLedger.size())
    }

    @Test
    fun tryRestore_selectionKeepsEntry() {
        ClearedTextLedger.record("被清文字", "前文")
        val ic = FakeInputConnection(beforeCursor = "前文", selected = "选区")
        assertFalse(ClearedTextLedger.tryRestore(ic))
        assertEquals(1, ClearedTextLedger.size())
    }

    @Test
    fun tryRestore_commitFailureKeepsEntry() {
        ClearedTextLedger.record("被清文字", "前文")
        val ic = FakeInputConnection(beforeCursor = "前文", commitResult = false)
        assertFalse(ClearedTextLedger.tryRestore(ic))
        assertEquals(1, ClearedTextLedger.size())
    }

    @Test
    fun tryRestore_emptyAnchorNullWindowRestores() {
        // 空锚点偏差点在 tryRestore 层的对应情形：字段为空、实现
        // 返回 null 窗口时仍能接管（与纯判定测试同口径）
        ClearedTextLedger.record("abc", "")
        val ic = FakeInputConnection(beforeCursor = null)
        assertTrue(ClearedTextLedger.tryRestore(ic))
        assertEquals(listOf("abc"), ic.committed)
        assertEquals(0, ClearedTextLedger.size())
    }

    // ---- 光标绑定（审计 #1-9）：锚点相同 ≠ 同一位置 ----

    @Test
    fun restore_repeatedFullAnchor_moveToSecondPosition_refuses() {
        // 核心回归：同一 16 字满锚点在两处重复出现。清第一处后
        // 把光标移到第二处，锚点判定本身会放行（窗口以锚点结尾、
        // 长度落在区间内），光标位置绑定必须拦住，不误插。
        val anchor = "0123456789abcdef"
        val first = FakeInputConnection(beforeCursor = anchor)
        ClearedTextLedger.record("X", anchor, first)
        assertEquals(16 to 16, ClearedTextLedger.peekCursor())
        // 对照：光标不动，恢复成功
        assertTrue(ClearedTextLedger.tryRestore(first))
        assertEquals(listOf("X"), first.committed)

        ClearedTextLedger.record("X", anchor, first)
        // 移到第二处：光标前变为「锚点＋X＋锚点」，锚点判定放行
        val moved = FakeInputConnection(beforeCursor = anchor + "X" + anchor)
        assertFalse(ClearedTextLedger.tryRestore(moved))
        assertTrue(moved.committed.isEmpty())
        // 条目保留：移回原处仍可恢复（未接 onSelectionChanged 时）
        assertEquals(1, ClearedTextLedger.size())
        assertTrue(ClearedTextLedger.tryRestore(first))
        // first 在上文已提交过一次 "X"，此次再提交一次，累计两次
        assertEquals(listOf("X", "X"), first.committed)
    }

    @Test
    fun restore_longField_fingerprint_moveRefuses() {
        // 长字段（绝对位置不可知）：登记时存 256 字窗口指纹；
        // 光标移到前文不足 256 字的位置，窗口变化即拒。
        val before = "A".repeat(300)
        val anchor = "A".repeat(16)
        val at300 = FakeInputConnection(beforeCursor = before)
        ClearedTextLedger.record("X", anchor, at300)
        assertNull(ClearedTextLedger.peekCursor())
        assertTrue(ClearedTextLedger.tryRestore(at300))

        ClearedTextLedger.record("X", anchor, at300)
        val at150 = FakeInputConnection(beforeCursor = "A".repeat(150))
        assertFalse(ClearedTextLedger.tryRestore(at150))
        assertTrue(at150.committed.isEmpty())
        assertEquals(1, ClearedTextLedger.size())
    }

    @Test
    fun restore_epochChanged_dropsEntry() {
        // 编辑器会话重用：纪元推进后旧条目永不匹配，直接丢弃。
        val saved = ClearedTextLedger.epochProvider
        try {
            ClearedTextLedger.epochProvider = { 7L }
            ClearedTextLedger.record("abc", "前文")
            ClearedTextLedger.epochProvider = { 7L }
            val ic = FakeInputConnection(beforeCursor = "前文")
            assertTrue(ClearedTextLedger.tryRestore(ic))

            ClearedTextLedger.epochProvider = { 7L }
            ClearedTextLedger.record("abc", "前文")
            ClearedTextLedger.epochProvider = { 8L }
            val ic2 = FakeInputConnection(beforeCursor = "前文")
            assertFalse(ClearedTextLedger.tryRestore(ic2))
            assertTrue(ic2.committed.isEmpty())
            assertEquals(0, ClearedTextLedger.size())
        } finally {
            ClearedTextLedger.epochProvider = saved
        }
    }

    @Test
    fun selectionChange_invalidatesBoundEntryAndMoveBackStaysInvalid() {
        // onSelectionChanged：位置已知的条目，任何不一致的选区
        // 更新都立即作废；移开再移回原处也不恢复。
        val ic = FakeInputConnection(beforeCursor = "前文")
        ClearedTextLedger.record("abc", "前文", ic)
        ClearedTextLedger.onSelectionChanged(2, 2)
        assertEquals(1, ClearedTextLedger.size())
        assertTrue(ClearedTextLedger.tryRestore(ic))

        ClearedTextLedger.record("abc", "前文", ic)
        ClearedTextLedger.onSelectionChanged(5, 5)
        assertEquals(0, ClearedTextLedger.size())
        assertFalse(ClearedTextLedger.tryRestore(ic))
        // 移回原处：条目已在移开时作废，不再恢复
        ClearedTextLedger.onSelectionChanged(2, 2)
        assertEquals(0, ClearedTextLedger.size())
    }

    @Test
    fun selectionChange_keepsUnboundEntry() {
        // 未传连接的条目（位置未知）不受选区通知影响：删除动作
        // 本身就会触发一次选区更新，清掉会让旧路径撤销立即失效。
        ClearedTextLedger.record("abc", "前文")
        ClearedTextLedger.onSelectionChanged(99, 99)
        assertEquals(1, ClearedTextLedger.size())
    }

    @Test
    fun record_withSelection_bindsRange() {
        // 登记时有选区：起点/尾都记；恢复时选区已清但位置仍须一致。
        val ic = FakeInputConnection(beforeCursor = "XY ", selected = "ab")
        ClearedTextLedger.record("X", "XY ", ic)
        assertEquals(3 to 5, ClearedTextLedger.peekCursor())
        val cleared = FakeInputConnection(beforeCursor = "XY ")
        assertFalse(ClearedTextLedger.tryRestore(cleared))
        assertTrue(cleared.committed.isEmpty())
    }

    @Test
    fun record_withIc_bindsCursor_withoutIcUnbound() {
        val ic = FakeInputConnection(beforeCursor = "前文")
        ClearedTextLedger.record("abc", "前文", ic)
        assertEquals(2 to 2, ClearedTextLedger.peekCursor())
        ClearedTextLedger.clearAll()
        ClearedTextLedger.record("abc", "前文")
        assertNull(ClearedTextLedger.peekCursor())
    }
}

/**
 * 仅供本文件测试的最小 InputConnection 实现：getTextBeforeCursor
 * 按长度截取（返回窗口尾部至多 n 字，与真实连接同口径——长度
 * 敏感情形与光标位置绑定都依赖它）；其余方法返回固定默认值，
 * 不触碰 Android 框架实现。
 */
private class FakeInputConnection(
    var beforeCursor: CharSequence?,
    var selected: CharSequence? = null,
    var commitResult: Boolean = true,
) : InputConnection {
    val committed = mutableListOf<String>()

    override fun getSelectedText(flags: Int): CharSequence? = selected
    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        beforeCursor?.takeLast(n)
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = null
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        if (commitResult) committed.add(text.toString())
        return commitResult
    }

    override fun beginBatchEdit(): Boolean = true
    override fun clearMetaKeyStates(states: Int): Boolean = false
    override fun closeConnection() = Unit
    override fun commitCompletion(text: CompletionInfo): Boolean = false
    override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
    override fun commitCorrection(info: CorrectionInfo): Boolean = false
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = false
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = false
    override fun endBatchEdit(): Boolean = true
    override fun finishComposingText(): Boolean = false
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest, flags: Int): ExtractedText? = null
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
