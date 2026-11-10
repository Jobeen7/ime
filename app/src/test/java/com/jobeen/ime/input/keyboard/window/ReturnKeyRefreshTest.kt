package com.jobeen.ime.input.keyboard.window

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回车键刷新去重（五元组）：同动作的单行↔多行换框必须刷新。 */
class ReturnKeyRefreshTest {

    private val base = ReturnKeyRefreshKey(
        action = 0, // IME_ACTION_UNSPECIFIED
        empty = true,
        composing = false,
        isMultiLine = false,
        noEnterAction = false,
    )

    @Test
    fun noPreviousRefreshes() {
        assertTrue(returnKeyNeedsRefresh(null, base))
    }

    @Test
    fun identicalKeyDoesNotRefresh() {
        assertFalse(returnKeyNeedsRefresh(base, base))
    }

    @Test
    fun multiLineFlipRefreshes() {
        assertTrue(returnKeyNeedsRefresh(base, base.copy(isMultiLine = true)))
    }

    @Test
    fun noEnterActionFlipRefreshes() {
        assertTrue(returnKeyNeedsRefresh(base, base.copy(noEnterAction = true)))
    }

    @Test
    fun actionChangeRefreshes() {
        assertTrue(returnKeyNeedsRefresh(base, base.copy(action = 5))) // IME_ACTION_NEXT
    }

    @Test
    fun emptyFlipRefreshes() {
        assertTrue(returnKeyNeedsRefresh(base, base.copy(empty = false)))
    }

    @Test
    fun composingFlipRefreshes() {
        assertTrue(returnKeyNeedsRefresh(base, base.copy(composing = true)))
    }
}
