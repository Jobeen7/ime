package com.jobeen.ime.skin.admin

import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import com.jobeen.ime.skin.sync.SkinSyncStateStore
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

/**
 * 皮肤库管理操作（详设 §2/§4.3）：删除是 B1 唯一的破坏性库操作，
 * 口径写死——
 *
 * - 删生效中的皮肤：先经应用事务回退内置默认，成功后才删
 *   （运行态永不指向已删代次）；回退失败则拒绝删除。
 * - Last-Good 指针指向被删皮肤：一并清除（指针不得悬空）。
 * - 已同步过的实体：记墓碑待同步传播删除（§4.3）；未同步的
 *   只置脏标记。连带删除其草稿（草稿归属该 id，一并失效）。
 * - 删布局：仍被任一本地皮肤引用的拒绝（引用完整性优先）。
 * - 资产实体不在此处回收：引用计数由启动对账全量重建（七.5）。
 */
class SkinLibraryAdmin(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
    private val stateStore: SkinSyncStateStore,
) {

    sealed interface Result {
        data class Done(val id: String) : Result
        data class Refused(val reason: String) : Result
    }

    /**
     * @param revertToDefault 生效中皮肤删除前由调用方执行的回退
     * （应用事务 revertToDefault），返回是否成功；非生效皮肤不调用。
     */
    suspend fun deleteSkin(
        skinId: String,
        revertToDefault: suspend () -> Boolean,
    ): Result {
        val index = readIndex() ?: return Result.Refused("index_unavailable")
        val entry = index.skins.firstOrNull { it.id == skinId }
            ?: return Result.Refused("not_found")
        val active = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
        if (active?.skinId == skinId && !revertToDefault()) {
            return Result.Refused("revert_failed")
        }
        val lastGood = (store.readLastGood() as? SkinLibraryStore.ReadResult.Ok)?.value
        if (lastGood?.skinId == skinId) {
            store.clearLastGood()
        }
        noteDeletion("skin", skinId, entry.hash)
        File(layout.skinsDir, skinId).deleteRecursively()
        File(layout.draftsDir, skinId).deleteRecursively()
        store.writeIndex(index.copy(skins = index.skins.filterNot { it.id == skinId }))
        return Result.Done(skinId)
    }

    suspend fun deleteLayout(layoutId: String): Result {
        val index = readIndex() ?: return Result.Refused("index_unavailable")
        val entry = index.layouts.firstOrNull { it.id == layoutId }
            ?: return Result.Refused("not_found")
        for (skin in index.skins) {
            val tree = runCatching {
                SkinJson.parseObject(
                    File(layout.fileFromRelative(skin.dir), "skin.json").readText(),
                )
            }.getOrNull()
            val referenced = (tree?.get("layoutId") as? JsonPrimitive)?.content
            if (referenced == layoutId) {
                return Result.Refused("referenced_by:${skin.id}")
            }
        }
        noteDeletion("layout", layoutId, entry.hash)
        File(layout.layoutsDir, layoutId).deleteRecursively()
        store.writeIndex(index.copy(layouts = index.layouts.filterNot { it.id == layoutId }))
        return Result.Done(layoutId)
    }

    private suspend fun noteDeletion(kind: String, id: String, hash: String) {
        val state = stateStore.read()
        if (state.units.containsKey("$kind:$id")) {
            stateStore.recordTombstone(kind, id, hash)
        } else {
            stateStore.markDirty()
        }
    }

    private suspend fun readIndex(): LibraryIndex? =
        (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
}
