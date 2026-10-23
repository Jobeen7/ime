package com.jobeen.ime.skin.sync

import com.jobeen.ime.engine.rime.data.userdict.UserDictPrefs
import com.jobeen.ime.skin.store.AtomicFiles
import com.jobeen.ime.skin.store.SkinLibraryLayout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 同步本地基准（详设 §4.3）：`sync_state.json`，仅本地、不上传。
 *
 * - units：unitKey（`skin:<id>` / `layout:<id>` / `asset:<sha256>`）
 *   → 上轮同步收敛时的内容 hash（判定表中的 B）。
 * - tombstones：本地删除已同步实体的墓碑（删除传播口径见 §4.3）。
 * - dirty：导入/应用/删除后置位的脏标记（§4.4 触发口径）；同步
 *   整轮跑完且无失败单元时清除，设置页据此提示可同步。
 */
@Serializable
data class SkinSyncState(
    val units: Map<String, String> = emptyMap(),
    val tombstones: List<Tombstone> = emptyList(),
    val dirty: Boolean = false,
)

@Serializable
data class Tombstone(
    /** skin / layout */
    val kind: String,
    val id: String,
    val hashAtDelete: String,
)

class SkinSyncStateStore(private val layout: SkinLibraryLayout) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun read(): SkinSyncState {
        val file = layout.syncStateFile
        if (!file.isFile) return SkinSyncState()
        return runCatching { json.decodeFromString<SkinSyncState>(file.readText()) }
            .getOrDefault(SkinSyncState())
    }

    suspend fun write(state: SkinSyncState) {
        AtomicFiles.writeAtomically(
            layout.root, "sync_state.json", json.encodeToString(state),
        )
    }

    suspend fun markDirty() {
        val state = read()
        if (!state.dirty) write(state.copy(dirty = true))
    }

    /** 记录/更新墓碑（同 kind+id 只留最新一条）。 */
    suspend fun recordTombstone(kind: String, id: String, hashAtDelete: String) {
        val state = read()
        val kept = state.tombstones.filterNot { it.kind == kind && it.id == id }
        write(
            state.copy(
                tombstones = kept + Tombstone(kind, id, hashAtDelete),
                dirty = true,
            ),
        )
    }
}
