package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用事务状态机（详设 §2.4/§2.5）：两态流转、晋升条件、A-1 构建
 * 失败、切换/首渲失败的旧代次恢复（A-6 口径）、手动回退。
 * Ports 为脚本化假实现，状态机本体走真实 store 与磁盘代次。
 */
class ApplyTransactionTest {

    private class FakePorts : ApplyTransaction.Ports {
        var buildResult = true
        var switchBehavior: (SkinGenRef?) -> Boolean = { true }
        var firstRenderResult = true
        val switchCalls = mutableListOf<SkinGenRef?>()
        var buildCalls = 0
        var stateAtSwitch: ApplyState? = null
        var stateAtFirstRender: ApplyState? = null
        var peekState: (suspend () -> ApplyState?)? = null

        override fun buildSnapshot(ref: SkinGenRef): Boolean {
            buildCalls++
            return buildResult
        }

        override fun switchRuntime(ref: SkinGenRef?): Boolean {
            switchCalls += ref
            peekState?.let { stateAtSwitch = runBlocking { it() } }
            return switchBehavior(ref)
        }

        override suspend fun awaitFirstRender(): Boolean {
            peekState?.let { stateAtFirstRender = runBlocking { it() } }
            return firstRenderResult
        }
    }

    private class Fixture {
        val root: File = Files.createTempDirectory("skin-apply").toFile()
        val layout = SkinLibraryLayout(root)
        val store = SkinLibraryStore(layout)
        val ports = FakePorts()
        val tx = ApplyTransaction(layout, store, ports) { 7_000L }

        init {
            layout.ensureDirs()
            ports.peekState = {
                (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value?.state
            }
        }

        fun skinGen(id: String, gen: Int, salt: String = ""): SkinGenRef {
            val dir = layout.skinGenDir(id, gen)
            dir.mkdirs()
            val skinJson = """{"meta":{"id":"$id","name":"S$gen","version":"1.0"},"styles":{},"salt":"$salt"}"""
            File(dir, "skin.json").writeText(skinJson)
            val hash = DigestUtil.sha256Hex(skinJson.toByteArray())
            File(dir, "manifest.json").writeText(
                """{"version":1,"contents":[{"type":"skin","id":"$id","path":"skin.json","sha256":"$hash"}]}""",
            )
            return SkinGenRef(id, hash, gen)
        }

        suspend fun active(): ActiveRecord =
            (store.readActive() as SkinLibraryStore.ReadResult.Ok).value
    }

    @Test
    fun happyPathPendingThenVerifiedAndPromoted() = runBlocking {
        val f = Fixture()
        val ref = f.skinGen("s1", 1)
        val result = f.tx.apply(ref)
        assertEquals(ApplyTransaction.Result.Applied(ref), result)
        // 切换与首渲时刻记录必为 pending，验证后才置 verified 并晋升 Last-Good
        assertEquals(ApplyState.PENDING, f.ports.stateAtSwitch)
        assertEquals(ApplyState.PENDING, f.ports.stateAtFirstRender)
        val active = f.active()
        assertEquals(ApplyState.VERIFIED, active.state)
        assertEquals(ref.generation, active.generation)
        val lastGood = (f.store.readLastGood() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(LastGoodPointer(ref.skinId, ref.hash, ref.generation), lastGood)
    }

    @Test
    fun buildFailureLeavesOldStateUntouched_A1() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        f.tx.apply(ref1)
        f.ports.buildResult = false
        val result = f.tx.apply(ref2)
        assertEquals(ApplyTransaction.Result.BuildFailed(ref2), result)
        assertEquals(1, f.active().generation)
        assertEquals(ApplyState.VERIFIED, f.active().state)
        // 切换从未发生（只有第一次 apply 的一次切换）
        assertEquals(1, f.ports.switchCalls.size)
    }

    @Test
    fun switchFailureRestoresPreviousRecordAndRuntime() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        f.tx.apply(ref1)
        val before = f.active()
        f.ports.switchBehavior = { ref -> ref?.generation != 2 }
        val result = f.tx.apply(ref2)
        assertEquals(ApplyTransaction.Result.SwitchFailedRolledBack(ref2, ref1), result)
        assertEquals(before, f.active())
        assertEquals(listOf(ref1, ref2, ref1), f.ports.switchCalls)
    }

    @Test
    fun firstRenderFailureRestoresPrevious() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        f.tx.apply(ref1)
        val before = f.active()
        f.ports.firstRenderResult = false
        val result = f.tx.apply(ref2)
        assertEquals(ApplyTransaction.Result.FirstRenderFailedRolledBack(ref2, ref1), result)
        assertEquals(before, f.active())
        // Last-Good 不被未验证代次污染
        val lastGood = (f.store.readLastGood() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, lastGood.generation)
    }

    @Test
    fun firstApplyFailureReturnsToNoSkin() = runBlocking {
        val f = Fixture()
        val ref = f.skinGen("s1", 1)
        f.ports.firstRenderResult = false
        val result = f.tx.apply(ref)
        assertEquals(ApplyTransaction.Result.FirstRenderFailedRolledBack(ref), result)
        assertEquals(null, f.active().skinId)
        assertTrue(f.store.readLastGood() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun targetWithWrongHashIsInvalid() = runBlocking {
        val f = Fixture()
        f.skinGen("s1", 1)
        val bogus = SkinGenRef("s1", "0".repeat(64), 1)
        val result = f.tx.apply(bogus)
        assertTrue(result is ApplyTransaction.Result.TargetInvalid)
        assertEquals(0, f.ports.buildCalls)
        assertTrue(f.store.readActive() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun manualRollbackToLastGood() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        f.tx.apply(ref1)
        // Last-Good = 最近已晋升代次（§2.5.1）：apply(ref1) 后 LG=gen1。
        // 直接把 active 置为 gen2 verified（模拟未经本事务晋升的生效
        // 记录），手动回退应落回 LG 的 gen1。
        f.store.writeActive(ActiveRecord(ref2.skinId, ref2.hash, ref2.generation, ApplyState.VERIFIED, 9))
        val result = f.tx.rollbackToLastGood()
        assertEquals(ApplyTransaction.Result.RolledBackToLastGood(ref1), result)
        val active = f.active()
        assertEquals(1, active.generation)
        assertEquals(ApplyState.VERIFIED, active.state)
    }

    @Test
    fun rollbackUnavailableWithoutLastGood() = runBlocking {
        val f = Fixture()
        f.skinGen("s1", 1)
        val result = f.tx.rollbackToLastGood()
        assertTrue(result is ApplyTransaction.Result.RollbackUnavailable)
    }

    @Test
    fun revertToDefaultKeepsLastGood() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        f.tx.apply(ref1)
        val result = f.tx.revertToDefault()
        assertEquals(ApplyTransaction.Result.RevertedToDefault, result)
        assertEquals(null, f.active().skinId)
        assertEquals(null, f.ports.switchCalls.last())
        // Last-Good 指针保留，之后仍可手动回退到它
        assertTrue(f.store.readLastGood() is SkinLibraryStore.ReadResult.Ok)
        val back = f.tx.rollbackToLastGood()
        assertEquals(ApplyTransaction.Result.RolledBackToLastGood(ref1), back)
    }
}
