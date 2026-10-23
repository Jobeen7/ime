package com.jobeen.ime.skin.sync

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.ZipSafeExtractor
import com.jobeen.ime.skin.store.ImportTransaction
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * 远端索引（详设 §4.2）：只作清单与 hash 来源，不作可信数据——
 * 每个单元落库前本地重算 sha256 比对（§4.4.2）。
 *
 * 实现裁量（§4.4「重组为包」的落实）：skin 条目附带 `assets`
 * （逻辑 id → sha256 + 原路径），否则接收端无法把内容寻址的
 * 资产字节与皮肤树引用的逻辑 id 配对重组出包清单。资产本身
 * 仍按 sha256 自校验，索引不因此被信任。
 */
@Serializable
data class RemoteSkinIndex(
    val version: Int = 1,
    val entries: List<RemoteSkinEntry> = emptyList(),
    val assets: List<RemoteAssetEntry> = emptyList(),
)

@Serializable
data class RemoteSkinEntry(
    val id: String,
    val kind: String,
    val hash: String,
    val sizeBytes: Long = 0,
    val updatedAt: Long = 0,
    val assets: List<RemoteEntryAsset> = emptyList(),
)

@Serializable
data class RemoteEntryAsset(
    val id: String,
    val sha256: String,
    val path: String,
)

@Serializable
data class RemoteAssetEntry(
    val sha256: String,
    val sizeBytes: Long = 0,
)

/**
 * 皮肤库 WebDAV 同步引擎（详设 §4）：单实体文件、sha256 增量、
 * 墓碑删除、判定表决策、逐单元重试与结果清单。触发为手动
 * （§4.4.4）；与词典同步独立 Mutex、独立语义。
 */
