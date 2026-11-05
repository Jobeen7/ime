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
        var buildBehavior: (SkinGenRef) -> Boolean = { buildResult }
        var switchBehavior: (SkinGenRef?) -> Boolean = { true }
        var firstRenderResult = true
        val switchCalls = mutableListOf<SkinGenRef?>()
        var buildCalls = 0
        var stateAtSwitch: ApplyState? = null
        var stateAtFirstRender: ApplyState? = null
        var peekState: (suspend () -> ApplyState?)? = null
        /** 切换成功后运行态的皮肤（null = 内置默认；只在成功时更新）。 */
        var installed: SkinGenRef? = null
        /** 首渲判定前注入故障（如把库目录置只读，令后续写记录失败）。 */
        var onFirstRender: (() -> Unit)? = null

        override fun buildSnapshot(ref: SkinGenRef): Boolean {
            buildCalls++
            return buildBehavior(ref)
        }

        override fun switchRuntime(ref: SkinGenRef?): Boolean {
            switchCalls += ref
            peekState?.let { stateAtSwitch = runBlocking { it() } }
            val ok = switchBehavior(ref)
            if (ok) installed = ref
            return ok
        }

        override suspend fun awaitFirstRender(): Boolean {
            onFirstRender?.invoke()
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

        /**
         * 把 active.json 换成同名空目录并备份原文件：原子写经 tmp＋
         * rename 覆盖目标，覆盖目录时 rename 必失败（root 亦然；
         * chmod 权限位对 root 无效，不能用）。令后续 writeActive
         * 抛错，测恢复的写失败分支；[unblockActiveWrite] 恢复原文件。
         */
        fun blockActiveWrite() {
            val active = layout.activeFile
            val bak = File(root, "active.json.bak-test")
            check(active.renameTo(bak)) { "backup active.json failed" }
            check(active.mkdir()) { "mkdir active.json failed" }
        }

        /** 恢复 [blockActiveWrite] 的备份（finally 里调）。 */
        fun unblockActiveWrite() {
            val active = layout.activeFile
            val bak = File(root, "active.json.bak-test")
            if (active.isDirectory) active.delete()
            if (bak.isFile) bak.renameTo(active)
        }
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

    /**
     * 模拟真实 SkinApplier 端口的 staged 语义：switchRuntime 只接受
     * 本事务刚构建的快照（staged），切其它引用恒返回 false；null
     *（回默认）不受 staged 限制。旧 restorePrevious 不重建上一好
     * 就直接切，在此端口下回滚恒失败——本测试钉住修复后的行为。
     */
    private class StagedPorts : ApplyTransaction.Ports {
        var buildBehavior: (SkinGenRef) -> Boolean = { true }
        var firstRenderResult = true
        var staged: SkinGenRef? = null
        var installed: SkinGenRef? = null

        override fun buildSnapshot(ref: SkinGenRef): Boolean {
            if (!buildBehavior(ref)) return false
            staged = ref
            return true
        }

        override fun switchRuntime(ref: SkinGenRef?): Boolean {
            if (ref == null) {
                installed = null
                return true
            }
            if (staged != ref) return false
            installed = ref
            return true
        }

        override suspend fun awaitFirstRender(): Boolean = firstRenderResult
    }

    @Test
    fun firstRenderFailureRestoresPreviousRuntimeWithStagedPorts() = runBlocking {
        val f = Fixture()
        val ports = StagedPorts()
        val tx = ApplyTransaction(f.layout, f.store, ports) { 7_000L }
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        assertEquals(ApplyTransaction.Result.Applied(ref1), tx.apply(ref1))
        assertEquals(ref1, ports.installed)
        ports.firstRenderResult = false
        val result = tx.apply(ref2)
        assertEquals(ApplyTransaction.Result.FirstRenderFailedRolledBack(ref2, ref1), result)
        // 运行态必须真切回上一好，而非只把记录写回（旧实现此处仍停在 ref2）
        assertEquals(ref1, ports.installed)
        assertEquals(1, f.active().generation)
    }

    @Test
    fun restoreBuildFailureFallsBackToDefault() = runBlocking {
        val f = Fixture()
        val ports = StagedPorts()
        val tx = ApplyTransaction(f.layout, f.store, ports) { 7_000L }
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        assertEquals(ApplyTransaction.Result.Applied(ref1), tx.apply(ref1))
        // 上一好代次在回滚重建时损坏：退默认，不留失败皮肤
        ports.buildBehavior = { ref -> ref != ref1 }
        ports.firstRenderResult = false
        val result = tx.apply(ref2)
        assertEquals(ApplyTransaction.Result.FirstRenderFailedRolledBack(ref2, null), result)
        assertEquals(null, ports.installed)
        assertEquals(null, f.active().skinId)
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

    // ---- 2026-11-05 审计 #1 项 2：回滚 fault injection ----
    // 结果、运行快照（ports.installed）、磁盘 active/LastGood 三者
    // 必须一致；任一步失败都返回 RollbackFailed，绝不声称已恢复。

    @Test
    fun restoreSwitchFalseFallsBackToDefault() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        assertEquals(ApplyTransaction.Result.Applied(ref1), f.tx.apply(ref1))
        assertEquals(ref1, f.ports.installed)
        // 新皮肤切换失败，且切回上一好也返回 false：退默认。
        f.ports.switchBehavior = { ref -> ref == null }
        val result = f.tx.apply(ref2)
        assertEquals(ApplyTransaction.Result.SwitchFailedRolledBack(ref2, null), result)
        assertEquals(null, f.ports.installed)
        assertEquals(null, f.active().skinId)
    }

    @Test
    fun restoreSwitchThrowsFallsBackToDefault() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        assertEquals(ApplyTransaction.Result.Applied(ref1), f.tx.apply(ref1))
        // 切上一好抛异常与返回 false 同义（runPort 口径）：退默认。
        f.ports.switchBehavior = { ref ->
            if (ref == null) true else throw IllegalStateException("switch boom")
        }
        val result = f.tx.apply(ref2)
        assertEquals(ApplyTransaction.Result.SwitchFailedRolledBack(ref2, null), result)
        assertEquals(null, f.ports.installed)
        assertEquals(null, f.active().skinId)
    }

    @Test
    fun restoreDefaultSwitchFalseReportsRollbackFailed() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        assertEquals(ApplyTransaction.Result.Applied(ref1), f.tx.apply(ref1))
        // 全部切换都失败：上一好切不回、默认也切不回。
        f.ports.switchBehavior = { false }
        val result = f.tx.apply(ref2)
        assertEquals(
            ApplyTransaction.Result.RollbackFailed(
                ref2, "switch_failed", "restore_default_switch_failed",
            ),
            result,
        )
        // 运行态仍是上一好（切换从未成功移动），记录仍是失败皮肤
        // 的 pending（未被声称为已恢复），Last-Good 仍为 gen1。
        assertEquals(ref1, f.ports.installed)
        assertEquals(2, f.active().generation)
        assertEquals(ApplyState.PENDING, f.active().state)
        val lastGood = (f.store.readLastGood() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, lastGood.generation)
    }

    @Test
    fun firstApplyRestoreDefaultSwitchFalseReportsRollbackFailed() = runBlocking {
        val f = Fixture()
        val ref = f.skinGen("s1", 1)
        f.ports.switchBehavior = { false }
        val result = f.tx.apply(ref)
        assertEquals(
            ApplyTransaction.Result.RollbackFailed(
                ref, "switch_failed", "restore_default_switch_failed",
            ),
            result,
        )
        assertEquals(null, f.ports.installed)
        assertEquals(1, f.active().generation)
        assertEquals(ApplyState.PENDING, f.active().state)
    }

    @Test
    fun restoreActiveWriteFailureReportsRollbackFailed() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        assertEquals(ApplyTransaction.Result.Applied(ref1), f.tx.apply(ref1))
        f.ports.firstRenderResult = false
        // 首渲判定后把 active.json 换成目录：恢复的切换能成功、写记录失败。
        f.ports.onFirstRender = { f.blockActiveWrite() }
        val result: ApplyTransaction.Result
        try {
            result = f.tx.apply(ref2)
        } finally {
            f.unblockActiveWrite()
        }
        assertEquals(
            ApplyTransaction.Result.RollbackFailed(
                ref2, "first_render_failed", "restore_active_write_failed",
            ),
            result,
        )
        // 运行态已切回上一好，但记录仍是失败皮肤的 pending（写被
        // 注入挡住、原文件未被改写），Last-Good 仍为 gen1。
        assertEquals(ref1, f.ports.installed)
        assertEquals(2, f.active().generation)
        assertEquals(ApplyState.PENDING, f.active().state)
        val lastGood = (f.store.readLastGood() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(1, lastGood.generation)
    }

    @Test
    fun restoreDefaultWriteFailureReportsRollbackFailed() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        val ref2 = f.skinGen("s1", 2, salt = "new")
        assertEquals(ApplyTransaction.Result.Applied(ref1), f.tx.apply(ref1))
        // 上一好重建失败 → 退默认；默认切换成功、写默认记录失败。
        f.ports.buildBehavior = { ref -> ref != ref1 }
        f.ports.firstRenderResult = false
        f.ports.onFirstRender = { f.blockActiveWrite() }
        val result: ApplyTransaction.Result
        try {
            result = f.tx.apply(ref2)
        } finally {
            f.unblockActiveWrite()
        }
        assertEquals(
            ApplyTransaction.Result.RollbackFailed(
                ref2, "first_render_failed", "restore_default_write_failed",
            ),
            result,
        )
        assertEquals(null, f.ports.installed)
        assertEquals(2, f.active().generation)
        assertEquals(ApplyState.PENDING, f.active().state)
    }

    @Test
    fun revertToDefaultWriteFailureReportsUnavailable() = runBlocking {
        val f = Fixture()
        val ref1 = f.skinGen("s1", 1)
        f.tx.apply(ref1)
        f.blockActiveWrite()
        val result: ApplyTransaction.Result
        try {
            result = f.tx.revertToDefault()
        } finally {
            f.unblockActiveWrite()
        }
        assertEquals(
            ApplyTransaction.Result.RollbackUnavailable("active_write_failed"),
            result,
        )
        assertEquals(null, f.ports.installed)
        assertEquals(1, f.active().generation)
        assertEquals(ApplyState.VERIFIED, f.active().state)
    }
}
