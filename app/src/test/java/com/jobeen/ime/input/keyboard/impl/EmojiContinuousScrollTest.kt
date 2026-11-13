// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.input.keyboard.impl

import com.jobeen.ime.data.Symbol
import com.jobeen.ime.input.keyboard.key.KeyboardAction
import com.jobeen.ime.skin.model.KeyboardTypes
import com.jobeen.ime.skin.symbol.BoardAutoReturn
import com.jobeen.ime.skin.symbol.SubClassifiedBoardState
import com.jobeen.ime.skin.symbol.SubClassifiedSymbols
import com.jobeen.ime.skin.symbol.SymbolItem
import com.jobeen.ime.skin.symbol.SymbolItemActionResolver
import com.jobeen.ime.skin.symbol.SymbolParentCategory
import com.jobeen.ime.skin.symbol.SymbolSubCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO31 emoji 板连续竖滑替代分页（纯 JVM 单测）：
 *
 * 能测（本文件）：网格喂数的数据层——新喂数 [SubClassifiedBoardState.currentItems]
 * 相对旧喂数 visibleItems 的全量性、page 不再驱动 currentItems、点按解析＋
 * 自动返回决策链、滚动门控的数据前提（≤25 一屏装下／>25 需滚动）、
 * 切类／切子类／重置的状态机、颜文字／符号板共用的分页状态机未动。
 *
 * 测不了（需真机／插桩验收，按仓库惯例见 SymbolSwipeTest 头注）：
 * EmojiKeyboard.pageSubCollection 恒 false、GridKeyboardView.onInterceptTouchEvent
 * 的拦截判定（竖向占优＋maxScroll>0 才拦截）、verticalScrollOnlyWhenScrollable
 * 的默认 false 与 emoji 板开 true 的接线、reset()/applyCategory() 的 View 接线、
 * setItems 滚动归零——以上均需实例化 View / 构造 MotionEvent，本模块
 * 只有 JUnit、无 Robolectric/Mockito，做不到。
 */
class EmojiContinuousScrollTest {

    private fun sub(label: String, count: Int, prefix: String = "e") =
        SymbolSubCategory(
            label = label,
            items = (1..count).map { SymbolItem.Plain("$prefix$it") },
        )

    private fun parent(label: String, vararg subs: SymbolSubCategory) =
        SymbolParentCategory(label = label, subCategories = subs.toList())

    /** 30 项单子类：旧分页口径下正好 25＋5 两页。 */
    private fun thirtyItemState() = SubClassifiedBoardState(
        SubClassifiedSymbols(listOf(parent("大类", sub("全部", 30)))),
    )

    // ── 验收 1：连续竖滑替代分页 ──

    @Test
    fun gridFeedsFullSubCategory_notFirstPageOnly() {
        val state = thirtyItemState()
        // 新喂数是当前子类全部项：30 项一个不少，首尾都在。
        assertEquals(30, state.currentItems.size)
        assertEquals(SymbolItem.Plain("e1"), state.currentItems.first())
        assertEquals(SymbolItem.Plain("e30"), state.currentItems.last())
        // 对照旧喂数：page=0 时 visibleItems 只有第一页 25 项，
        // 第 26～30 项在旧板上翻页前不可见。
        assertEquals(25, state.visibleItems.size)
        assertEquals(SymbolItem.Plain("e25"), state.visibleItems.last())
    }

    @Test
    fun pageDoesNotDriveCurrentItems() {
        val state = thirtyItemState()
        val before = state.currentItems
        assertTrue(state.nextPage())
        assertEquals(1, state.page)
        // 翻页只动旧喂数（25→5），新喂数恒为全量 30 项。
        assertEquals(5, state.visibleItems.size)
        assertEquals(before, state.currentItems)
        assertTrue(state.prevPage())
        assertEquals(0, state.page)
        assertEquals(before, state.currentItems)
    }

    @Test
    fun builtinEmojiCategories_allExceedOneViewport() {
        // 与 EmojiKeyboard 无皮肤表时的回落路径一致：
        // SkinSnapshotProvider 无表即 fromSingleLevel(Symbol.Emoji)。
        val state = SubClassifiedBoardState(
            SubClassifiedSymbols.fromSingleLevel(Symbol.Emoji),
        )
        assertEquals(9, state.parentLabels.size)
        val sizes = (0 until 9).map { index ->
            assertTrue(state.selectParent(index))
            state.currentItems.size
        }
        assertEquals(listOf(170, 390, 155, 140, 220, 90, 265, 225, 270), sizes)
        assertEquals(1925, sizes.sum())
        // 9 类全都一屏（5 列 × 5 行＝25 项）装不下：旧分页口径下
        // 每类都要翻页，新连续竖滑下每类一次喂全。
        assertTrue(sizes.all { it > SubClassifiedBoardState.DEFAULT_PAGE_SIZE })
        (0 until 9).forEach { index ->
            assertTrue(state.selectParent(index))
            assertTrue(state.pageCount > 1)
        }
    }

