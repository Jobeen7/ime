package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.fault.SkinFaultHooks
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 导入事务的机制层（详设 §2.3，步骤写死）：
 * `staging 解包/构建 → 校验 → staging 内成型 → rename 进库 → 更新索引`。
 *
 * 本类只管事务机制：staging 生命周期、内容 hash 复核、代次分配、
 * rename、索引更新、失败清理。包格式校验（zip 安全/manifest/字段，
 * 定稿九/十一章）与背景预处理是构建回调 [build] 的职责（T3），
 * 回调在 staging 内把一代内容摆好并描述出来：
 * - 皮肤：`staging/skins/<id>/` 即一代的完整内容（skin.json + manifest.json + prepared/）
 * - 布局：`staging/layouts/<id>/` 同上（layout.json + manifest.json）
 * - 资产：`staging/assets/<sha256>.<ext>` 实体文件
 *
 * 失败口径（§2.3.2/3）：rename 前失败 → staging 整删、库零变化；
 * rename 后索引更新前失败 → 已 rename 的代次目录留作孤儿，由启动
 * 对账收敛（I-3），本类如实以 [Result.Failed] 报出阶段。
 */
class ImportTransaction(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
    private val uuid: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class StagedSkin(val id: String, val name: String, val dir: File, val hash: String)
    data class StagedLayout(val id: String, val dir: File, val hash: String)
    data class StagedAsset(val sha256: String, val file: File)
    data class BuiltPackage(
        val skins: List<StagedSkin> = emptyList(),
        val layouts: List<StagedLayout> = emptyList(),
        val assets: List<StagedAsset> = emptyList(),
    )

    sealed interface Result {
        data class Imported(
            val skins: List<SkinIndexEntry>,
            val layouts: List<LayoutIndexEntry>,
            val skippedSkinIds: List<String>,
            val skippedLayoutIds: List<String>,
        ) : Result

        data class Failed(val stage: String, val reason: String) : Result
    }

    suspend fun importPackage(build: (stagingDir: File) -> BuiltPackage): Result {
        layout.ensureDirs()
        val staging = File(layout.stagingDir, uuid())
        var stage = "build"
        // 第 5 批 §1 提交失败双删：本笔已 rename 到库内的目标
        // （新代次目录／新资产文件）在此登记；失败时与暂存区
        // 一并删（索引未更新时删目标 = 库零变化，不留孤儿）。
        // 索引写成功后（stage=post_index）不再删目标——此时
        // 目标已是索引指向的活代次，删之即毁库。
        val renamedTargets = mutableListOf<File>()
        // 失败日志用：构建回调报出的皮肤/布局 id（构建中途失败时仍为空，属实记录）。
        val builtIds = mutableListOf<String>()
        // 本笔要提交的条目：声明在 try 之外——取消分支需在 catch 里
        // 凭它们重读索引核对提交与否，不能只信内存 stage。
        val skippedSkins = mutableListOf<String>()
        val skippedLayouts = mutableListOf<String>()
        val newSkinEntries = mutableListOf<SkinIndexEntry>()
        val newLayoutEntries = mutableListOf<LayoutIndexEntry>()
        val importedAssetShas = mutableListOf<String>()
        return try {
            staging.mkdirs()
            SkinFaultHooks.hit(SkinFaultHooks.Point.I1)
            val built = build(staging)
            builtIds += built.skins.map { it.id }
            builtIds += built.layouts.map { it.id }
            SkinFaultHooks.hit(SkinFaultHooks.Point.I2)
            verifyStaged(built)

            // 提交锁（同库根共享）：读索引→改名→写索引须整段串行——
            // 同 id 会撞同一代次目录（rename 失败），异 id 会各自拿旧索引
            // 快照写回、丢对方条目。构建回调与校验只碰本笔 staging
            // （uuid 唯一），不进锁。
            store.withCommitLock {
                val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
                    ?: LibraryIndex()

                // 身份判定（定稿七.4）：同 id 同 hash → 跳过；同 id 异 hash 由
                // 构建回调在 T3 层先行做副本判定，本机制层只处理无冲突入库
                val skinsToImport = mutableListOf<StagedSkin>()
                for (staged in built.skins) {
                    val existing = index.skins.firstOrNull { it.id == staged.id }
                    if (existing != null && existing.hash == staged.hash) {
                        skippedSkins += staged.id
                    } else {
                        skinsToImport += staged
                    }
                }
                val layoutsToImport = mutableListOf<StagedLayout>()
                for (staged in built.layouts) {
                    val existing = index.layouts.firstOrNull { it.id == staged.id }
                    if (existing != null && existing.hash == staged.hash) {
                        skippedLayouts += staged.id
                    } else {
                        layoutsToImport += staged
                    }
                }

                stage = "rename"
                for (staged in skinsToImport) {
                    val generation = nextGeneration(
                        index.skins.firstOrNull { it.id == staged.id }?.generation,
                        diskGenerations(layout.skinsDir, staged.id),
                    )
                    val target = layout.skinGenDir(staged.id, generation)
                    target.parentFile?.mkdirs()
                    check(staged.dir.renameTo(target)) { "rename staged skin ${staged.id} failed" }
                    renamedTargets += target
                    newSkinEntries += SkinIndexEntry(
                        id = staged.id,
                        name = staged.name,
                        hash = staged.hash,
                        generation = generation,
                        dir = layout.skinGenRelative(staged.id, generation),
                        sizeBytes = layout.dirBytes(target),
                        updatedAt = clock(),
                    )
                }
                for (staged in layoutsToImport) {
                    val generation = nextGeneration(
                        index.layouts.firstOrNull { it.id == staged.id }?.generation,
                        diskGenerations(layout.layoutsDir, staged.id),
                    )
                    val target = layout.layoutGenDir(staged.id, generation)
                    target.parentFile?.mkdirs()
                    check(staged.dir.renameTo(target)) { "rename staged layout ${staged.id} failed" }
                    renamedTargets += target
                    newLayoutEntries += LayoutIndexEntry(
                        id = staged.id,
                        hash = staged.hash,
                        generation = generation,
                        dir = layout.layoutGenRelative(staged.id, generation),
                        sizeBytes = layout.dirBytes(target),
                        updatedAt = clock(),
                    )
                }
                // 资产实体：内容寻址，已存在即去重跳过（定稿七.5）
                for (staged in built.assets) {
                    if (layout.assetFiles(staged.sha256).isNotEmpty()) continue
                    val ext = staged.file.extension.ifBlank { null }
                    val target = layout.assetFile(staged.sha256, ext)
                    check(staged.file.renameTo(target)) { "rename staged asset ${staged.sha256} failed" }
                    renamedTargets += target
                    importedAssetShas += staged.sha256
                }
                SkinFaultHooks.hit(SkinFaultHooks.Point.I3)

                stage = "index"
                SkinFaultHooks.hit(SkinFaultHooks.Point.I4)
                var newIndex = index.copy(
                    skins = mergeEntries(index.skins, newSkinEntries) { it.id },
                    layouts = mergeEntries(index.layouts, newLayoutEntries) { it.id },
                )
                // 资产条目并入后按引用图统一刷新 refcountCache（与对账同口径）
                val knownShas = newIndex.assets.map { it.sha256 }.toMutableSet()
                val assetEntries = newIndex.assets.toMutableList()
                for (sha in importedAssetShas) {
                    if (knownShas.add(sha)) {
                        val size = layout.assetFiles(sha).firstOrNull()?.length() ?: 0L
                        assetEntries += AssetIndexEntry(sha256 = sha, sizeBytes = size, refcountCache = 0)
                    }
                }
                newIndex = newIndex.copy(assets = assetEntries)
                val refcounts = AssetRefcounts.compute(layout, newIndex)
                newIndex = newIndex.copy(
                    assets = newIndex.assets.map { it.copy(refcountCache = refcounts[it.sha256] ?: 0) },
                )
                // 提交边界：写索引＋记录已提交结果不可取消——取消若在
                // 写索引挂起期间请求，会被推迟到本块结束后才投递；此
                // 时原子 rename 已落盘、stage 已是 post_index，补偿
                // 不会误删已提交的目标。
                withContext(NonCancellable) {
                    store.writeIndex(newIndex)
                    stage = "post_index"
                }
            }
            // 被推迟的取消在此投递——提交已完成且 stage 已是
            // post_index；取消分支会重读索引确认已提交、不删目标，
            // 再把取消重抛给调用方。
            currentCoroutineContext().ensureActive()
            SkinFaultHooks.hit(SkinFaultHooks.Point.I5)

            runCatching { staging.deleteRecursively() }
            DiagLog.i(
                "store", "import_done",
                mapOf(
                    "skins" to newSkinEntries.size, "layouts" to newLayoutEntries.size,
                    "assets" to importedAssetShas.size,
                    "skippedSkins" to skippedSkins.size, "skippedLayouts" to skippedLayouts.size,
                ),
            )
            Result.Imported(newSkinEntries, newLayoutEntries, skippedSkins, skippedLayouts)
        } catch (e: CancellationException) {
            // 取消：绝不只信内存 stage——持提交锁重读持久化索引，核
            // 对本次要提交的条目是否已被引用，再决定补偿；随后重抛。
            // （取消若在写索引挂起期间请求，恢复时才投递：此时原子
            // rename 已落盘，内存 stage 仍是 "index"，只凭 stage
            // 删目标即毁库——此即本窗口的病根。）
            runCatching { staging.deleteRecursively() }
            val committed = if (renamedTargets.isEmpty()) {
                false
            } else {
                isImportCommitted(newSkinEntries, newLayoutEntries, importedAssetShas)
            }
            if (committed == false) {
                // 确认未提交：删已 rename 的目标，回滚到库零变化。
                for (target in renamedTargets) {
                    runCatching {
                        if (target.isDirectory) target.deleteRecursively() else target.delete()
                    }
                }
            }
            // committed == true（已提交）或 null（索引不可读、无法
            // 裁决）时不删目标：已提交的不动；无法裁决的留孤儿给启
            // 动对账收敛（索引不可读时一律保留，与恢复流程同口径）。
            DiagLog.i(
                "store", "import_cancelled",
                mapOf(
                    "stage" to stage,
                    "committed" to (committed?.toString() ?: "unknown"),
                    "skinId" to newSkinEntries.firstOrNull()?.id,
                ),
            )
            throw e
        } catch (e: Exception) {
            // 第 5 批 §1 双删：暂存区恒删；索引写成功前已 rename 的
            // 目标亦删（索引未指过去，删 = 库零变化，不留孤儿待对账）。
            // 索引写成功后（post_index）的失败不删目标（已是活代次）。
            runCatching { staging.deleteRecursively() }
            if (stage != "post_index") {
                for (target in renamedTargets) {
                    runCatching {
                        if (target.isDirectory) target.deleteRecursively() else target.delete()
                    }
                }
            }
            DiagLog.e(
                "store", "import_failed",
                mapOf(
                    "stage" to stage,
                    "ids" to builtIds.take(5).joinToString(","),
                    "error" to e.javaClass.simpleName,
                    "message" to (e.message ?: ""),
                ),
                e,
            )
            Result.Failed(stage, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 持提交锁重读持久化索引，核对本次要提交的条目是否全已引用。
     * true=已提交；false=确认未提交；null=索引不可读、无法裁决。
     * 索引不存在（Missing）即未提交——无索引则无任何引用，与
     * 「索引未落盘＝空库」同口径；只有损坏（Corrupt）才无法裁决。
     * 在 NonCancellable 下运行——调用方已处于取消投递中，读索引
     * 的挂起点不得再抛取消。
     */
    private suspend fun isImportCommitted(
        newSkins: List<SkinIndexEntry>,
        newLayouts: List<LayoutIndexEntry>,
        assetShas: List<String>,
    ): Boolean? = withContext(NonCancellable) {
        store.withCommitLock {
            val current = when (val read = store.readIndex()) {
                is SkinLibraryStore.ReadResult.Ok -> read.value
                is SkinLibraryStore.ReadResult.Missing -> return@withCommitLock false
                is SkinLibraryStore.ReadResult.Corrupt -> return@withCommitLock null
            }
            val skinsOk = newSkins.all { e ->
                current.skins.any { it.id == e.id && it.generation == e.generation && it.dir == e.dir }
            }
            val layoutsOk = newLayouts.all { e ->
                current.layouts.any { it.id == e.id && it.generation == e.generation && it.dir == e.dir }
            }
            val assetsOk = assetShas.all { sha ->
                current.assets.any { it.sha256 == sha }
            }
            skinsOk && layoutsOk && assetsOk
        }
    }

    /** 内容 hash 复核：构建回调自报的 hash 必须与暂存文件实算一致，否则整笔拒绝。 */
    private fun verifyStaged(built: BuiltPackage) {
        for (staged in built.skins) {
            val skinJson = File(staged.dir, "skin.json")
            check(skinJson.isFile) { "staged skin ${staged.id} missing skin.json" }
            check(File(staged.dir, "manifest.json").isFile) {
                "staged skin ${staged.id} missing manifest.json"
            }
            check(DigestUtil.sha256Hex(skinJson) == staged.hash) {
                "staged skin ${staged.id} hash mismatch"
            }
        }
        for (staged in built.layouts) {
            val layoutJson = File(staged.dir, "layout.json")
            check(layoutJson.isFile) { "staged layout ${staged.id} missing layout.json" }
            check(DigestUtil.sha256Hex(layoutJson) == staged.hash) {
                "staged layout ${staged.id} hash mismatch"
            }
        }
        for (staged in built.assets) {
            check(staged.file.isFile) { "staged asset ${staged.sha256} missing" }
            check(DigestUtil.sha256Hex(staged.file) == staged.sha256) {
                "staged asset hash mismatch"
            }
        }
    }

    private fun nextGeneration(indexGeneration: Int?, diskGenerations: List<Int>): Int =
        maxOf(indexGeneration ?: 0, diskGenerations.maxOrNull() ?: 0) + 1

    private fun diskGenerations(parentDir: File, id: String): List<Int> {
        val idDir = File(parentDir, id)
        return idDir.listFiles { f -> f.isDirectory && f.name.startsWith("gen-") }
            ?.mapNotNull { it.name.removePrefix("gen-").toIntOrNull() }
            ?: emptyList()
    }

    private fun <T> mergeEntries(
        existing: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        if (incoming.isEmpty()) return existing
        val incomingIds = incoming.map(idOf).toSet()
        return existing.filter { idOf(it) !in incomingIds } + incoming
    }
}
