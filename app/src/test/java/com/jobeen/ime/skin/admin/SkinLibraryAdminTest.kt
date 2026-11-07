package com.jobeen.ime.skin.admin

import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyState
import com.jobeen.ime.skin.store.LastGoodPointer
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.LibraryReconciler
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 删除口径：先生效回退、Last-Good 清理、布局引用全量列出后可删。 */
class SkinLibraryAdminTest {

    /**
     * 可注入失败的 store：除 writeIndex 可按需抛错外，其余直通
     * 真实 store（fault injection 用；真实 store 是 final 类，
     * 无法子类化伪造写失败，见 SkinLibraryAdmin.AdminStore）。
     */
    private class FakeAdminStore(
        private val real: SkinLibraryStore,
    ) : SkinLibraryAdmin.AdminStore {
        var failNextWrite = false
        /** 下一次写索引时先把 active 记录翻成该值（模拟删除中途另应用了皮肤）。 */
        var flipActiveOnWrite: ActiveRecord? = null
        /** 下一次写索引前抛取消（模拟提交前、tombstone 已建时取消）。 */
        var cancelBeforeNextWrite = false
        /** 下一次写索引提交后抛取消（模拟提交后、调用方观测前取消）。 */
        var cancelAfterNextWrite = false

        override suspend fun readIndex(): SkinLibraryStore.ReadResult<LibraryIndex> =
            real.readIndex()

        override suspend fun readActive(): SkinLibraryStore.ReadResult<ActiveRecord> =
            real.readActive()

        override suspend fun readLastGood(): SkinLibraryStore.ReadResult<LastGoodPointer> =
            real.readLastGood()

        override suspend fun writeIndex(index: LibraryIndex) {
            if (failNextWrite) {
                failNextWrite = false
                throw java.io.IOException("injected index write failure")
            }
            if (cancelBeforeNextWrite) {
                cancelBeforeNextWrite = false
                throw CancellationException("injected cancel before delete commit")
            }
            flipActiveOnWrite?.let { real.writeActive(it); flipActiveOnWrite = null }
            real.writeIndex(index)
            if (cancelAfterNextWrite) {
                cancelAfterNextWrite = false
                throw CancellationException("injected cancel after delete commit")
            }
        }

        override suspend fun clearLastGood() = real.clearLastGood()
    }

    private class Fixture(
        val layout: SkinLibraryLayout,
        val store: SkinLibraryStore,
        val admin: SkinLibraryAdmin,
        val fake: FakeAdminStore,
    )

    /**
     * @param fallbackRetryDelayMs 待回退重试延迟：默认 60 秒——
     * 回退失败的测试不希望调度重试在断言期间跑起来；要测调度
     * 路径的测试显式传 0。
     */
    private fun fixture(fallbackRetryDelayMs: Long = 60_000L): Fixture {
        val root = Files.createTempDirectory("jime-admin").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val fake = FakeAdminStore(store)
        return Fixture(layout, store, SkinLibraryAdmin(layout, fake, fallbackRetryDelayMs), fake)
    }

    /** tombstone 残留：手造一次崩溃后的中间态（rename 已做、索引未提交）。 */
    private fun plantTombstone(
        f: Fixture,
        kind: String,
        id: String,
        withIntent: Boolean = true,
    ): File {
        val source = if (kind == "skin") File(f.layout.skinsDir, id) else File(f.layout.layoutsDir, id)
        val tombstone = File(File(f.layout.root, "trash"), "$kind-$id-123456789").apply { mkdirs() }
        val moved = source.renameTo(File(tombstone, if (kind == "skin") "skin" else "layout"))
        check(moved) { "plant tombstone failed" }
        if (withIntent) {
            File(tombstone, "delete-intent.json").writeText(
                """{"kind":"$kind","id":"$id","atMs":123}""",
            )
        }
        return tombstone
    }

    private fun trashChildren(f: Fixture): List<File> =
        File(f.layout.root, "trash").listFiles()?.toList().orEmpty()

