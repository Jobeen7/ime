package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 启动对账（详设 §2.8，顺序写死）：进程冷启动时收敛一切崩溃中间态，
 * 使「磁盘上恒有一套完整可用皮肤、未验证代次从未被采用」成立。
 * 只动 skin_library 目录；重活（hash/引用图）都在调用方的 IO 线程跑。
 *
 * 顺序：清 staging → 裁决 active（pending/损坏走回退链并落盘）→
 * 校验 Last-Good 指针 → 索引与目录对账（缺失剔除、完整补登）→
 * 代次回收（§2.5.4）→ 资产引用计数全量重建（孤儿只标记不删，
 * 回收走 [pruneOrphanAssets]，§2.8.4 启动后异步）→ 配额复核（只报不删）。
 */
class LibraryReconciler(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class Report(
        val indexRebuilt: Boolean,
        val droppedEntries: List<String>,
        val registeredEntries: List<String>,
        val repairedEntries: List<String>,
        val prunedGenerations: List<String>,
        val orphanAssets: List<String>,
        val quotaBytes: Long,
        val overQuota: Boolean,
        val activeResolution: ActiveResolver.Resolution,
    )

    private data class GenOnDisk(
        val id: String,
        val generation: Int,
        val dir: File,
        val complete: Boolean,
    )

    suspend fun reconcile(): Report {
        layout.ensureDirs()
        // 1. 清 staging 残留（崩溃点 I-1/I-2：未走完的导入不留痕）
        runCatching {
            layout.stagingDir.deleteRecursively()
            layout.stagingDir.mkdirs()
        }

        // 2. 读三状态文件 + 扫盘
        val indexRead = store.readIndex()
        val indexRebuilt = indexRead is SkinLibraryStore.ReadResult.Corrupt
        var index = (indexRead as? SkinLibraryStore.ReadResult.Ok)?.value ?: LibraryIndex()
        if (indexRebuilt) {
            DiagLog.w("store", "index_corrupt_rebuild")
        }
        val activeRead = store.readActive()
        val lastGoodRead = store.readLastGood()
        val skinGens = scanGens(layout.skinsDir, layout::isSkinGenComplete)
        val layoutGens = scanGens(layout.layoutsDir, layout::isLayoutGenComplete)

        // 代次状态回调（hash 按需算并缓存）
        val hashCache = mutableMapOf<String, String?>()
        fun genState(skinId: String, generation: Int): ActiveResolver.GenState {
            val gen = skinGens[skinId]?.firstOrNull { it.generation == generation }
                ?: return ActiveResolver.GenState(complete = false, skinJsonHash = null)
            if (!gen.complete) return ActiveResolver.GenState(complete = false, skinJsonHash = null)
            val hash = hashCache.getOrPut("$skinId/$generation") {
                runCatching { DigestUtil.sha256Hex(File(gen.dir, "skin.json")) }.getOrNull()
            }
            return ActiveResolver.GenState(complete = true, skinJsonHash = hash)
        }

        // 3. 裁决 active 并落盘回退结果
        val resolution = ActiveResolver.resolve(activeRead, lastGoodRead, ::genState, clock())
        when (resolution) {
            is ActiveResolver.Resolution.RollbackToLastGood -> {
                store.writeActive(resolution.target)
                DiagLog.w(
                    "store", "active_rollback_last_good",
                    mapOf("reason" to resolution.reason, "skinId" to resolution.target.skinId),
                )
            }
            is ActiveResolver.Resolution.RollbackToDefault -> {
                store.writeActive(ActiveRecord())
                DiagLog.w("store", "active_rollback_default", mapOf("reason" to resolution.reason))
            }
            is ActiveResolver.Resolution.NoSkin -> {
                if (activeRead is SkinLibraryStore.ReadResult.Corrupt) {
                    // 损坏记录收敛为干净的「无皮肤」记录，避免每次启动重复走损坏分支
                    store.writeActive(ActiveRecord())
                    DiagLog.w("store", "active_corrupt_reset")
                }
            }
            is ActiveResolver.Resolution.Verified -> Unit
        }

        // 受保护代次（永不回收）：生效代次 + Last-Good 代次（§2.5.4）
        val protectedGens = mutableSetOf<Pair<String, Int>>()
        when (resolution) {
            is ActiveResolver.Resolution.Verified ->
                protectedGens += resolution.record.skinId!! to resolution.record.generation
            is ActiveResolver.Resolution.RollbackToLastGood ->
                protectedGens += resolution.target.skinId!! to resolution.target.generation
            else -> Unit
        }
        // 4. Last-Good 指针校验：目标不在/不完整/hash 不符 → 指针作废清除
        val lastGoodPointer = (lastGoodRead as? SkinLibraryStore.ReadResult.Ok)?.value
        if (lastGoodPointer != null) {
            val valid = ActiveResolver.isTargetValid(
                lastGoodPointer.skinId, lastGoodPointer.generation, lastGoodPointer.hash, ::genState,
            )
            if (valid) {
                protectedGens += lastGoodPointer.skinId to lastGoodPointer.generation
            } else {
                store.clearLastGood()
                DiagLog.w(
                    "store", "last_good_pointer_invalid",
                    mapOf("skinId" to lastGoodPointer.skinId, "gen" to lastGoodPointer.generation),
                )
            }
        }

        // 5. 索引与目录对账（皮肤/布局同构处理）
        val dropped = mutableListOf<String>()
        val registered = mutableListOf<String>()
        val repaired = mutableListOf<String>()
        val newSkins = repairEntries(
            kind = "skin",
            indexEntries = index.skins.map { SkinEntryView(it.id, it.generation, it.hash, it.dir) },
            gensOnDisk = skinGens,
            dropped = dropped, registered = registered, repaired = repaired,
        ) { id, gen, dir ->
            val skinJson = File(dir, "skin.json")
            SkinIndexEntry(
                id = id,
                name = readSkinName(skinJson) ?: id,
                hash = hashCache.getOrPut("$id/$gen") {
                    runCatching { DigestUtil.sha256Hex(skinJson) }.getOrNull()
                } ?: "",
                generation = gen,
                dir = layout.skinGenRelative(id, gen),
                sizeBytes = layout.dirBytes(dir),
                updatedAt = skinJson.lastModified(),
            )
        }
        val newLayouts = repairEntries(
            kind = "layout",
            indexEntries = index.layouts.map { SkinEntryView(it.id, it.generation, it.hash, it.dir) },
            gensOnDisk = layoutGens,
            dropped = dropped, registered = registered, repaired = repaired,
        ) { id, gen, dir ->
            val layoutJson = File(dir, "layout.json")
            LayoutIndexEntry(
                id = id,
                hash = runCatching { DigestUtil.sha256Hex(layoutJson) }.getOrNull() ?: "",
                generation = gen,
                dir = layout.layoutGenRelative(id, gen),
                sizeBytes = layout.dirBytes(dir),
                updatedAt = layoutJson.lastModified(),
            )
        }

        // 6. 代次回收：每皮肤只留「最新完整代次 + 受保护代次」，其余删（§2.5.4）
        val pruned = mutableListOf<String>()
        pruned += pruneGenerations("skin", skinGens, protectedGens)
        // 布局无 active/last-good 概念，保护集为空、只留最新完整代次
        pruned += pruneGenerations("layout", layoutGens, emptySet())

        // 7. 资产引用计数按引用图全量重建（引用图来源 = 各保留代次的 manifest contents）
        val refIndex = index.copy(skins = newSkins, layouts = newLayouts)
        val refcounts = computeRefcounts(refIndex)
        val diskAssets = scanDiskAssets()
        val newAssets = diskAssets.map { (sha, size) ->
            AssetIndexEntry(sha256 = sha, sizeBytes = size, refcountCache = refcounts[sha] ?: 0)
        }.sortedBy { it.sha256 }
        val orphans = newAssets.filter { it.refcountCache == 0 }.map { it.sha256 }

        val newIndex = refIndex.copy(assets = newAssets)
        if (indexRebuilt || indexRead is SkinLibraryStore.ReadResult.Missing || newIndex != index) {
            store.writeIndex(newIndex)
            index = newIndex
        }

        // 8. 配额复核：超限只提示、不自动删在用数据（§2.8.5）
        val quota = layout.quotaBytes()
        val overQuota = quota > QUOTA_LIMIT_BYTES
        DiagLog.i(
            "store", "reconcile_done",
            mapOf(
                "skins" to newSkins.size, "layouts" to newLayouts.size,
                "dropped" to dropped.size, "registered" to registered.size,
                "repaired" to repaired.size, "pruned" to pruned.size,
                "orphanAssets" to orphans.size, "quotaBytes" to quota,
                "overQuota" to overQuota, "indexRebuilt" to indexRebuilt,
            ),
        )
        return Report(
            indexRebuilt = indexRebuilt,
            droppedEntries = dropped,
            registeredEntries = registered,
            repairedEntries = repaired,
            prunedGenerations = pruned,
            orphanAssets = orphans,
            quotaBytes = quota,
            overQuota = overQuota,
            activeResolution = resolution,
        )
    }

    /** 删除孤儿资产实体与其索引条目（§2.8.4 的异步回收动作）；返回删除的实体文件数。 */
    suspend fun pruneOrphanAssets(orphanShas: List<String>): Int {
        var deleted = 0
        for (sha in orphanShas) {
            for (file in layout.assetFiles(sha)) {
                if (runCatching { file.delete() }.getOrDefault(false)) deleted++
            }
        }
        if (orphanShas.isNotEmpty()) {
            val current = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            if (current != null) {
                val kept = current.assets.filter { it.sha256 !in orphanShas }
                if (kept.size != current.assets.size) {
                    store.writeIndex(current.copy(assets = kept))
                }
            }
            DiagLog.i("store", "orphan_assets_pruned", mapOf("count" to orphanShas.size))
        }
        return deleted
    }

    // ---- 内部 ----

    private data class SkinEntryView(val id: String, val generation: Int, val hash: String, val dir: String)

    /**
     * 条目对账：某 id 无完整代次 → 条目剔除；条目缺失 → 按最新完整代次
     * 补登（崩溃点 I-3）；条目所指非最新完整代次或 hash 不符 → 修复到
     * 最新完整代次。
     */
    private fun <T> repairEntries(
        kind: String,
        indexEntries: List<SkinEntryView>,
        gensOnDisk: Map<String, List<GenOnDisk>>,
        dropped: MutableList<String>,
        registered: MutableList<String>,
        repaired: MutableList<String>,
        buildEntry: (id: String, generation: Int, dir: File) -> T,
    ): List<T> {
        val result = mutableListOf<T>()
        val ids = (indexEntries.map { it.id } + gensOnDisk.keys).distinct().sorted()
        for (id in ids) {
            val completeGens = gensOnDisk[id].orEmpty().filter { it.complete }
            val latest = completeGens.maxByOrNull { it.generation }
            val existing = indexEntries.firstOrNull { it.id == id }
            if (latest == null) {
                if (existing != null) dropped += "$kind:$id"
                continue
            }
            val rebuilt = buildEntry(id, latest.generation, latest.dir)
            val rebuiltView = when (rebuilt) {
                is SkinIndexEntry -> SkinEntryView(rebuilt.id, rebuilt.generation, rebuilt.hash, rebuilt.dir)
                is LayoutIndexEntry -> SkinEntryView(rebuilt.id, rebuilt.generation, rebuilt.hash, rebuilt.dir)
                else -> null
            }
            when {
                existing == null -> {
                    registered += "$kind:$id"
                    result += rebuilt
                }
                rebuiltView != null && rebuiltView == existing -> result += rebuilt
                else -> {
                    repaired += "$kind:$id"
                    result += rebuilt
                }
            }
        }
        return result
    }

    private fun pruneGenerations(
        kind: String,
        gensOnDisk: Map<String, List<GenOnDisk>>,
        protectedGens: Set<Pair<String, Int>>,
    ): List<String> {
        val pruned = mutableListOf<String>()
        for ((id, gens) in gensOnDisk) {
            val latestComplete = gens.filter { it.complete }.maxByOrNull { it.generation }?.generation
            for (gen in gens) {
                val keep = gen.generation == latestComplete || (id to gen.generation) in protectedGens
                if (!keep) {
                    runCatching { gen.dir.deleteRecursively() }
                    pruned += "$kind:$id/gen-${gen.generation}"
                }
            }
            // id 目录已空则一并清掉（皮肤被整体删除后的残留）
            val idDir = gens.first().dir.parentFile
            if (idDir != null && idDir.listFiles().isNullOrEmpty()) {
                runCatching { idDir.delete() }
            }
        }
        return pruned
    }

    private fun scanGens(parentDir: File, complete: (File) -> Boolean): Map<String, List<GenOnDisk>> {
        val result = mutableMapOf<String, List<GenOnDisk>>()
        val idDirs = parentDir.listFiles { f -> f.isDirectory } ?: return result
        for (idDir in idDirs) {
            val gens = idDir.listFiles { f -> f.isDirectory && f.name.startsWith("gen-") }
                ?.mapNotNull { genDir ->
                    val gen = genDir.name.removePrefix("gen-").toIntOrNull() ?: return@mapNotNull null
                    GenOnDisk(idDir.name, gen, genDir, complete(genDir))
                }
                ?.sortedBy { it.generation }
                ?: emptyList()
            if (gens.isNotEmpty()) result[idDir.name] = gens
        }
        return result
    }

    /** 引用图口径见 [AssetRefcounts]（与导入事务共用，保证计数一致）。 */
    private fun computeRefcounts(index: LibraryIndex): Map<String, Int> =
        AssetRefcounts.compute(layout, index)

    /** 磁盘上的资产实体：文件名主体须为 64 位小写 hex（sha256），杂文件不入索引。 */
    private fun scanDiskAssets(): List<Pair<String, Long>> {
        val files = layout.assetsDir.listFiles { f -> f.isFile } ?: return emptyList()
        return files.mapNotNull { file ->
            val sha = file.name.substringBefore('.')
            if (SHA256_HEX.matches(sha)) sha to file.length() else null
        }
    }

    private fun readSkinName(skinJson: File): String? = runCatching {
        val root = Json.parseToJsonElement(skinJson.readText()).jsonObject
        (root["meta"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        /** 库总配额（详设 §2.1 规则 3，暂定值——裁定记录 #8，待真机校准后锁定）。 */
        const val QUOTA_LIMIT_BYTES = 200L * 1024 * 1024
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}
