package com.jobeen.ime.skin.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动裁决（S1 保留 pending 口径，2026-11-02 修复批）：纯逻辑。 */
class ActiveResolverTest {

    private fun genState(validHash: String?): (String, Int) -> ActiveResolver.GenState =
        { _, _ -> ActiveResolver.GenState(complete = true, skinJsonHash = validHash) }

    private fun okActive(hash: String, gen: Int, state: ApplyState) =
        SkinLibraryStore.ReadResult.Ok(ActiveRecord("s1", hash, gen, state, 5))

    private fun okLastGood(hash: String, gen: Int) =
        SkinLibraryStore.ReadResult.Ok(LastGoodPointer("s1", hash, gen))

    @Test
    fun pendingWithValidTargetKeepsPending() {
        val resolution = ActiveResolver.resolve(
            okActive("h2", 2, ApplyState.PENDING),
            okLastGood("h1", 1),
            genState("h2"),
            1_000L,
        )
        assertTrue(resolution is ActiveResolver.Resolution.KeepPending)
        val kept = (resolution as ActiveResolver.Resolution.KeepPending).record
        assertEquals(2, kept.generation)
        assertEquals("h2", kept.hash)
        assertEquals(ApplyState.PENDING, kept.state)
    }

    @Test
    fun pendingWithInvalidTargetRollsBackToLastGood() {
        val resolution = ActiveResolver.resolve(
            okActive("hX", 2, ApplyState.PENDING),
            okLastGood("h1", 1),
            { _, gen -> ActiveResolver.GenState(complete = true, skinJsonHash = if (gen == 1) "h1" else "h2") },
            1_000L,
        )
        assertTrue(resolution is ActiveResolver.Resolution.RollbackToLastGood)
        val target = (resolution as ActiveResolver.Resolution.RollbackToLastGood).target
        assertEquals(1, target.generation)
        assertEquals(ApplyState.VERIFIED, target.state)
    }

    @Test
    fun pendingWithInvalidTargetAndNoLastGoodDefaults() {
        val resolution = ActiveResolver.resolve(
            okActive("hX", 2, ApplyState.PENDING),
            SkinLibraryStore.ReadResult.Missing,
            genState("h2"),
            1_000L,
        )
        assertTrue(resolution is ActiveResolver.Resolution.RollbackToDefault)
    }

    @Test
    fun verifiedWithValidTargetStaysVerified() {
        val resolution = ActiveResolver.resolve(
            okActive("h1", 1, ApplyState.VERIFIED),
            SkinLibraryStore.ReadResult.Missing,
            genState("h1"),
            1_000L,
        )
        assertTrue(resolution is ActiveResolver.Resolution.Verified)
    }
}
