package com.jobeen.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** WO52 4.1：外部调用分发校验（三 action、text 缺失／超限）。 */
class ExternalActionDispatcherTest {

    @Test
    fun `deploy action resolves`() {
        assertEquals(
            ExternalActionDispatcher.Resolved.Deploy,
            ExternalActionDispatcher.resolve(ExternalActionActivity.ACTION_DEPLOY, null),
        )
    }

    @Test
    fun `sync action resolves`() {
        assertEquals(
            ExternalActionDispatcher.Resolved.Sync,
            ExternalActionDispatcher.resolve(ExternalActionActivity.ACTION_SYNC, null),
        )
    }

    @Test
    fun `set clipboard resolves with text`() {
        assertEquals(
            ExternalActionDispatcher.Resolved.SetClipboard("hello"),
            ExternalActionDispatcher.resolve(ExternalActionActivity.ACTION_SET_CLIPBOARD, "hello"),
        )
    }

    @Test
    fun `set clipboard without text is rejected`() {
        assertNull(
            ExternalActionDispatcher.resolve(ExternalActionActivity.ACTION_SET_CLIPBOARD, null),
        )
    }

    @Test
    fun `set clipboard at exactly 4096 chars is accepted`() {
        val text = "a".repeat(4096)
        assertEquals(
            ExternalActionDispatcher.Resolved.SetClipboard(text),
            ExternalActionDispatcher.resolve(ExternalActionActivity.ACTION_SET_CLIPBOARD, text),
        )
    }

    @Test
    fun `set clipboard over 4096 chars is rejected`() {
        assertNull(
            ExternalActionDispatcher.resolve(
                ExternalActionActivity.ACTION_SET_CLIPBOARD,
                "a".repeat(4097),
            ),
        )
    }

    @Test
    fun `unknown action is rejected`() {
        assertNull(ExternalActionDispatcher.resolve("com.jobeen.ime.action.NOPE", null))
        assertNull(ExternalActionDispatcher.resolve(null, null))
    }
}
