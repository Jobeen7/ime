package com.jobeen.ime.skin.admin

import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.LastGoodPointer
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import com.jobeen.ime.skin.store.VideoAssetPruner
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 皮肤库管理操作（详设 §2）：删除是 B1 唯一的破坏性库操作，
 * 口径写死——
 *
 * - 删生效中的皮肤：先删（tombstone + 原子索引提交），提交
 *   成功后再经应用事务回退内置默认（运行态永不指向已删代次）；
 *   提交前任一步失败则删除如同没发生；回退失败不翻案（皮肤确
 *   已删，滞后记录由下次启动对账收敛，见 deleteSkinInner 第 4 步）。
 * - Last-Good 指针指向被删皮肤：一并清除（指针不得悬空）。
 * - 连带删除其草稿（草稿归属该 id，一并失效）。
 * - 删布局（B3 §4.6 按定稿改）：不再因被引用而拒绝——调用方先经
 *   [layoutReferrers] 列出全部引用方并警告，用户确认后可删；
 *   引用方皮肤文件不改（代次不可变），其快照在下次构建时按
 *   layoutId 未设处理并回退默认布局。连带其布局草稿。
 * - 资产实体不在此处回收：引用计数由启动对账全量重建（七.5）。
 *
 * 删除事务（tombstone + 原子索引提交，审计 #1-5；2026-11-07
 * 审计问题 1 重排：回退挪到索引提交之后）：
 * 旧顺序「先删目录、后写索引」在索引写失败时留下「目录已删、
 * 旧索引仍指向」的半截状态；「先回退、后删」的旧顺序则在回退
 * 成功后任一步失败时留下「目录／索引仍在、运行态与记录已是
 * 默认」的半截状态，且管理层无 writeActive 可恢复。新顺序分
 * 四步，提交前任一步失败都不丢数据、且不碰生效态：
 *
 * 1. 把待删目录原子 rename 到同库 `trash/` 下的 tombstone
 *    （同文件系统内 rename，成功即整目录瞬间搬走；tombstone
 *    内含 `delete-intent.json`，记 kind/id/atMs，是崩溃恢复的凭据）。
 * 2. 提交新索引（[SkinLibraryStore.writeIndex] 经 [com.jobeen.ime.skin.store.AtomicFiles]
 *    tmp+rename 原子替换，旧索引或新索引完整二选一）；失败则
 *    把目录从 tombstone 原样 rename 回去，返回
 *    Refused（index_write_failed），索引与目录都保持删除前状态。
 * 3. 索引成功后才清 Last-Good（现读现判），并异步清理 tombstone。
 * 4. 删的是生效皮肤时才回退内置默认（提交后现读 active 判定）；
 *    回退先重试一次，两次失败仍返回 Done（皮肤确已删干净）并记
 *    日志：active 记录滞后指向已删皮肤，下次启动对账按
 *    ActiveResolver 裁决收敛为默认，不悬空；修剪放回退之后，
 *    回退失败时跳过（孤儿资产由下次启动异步回收）。
 *
 * 进程在四处被杀的收敛（[recoverPendingDeletes]，每次删除前与
 * 下次管理操作时执行；启动对账本身也会收敛悬空条目）：
 * - rename 后、索引提交前被杀：tombstone 在、旧索引仍指向该 id
 *   → 恢复目录名（回滚），删除如同没发生，生效态全程未动；
 * - 索引提交后、回退前被杀：tombstone 在、新索引已
 *   不含该 id → 删 tombstone（继续完成删除）；active 记录仍
 *   指已删皮肤，下次启动对账按 ActiveResolver 裁决收敛为默认；
 * - 索引写进行中被杀：索引原子写保证旧版或新版完整二选一，
 *   按上两条之一收敛，绝不出现半截索引；
 * - 回退进行中被杀：切换与写记录各至多完成其一；记录若已是
 *   默认则一致，若仍指已删皮肤则下次启动对账收敛为默认；
 *   运行态随进程重建，不残留。
 * 若同一 id 在崩溃后又被重新导入（目标目录已存在），tombstone
 * 视为过期残留、直接清理，不覆盖新目录。
 *
 * 并发：删除全程串行（[deleteMutex]），旧「读-改-写索引」在
 * 两次并发删除下会互相覆盖、复活对方条目的竞态就此关闭。
 */
