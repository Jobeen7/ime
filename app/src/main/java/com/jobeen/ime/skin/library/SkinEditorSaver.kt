// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.Context
import com.jobeen.ime.R
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinApplier
import com.jobeen.ime.skin.runtime.SkinApplyPort
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.ApplyTransaction
import com.jobeen.ime.skin.store.AtomicFiles
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinIndexEntry
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest
import timber.log.Timber

/** 编辑器可打开的文件（skin.json 或布局文件）。 */
data class EditorFile(
    val id: String,
    val displayName: String,
    val file: File,
    val kind: Kind,
    /** 该布局是否被当前皮肤之外的皮肤共用（问题 6：共用布局须标注）。 */
    val isShared: Boolean = false,
) {
    enum class Kind { SKIN_JSON, LAYOUT }
}

/** 当前生效皮肤的可编辑文件集（skin.json＋该皮肤实际引用的布局文件）。 */
data class ActiveSkinFiles(
    val skinId: String,
    val generation: Int,
    /** 解析时活动记录的 skin.json hash（保存前 CAS 的比对基准）。 */
    val hash: String?,
    val files: List<EditorFile>,
    /** 主布局 id（skin.json keyboards.pinyin.layoutId，缺失时为 null＝未绑定布局）。 */
    val primaryLayoutId: String?,
    /**
     * 因索引 hash 失配被排除的布局 id（S-L3）：文件本身可读、只是索引
     * 过期，编辑器照常打开、该布局不在 [files] 里（无法编辑／保存，
     * 不会用过期索引覆盖外部改动）；恢复靠重新导入皮肤。入口据此
     * 给提示（见 SkinEditorEntry）。
     */
    val unavailableLayouts: List<String> = emptyList(),
)

/** 解析当前生效皮肤的结果（问题 5：manifest 不合格时 fail closed 并给出原因）。 */
sealed interface ResolveActiveResult {
    data class Ok(val files: ActiveSkinFiles) : ResolveActiveResult
    data class Unavailable(val reason: String) : ResolveActiveResult
}

sealed interface SkinEditorSaveResult {
    /** 已保存并重载生效；issues 为校验回落项（逐条页内明示）。 */
    data class Saved(val issues: List<String>) : SkinEditorSaveResult
    /** 校验未通过：文件未写、旧快照仍生效、文本留在编辑器里。 */
    data class Blocked(val reason: String, val issues: List<String>) : SkinEditorSaveResult
    /** 保存／重载过程失败（新代已丢弃、旧版未动，见实现注释）。 */
    data class Failed(val reason: String) : SkinEditorSaveResult
    /** 活动皮肤已变化：编辑页读到的版本已过期，未写任何文件，须刷新页面重读。 */
    data class Stale(val reason: String) : SkinEditorSaveResult
}

/**
 * App 内置皮肤文件编辑器保存编排（W19-7；2026-11-12 审计 7 问题事务化重构）：
 *
 * 口径（已核实）：manifest.json sha256 只在导入时校验；运行时重载只认
 * active.json 的 hash。保存顺序是先校验、再落盘：
 * 1. decide（纯函数，见 SkinEditorSave）：解析＋限长＋校验器（用与快照
 *    工厂同一口径的 manifest resolver，问题 7）；
 * 2. copy-on-write（问题 1）：新 generation 目录＝旧代全量复制＋新文件
 *    覆写，旧代只读不改；新代完整落盘＋校验后才切换；
 * 3. 走 SkinApplier.applySkin（问题 1/2/3）：应用事务承接串行锁、
 *    请求 token、安装、刷新、首帧等待、VERIFIED 提交与 Last-Good 晋升；
 *    首帧成功后才提交，失败由事务回滚运行态与活动记录；
 * 4. 成功后索引皮肤条目指向新代；失败只丢弃新代（问题 4：回滚后读回
 *    磁盘核对旧 hash，“已恢复”只建立在校验成功上）。
 * 布局编辑同理：新布局代次＋索引先指新代（快照构建经索引解析布局），
 * 失败恢复旧索引并删新代，切换阶段失败时重装当前生效快照收敛运行态。
 *
 * 依赖以构造参数注入（缺省即生产接线），单测可传假件／临时库：
 * [store]/[layout] 指向临时库根，[applier] 为假应用事务，
 * [currentVersionCode]/[fallbacks] 为显式校验口径，
 * [mainDispatcher] 为应用事务的执行线程（JVM 单测无 Main）。
 * 其余仍为生产行为：IO 线程不变，保存互斥仍为伴生对象共享锁。
 */