    /** 异步 tombstone 清理：轮询等 trash 空（上限 10 秒）。 */
    private fun awaitTrashEmpty(f: Fixture) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (trashChildren(f).isEmpty()) return
            Thread.sleep(50)
        }
        throw AssertionError("trash not cleaned: ${trashChildren(f).map { it.name }}")
    }

    private suspend fun addSkin(
        f: Fixture,
        id: String,
        hash: String,
        layoutId: String? = null,
    ) {
        val genDir = f.layout.skinGenDir(id, 1).apply { mkdirs() }
        val layoutRef = layoutId?.let { ""","layoutId":"$it"""" } ?: ""
        File(genDir, "skin.json").writeText(
            """{"formatVersion":3,"meta":{"id":"$id","name":"$id"}$layoutRef}""",
        )
        File(genDir, "manifest.json").writeText("""{"formatVersion":3,"contents":[]}""")
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        f.store.writeIndex(
            index.copy(
                skins = index.skins + SkinIndexEntry(
                    id, id, hash, 1, "skins/$id/gen-1", 10L, 0L,
                ),
            ),
        )
    }

    @Test
    fun deletePlainSkinRemovesEntryAndDirs() = runBlocking {
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
    }

    @Test
    fun deleteActiveSkinCommitsFirstThenReverts() = runBlocking {
        // 审计问题 1 新顺序（原“先回退”口径已废止，测试随口径改，
        // 见施工单 5 第 4 条）：提交在回退之前；回退两次失败也不
        // 翻案——皮肤确已删干净，但不再谎报 Done：返回
        // DoneFallbackPending 并落盘待回退标记（施工单 6 第 4 条，
        // 用户定口径）；active 记录滞后指向已删皮肤（下次启动对账
        // 按 ActiveResolver 裁决收敛为默认）。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        var revertCalls = 0
        val result = f.admin.deleteSkin("s1") {
            revertCalls++
            false
        }
        assertEquals(SkinLibraryAdmin.Result.DoneFallbackPending("s1"), result)
        assertEquals(2, revertCalls) // 失败重试一次
        assertEquals("s1", f.layout.readPendingFallbackSkinId())
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        assertTrue(f.store.readLastGood() is SkinLibraryStore.ReadResult.Missing)
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s1", active.skinId) // 滞后记录：对账收敛
        // 回退成功支：写默认记录后 Done，active 已是默认
        val f2 = fixture()
        addSkin(f2, "s1", "hash1")
        f2.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f2.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        val ok = f2.admin.deleteSkin("s1") {
            // 模拟事务回退后 active 已清空
            f2.store.writeActive(ActiveRecord(appliedAt = 0L))
            true
        }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), ok)
        val active2 = (f2.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(null, active2.skinId)
        assertTrue(f2.store.readLastGood() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun deleteActiveSkin_indexWriteFailure_revertNeverRunsAndSkinStaysActive() = runBlocking {
        // 审计问题 1 验收支：回退成功、随后 tombstone／改名／索引
        // 提交失败——新顺序下提交在回退之前，索引提交失败时回退
        // 根本不运行；目录／索引／active 记录／Last-Good 全数保持
        // 删除前状态，皮肤完整仍生效（运行快照亦未动：管理层除
        // 回退回调外不碰运行态，回调未运行即快照未动）。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        f.fake.failNextWrite = true
        var revertCalls = 0
        val result = f.admin.deleteSkin("s1") {
            revertCalls++
            true
        }
        assertEquals(SkinLibraryAdmin.Result.Refused("index_write_failed"), result)
        assertEquals(0, revertCalls)
        val genDir = f.layout.skinGenDir("s1", 1)
        assertTrue(File(genDir, "skin.json").isFile)
        assertTrue(File(genDir, "manifest.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("s1"), index.skins.map { it.id })
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s1", active.skinId)
        assertEquals(ApplyState.VERIFIED, active.state)
        val lastGood = (f.store.readLastGood() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s1", lastGood.skinId)
        assertTrue(trashChildren(f).isEmpty())
    }

    @Test
    fun deleteActiveSkin_revertRetrySucceeds() = runBlocking {
        // 提交后回退第一次失败、重试成功：删除 Done，active 已是默认。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        var calls = 0
        val result = f.admin.deleteSkin("s1") {
            calls++
            if (calls == 1) {
                false // 模拟第一次写记录失败
            } else {
                f.store.writeActive(ActiveRecord(appliedAt = 0L))
                true
            }
        }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        assertEquals(2, calls)
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(null, active.skinId)
    }

    @Test
    fun deleteActiveSkin_keepsLastGoodPointingAtOtherSkin() = runBlocking {
        // 提交后现读现判：Last-Good 指向他皮肤时不清除。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        addSkin(f, "s2", "hash2")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s2", "hash2", 1))
        val result = f.admin.deleteSkin("s1") {
            f.store.writeActive(ActiveRecord(appliedAt = 0L))
            true
        }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val lastGood = (f.store.readLastGood() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s2", lastGood.skinId)
    }

    @Test
    fun deleteActiveSkin_activeChangedMidDelete_doesNotRevertNewSkin() = runBlocking {
        // 删除中途用户另应用了皮肤：提交后现读 active 已指新皮肤，
        // 不回退，新皮肤保持生效。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        addSkin(f, "s2", "hash2")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.fake.flipActiveOnWrite = ActiveRecord("s2", "hash2", 1, ApplyState.VERIFIED, 0L)
        var revertCalls = 0
        val result = f.admin.deleteSkin("s1") {
            revertCalls++
            true
        }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        assertEquals(0, revertCalls)
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s2", active.skinId)
    }

    @Test
    fun deleteActiveSkin_revertFailure_skipsPruneKeepsSharedVideo() = runBlocking {
        // 回退失败时跳过修剪：旧快照仍在装，共享视频不断粮；
        // 回退成功时正常修剪。失败支按施工单 6 第 4 条返回
        // DoneFallbackPending（原 Done 期望随口径改）。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        writeVideoManifest(f, "s1", "vidsha1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        val failed = f.admin.deleteSkin("s1") { false }
        assertEquals(SkinLibraryAdmin.Result.DoneFallbackPending("s1"), failed)
        assertTrue(File(f.layout.assetsDir, "vidsha1.mp4").isFile)

        val f2 = fixture()
        addSkin(f2, "s1", "hash1")
        writeVideoManifest(f2, "s1", "vidsha1")
        f2.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        val ok = f2.admin.deleteSkin("s1") {
            f2.store.writeActive(ActiveRecord(appliedAt = 0L))
            true
        }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), ok)
        assertFalse(File(f2.layout.assetsDir, "vidsha1.mp4").exists())
    }

    @Test
    fun deleteActiveSkin_revertTwiceFailed_retrySucceeds_clearsMarker() = runBlocking {
        // 施工单 6 第 4 条：两次回退失败返回 DoneFallbackPending，
        // 标记落盘；进程内重试成功后标记清除。回调是测试替身：
        // 生产里 revertToDefault 内部写默认记录，此处第三次调用
        // 模拟它——写默认记录后 active 收敛为默认。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        var calls = 0
        val result = f.admin.deleteSkin("s1") {
            calls++
            if (calls <= 2) {
                false
            } else {
                f.store.writeActive(ActiveRecord(appliedAt = 0L))
                true
            }
        }
        assertEquals(SkinLibraryAdmin.Result.DoneFallbackPending("s1"), result)
        assertEquals(2, calls)
        assertEquals("s1", f.layout.readPendingFallbackSkinId())
        val activeBeforeRetry =
            (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s1", activeBeforeRetry.skinId) // 回退前滞后记录仍指已删皮肤
        assertTrue(f.admin.retryPendingFallbackOnce())
        assertEquals(3, calls)
        assertEquals(null, f.layout.readPendingFallbackSkinId())
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(null, active.skinId)
    }

    @Test
    fun deleteActiveSkin_revertTwiceFailed_scheduledRetryRuns() = runBlocking {
        // 调度路径：延迟 0 时删除返回后重试任务自行跑起来，清标记；
        // 只轮询标记文件（上限 10 秒），与 awaitTrashEmpty 同口径。
        val f = fixture(fallbackRetryDelayMs = 0L)
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        var calls = 0
        val result = f.admin.deleteSkin("s1") {
            calls++
            if (calls <= 2) {
                false
            } else {
                f.store.writeActive(ActiveRecord(appliedAt = 0L))
                true
            }
        }
        assertEquals(SkinLibraryAdmin.Result.DoneFallbackPending("s1"), result)
        assertEquals("s1", f.layout.readPendingFallbackSkinId())
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            f.layout.readPendingFallbackSkinId() != null
        ) {
            delay(50)
        }
        assertEquals(null, f.layout.readPendingFallbackSkinId())
        assertEquals(3, calls)
    }

    @Test
    fun deleteActiveSkin_revertTwiceFailed_retryFailsAgain_markerStays() = runBlocking {
        // 重试也失败：标记保留，等下次启动对账收敛；结果仍是
        // DoneFallbackPending，不翻案。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        var calls = 0
        val result = f.admin.deleteSkin("s1") {
            calls++
            false
        }
        assertEquals(SkinLibraryAdmin.Result.DoneFallbackPending("s1"), result)
        assertEquals(2, calls)
        assertFalse(f.admin.retryPendingFallbackOnce())
        assertEquals(3, calls)
        assertEquals("s1", f.layout.readPendingFallbackSkinId())
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s1", active.skinId)
    }

    @Test
    fun deleteActiveSkin_revertTwiceFailed_processKilled_reconcilerConverges() = runBlocking {
        // 进程被杀模拟：删除返回 DoneFallbackPending 后丢掉 Admin
        // （重试延迟 60 秒，本测试内不跑），全新对账器对同一目录
        // 做启动对账——active 收敛为默认，标记清除。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        val result = f.admin.deleteSkin("s1") { false }
        assertEquals(SkinLibraryAdmin.Result.DoneFallbackPending("s1"), result)
        assertEquals("s1", f.layout.readPendingFallbackSkinId())
        LibraryReconciler(f.layout, f.store).reconcile()
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(null, active.skinId)
        assertEquals(null, f.layout.readPendingFallbackSkinId())
    }

    @Test
    fun deleteActiveSkin_revertTwiceFailed_applyOtherSkin_retrySkips() = runBlocking {
        // 用户在重试前另应用了皮肤：重试现读 active 已指他皮肤，
        // 跳过并清标记，绝不把新皮肤回退掉；回调不再调用。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        var calls = 0
        val result = f.admin.deleteSkin("s1") {
            calls++
            false
        }
        assertEquals(SkinLibraryAdmin.Result.DoneFallbackPending("s1"), result)
        assertEquals(2, calls)
        f.store.writeActive(ActiveRecord("s2", "hash2", 1, ApplyState.VERIFIED, 0L))
        assertTrue(f.admin.retryPendingFallbackOnce())
        assertEquals(2, calls)
        assertEquals(null, f.layout.readPendingFallbackSkinId())
    }

    /** 代次清单里挂一个视频资产（复用 VideoAssetPrunerTest 的写法）。 */
    private fun writeVideoManifest(f: Fixture, id: String, sha: String) {
        val contents = listOf(
            PackageManifest.Content(PackageManifest.TYPE_ASSET, "vid-$id", "assets/vid-$id.mp4", sha),
        )
        File(f.layout.skinGenDir(id, 1), "manifest.json").writeText(
            PackageManifest.toJson(PackageManifest(3, contents)),
        )
        File(f.layout.assetsDir, "$sha.mp4").writeBytes(byteArrayOf(9))
    }

    private suspend fun addLayout(f: Fixture, id: String) {
        val layoutGen = f.layout.layoutGenDir(id, 1).apply { mkdirs() }
        File(layoutGen, "layout.json").writeText("""{"rows":[]}""")
        val index = (f.store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        f.store.writeIndex(
            index.copy(
                layouts = index.layouts + LayoutIndexEntry(
                    id, "lhash-$id", 1, "layouts/$id/gen-1", 5L, 0L,
                ),
            ),
        )
    }

    @Test
    fun layoutReferrersListsAllReferencingSkins() = runBlocking {
        val f = fixture()
        addLayout(f, "lay-1")
        addLayout(f, "lay-2")
        addSkin(f, "s1", "hash1", layoutId = "lay-1")
        addSkin(f, "s2", "hash2", layoutId = "lay-1")
        addSkin(f, "s3", "hash3", layoutId = "lay-2")
        addSkin(f, "s4", "hash4")
        val referrers = f.admin.layoutReferrers("lay-1")
        assertEquals(listOf("s1", "s2"), referrers.map { it.skinId })
        assertEquals(listOf("s3"), f.admin.layoutReferrers("lay-2").map { it.skinId })
        assertTrue(f.admin.layoutReferrers("lay-none").isEmpty())
    }

    @Test
    fun deleteReferencedLayoutNowSucceedsAndKeepsSkinFiles() = runBlocking {
        // B3 §4.6 新口径（取代旧的硬拒绝）：列出→警告→可删→引用方回退
        val f = fixture()
        addLayout(f, "lay-1")
        addSkin(f, "s1", "hash1", layoutId = "lay-1")
        val result = f.admin.deleteLayout("lay-1")
        assertEquals(SkinLibraryAdmin.Result.Done("lay-1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.layouts.isEmpty())
        assertFalse(File(f.layout.layoutsDir, "lay-1").exists())
        // 引用方皮肤文件原样保留（代次不可变；绑定靠重解析自然悬空回退）
        assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        assertEquals(1, index.skins.size)
    }

    @Test
    fun deleteLayoutRefusals() = runBlocking {
        val f = fixture()
        // 索引未落盘 → index_unavailable；空索引 → not_found
        assertEquals(
            SkinLibraryAdmin.Result.Refused("index_unavailable"),
            f.admin.deleteLayout("nope"),
        )
        f.store.writeIndex(LibraryIndex())
        assertEquals(
            SkinLibraryAdmin.Result.Refused("not_found"), f.admin.deleteLayout("nope"),
        )
    }

    // ---- 审计 #1-5：tombstone 删除事务与崩溃恢复 ----

    @Test
    fun deleteSkin_indexWriteFailure_restoresDirAndKeepsIndex() = runBlocking {
        // 崩溃点 2（索引提交失败）：目录必须原样回来、索引不动、
        // tombstone 不留；调用方拿到 Refused 而非删除后抛异常。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.fake.failNextWrite = true
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Refused("index_write_failed"), result)
        val genDir = f.layout.skinGenDir("s1", 1)
        assertTrue(File(genDir, "skin.json").isFile)
        assertTrue(File(genDir, "manifest.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("s1"), index.skins.map { it.id })
        assertTrue(trashChildren(f).isEmpty())
    }

    @Test
    fun deleteLayout_indexWriteFailure_restoresDirAndKeepsIndex() = runBlocking {
        val f = fixture()
        addLayout(f, "lay-1")
        f.fake.failNextWrite = true
        val result = f.admin.deleteLayout("lay-1")
        assertEquals(SkinLibraryAdmin.Result.Refused("index_write_failed"), result)
        assertTrue(File(f.layout.layoutGenDir("lay-1", 1), "layout.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("lay-1"), index.layouts.map { it.id })
        assertTrue(trashChildren(f).isEmpty())
    }

    @Test
    fun deleteSkin_success_cleansTombstoneAsync() = runBlocking {
        val f = fixture()
        addSkin(f, "s1", "hash1")
        // 连带草稿一并进 tombstone、随索引提交清理
        File(f.layout.draftsDir, "s1").mkdirs()
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        assertFalse(File(f.layout.draftsDir, "s1").exists())
        awaitTrashEmpty(f)
    }

    @Test
    fun deleteSkin_missingDirStillRemovesIndexEntry() = runBlocking {
        // 目录已先行消失（对账/外部清理）：删除仍提交索引，
        // 与旧 deleteRecursively 空操作口径一致。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        File(f.layout.skinsDir, "s1").deleteRecursively()
        val result = f.admin.deleteSkin("s1") { true }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
    }

    @Test
    fun deleteSkin_withRealStore_forwardsThroughAdapter() = runBlocking {
        // 真实 store 直通适配（RealAdminStore）：生产构造路径
        // 覆盖全部 5 个 store 方法（读索引/读 active/读 lastGood/
        // 写索引/清 lastGood），防接口委托增量陷阱。
        val root = Files.createTempDirectory("jime-admin-real").toFile()
        val layout = SkinLibraryLayout(root).apply { ensureDirs() }
        val store = SkinLibraryStore(layout)
        val admin = SkinLibraryAdmin(layout, store)
        val genDir = layout.skinGenDir("s1", 1).apply { mkdirs() }
        File(genDir, "skin.json").writeText(
            """{"formatVersion":3,"meta":{"id":"s1","name":"s1"}}""",
        )
        File(genDir, "manifest.json").writeText("""{"formatVersion":3,"contents":[]}""")
        store.writeIndex(
            LibraryIndex(
                skins = listOf(
                    SkinIndexEntry("s1", "s1", "hash1", 1, "skins/s1/gen-1", 10L, 0L),
                ),
            ),
        )
        store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        val result = admin.deleteSkin("s1") {
            store.writeActive(ActiveRecord(appliedAt = 0L))
            true
        }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), result)
        assertTrue(store.readLastGood() is SkinLibraryStore.ReadResult.Missing)
    }

    @Test
    fun recoverPendingDeletes_rollsBackUncommittedDelete() = runBlocking {
        // 崩溃点 1（rename 后、索引提交前被杀）：tombstone 在、
        // 旧索引仍指向该 id → 恢复目录名，删除如同没发生。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        f.admin.recoverPendingDeletes()
        assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("s1"), index.skins.map { it.id })
        assertFalse(tombstone.exists())
    }

    @Test
    fun recoverPendingDeletes_rollsBackWithoutIntentFile() = runBlocking {
        // intent 没落盘：按目录名 `<kind>-<id>-<nanoTime>` 回退解析，
        // 同样回滚，不留不可裁决的孤儿。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1", withIntent = false)
        f.admin.recoverPendingDeletes()
        assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        assertFalse(tombstone.exists())
    }

    @Test
    fun recoverPendingDeletes_completesCommittedDelete() = runBlocking {
        // 崩溃点 3（索引提交后、tombstone 清理前被杀）：新索引
        // 已不含该 id → 删 tombstone，继续完成删除。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        f.store.writeIndex(LibraryIndex())
        f.admin.recoverPendingDeletes()
        assertFalse(tombstone.exists())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
    }

    @Test
    fun recoverPendingDeletes_staleTombstoneDoesNotClobberNewSkin() = runBlocking {
        // 崩溃后同 id 被重新导入：索引仍指向该 id、目标目录已
        // 存在 → tombstone 是过期残留，直接清理，不覆盖新目录。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        val genDir = f.layout.skinGenDir("s1", 1).apply { mkdirs() }
        File(genDir, "skin.json").writeText(
            """{"formatVersion":3,"meta":{"id":"s1","name":"new"}}""",
        )
        File(genDir, "manifest.json").writeText("""{"formatVersion":3,"contents":[]}""")
        f.admin.recoverPendingDeletes()
        assertFalse(tombstone.exists())
        assertEquals(
            "new",
            File(genDir, "skin.json").readText().substringAfter("\"name\":\"").substringBefore("\""),
        )
    }

    @Test
    fun recoverPendingDeletes_layoutRollback() = runBlocking {
        val f = fixture()
        addLayout(f, "lay-1")
        val tombstone = plantTombstone(f, "layout", "lay-1")
        f.admin.recoverPendingDeletes()
        assertTrue(File(f.layout.layoutGenDir("lay-1", 1), "layout.json").isFile)
        assertFalse(tombstone.exists())
    }

    @Test
    fun recoverPendingDeletes_restoreFailure_keepsTombstone() = runBlocking {
        // 回滚搬回失败时 tombstone 必须保留（不得删未归位的数据），
        // 下次恢复再试；索引仍指向该 id。写权限恢复后回滚成功。
        // 以 Assume 跳过 root 用户环境（chmod 对 root 无效）。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val tombstone = plantTombstone(f, "skin", "s1")
        val skinsDir = f.layout.skinsDir
        try {
            skinsDir.setWritable(false)
            org.junit.Assume.assumeTrue(
                "restore failure needs a non-writable skins dir",
                !skinsDir.canWrite(),
            )
            f.admin.recoverPendingDeletes()
            assertTrue(tombstone.exists())
            assertTrue(File(tombstone, "skin").exists())
            val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
            assertEquals(listOf("s1"), index.skins.map { it.id })
            skinsDir.setWritable(true)
            f.admin.recoverPendingDeletes()
            assertFalse(tombstone.exists())
            assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
        } finally {
            skinsDir.setWritable(true)
        }
    }

    @Test
    fun deleteSkin_renameFailure_refusesAndKeepsEverything() = runBlocking {
        // rename 失败（源目录不可写）：索引与目录都不动。
        // 以 Assume 跳过 root 用户环境（chmod 对 root 无效）。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        val skinsDir = f.layout.skinsDir
        try {
            skinsDir.setWritable(false)
            org.junit.Assume.assumeTrue(
                "rename failure needs a non-writable skins dir",
                !skinsDir.canWrite(),
            )
            val result = f.admin.deleteSkin("s1") { true }
            assertEquals(SkinLibraryAdmin.Result.Refused("rename_failed"), result)
            assertTrue(File(f.layout.skinGenDir("s1", 1), "skin.json").isFile)
            val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
            assertEquals(listOf("s1"), index.skins.map { it.id })
        } finally {
            skinsDir.setWritable(true)
        }
    }

    @Test
    fun deleteSkin_cancelAfterIndexCommit_doesNotRestoreAndRethrows() = runBlocking {
        // 删除写入后取消：索引提交已落盘、随后取消——不回滚（目录
        // 不搬回）、取消重抛；tombstone 留待恢复流程清理，恢复后收敛。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        f.fake.cancelAfterNextWrite = true
        val deferred = async { f.admin.deleteSkin("s1") { true } }
        val error = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
        assertFalse(File(f.layout.skinsDir, "s1").exists())
        assertEquals(1, trashChildren(f).size)
        f.admin.recoverPendingDeletes()
        assertTrue(trashChildren(f).isEmpty())
    }

    @Test
    fun deleteSkin_cancelBeforeIndexCommit_restoresAndRethrows() = runBlocking {
        // 删除写入前取消：索引未提交——重读索引确认 id 仍在，目录
        // 搬回原位、tombstone 清理，取消重抛；索引与目录保持删除前状态。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        f.store.writeActive(ActiveRecord("s1", "hash1", 1, ApplyState.VERIFIED, 0L))
        f.store.writeLastGood(LastGoodPointer("s1", "hash1", 1))
        f.fake.cancelBeforeNextWrite = true
        val deferred = async { f.admin.deleteSkin("s1") { true } }
        val error = runCatching { deferred.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        val genDir = f.layout.skinGenDir("s1", 1)
        assertTrue(File(genDir, "skin.json").isFile)
        assertTrue(File(genDir, "manifest.json").isFile)
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals(listOf("s1"), index.skins.map { it.id })
        assertTrue(trashChildren(f).isEmpty())
        val active = (f.store.readActive() as SkinLibraryStore.ReadResult.Ok).value
        assertEquals("s1", active.skinId)
    }

    @Test
    fun concurrentDeletes_doNotResurrectEachOther() = runBlocking {
        // 旧读-改-写竞态：两次并发删除互相覆盖写回、复活对方条目。
        // 删除串行化后两者都提交，索引最终为空。
        val f = fixture()
        addSkin(f, "s1", "hash1")
        addSkin(f, "s2", "hash2")
        val first = async { f.admin.deleteSkin("s1") { true } }
        val second = async { f.admin.deleteSkin("s2") { true } }
        assertEquals(SkinLibraryAdmin.Result.Done("s1"), first.await())
        assertEquals(SkinLibraryAdmin.Result.Done("s2"), second.await())
        val index = (f.store.readIndex() as SkinLibraryStore.ReadResult.Ok).value
        assertTrue(index.skins.isEmpty())
    }
}