class SkinLibraryAdmin internal constructor(
    private val layout: SkinLibraryLayout,
    private val store: AdminStore,
) {

    /** 生产入口：包一层真实 store，调用方签名不变。 */
    constructor(layout: SkinLibraryLayout, store: SkinLibraryStore) :
        this(layout, RealAdminStore(store))

    sealed interface Result {
        data class Done(val id: String) : Result
        data class Refused(val reason: String) : Result
    }

    /** 删除串行化：读索引→搬目录→写索引整个事务不可交错。 */
    private val deleteMutex = Mutex()

    /** tombstone 异步清理作用域（SupervisorJob：清理失败不牵连其它任务）。 */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 崩溃恢复区：与 skins/layouts 平级的 trash/，对账扫描不到它。 */
    private val trashDir: File get() = File(layout.root, "trash")

    /**
     * @param revertToDefault 删的是生效皮肤时，索引提交后由调用方
     * 执行的回退（应用事务 revertToDefault），返回是否成功；非生效
     * 皮肤、或提交后 active 已改指他皮肤时不调用。
     */
    /**
     * 删除被拒绝记日志（2026-10-27 硬要求）：种类 + id + 原因。
     */
    suspend fun deleteSkin(
        skinId: String,
        revertToDefault: suspend () -> Boolean,
    ): Result {
        val result = deleteSkinInner(skinId, revertToDefault)
        if (result is Result.Refused) {
            DiagLog.w(
                "admin", "delete_refused",
                mapOf("kind" to "skin", "id" to skinId, "reason" to result.reason),
            )
        }
        return result
    }

    private suspend fun deleteSkinInner(
        skinId: String,
        revertToDefault: suspend () -> Boolean,
    ): Result = deleteMutex.withLock {
        recoverPendingDeletesLocked()
        val index = readIndex() ?: return@withLock Result.Refused("index_unavailable")
        val entry = index.skins.firstOrNull { it.id == skinId }
            ?: return@withLock Result.Refused("not_found")
        // 顺序（审计问题 1）：破坏性步骤先行、回退最后。旧顺序
        // 先回退再删：回退成功后 tombstone／改名／索引提交任一步
        // 失败，目录已恢复、索引仍在，但运行快照与 active 记录
        // 已是默认——五项（目录／索引／active／Last-Good／快照）
        // 不一致，且 AdminStore 没有 writeActive，管理层在结构上
        // 无法把活动皮肤恢复回来。新顺序把回退挪到索引提交之后：
        // 提交前任一步失败，active 记录／运行快照／Last-Good
        // 全程未动，删除如同没发生，无需恢复路径。
        // 1. 先搬 tombstone，再提交索引（目录在索引提交前绝不消失）。
        val tombstone = prepareTombstone("skin", skinId)
            ?: return@withLock Result.Refused("tombstone_failed")
        val movedSkin = moveToTombstone(File(layout.skinsDir, skinId), File(tombstone, "skin"))
        val movedDrafts =
            moveToTombstone(File(layout.draftsDir, skinId), File(tombstone, "drafts"))
        if (!movedSkin || !movedDrafts) {
            // 搬移失败：已搬走的搬回；搬回失败时 tombstone 留待
            // 下次恢复重试（绝不删一个还没归位的 tombstone）。
            if (movedSkin || movedDrafts) {
                if (!restoreTombstone(tombstone, "skin", skinId)) {
                    return@withLock Result.Refused("rename_failed")
                }
            }
            runCatching { tombstone.deleteRecursively() }
            return@withLock Result.Refused("rename_failed")
        }
        // 2. 提交索引：失败则原样恢复目录，索引与目录都保持删除前状态。
        val committed = runCatching {
            store.writeIndex(index.copy(skins = index.skins.filterNot { it.id == skinId }))
        }.isSuccess
        if (!committed) {
            // 恢复失败时 tombstone 留待下次恢复重试（索引仍指向
            // 该 id，恢复会按「未提交」回滚；绝不删未归位的 tombstone）。
            if (restoreTombstone(tombstone, "skin", skinId)) {
                runCatching { tombstone.deleteRecursively() }
            }
            DiagLog.w(
                "admin", "delete_index_failed_rolled_back",
                mapOf("kind" to "skin", "id" to skinId),
            )
            return@withLock Result.Refused("index_write_failed")
        }
        // 3. 索引已提交：异步清理 tombstone，再清指针（清指针放
        // 提交后：提交前崩溃由启动对账清悬空指针；提交后指针若
        // 未清，仍指向一个已不在索引里的 id，对账同样会清，不悬空）。
        // active／Last-Good 在此现读现判：提交前读的值在删除与
        // 提交之间可能已变（用户另应用了皮肤），过期判定会误清
        // 新指针、误回退新皮肤。
        scheduleTombstoneCleanup(tombstone)
        val lastGood = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
        if (lastGood?.skinId == skinId) {
            store.clearLastGood()
        }
        // 4. 提交后才回退生效态：删的是生效皮肤时才回退（现读
        //    active 记录判定，中途改生效的皮肤不在此列）。
        //    回退失败不翻案——目录与索引已无该皮肤，删除确已
        //    成功，返回 Done；先重试一次（写记录的瞬时磁盘错
        //    可自愈），两次都失败只记日志：active 记录滞后指向
        //    已删皮肤，下次启动对账（LibraryReconciler 步骤 3）
        //    按 ActiveResolver 裁决收敛为默认，不悬空。
        //    修剪放回退之后：键图／字体／GIF／视频均为装后按
        //    共享资产路径懒读，旧快照仍在装时修剪会让键面短暂
        //    掉图；回退失败时跳过修剪（孤儿资产由下次启动异步
        //    pruneOrphanAssets 回收），旧快照在装期间不断粮。
        val active = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
        val revertOk = if (active?.skinId == skinId) {
            var ok = revertToDefault()
            if (!ok) ok = revertToDefault()
            if (!ok) {
                DiagLog.w(
                    "admin", "delete_revert_failed_after_commit",
                    mapOf("kind" to "skin", "id" to skinId),
                )
            }
            ok
        } else {
            true
        }
        if (revertOk) {
            // 删皮肤后清无主视频资产（2026-10-29 外观轮）：共享资产
            // 目录不随皮肤目录走，末引用者删后视频即成孤儿。
            VideoAssetPruner.pruneUnreferencedVideos(layout)
            // 第 5 批 §1：同口径清无主非视频资产（模型引用 + 剩余
            // 引用计数为零 + canonical 落在素材根内才删；图片缓存
            // 不全局清，见 SkinAssetPruner 注释——生效皮肤的键图
            // 字节源随快照，删非生效皮肤不得断它）。
            com.jobeen.ime.skin.store.SkinAssetPruner.pruneUnreferenced(layout)
        }
        Result.Done(skinId)
    }

    /** 引用某布局的全部皮肤（删除警告与删后回退判定共用此一份事实）。 */
    data class LayoutReferrer(val skinId: String, val skinName: String)

    suspend fun layoutReferrers(layoutId: String): List<LayoutReferrer> {
        val index = readIndex() ?: return emptyList()
        return index.skins.mapNotNull { skin ->
            val tree = runCatching {
                SkinJson.parseObject(
                    File(layout.fileFromRelative(skin.dir), "skin.json").readText(),
                )
            }.getOrNull()
            val referenced = (tree?.get("layoutId") as? JsonPrimitive)
                ?.takeIf { it.isString }?.content
            if (referenced != layoutId) {
                null
            } else {
                val metaName = ((tree?.get("meta") as? kotlinx.serialization.json.JsonObject)
                    ?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                LayoutReferrer(skin.id, metaName ?: skin.name.ifBlank { skin.id })
            }
        }
    }

    suspend fun deleteLayout(layoutId: String): Result {
        val result = deleteLayoutInner(layoutId)
        if (result is Result.Refused) {
            DiagLog.w(
                "admin", "delete_refused",
                mapOf("kind" to "layout", "id" to layoutId, "reason" to result.reason),
            )
        }
        return result
    }

    private suspend fun deleteLayoutInner(layoutId: String): Result = deleteMutex.withLock {
        recoverPendingDeletesLocked()
        val index = readIndex() ?: return@withLock Result.Refused("index_unavailable")
        val entry = index.layouts.firstOrNull { it.id == layoutId }
            ?: return@withLock Result.Refused("not_found")
        // 被引用不再拒绝（B3 §4.6）：引用方经快照重解析回退，本层
        // 只做删除本身（目录 + 索引），事务口径与删皮肤相同。
        val tombstone = prepareTombstone("layout", layoutId)
            ?: return@withLock Result.Refused("tombstone_failed")
        val moved = moveToTombstone(
            File(layout.layoutsDir, layoutId), File(tombstone, "layout"),
        )
        if (!moved) {
            runCatching { tombstone.deleteRecursively() }
            return@withLock Result.Refused("rename_failed")
        }
        val committed = runCatching {
            store.writeIndex(index.copy(layouts = index.layouts.filterNot { it.id == layoutId }))
        }.isSuccess
        if (!committed) {
            // 恢复失败时 tombstone 留待下次恢复重试（索引仍指向
            // 该 id，恢复会按「未提交」回滚；绝不删未归位的 tombstone）。
            if (restoreTombstone(tombstone, "layout", layoutId)) {
                runCatching { tombstone.deleteRecursively() }
            }
            DiagLog.w(
                "admin", "delete_index_failed_rolled_back",
                mapOf("kind" to "layout", "id" to layoutId),
            )
            return@withLock Result.Refused("index_write_failed")
        }
        scheduleTombstoneCleanup(tombstone)
        Result.Done(layoutId)
    }

    /**
     * 崩溃恢复：按 trash/ 下的 tombstone 继续或回滚未完成的删除。
     * 索引不可读时无法裁决，一律保留、留待下次（绝不凭猜测删数据）。
     * 无凭据的 tombstone（intent 丢失且目录名无法解析）同样保留，
     * 只记日志——删错数据的代价远大于留一个孤儿目录。
     */
    internal suspend fun recoverPendingDeletes() {
        deleteMutex.withLock { recoverPendingDeletesLocked() }
    }

    private suspend fun recoverPendingDeletesLocked() {
        val tombstones = runCatching {
            trashDir.listFiles { f -> f.isDirectory }
        }.getOrNull().orEmpty()
        if (tombstones.isEmpty()) return
        val index = readIndex()
        if (index == null) {
            DiagLog.w(
                "admin", "tombstone_recovery_deferred",
                mapOf("reason" to "index_unavailable", "count" to tombstones.size),
            )
            return
        }
        for (tombstone in tombstones) {
            val intent = readIntent(tombstone) ?: parseTombstoneName(tombstone.name)
            if (intent == null) {
                DiagLog.w(
                    "admin", "tombstone_unreadable",
                    mapOf("dir" to tombstone.name),
                )
                continue
            }
            val referenced = when (intent.kind) {
                "skin" -> index.skins.any { it.id == intent.id }
                "layout" -> index.layouts.any { it.id == intent.id }
                else -> {
                    DiagLog.w(
                        "admin", "tombstone_unknown_kind",
                        mapOf("dir" to tombstone.name, "kind" to intent.kind),
                    )
                    continue
                }
            }
            if (!referenced) {
                // 删除已提交（索引已不含该 id）：继续完成删除。
                runCatching { tombstone.deleteRecursively() }
                DiagLog.i(
                    "admin", "tombstone_committed_cleaned",
                    mapOf("kind" to intent.kind, "id" to intent.id),
                )
                continue
            }
            val target = targetDir(intent.kind, intent.id)
            if (target.exists()) {
                // 索引仍指向该 id、且目标目录已存在（崩溃后重建的
                // 同 id 新目录）：tombstone 是过期残留，不覆盖新目录。
                runCatching { tombstone.deleteRecursively() }
                DiagLog.i(
                    "admin", "tombstone_stale_cleaned",
                    mapOf("kind" to intent.kind, "id" to intent.id),
                )
            } else {
                // 删除未提交（索引仍指向该 id、目录不在原位）：回滚。
                // 恢复失败时 tombstone 保留，下次再试（绝不删未归位的数据）。
                if (restoreTombstone(tombstone, intent.kind, intent.id)) {
                    runCatching { tombstone.deleteRecursively() }
                    DiagLog.i(
                        "admin", "tombstone_rolled_back",
                        mapOf("kind" to intent.kind, "id" to intent.id),
                    )
                }
            }
        }
    }

    private suspend fun readIndex(): LibraryIndex? =
        (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value

    // ---- tombstone 原语（不抛：失败以返回值/日志表达） ----

    private data class DeleteIntent(val kind: String, val id: String, val atMs: Long)

    /**
     * 建 tombstone 并写删除凭据。目录名本身也编码 kind/id/时间
     * （intent 万一没落盘，恢复仍可按名解析，不留不可裁决的孤儿）。
     * 任一步失败返回 null，调用方不得动原目录。
     */
    private fun prepareTombstone(kind: String, id: String): File? {
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tombstone = File(trashDir, "$kind-$safeId-${System.nanoTime()}")
        return runCatching {
            tombstone.mkdirs()
            File(tombstone, INTENT_FILE).writeText(
                Json.encodeToString(
                    JsonObject.serializer(),
                    JsonObject(
                        mapOf(
                            "kind" to JsonPrimitive(kind),
                            "id" to JsonPrimitive(id),
                            "atMs" to JsonPrimitive(System.currentTimeMillis()),
                        ),
                    ),
                ),
            )
            tombstone
        }.getOrNull()
    }

    private fun readIntent(tombstone: File): DeleteIntent? {
        val text = runCatching { File(tombstone, INTENT_FILE).readText() }.getOrNull()
            ?: return null
        val obj = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return null
        val kind = (obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return null
        val atMs = (obj["atMs"] as? JsonPrimitive)?.longOrNull ?: return null
        if (kind != "skin" && kind != "layout") return null
        return DeleteIntent(kind, id, atMs)
    }

    /** 目录名回退解析：`<kind>-<safeId>-<nanoTime>`（nanoTime 全数字）。 */
    private fun parseTombstoneName(name: String): DeleteIntent? {
        val match = TOMBSTONE_NAME_REGEX.matchEntire(name) ?: return null
        val kind = match.groupValues[1]
        val id = match.groupValues[2]
        val atMs = match.groupValues[3].toLongOrNull() ?: return null
        if (kind != "skin" && kind != "layout") return null
        return DeleteIntent(kind, id, atMs)
    }

    /**
     * 同库 rename：源不存在视为已删完（返回 true，不报错）；
     * 源存在但 rename 失败返回 false，调用方据此回滚/拒绝。
     * 同根目录内 rename 是原子操作，不跨文件系统。
     */
    private fun moveToTombstone(source: File, target: File): Boolean {
        if (!source.exists()) return true
        return source.renameTo(target)
    }

    /** 把 tombstone 里的目录搬回原位（目标已存在时不覆盖，只记日志）。
     * 返回是否全部归位：任一分部搬回失败即返回 false，调用方不得
     * 删 tombstone（留待下次恢复重试）。顺序：草稿先、主目录后——
     * 这样半途失败时主目录必仍在 tombstone 里、目标仍缺失，下次
     * 恢复必按「未提交」重试，不会把 tombstone 误判为过期残留。 */
    private fun restoreTombstone(tombstone: File, kind: String, id: String): Boolean {
        var ok = true
        if (kind == "skin") {
            val drafts = File(tombstone, "drafts")
            if (drafts.exists()) {
                val restored = runCatching {
                    drafts.renameTo(File(layout.draftsDir, id))
                }.getOrDefault(false)
                if (!restored) {
                    DiagLog.w(
                        "admin", "tombstone_restore_failed",
                        mapOf("kind" to kind, "id" to id, "part" to "drafts"),
                    )
                    ok = false
                }
            }
        }
        val sub = if (kind == "skin") "skin" else "layout"
        val moved = File(tombstone, sub)
        if (moved.exists()) {
            val restored = runCatching {
                moved.renameTo(targetDir(kind, id))
            }.getOrDefault(false)
            if (!restored) {
                DiagLog.w(
                    "admin", "tombstone_restore_failed",
                    mapOf("kind" to kind, "id" to id),
                )
                ok = false
            }
        }
        return ok
    }

    private fun targetDir(kind: String, id: String): File =
        if (kind == "skin") File(layout.skinsDir, id) else File(layout.layoutsDir, id)

    /** 索引提交后异步清理 tombstone（失败留待下次恢复处理，不阻塞删除返回）。 */
    private fun scheduleTombstoneCleanup(tombstone: File) {
        cleanupScope.launch {
            runCatching { tombstone.deleteRecursively() }
        }
    }

    companion object {
        private const val INTENT_FILE = "delete-intent.json"
        private val TOMBSTONE_NAME_REGEX = Regex("^([a-z]+)-(.+)-(\\d+)$")
    }

    /**
     * 管理操作依赖的 store 口径（仅本类用的 5 个方法，供单测做
     * fault injection：真实 store 是 final 类，无法子类化伪造
     * 写失败）。生产实现 [RealAdminStore] 是对 [SkinLibraryStore]
     * 的直通适配（逐方法显式 override、无 `by` 委托，故不触发
     * AGENTS.md 的接口委托增量陷阱）；调用方仍走原双参构造，
     * 签名不变。
     */
    internal interface AdminStore {
        suspend fun readIndex(): SkinLibraryStore.ReadResult<LibraryIndex>
        suspend fun readActive(): SkinLibraryStore.ReadResult<ActiveRecord>
        suspend fun readLastGood(): SkinLibraryStore.ReadResult<LastGoodPointer>
        suspend fun writeIndex(index: LibraryIndex)
        suspend fun clearLastGood()
    }

    private class RealAdminStore(private val store: SkinLibraryStore) : AdminStore {
        override suspend fun readIndex(): SkinLibraryStore.ReadResult<LibraryIndex> =
            store.readIndex()

        override suspend fun readActive(): SkinLibraryStore.ReadResult<ActiveRecord> =
            store.readActive()

        override suspend fun readLastGood(): SkinLibraryStore.ReadResult<LastGoodPointer> =
            store.readLastGood()

        override suspend fun writeIndex(index: LibraryIndex) = store.writeIndex(index)

        override suspend fun clearLastGood() = store.clearLastGood()
    }
}
