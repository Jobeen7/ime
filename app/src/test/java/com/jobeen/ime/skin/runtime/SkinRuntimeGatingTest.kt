package com.jobeen.ime.skin.runtime

import com.jobeen.ime.skin.store.SkinGenRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用事务选路门控与延迟验证钩子（真机缺陷回归）：
 *
 * 1. IME 窗口隐藏时视图仍 attached——只认挂载会误走即时验证，
 *    首帧永不到达、2s 超时回滚，设置页内应用皮肤永远失败。
 *    选路必须以「已挂载 ∧ 可见」为准（[SkinRuntime.canVerifyInline]）。
 * 2. 延迟验证武装时窗口可能早已挂载（不会再有 onWindowAttached），
 *    必须当场挂下一帧绘制钩子，否则 pending 永不推进、下次启动
 *    对账回滚，等于没应用。
 */
class SkinRuntimeGatingTest {

    private class FakeWindow(var visible: Boolean) : SkinRuntime.SkinWindow {
        val drawHooks = mutableListOf<() -> Unit>()

        override fun refreshSkinTheme(onFirstDraw: (() -> Unit)?) {
            if (onFirstDraw != null) drawHooks += onFirstDraw
        }

        override fun isVisibleNow(): Boolean = visible
    }

    @After
    fun tearDown() {
        SkinRuntime.disarmPendingVerification()
    }

    @Test
    fun canVerifyInlineRequiresAttachedAndVisible() {
        val hidden = FakeWindow(visible = false)
        SkinRuntime.attachWindow(hidden)
        try {
            assertTrue(SkinRuntime.hasLiveWindow())
            assertFalse(SkinRuntime.isWindowVisible())
            assertFalse(SkinRuntime.canVerifyInline())

            hidden.visible = true
            assertTrue(SkinRuntime.isWindowVisible())
            assertTrue(SkinRuntime.canVerifyInline())
        } finally {
            SkinRuntime.detachWindow(hidden)
        }
        assertFalse(SkinRuntime.hasLiveWindow())
        assertFalse(SkinRuntime.canVerifyInline())
    }

    @Test
    fun armWithAlreadyAttachedWindowHooksNextDrawImmediately() {
        val window = FakeWindow(visible = false)
        SkinRuntime.attachWindow(window)
        try {
            val ref = SkinGenRef("skin-a", "hash-a", 1)
            SkinRuntime.armPendingVerification(ref)
            // 不等任何新的挂载事件，钩子必须已挂上
            assertEquals(1, window.drawHooks.size)

            // 模拟下一帧绘制：provider 无此快照 → 放弃推进并解除武装；
            // 之后再挂载也不应再挂钩子（pending 已清）
            window.drawHooks.single().invoke()
            Thread.sleep(500)
            SkinRuntime.onWindowAttached()
            assertEquals(1, window.drawHooks.size)
        } finally {
            SkinRuntime.detachWindow(window)
        }
    }

    @Test
    fun armWithoutWindowHooksOnLaterAttach() {
        val ref = SkinGenRef("skin-b", "hash-b", 2)
        SkinRuntime.armPendingVerification(ref)

        val window = FakeWindow(visible = false)
        SkinRuntime.attachWindow(window)
        try {
            assertEquals(0, window.drawHooks.size)
            SkinRuntime.onWindowAttached()
            assertEquals(1, window.drawHooks.size)
        } finally {
            SkinRuntime.detachWindow(window)
        }
    }
}