class SkinLibrarySync(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
    private val stateStore: SkinSyncStateStore,
    private val transport: SkinSyncTransport,
    private val importTransaction: ImportTransaction,
    private val importerProvider: () -> Pair<JimeImporter, JimeImporter.Context>,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    enum class Action { SKIPPED, UPLOADED, DOWNLOADED, CONFLICT_COPY, DELETED_REMOTE, FAILED }

    data class UnitResult(
        val unitKey: String,
        val action: Action,
        val detail: String? = null,
        val copyId: String? = null,
    )

    data class Report(
        val results: List<UnitResult> = emptyList(),
        val abortedReason: String? = null,
        /** 生效中皮肤被同步更新（只入新代次，§4.4.3）：UI 提示重新应用。 */
        val activeSkinUpdated: Boolean = false,
    )

    private class AbortRound(val reason: String) : Exception(reason)
    private class RejudgeSignal : Exception()
    private class UnitFailed(val detail: String) : Exception(detail)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sync(): Report = mutex.withLock {
        try {
            runRound()
        } catch (abort: AbortRound) {
            Report(abortedReason = abort.reason)
        } catch (signal: RejudgeSignal) {
            // 409/412 并发写：重拉远端索引整轮重判一次（§4.5），再失败即中止报告
            try {
                runRound()
            } catch (abort: AbortRound) {
                Report(abortedReason = abort.reason)
            } catch (signal2: RejudgeSignal) {
                Report(abortedReason = "conflict_rejudge_exhausted")
            }
        }
    }

    // ---- 一轮同步 ----

    private inner class Round(
        val state: SkinSyncState,
        val localSkins: Map<String, SkinIndexEntry>,
        val localLayouts: Map<String, LayoutIndexEntry>,
        val localAssets: Set<String>,
    ) {
        val results = mutableListOf<UnitResult>()
        val newUnits = state.units.toMutableMap()
        val tombstones = state.tombstones.toMutableList()
        val workEntries = mutableMapOf<String, RemoteSkinEntry>()
        val workAssetSizes = mutableMapOf<String, Long>()
        val done = mutableSetOf<String>()
        var activeSkinUpdated = false

        fun addResult(key: String, action: Action, detail: String? = null, copyId: String? = null) {
            results += UnitResult(key, action, detail, copyId)
        }
    }

    private suspend fun runRound(): Report {
        val state = stateStore.read()
        // 索引文件尚不存在（新库首轮）按空库处理；损坏才中止
        val index = when (val read = store.readIndex()) {
            is SkinLibraryStore.ReadResult.Ok -> read.value
            is SkinLibraryStore.ReadResult.Missing ->
                com.jobeen.ime.skin.store.LibraryIndex()
            else -> throw AbortRound("index_unavailable")
        }
        val round = Round(
            state = state,
            localSkins = index.skins.associateBy { it.id },
            localLayouts = index.layouts.associateBy { it.id },
            localAssets = layout.assetsDir.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name.substringBefore('.') }
                ?.toSet() ?: emptySet(),
        )
        val remoteIndex = fetchRemoteIndex()
        for (entry in remoteIndex.entries) {
            round.workEntries[unitKey(entry.kind, entry.id)] = entry
        }
        for (asset in remoteIndex.assets) {
            round.workAssetSizes[asset.sha256] = asset.sizeBytes
        }

        handleTombstones(round)
        syncSkins(round)
        syncLayouts(round)
        syncAssets(round)

        // 远端索引回写（工作模型即最终远端形态）
        val newRemoteIndex = RemoteSkinIndex(
            entries = round.workEntries.values.sortedWith(compareBy({ it.kind }, { it.id })),
            assets = round.workAssetSizes.entries
                .map { RemoteAssetEntry(it.key, it.value) }
                .sortedBy { it.sha256 },
        )
        var indexFailed = false
        try {
            withRetry { transport.put("index.json", json.encodeToString(newRemoteIndex).encodeToByteArray()) }
        } catch (failed: UnitFailed) {
            indexFailed = true
            round.addResult("index", Action.FAILED, failed.detail)
        }

        val anyFailed = indexFailed || round.results.any { it.action == Action.FAILED }
        stateStore.write(
            SkinSyncState(
                units = round.newUnits,
                tombstones = round.tombstones,
                dirty = anyFailed,
            ),
        )
        return Report(
            results = round.results,
            activeSkinUpdated = round.activeSkinUpdated,
        )
    }

    // ---- 墓碑（§4.3 删除传播） ----

    private suspend fun handleTombstones(round: Round) {
        for (tomb in round.state.tombstones.toList()) {
            val key = unitKey(tomb.kind, tomb.id)
            val remote = round.workEntries[key]
            when {
                remote == null -> {
                    round.tombstones.remove(tomb)
                    round.newUnits.remove(key)
                    round.done += key
                }
                remote.hash == tomb.hashAtDelete -> {
                    try {
                        withRetry { transport.delete(remotePath(tomb.kind, tomb.id)) }
                        round.workEntries.remove(key)
                        round.tombstones.remove(tomb)
                        round.newUnits.remove(key)
                        round.addResult(key, Action.DELETED_REMOTE)
                    } catch (failed: UnitFailed) {
                        round.addResult(key, Action.FAILED, failed.detail)
                    }
                    round.done += key
                }
                else -> {
                    // 远端在删除后又变了：按双边冲突，远端新版以副本复活（§4.3）
                    val copyId = if (tomb.kind == "skin") {
                        downloadSkin(
                            round, tomb.id, remote,
                            JimeImporter.ConflictMode.FAIL, forceCopyId = newId(),
                        )
                    } else {
                        downloadLayout(round, tomb.id, remote, copy = true)
                    }
                    if (copyId != null) {
                        round.tombstones.remove(tomb)
                        round.newUnits.remove(key)
                        round.newUnits[unitKey(tomb.kind, copyId)] = remote.hash
                    }
                    round.done += key
                }
            }
        }
    }

    // ---- 皮肤 / 布局判定 ----

    private enum class Decision { SKIP, UPLOAD, DOWNLOAD, CONFLICT }

    private fun decide(local: String?, remote: String?, base: String?): Decision? = when {
        local == null && remote == null -> null
        local != null && remote == null -> Decision.UPLOAD
        local == null && remote != null -> Decision.DOWNLOAD
        local == remote -> Decision.SKIP
        base != null && remote == base -> Decision.UPLOAD
        base != null && local == base -> Decision.DOWNLOAD
        else -> Decision.CONFLICT
    }

    private suspend fun syncSkins(round: Round) {
        val keys = round.localSkins.keys.map { unitKey("skin", it) } +
            round.workEntries.keys.filter { it.startsWith("skin:") } +
            round.state.units.keys.filter { it.startsWith("skin:") }
        for (key in keys.distinct()) {
            if (key in round.done) continue
            round.done += key
            val id = key.removePrefix("skin:")
            val local = round.localSkins[id]
            val remote = round.workEntries[key]
            when (decide(local?.hash, remote?.hash, round.state.units[key])) {
                null -> round.newUnits.remove(key)
                Decision.SKIP -> {
                    round.addResult(key, Action.SKIPPED)
                    round.newUnits[key] = local!!.hash
                }
                Decision.UPLOAD -> uploadSkin(round, local!!, key)
                Decision.DOWNLOAD -> {
                    val mode = if (local == null) {
                        JimeImporter.ConflictMode.FAIL
                    } else {
                        JimeImporter.ConflictMode.UPDATE
                    }
                    if (downloadSkin(round, id, remote!!, mode) != null) {
                        round.newUnits[key] = remote.hash
                        noteActiveUpdated(round, id)
                    }
                }
                Decision.CONFLICT -> {
                    val copyId = downloadSkin(round, id, remote!!, JimeImporter.ConflictMode.COPY)
                    if (copyId != null) {
                        round.newUnits[unitKey("skin", copyId)] = remote.hash
                        noteActiveUpdated(round, id)
                        // 本地版随后上传（判定表口径），B 收敛到本地 hash；
                        // 副本落库失败时不强行覆盖远端，留下轮再判。
                        // 上传是冲突处置的一部分，不单列结果行（§4.5 逐单元一列）
                        uploadSkin(round, local!!, key, quietDeps = true, reportResult = false)
                    }
                }
            }
        }
    }

    private suspend fun syncLayouts(round: Round) {
        val keys = round.localLayouts.keys.map { unitKey("layout", it) } +
            round.workEntries.keys.filter { it.startsWith("layout:") } +
            round.state.units.keys.filter { it.startsWith("layout:") }
        for (key in keys.distinct()) {
            if (key in round.done) continue
            round.done += key
            val id = key.removePrefix("layout:")
            val local = round.localLayouts[id]
            val remote = round.workEntries[key]
            when (decide(local?.hash, remote?.hash, round.state.units[key])) {
                null -> round.newUnits.remove(key)
                Decision.SKIP -> {
                    round.addResult(key, Action.SKIPPED)
                    round.newUnits[key] = local!!.hash
                }
                Decision.UPLOAD -> uploadLayout(round, local!!, key)
                Decision.DOWNLOAD -> {
                    if (downloadLayout(round, id, remote!!, copy = false) != null) {
                        round.newUnits[key] = remote.hash
                    }
                }
                Decision.CONFLICT -> {
                    val copyId = downloadLayout(round, id, remote!!, copy = true)
                    if (copyId != null) {
                        round.newUnits[unitKey("layout", copyId)] = remote.hash
                        uploadLayout(round, local!!, key)
                    }
                }
            }
        }
    }

    private suspend fun noteActiveUpdated(round: Round, skinId: String) {
        val active = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
        if (active?.skinId == skinId) round.activeSkinUpdated = true
    }

    // ---- 上传 ----

    private suspend fun uploadSkin(
        round: Round,
        entry: SkinIndexEntry,
        key: String,
        quietDeps: Boolean = false,
        reportResult: Boolean = true,
    ) {
        try {
            val genDir = layout.fileFromRelative(entry.dir)
            val skinBytes = File(genDir, "skin.json").readBytes()
            val manifest = runCatching {
                PackageManifest.parse(File(genDir, "manifest.json").readText())
            }.getOrNull()
            // 依赖先行：引用布局与资产补齐后，皮肤文件才 PUT（接收端可即时重组）
            val tree = SkinJson.parseObject(skinBytes.decodeToString())
            val layoutId = (tree?.get("layoutId") as? JsonPrimitive)?.content
            if (layoutId != null) {
                val layoutEntry = round.localLayouts[layoutId]
                val layoutKey = unitKey("layout", layoutId)
                if (layoutEntry != null && round.workEntries[layoutKey]?.hash != layoutEntry.hash) {
                    uploadLayout(round, layoutEntry, layoutKey, reportResult = !quietDeps)
                }
            }
            val entryAssets = mutableListOf<RemoteEntryAsset>()
            for (asset in manifest?.assets().orEmpty()) {
                ensureAssetRemote(round, asset.sha256, reportResult = !quietDeps)
                entryAssets += RemoteEntryAsset(asset.id, asset.sha256, asset.path)
            }
            withRetry { transport.put(remotePath("skin", entry.id), skinBytes) }
            round.workEntries[key] = RemoteSkinEntry(
                id = entry.id, kind = "skin", hash = entry.hash,
                sizeBytes = skinBytes.size.toLong(), updatedAt = clock(),
                assets = entryAssets,
            )
            round.newUnits[key] = entry.hash
            if (reportResult) round.addResult(key, Action.UPLOADED)
        } catch (failed: UnitFailed) {
            round.addResult(key, Action.FAILED, failed.detail)
        } catch (io: IOException) {
            round.addResult(key, Action.FAILED, io.message ?: "io")
        }
    }

    private suspend fun uploadLayout(
        round: Round,
        entry: LayoutIndexEntry,
        key: String,
        reportResult: Boolean = true,
    ) {
        try {
            val bytes = File(layout.fileFromRelative(entry.dir), "layout.json").readBytes()
            withRetry { transport.put(remotePath("layout", entry.id), bytes) }
            round.workEntries[key] = RemoteSkinEntry(
                id = entry.id, kind = "layout", hash = entry.hash,
                sizeBytes = bytes.size.toLong(), updatedAt = clock(),
            )
            round.newUnits[key] = entry.hash
            round.done += key
            if (reportResult) round.addResult(key, Action.UPLOADED)
        } catch (failed: UnitFailed) {
            if (reportResult) round.addResult(key, Action.FAILED, failed.detail)
        } catch (io: IOException) {
            if (reportResult) round.addResult(key, Action.FAILED, io.message ?: "io")
        }
    }

    // ---- 下载 ----

    /**
     * 下载皮肤并经导入管线落库（§4.4.1 全量校验）。成功返回
     * 最终入库的皮肤 id（副本时为新 id），失败返回 null。
     *
     * [forceCopyId] 非空时强制副本（墓碑+远端已变专用）：本地已
     * 无同 id 持有者，导入器的冲突判定不会触发，须在同步层先
     * 改写 meta.id 再以新 id 落库（§4.3 远端新版以副本复活）。
     */
    private suspend fun downloadSkin(
        round: Round,
        id: String,
        remote: RemoteSkinEntry,
        mode: JimeImporter.ConflictMode,
        forceCopyId: String? = null,
    ): String? {
        val key = unitKey("skin", id)
        try {
            val skinBytes = withRetry { transport.get(remotePath("skin", id)) }
                ?: throw UnitFailed("remote_missing")
            if (DigestUtil.sha256Hex(skinBytes) != remote.hash) {
                throw UnitFailed("hash_mismatch")
            }
            var effectiveId = id
            var effectiveBytes = skinBytes
            var effectiveHash = remote.hash
            if (forceCopyId != null) {
                val parsed = SkinJson.parseObject(skinBytes.decodeToString())
                    ?: throw UnitFailed("skin_unparseable")
                val meta = parsed["meta"] as? kotlinx.serialization.json.JsonObject
                    ?: throw UnitFailed("skin_meta_missing")
                val newMeta = kotlinx.serialization.json.JsonObject(
                    meta + ("id" to JsonPrimitive(forceCopyId)),
                )
                val newTree = kotlinx.serialization.json.JsonObject(
                    parsed + ("meta" to newMeta),
                )
                effectiveBytes = Json.encodeToString(
                    kotlinx.serialization.json.JsonObject.serializer(), newTree,
                ).encodeToByteArray()
                effectiveHash = DigestUtil.sha256Hex(effectiveBytes)
                effectiveId = forceCopyId
            }
            val files = mutableListOf(
                ZipSafeExtractor.ExtractedFile("skin.json", effectiveBytes),
            )
            val contents = mutableListOf(
                PackageManifest.Content(
                    PackageManifest.TYPE_SKIN, effectiveId, "skin.json", effectiveHash,
                ),
            )
            val tree = SkinJson.parseObject(effectiveBytes.decodeToString())
            val layoutId = (tree?.get("layoutId") as? JsonPrimitive)?.content
            if (layoutId != null) {
                val layoutRemote = round.workEntries[unitKey("layout", layoutId)]
                if (layoutRemote != null) {
                    val layoutBytes = withRetry { transport.get(remotePath("layout", layoutId)) }
                        ?: throw UnitFailed("layout_remote_missing")
                    if (DigestUtil.sha256Hex(layoutBytes) != layoutRemote.hash) {
                        throw UnitFailed("layout_hash_mismatch")
                    }
                    val path = "layouts/$layoutId.json"
                    files += ZipSafeExtractor.ExtractedFile(path, layoutBytes)
                    contents += PackageManifest.Content(
                        PackageManifest.TYPE_LAYOUT, layoutId, path, layoutRemote.hash,
                    )
                }
            }
            for (asset in remote.assets) {
                fetchAssetLocal(round, asset.sha256)
                val path = "assets/" + asset.path.substringAfterLast('/')
                val local = layout.assetFiles(asset.sha256).firstOrNull()
                    ?: throw UnitFailed("asset_missing:${asset.id}")
                files += ZipSafeExtractor.ExtractedFile(path, local.readBytes())
                contents += PackageManifest.Content(
                    PackageManifest.TYPE_ASSET, asset.id, path, asset.sha256,
                )
            }
            val manifest = PackageManifest(formatVersion = 3, contents = contents)
            files += ZipSafeExtractor.ExtractedFile(
                "manifest.json", PackageManifest.toJson(manifest).encodeToByteArray(),
            )
            val (importer, ctx) = importerProvider()
            return when (val outcome = importer.importFiles(files, ctx, mode)) {
                is JimeImporter.Outcome.Imported -> {
                    val copyId = forceCopyId ?: outcome.report.copies[id]
                    when {
                        copyId != null -> {
                            round.addResult(key, Action.CONFLICT_COPY, copyId = copyId)
                            copyId
                        }
                        effectiveId in outcome.report.skippedSkinIds -> {
                            round.addResult(key, Action.SKIPPED)
                            effectiveId
                        }
                        else -> {
                            round.addResult(key, Action.DOWNLOADED)
                            effectiveId
                        }
                    }
                }
                is JimeImporter.Outcome.ConflictPending ->
                    throw UnitFailed("conflict_pending")
                is JimeImporter.Outcome.Rejected ->
                    throw UnitFailed("rejected:${outcome.reason}")
            }
        } catch (failed: UnitFailed) {
            round.addResult(key, Action.FAILED, failed.detail)
            return null
        }
    }

    /**
     * 下载布局单元（无皮肤伴随，无法走包导入）：hash 校验 + 结构
     * 限制与导入器同口径，之后经导入事务落库。成功返回入库 id
     * （副本模式为新 id），失败返回 null。
     */
    private suspend fun downloadLayout(
        round: Round,
        id: String,
        remote: RemoteSkinEntry,
        copy: Boolean,
    ): String? {
        val key = unitKey("layout", id)
        try {
            val bytes = withRetry { transport.get(remotePath("layout", id)) }
                ?: throw UnitFailed("remote_missing")
            if (DigestUtil.sha256Hex(bytes) != remote.hash) {
                throw UnitFailed("hash_mismatch")
            }
            val tree = SkinJson.parseObject(bytes.decodeToString())
                ?: throw UnitFailed("layout_unparseable")
            if (SkinJson.depth(tree) > com.jobeen.ime.skin.model.SkinValidator.MAX_JSON_DEPTH ||
                SkinJson.maxStringLength(tree) >
                com.jobeen.ime.skin.model.SkinValidator.MAX_STRING_LENGTH
            ) {
                throw UnitFailed("layout_json_limits_exceeded")
            }
            val finalId = if (copy) newId() else id
            val hash = DigestUtil.sha256Hex(bytes)
            val manifest = PackageManifest(
                formatVersion = 3,
                contents = listOf(
                    PackageManifest.Content(
                        PackageManifest.TYPE_LAYOUT, finalId, "layout.json", hash,
                    ),
                ),
            )
            val result = importTransaction.importPackage { staging ->
                val dir = File(staging, "layouts/$finalId").apply { mkdirs() }
                File(dir, "layout.json").writeBytes(bytes)
                File(dir, "manifest.json").writeText(PackageManifest.toJson(manifest))
                ImportTransaction.BuiltPackage(
                    layouts = listOf(ImportTransaction.StagedLayout(finalId, dir, hash)),
                )
            }
            if (result !is ImportTransaction.Result.Imported) {
                throw UnitFailed("stage_failed")
            }
            if (copy) {
                round.addResult(key, Action.CONFLICT_COPY, copyId = finalId)
            } else {
                round.addResult(key, Action.DOWNLOADED)
            }
            return finalId
        } catch (failed: UnitFailed) {
            round.addResult(key, Action.FAILED, failed.detail)
            return null
        }
    }

    // ---- 资产（只补缺，不判冲突、不覆盖，§4.3） ----

    private suspend fun syncAssets(round: Round) {
        val shas = round.localAssets + round.workAssetSizes.keys +
            round.state.units.keys
                .filter { it.startsWith("asset:") }
                .map { it.removePrefix("asset:") }
        for (sha in shas.distinct()) {
            val key = unitKey("asset", sha)
            val hasLocal = sha in round.localAssets
            val hasRemote = sha in round.workAssetSizes
            when {
                hasLocal && hasRemote -> {
                    round.addResult(key, Action.SKIPPED)
                    round.newUnits[key] = sha
                }
                hasLocal -> ensureAssetRemote(round, sha, reportResult = true)
                hasRemote -> {
                    try {
                        fetchAssetLocal(round, sha)
                        round.addResult(key, Action.DOWNLOADED)
                        round.newUnits[key] = sha
                    } catch (failed: UnitFailed) {
                        round.addResult(key, Action.FAILED, failed.detail)
                    }
                }
                else -> round.newUnits.remove(key)
            }
        }
    }

    private suspend fun ensureAssetRemote(
        round: Round,
        sha256: String,
        reportResult: Boolean,
    ) {
        val key = unitKey("asset", sha256)
        if (sha256 in round.workAssetSizes) {
            round.newUnits[key] = sha256
            return
        }
        try {
            val file = layout.assetFiles(sha256).firstOrNull()
                ?: throw UnitFailed("asset_file_missing")
            val bytes = file.readBytes()
            withRetry { transport.put("assets/$sha256", bytes) }
            round.workAssetSizes[sha256] = bytes.size.toLong()
            round.newUnits[key] = sha256
            if (reportResult) round.addResult(key, Action.UPLOADED)
        } catch (failed: UnitFailed) {
            if (reportResult) round.addResult(key, Action.FAILED, failed.detail)
        }
    }

    /** 下载缺失资产并校验内容寻址（sha 即身份，符则落盘）。 */
    private suspend fun fetchAssetLocal(round: Round, sha256: String) {
        if (layout.assetFiles(sha256).isNotEmpty()) return
        val bytes = withRetry { transport.get("assets/$sha256") }
            ?: throw UnitFailed("asset_remote_missing")
        if (DigestUtil.sha256Hex(bytes) != sha256) {
            throw UnitFailed("asset_hash_mismatch")
        }
        layout.assetsDir.mkdirs()
        val tmp = File(layout.assetsDir, "$sha256.sync-tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(layout.assetFile(sha256, null))) { "asset rename failed" }
        round.workAssetSizes.putIfAbsent(sha256, bytes.size.toLong())
    }

    // ---- 基础设施 ----

    private suspend fun fetchRemoteIndex(): RemoteSkinIndex {
        val bytes = try {
            withRetry { transport.get("index.json") }
        } catch (failed: UnitFailed) {
            throw AbortRound("index_fetch_failed:${failed.detail}")
        } ?: return RemoteSkinIndex()
        // 索引不可信：解析失败按空索引处理，各单元落库前仍逐个验 hash
        return runCatching { json.decodeFromString<RemoteSkinIndex>(bytes.decodeToString()) }
            .getOrDefault(RemoteSkinIndex())
    }

    /**
     * 单元操作重试（§4.5）：IO/5xx 重试 3 次、退避 1s/2s/4s；
     * 401/403 整轮中止；409/412 触发整轮重判；其余 4xx 不重试。
     */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Exception? = null
        for (attempt in 0..3) {
            if (attempt > 0) sleeper(RETRY_BACKOFF_MS[attempt - 1])
            try {
                return block()
            } catch (auth: SkinSyncTransport.AuthException) {
                throw AbortRound(auth.message ?: "auth_failed")
            } catch (http: SkinSyncTransport.HttpException) {
                if (http.code == 409 || http.code == 412) throw RejudgeSignal()
                if (http.code in 400..499) throw UnitFailed("http_${http.code}")
                lastError = http
            } catch (io: IOException) {
                lastError = io
            } catch (config: IllegalArgumentException) {
                throw AbortRound(config.message ?: "not_configured")
            }
        }
        throw UnitFailed(lastError?.message ?: "io_failed")
    }

    private fun unitKey(kind: String, id: String): String = "$kind:$id"

    private fun remotePath(kind: String, id: String): String = when (kind) {
        "skin" -> "skins/$id.json"
        else -> "layouts/$id.json"
    }

    private companion object {
        val RETRY_BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000)
    }
}