class SkinEditorSaver(
    private val context: Context,
    private val store: SkinLibraryStore = SkinLibrary.store,
    private val layout: SkinLibraryLayout = SkinLibrary.layout,
    private val applier: SkinApplyPort = SkinApplier(context),
    private val currentVersionCode: Int = SkinSnapshotFactory.currentVersionCode(context),
    private val fallbacks: GeometryFallbacks = SkinSnapshotFactory.geometryFallbacks(context),
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * 取 strings.xml 串的解析器（S-L4）：默认走 context；JVM 单测无真实
     * 资源时由调用方显式传入——保存器直调 final 的 Context.getString
     * 在桩 jar 下抛错且不可覆盖，与本类“版本号／几何回落均由构造参数
     * 显式传入”的做法一致。
     */
    private val resolveString: (Int) -> String = context::getString,
) {

    companion object {
        /** 编辑器保存串行锁：同一时间只一笔编辑保存（代次分配与索引更新不被并发保存交错）。 */
        private val saveMutex = Mutex()
    }

    /** 解析当前生效皮肤：skin.json＋该皮肤实际引用的布局文件（问题 6）。 */
    suspend fun resolveActiveFiles(): ResolveActiveResult = withContext(Dispatchers.IO) {
        val record = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return@withContext ResolveActiveResult.Unavailable("no_active_skin")
        val skinId = record.skinId ?: return@withContext ResolveActiveResult.Unavailable("no_active_skin")
        val genDir = layout.skinGenDir(skinId, record.generation)
        val skinFile = File(genDir, "skin.json")
        if (!skinFile.isFile) return@withContext ResolveActiveResult.Unavailable("skin_json_missing")
        val skinBytes = runCatching { skinFile.readBytes() }.getOrNull()
            ?: return@withContext ResolveActiveResult.Unavailable("skin_json_unreadable")
        val skinHash = sha256Hex(skinBytes)
        val tree = SkinJson.parseObject(skinBytes.toString(Charsets.UTF_8))
            ?: return@withContext ResolveActiveResult.Unavailable("skin_json_unparseable")
        // 记录 hash 门（WO30 §2 项 4，与 ActiveResolver 启动口径一致）：
        // 活动记录的 hash 须与磁盘 skin.json 实际 hash 一致。不一致
        // 说明记录已过期（文件被外部改动／恢复后未同步记录），编辑器
        // 不静默沿用旧 hash——否则后续 CAS 基准就是错的，保存会把
        // 外部改动当场覆盖。明确报 active_hash_mismatch，不打开。
        if (record.hash != skinHash) {
            return@withContext ResolveActiveResult.Unavailable("active_hash_mismatch")
        }
        // Manifest fail-closed（问题 5）：缺失／损坏／缺 skin 条目／路径重复／hash 不符都拒绝打开。
        val manifestText = runCatching { File(genDir, "manifest.json").readText() }.getOrNull()
        when (val check = SkinEditorSave.validateManifestForEdit(manifestText, skinHash)) {
            is ManifestCheck.Invalid -> return@withContext ResolveActiveResult.Unavailable(check.reason)
            is ManifestCheck.Valid -> Unit
        }
        val primaryLayoutId = primaryLayoutId(tree)
        val boundIds = SkinEditorSave.boundLayoutIds(tree)
        val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        // 布局索引 hash 门（WO33 第 2(a) 项，与上文 skin.json 记录 hash 门
        // 同款比对）：索引条目的 hash 是 layout.json 内容 sha256，
        // 须与磁盘实算 hash 一致。不一致说明索引已过期（布局被外部改动／
        // 恢复后未同步索引），编辑器不静默沿用旧值——否则编辑页读到的
        // 布局代次就是错的，保存会把外部改动当场覆盖。
        // 失配处理（S-L3，2026-11-13 复查）：不整个拒开——文件本身可读、
        // 只是索引过期，把该布局排除在列表外（见 unavailableLayouts），
        // 编辑器照常打开、skin.json 仍可编辑；被排除的布局不在列表里，
        // 无法保存，不会覆盖外部改动。读失败则相反（S-L2）：文件存在却
        // 读不出来时无法确认内容，fail-closed（与 skin.json 门对齐）；
        // 缺失文件沿用旧行为跳过（不在本次范围）。
        val layoutFiles = mutableListOf<EditorFile>()
        val unavailableLayouts = mutableListOf<String>()
        for (entry in SkinEditorSave.filterBoundLayouts(index?.layouts ?: emptyList(), boundIds)) {
            val file = File(layout.fileFromRelative(entry.dir), "layout.json")
            if (!file.isFile) continue
            val layoutBytes = runCatching { file.readBytes() }.getOrNull()
            if (layoutBytes == null) {
                Timber.w("skin editor: layout unreadable: ${entry.id}")
                return@withContext ResolveActiveResult.Unavailable("layout_unreadable:${entry.id}")
            }
            if (sha256Hex(layoutBytes) != entry.hash) {
                Timber.w("skin editor: layout hash mismatch, excluded: ${entry.id}")
                unavailableLayouts += entry.id
                continue
            }
            layoutFiles += EditorFile(
                id = entry.id,
                displayName = entry.id,
                file = file,
                kind = EditorFile.Kind.LAYOUT,
                isShared = isLayoutSharedByOtherSkin(skinId, entry.id, index),
            )
        }
        ResolveActiveResult.Ok(
            ActiveSkinFiles(
                skinId = skinId,
                generation = record.generation,
                hash = record.hash,
                files = listOf(
                    EditorFile(skinId, "skin.json", skinFile, EditorFile.Kind.SKIN_JSON),
                ) + layoutFiles,
                primaryLayoutId = primaryLayoutId,
                unavailableLayouts = unavailableLayouts,
            ),
        )
    }

    /** 读文件文本（失败返回 null，页内提示读取失败）。 */
    suspend fun loadText(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { file.readText() }.getOrNull()
    }

    /** 保存 skin.json：校验→新代落盘→应用事务切换→成功更新索引。 */
    suspend fun saveSkinJson(active: ActiveSkinFiles, text: String): SkinEditorSaveResult =
        withContext(Dispatchers.IO) {
            saveMutex.withLock { saveSkinJsonLocked(active, text) }
        }

    /** 保存布局文件：校验→新布局代次＋索引→应用事务切换。 */
    suspend fun saveLayout(
        active: ActiveSkinFiles,
        file: EditorFile,
        text: String,
    ): SkinEditorSaveResult = withContext(Dispatchers.IO) {
        saveMutex.withLock { saveLayoutLocked(active, file, text) }
    }

    private suspend fun saveSkinJsonLocked(active: ActiveSkinFiles, text: String): SkinEditorSaveResult {
        val genDir = layout.skinGenDir(active.skinId, active.generation)
        // 1. Fail-closed 备份（问题 4）：旧文件、清单、活动记录缺一不可，否则不开始写。
        val backups = SkinEditorSave.readSkinBackups(store, genDir)
        if (!backups.isComplete()) {
            return SkinEditorSaveResult.Failed("backup_missing")
        }
        val oldBytes = backups.skinBytes!!
        val oldManifestText = backups.manifestText!!
        val oldRecord = backups.record!!
        // 2. CAS（问题 3）：活动记录须与编辑页读到的版本一致，否则中止、页面刷新。
        if (!SkinEditorSave.casMatches(oldRecord, active.skinId, active.generation, active.hash)) {
            return SkinEditorSaveResult.Stale("active_changed")
        }
        // 3. Manifest 再校验（问题 5）：入口已验，保存时重验防中途损坏；缺项不写 active。
        val oldHash = sha256Hex(oldBytes)
        val manifest = when (val check = SkinEditorSave.validateManifestForEdit(oldManifestText, oldHash)) {
            is ManifestCheck.Invalid -> return SkinEditorSaveResult.Failed("manifest_invalid:${check.reason}")
            is ManifestCheck.Valid -> check.manifest
        }
        // 4. 校验新文本，用与快照工厂同一口径的 manifest resolver（问题 7）
        //    与 isOverridable 口径（WO30 §2 项 2）：新皮肤绑定布局的
        //    坐标 ID 另行可覆写，坐标并集取自新树（校验前 Best-effort；
        //    布局缺失／损坏只跳过该布局，不拖垮校验）。
        val assetKindById = SkinEditorSave.manifestAssetKindById(manifest)
        val assetShaById = SkinEditorSave.manifestAssetShaById(manifest)
        val newTree = SkinJson.parseObject(text)
        val coordIds = if (newTree != null) {
            runCatching {
                SkinSnapshotFactory.layoutCoordIds(
                    newTree,
                    SkinSnapshotFactory.LayoutLookup(layout),
                )
            }.getOrDefault(emptySet())
        } else {
            emptySet()
        }
        val outcome = SkinEditorSave.decideSkinJson(text) { tree ->
            SkinValidator.validate(
                tree,
                currentVersionCode,
                fallbacks,
                assetKind = { assetKindById[it] },
                isOverridable = SkinEditorSave.editorIsOverridable(coordIds),
                assetBytes = { id ->
                    assetShaById[id]?.let { sha ->
                        layout.assetFiles(sha).firstOrNull { it.isFile }?.length()
                    }
                },
            )
        }
        if (outcome is EditorSaveOutcome.Block) {
            return SkinEditorSaveResult.Blocked(outcome.reason, outcome.issues)
        }
        val issues = (outcome as EditorSaveOutcome.Commit).issues
        // 5. Copy-on-write（问题 1）：新 generation＝旧代全量复制＋新 skin.json 覆写＋新代清单同步。
        val newBytes = text.toByteArray(Charsets.UTF_8)
        val newHash = sha256Hex(newBytes)
        val newGen = SkinEditorSave.nextGeneration(
            indexGeneration = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
                ?.skins?.firstOrNull { it.id == active.skinId }?.generation,
            diskGenerations = SkinEditorSave.diskGenerations(layout.skinsDir, active.skinId),
        )
        val newGenDir = layout.skinGenDir(active.skinId, newGen)
        if (!SkinEditorSave.copyGenDir(genDir, newGenDir)) {
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("new_generation_copy_failed")
        }
        try {
            AtomicFiles.writeAtomically(newGenDir, "skin.json", newBytes)
        } catch (e: Exception) {
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("write_failed:${e.message}")
        }
        val newManifest = SkinEditorSave.manifestWithUpdatedSha(manifest, "skin.json", newHash, newBytes.size.toLong())
        if (newManifest === manifest) {
            // 入口校验已保证清单有 skin.json 条目；防御性失败，不留半成品新代。
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("manifest_skin_entry_missing")
        }
        try {
            AtomicFiles.writeAtomically(newGenDir, "manifest.json", PackageManifest.toJson(newManifest))
        } catch (e: Exception) {
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("write_failed:${e.message}")
        }
        if (!layout.isSkinGenComplete(newGenDir)) {
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("new_generation_incomplete")
        }
        // 6. 应用事务切换（问题 1/2/3）：token 在构建前分配、刷新＋首帧等待、成功才提交 VERIFIED／Last-Good。
        //    内联判据须在主线程快照（读窗口视图状态），故切回 Main 调 applySkin。
        //    取消清理（2026-11-12 审计 K-S1）：取消抛 CancellationException
        //    直接穿出时，新代目录必须删掉——否则启动对账按磁盘最新完整
        //    代次修复索引，被取消的修改下次启动复活；同时重装当前生效
        //    快照收敛运行态（应用可能已部分切换）。清理在 NonCancellable
        //    下跑，完成后把取消重抛（取消语义不改写为失败）。
        val newRef = SkinGenRef(active.skinId, newHash, newGen)
        val result = try {
            withContext(mainDispatcher) { applier.applySkin(newRef) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                deleteDirBestEffort(newGenDir)
                // 事务可能已把 active 写成新代 PENDING：先写回保存前
                // 的旧记录（否则重装会去读已被删的新代、收敛失败），
                // 再重装当前生效快照收敛运行态。
                runCatching { store.writeActive(oldRecord) }
                runCatching { withContext(mainDispatcher) { applier.reinstallActiveSnapshot() } }
            }
            throw e
        }
        return when (result) {
            is ApplyTransaction.Result.Applied,
            is ApplyTransaction.Result.AppliedPending -> {
                // 成功才把索引皮肤条目指向新代；索引写失败只记问题，不推翻已生效的保存。
                val indexOk = updateIndexSkinEntry(active.skinId, newHash, newGen, newGenDir)
                val allIssues = if (indexOk) {
                    issues
                } else {
                    // S-L4：索引未同步的提示走 strings.xml，不再硬编码中文。
                    issues + resolveString(R.string.skin_editor_reason_index_update_failed)
                }
                SkinEditorSaveResult.Saved(allIssues)
            }
            else -> {
                // 失败只丢弃新代；旧代未动，事务已回滚运行态与活动记录。
                deleteDirBestEffort(newGenDir)
                val intact = SkinEditorSave.genSkinHashMatches(genDir, oldHash)
                // S-M2：RollbackFailed 表示事务回滚本身失败——运行态可能
                // 仍在新快照上、active 记录可能仍为 PENDING 新代，而新代
                // 目录已被删。按取消路径先例收敛：先写回保存前的旧记录
                // （否则重装会去读已删的新代），再重装当前生效快照；恢复
                // 结果进原因尾巴（recovery_ok／recovery_failed），不吞掉。
                // 其余结果事务已回滚（或未切换），不另行恢复。
                val recovery = if (result is ApplyTransaction.Result.RollbackFailed) {
                    recoverAfterRollbackFailed(oldRecord)
                } else {
                    null
                }
                val tail = "old_gen_${if (intact) "intact" else "check_failed"}" +
                    when (recovery) {
                        null -> ""
                        true -> ":recovery_ok"
                        false -> ":recovery_failed"
                    }
                SkinEditorSaveResult.Failed(
                    "apply_failed:${applyResultReason(result)}:$tail",
                )
            }
        }
    }

    private suspend fun saveLayoutLocked(
        active: ActiveSkinFiles,
        file: EditorFile,
        text: String,
    ): SkinEditorSaveResult {
        // 1. Fail-closed 备份（问题 4）。
        val backups = SkinEditorSave.readLayoutBackups(store, file.file)
        if (!backups.isComplete()) {
            return SkinEditorSaveResult.Failed("backup_missing")
        }
        val oldBytes = backups.layoutBytes!!
        val oldIndex = backups.index!!
        val oldRecord = backups.record!!
        // 2. CAS（问题 3）。
        if (!SkinEditorSave.casMatches(oldRecord, active.skinId, active.generation, active.hash)) {
            return SkinEditorSaveResult.Stale("active_changed")
        }
        // 3. 校验新布局文本。
        val outcome = SkinEditorSave.decideLayout(text, file.id) { tree, id ->
            LayoutValidator.validate(tree, id)
        }
        if (outcome is EditorSaveOutcome.Block) {
            return SkinEditorSaveResult.Blocked(outcome.reason, outcome.issues)
        }
        val issues = (outcome as EditorSaveOutcome.Commit).issues
        val oldEntry = oldIndex.layouts.firstOrNull { it.id == file.id }
            ?: return SkinEditorSaveResult.Failed("layout_index_entry_missing")
        val oldHash = sha256Hex(oldBytes)
        // 4. Copy-on-write：新布局代次，旧代不动；索引先指新代（快照构建经索引解析布局）。
        val newBytes = text.toByteArray(Charsets.UTF_8)
        val newHash = sha256Hex(newBytes)
        val newGen = SkinEditorSave.nextGeneration(
            indexGeneration = oldEntry.generation,
            diskGenerations = SkinEditorSave.diskGenerations(layout.layoutsDir, file.id),
        )
        val newGenDir = layout.layoutGenDir(file.id, newGen)
        try {
            newGenDir.mkdirs()
            AtomicFiles.writeAtomically(newGenDir, "layout.json", newBytes)
        } catch (e: Exception) {
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("write_failed:${e.message}")
        }
        if (!layout.isLayoutGenComplete(newGenDir)) {
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("new_generation_incomplete")
        }
        val newEntry = oldEntry.copy(
            hash = newHash,
            generation = newGen,
            dir = layout.layoutGenRelative(file.id, newGen),
            sizeBytes = layout.dirBytes(newGenDir),
            updatedAt = System.currentTimeMillis(),
        )
        // 索引先指新代是快照构建可见新布局的前提（K-S2 的取舍）：
        // 提交锁内重读核对——旧条目已被他人改动时不写（不盲写整份
        // 索引），返回失败并删新代；索引写本身仍在应用之前，取消／
        // 失败时下文统一恢复旧条目并删新代。
        val indexCommitted = store.updateIndex { current ->
            val currentEntry = current.layouts.firstOrNull { it.id == file.id }
            if (currentEntry != oldEntry) null else current.copy(
                layouts = current.layouts.map { if (it.id == file.id) newEntry else it },
            )
        }
        if (indexCommitted == null) {
            deleteDirBestEffort(newGenDir)
            return SkinEditorSaveResult.Failed("index_changed_concurrently")
        }
        // 5. 应用事务切换（同一皮肤 ref；内容因索引指向新布局而更新）。
        //    布局改动无颜色自愈路径，事务的显式刷新＋首帧等待是唯一即时生效通道。
        //    取消清理（2026-11-12 审计 K-S1／K-S2）：取消穿出时恢复
        //    旧索引条目（只写该条目，不盲写整份旧索引）、删新代、重装
        //    当前生效快照收敛运行态；清理在 NonCancellable 下跑，完
        //    成后把取消重抛。
        val ref = SkinGenRef(active.skinId, oldRecord.hash ?: "", active.generation)
        val result = try {
            withContext(mainDispatcher) { applier.applySkin(ref) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreLayoutIndexEntry(file.id, oldEntry)
                deleteDirBestEffort(newGenDir)
                runCatching { withContext(mainDispatcher) { applier.reinstallActiveSnapshot() } }
            }
            throw e
        }
        return when (result) {
            is ApplyTransaction.Result.Applied,
            is ApplyTransaction.Result.AppliedPending -> {
                SkinEditorSaveResult.Saved(issues)
            }
            else -> {
                // 失败：恢复旧索引条目（只写该条目，不盲写整份旧索引，
                // 避免覆盖并发导入／编辑的其它条目）、删新代；各恢复项
                // 各自执行、汇总结果（问题 4：不顺序一抛即断）。
                var indexRestored = false
                var genDeleted = false
                runCatching { indexRestored = restoreLayoutIndexEntry(file.id, oldEntry) }
                runCatching { newGenDir.deleteRecursively(); genDeleted = true }
                val oldLayoutIntact = runCatching {
                    val bytes = File(layout.fileFromRelative(oldEntry.dir), "layout.json").readBytes()
                    sha256Hex(bytes) == oldHash
                }.getOrDefault(false)
                // 切换／首帧阶段失败时，事务回滚可能已按新索引装了旧皮＋新布局快照；
                // 索引已恢复，须重装当前生效快照收敛运行态。
                // S-M2：重装结果不再被 runCatching 吞掉——收敛与否进
                // restored 汇总（restored_ok 只在全部恢复项都成功时写）；
                // RollbackFailed 时事务回滚本身失败，active 记录可能仍为
                // PENDING，先写回保存前的旧记录再重装（与 skin 路径同款）。
                var recordRepaired: Boolean? = null
                var reinstallOk: Boolean? = null
                if (result is ApplyTransaction.Result.SwitchFailedRolledBack ||
                    result is ApplyTransaction.Result.FirstRenderFailedRolledBack ||
                    result is ApplyTransaction.Result.RollbackFailed
                ) {
                    if (result is ApplyTransaction.Result.RollbackFailed) {
                        recordRepaired = try {
                            store.writeActive(oldRecord)
                            true
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            false
                        }
                    }
                    reinstallOk = try {
                        withContext(mainDispatcher) { applier.reinstallActiveSnapshot() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
                val restored = indexRestored && genDeleted && oldLayoutIntact &&
                    (reinstallOk ?: true) && (recordRepaired ?: true)
                SkinEditorSaveResult.Failed(
                    "apply_failed:${applyResultReason(result)}:restored_${if (restored) "ok" else "partial"}",
                )
            }
        }
    }

    private fun primaryLayoutId(tree: JsonObject): String? {
        val keyboards = tree["keyboards"] as? JsonObject ?: return null
        val pinyin = keyboards["pinyin"] as? JsonObject ?: return null
        return (pinyin["layoutId"] as? JsonPrimitive)?.contentOrNull
    }

    /** 该布局是否被当前皮肤之外的皮肤引用（问题 6：共用布局须标注）。 */
    private fun isLayoutSharedByOtherSkin(
        currentSkinId: String,
        layoutId: String,
        index: LibraryIndex?,
    ): Boolean {
        val skins = index?.skins ?: return false
        for (entry in skins) {
            if (entry.id == currentSkinId) continue
            val otherTree = runCatching {
                SkinJson.parseObject(File(layout.fileFromRelative(entry.dir), "skin.json").readText())
            }.getOrNull() ?: continue
            if (layoutId in SkinEditorSave.boundLayoutIds(otherTree)) return true
        }
        return false
    }

    /** 成功后把索引皮肤条目指向新代（失败返回 false，调用方记问题、不推翻保存）。 */
    private suspend fun updateIndexSkinEntry(
        skinId: String,
        hash: String,
        generation: Int,
        genDir: File,
    ): Boolean {
        // 提交锁内重读→变换→写回（2026-11-12 审计 K-M2）：只改本皮肤
        // 一条，不盲写调用前读到的整份旧索引。
        return runCatching {
            store.updateIndex { index ->
                val existing = index.skins.firstOrNull { it.id == skinId }
                val entry = SkinIndexEntry(
                    id = skinId,
                    name = existing?.name ?: skinId,
                    hash = hash,
                    generation = generation,
                    dir = layout.skinGenRelative(skinId, generation),
                    sizeBytes = layout.dirBytes(genDir),
                    updatedAt = System.currentTimeMillis(),
                )
                val skins = if (existing == null) {
                    index.skins + entry
                } else {
                    index.skins.map { if (it.id == skinId) entry else it }
                }
                index.copy(skins = skins)
            } != null
        }.getOrDefault(false)
    }

    /**
     * 把某布局的索引条目恢复为旧值（K-S2 回滚）：提交锁内只改该条目，
     * 不盲写整份旧索引；返回是否写回成功。
     */
    private suspend fun restoreLayoutIndexEntry(
        layoutId: String,
        oldEntry: com.jobeen.ime.skin.store.LayoutIndexEntry,
    ): Boolean {
        return runCatching {
            store.updateIndex { index ->
                index.copy(
                    layouts = index.layouts.map { if (it.id == layoutId) oldEntry else it },
                )
            } != null
        }.getOrDefault(false)
    }

    /**
     * RollbackFailed 后的收敛恢复（S-M2，skin 路径）：事务回滚本身失败时
     * 运行态与 active 记录可能仍指已删的新代。先写回保存前的旧记录
     * （否则重装会去读已删的新代），再重装当前生效快照；两步都成功
     * 才返回 true。取消原样重抛（不把取消改写为恢复失败）。
     */
    private suspend fun recoverAfterRollbackFailed(oldRecord: ActiveRecord): Boolean {
        val recordOk = try {
            store.writeActive(oldRecord)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!recordOk) return false
        return try {
            withContext(mainDispatcher) { applier.reinstallActiveSnapshot() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun applyResultReason(result: ApplyTransaction.Result): String = when (result) {
        is ApplyTransaction.Result.Applied -> "applied"
        is ApplyTransaction.Result.AppliedPending -> "applied_pending"
        is ApplyTransaction.Result.BuildFailed -> "snapshot_rebuild_failed"
        is ApplyTransaction.Result.TargetInvalid -> "target_invalid"
        is ApplyTransaction.Result.SwitchFailedRolledBack -> "switch_failed_rolled_back"
        is ApplyTransaction.Result.FirstRenderFailedRolledBack -> "first_render_failed_rolled_back"
        is ApplyTransaction.Result.RollbackFailed -> "rollback_failed:${result.reason}"
        is ApplyTransaction.Result.Superseded -> "superseded"
        is ApplyTransaction.Result.RollbackUnavailable -> "rollback_unavailable:${result.reason}"
        is ApplyTransaction.Result.RolledBackToLastGood -> "rolled_back_to_last_good"
        is ApplyTransaction.Result.RevertedToDefault -> "reverted_to_default"
    }

    private fun deleteDirBestEffort(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
