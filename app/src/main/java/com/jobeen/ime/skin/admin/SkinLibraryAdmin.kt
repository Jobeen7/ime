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
 * - 删布局（B3 §4.6 按定稿改）：不再因被引用而拒绝——调用方先经
 *   [layoutReferrers] 列出全部引用方并警告，用户确认后可删；
 *   引用方皮肤文件不改（代次不可变），其快照在下次构建时按
 *   layoutId 未设处理并回退默认布局。连带其布局草稿。
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
        val index = readIndex() ?: return Result.Refused("index_unavailable")
        val entry = index.layouts.firstOrNull { it.id == layoutId }
            ?: return Result.Refused("not_found")
        // 被引用不再拒绝（B3 §4.6）：引用方经快照重解析回退，本层
        // 只做删除本身（墓碑 + 目录 + 索引 + 连带布局草稿）
        noteDeletion("layout", layoutId, entry.hash)
        File(layout.layoutsDir, layoutId).deleteRecursively()
        File(
            layout.draftsDir,
            com.jobeen.ime.skin.draft.DraftStore.layoutDraftKey(layoutId),
        ).deleteRecursively()
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