    // ── 验收 2：点选仍可用（解析＋自动返回决策链） ──

    @Test
    fun emojiTap_resolvesToCommitAndAutoReturns() {
        // 板内 keyDef 的动作即 resolver 结果：表情点按提交原文。
        assertEquals(
            KeyboardAction.CommitAction("😀"),
            SymbolItemActionResolver.resolve(SymbolItem.Plain("😀")),
        )
        // 显示与提交分离项提交 value（皮肤表 LabelValue 形态）。
        assertEquals(
            KeyboardAction.CommitAction(" "),
            SymbolItemActionResolver.resolve(SymbolItem.LabelValue("空格", " ")),
        )
        // 提交后自动返回：emoji 板在三块自动返回板之列
        // （BoardAutoReturnTest 已单测返回判定本身，此处断言整条
        // “点按→提交→返回”链不断；View 内 onKeyAction→maybeAutoReturn
        // 的接线需真机验收）。
        assertTrue(BoardAutoReturn.shouldReturnAfterCommit(KeyboardTypes.EMOJIS))
    }

    // ── 验收 3：竖滑不误选——数据前提（拦截判定本身需真机验收） ──

    @Test
    fun shortSubCategory_fitsOneViewport() {
        // 25 项恰一屏：WO31 门控 maxScroll=0 的一侧——内容不可滚，
        // 网格不拦截竖向 MOVE，点按不受影响。
        val state = SubClassifiedBoardState(
            SubClassifiedSymbols(listOf(parent("小类", sub("全部", 25)))),
        )
        assertEquals(1, state.pageCount)
        assertEquals(state.currentItems, state.visibleItems)
    }

    @Test
    fun justOverOneViewport_needsScrolling() {
        // 26 项刚超一屏：WO31 门控 maxScroll>0 的一侧——内容可滚，
        // 竖向占优的 MOVE 才拦截。
        val state = SubClassifiedBoardState(
            SubClassifiedSymbols(listOf(parent("大类", sub("全部", 26)))),
        )
        assertEquals(2, state.pageCount)
        assertEquals(26, state.currentItems.size)
        assertEquals(25, state.visibleItems.size)
    }

    // ── 验收 4：分类切换不受影响 ──

    @Test
    fun switchingCategory_feedsNewCategoryInFull() {
        val state = SubClassifiedBoardState(
            SubClassifiedSymbols(
                listOf(
                    parent("大类", sub("全部", 30)),
                    parent("小类", sub("甲", 2), sub("乙", 3)),
                ),
            ),
        )
        assertTrue(state.selectParent(1))
        assertEquals(0, state.page)
        assertEquals(2, state.currentItems.size)
        assertTrue(state.selectSub(1))
        assertEquals(0, state.page)
        assertEquals(3, state.currentItems.size)
        assertEquals(SymbolItem.Plain("e3"), state.currentItems.last())
        assertTrue(state.selectParent(0))
        assertEquals(0, state.page)
        assertEquals(30, state.currentItems.size)
    }

    @Test
    fun reset_returnsToFirstCategoryInFull() {
        // reset() 的状态机等价动作：selectParent(0)（回首类＋首子类＋page=0）。
        val state = thirtyItemState()
        assertTrue(state.nextPage())
        assertEquals(1, state.page)
        assertTrue(state.selectParent(0))
        assertEquals(0, state.parentIndex)
        assertEquals(0, state.subIndex)
        assertEquals(0, state.page)
        assertEquals(30, state.currentItems.size)
        assertEquals(SymbolItem.Plain("e1"), state.currentItems.first())
    }

    @Test
    fun sharedPagingMachineStillPages_forKaomojiAndSymbolBoards() {
        // WO31 只改了 emoji 板的喂数与翻页入口，未动共用状态机：
        // 颜文字／符号板仍以 visibleItems 为喂数、仍靠 nextPage/prevPage
        // 翻页（两板 pageSubCollection 实现未变，见 KaomojiKeyboard 与
        // SymbolKeyboard）。开关默认 false 与两板 pageSubCollection 的
        // 行为本身需实例化 View，走真机验收。
        val state = thirtyItemState()
        assertEquals(2, state.pageCount)
        assertTrue(state.nextPage())
        assertEquals(1, state.page)
        assertEquals(5, state.visibleItems.size)
        assertFalse(state.nextPage()) // 末页为空操作，不绕回
        assertEquals(1, state.page)
        assertTrue(state.prevPage())
        assertEquals(0, state.page)
        assertFalse(state.prevPage()) // 首页为空操作
        assertEquals(0, state.page)
    }
}
