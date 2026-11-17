package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内联自动填充状态机与分区（纯函数）：条区覆盖层本身是 ViewGroup，
 * 在 JVM 存根上无法实例化，故只测决策与分区；挂载／度量／布局走
 * assembleDebug 编译证明＋真机验证。
 */
class InlineAutofillBarTest {

    @Test
    fun show_requiresSuggestionsAndIdleAndActiveField() {
        assertTrue(inlineAutofillShouldShow(true, false, true))
        assertFalse(inlineAutofillShouldShow(false, false, true))
        assertFalse(inlineAutofillShouldShow(true, true, true))
        assertFalse(inlineAutofillShouldShow(true, false, false))
        assertFalse(inlineAutofillShouldShow(false, true, false))
    }

    // R-1：空到达 → hide（清缓存并隐藏旧胶囊），与组字／会话状态无关。
    @Test
    fun arrival_emptyList_hides() {
        assertEquals(InlineAutofillArrival.HIDE, inlineAutofillArrival(false, false, true))
        assertEquals(InlineAutofillArrival.HIDE, inlineAutofillArrival(false, true, true))
        assertEquals(InlineAutofillArrival.HIDE, inlineAutofillArrival(false, false, false))
        assertEquals(InlineAutofillArrival.HIDE, inlineAutofillArrival(false, true, false))
    }

    // R-2 边角（空响应清缓存）：组字中已缓存建议、随后收到服务空响应时，
    // 空到达仍判 HIDE——服务侧空响应现走 showInlineAutofill(空, 空)，
    // HIDE 分支先清待重放缓存再隐藏，组字结束时不再重放过期建议。
    // 只看组字中＋会话有效这一条边角组合，其余组合见 arrival_emptyList_hides。
    @Test
    fun arrival_emptyResponseWhileComposing_hidesSoPendingCacheIsCleared() {
        assertEquals(InlineAutofillArrival.HIDE, inlineAutofillArrival(false, true, true))
    }

    // R-2：非空到达且门控通过 → 显示。
    @Test
    fun arrival_idleAndActive_shows() {
        assertEquals(InlineAutofillArrival.SHOW, inlineAutofillArrival(true, false, true))
    }

    // R-2：组字拦掉（非空＋组字中＋会话有效）→ 缓存，组字结束重放。
    @Test
    fun arrival_composingAndActive_caches() {
        assertEquals(InlineAutofillArrival.CACHE, inlineAutofillArrival(true, true, true))
    }

    // R-2：会话无效拦掉 → 丢弃，不缓存（组字与否都一样）。
    @Test
    fun arrival_fieldInactive_discards() {
        assertEquals(InlineAutofillArrival.DISCARD, inlineAutofillArrival(true, true, false))
        assertEquals(InlineAutofillArrival.DISCARD, inlineAutofillArrival(true, false, false))
    }

    // R-2 重放：有缓存且会话有效 → 重放；任一不满足 → 丢弃。
    @Test
    fun replay_onlyWithPendingAndActiveField() {
        assertTrue(inlineAutofillShouldReplay(true, true))
        assertFalse(inlineAutofillShouldReplay(true, false))
        assertFalse(inlineAutofillShouldReplay(false, true))
        assertFalse(inlineAutofillShouldReplay(false, false))
    }


}
