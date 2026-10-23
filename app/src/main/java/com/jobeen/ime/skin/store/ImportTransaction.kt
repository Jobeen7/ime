package com.jobeen.ime.skin.store

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.fault.SkinFaultHooks
import java.io.File
import java.util.UUID

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
        return try {
            staging.mkdirs()
            SkinFaultHooks.hit(SkinFaultHooks.Point.I1)
            val built = build(staging)
            SkinFaultHooks.hit(SkinFaultHooks.Point.I2)
            verifyStaged(built)

            val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
                ?: LibraryIndex()

            // 身份判定（定稿七.4）：同 id 同 hash → 跳过；同 id 异 hash 由
            // 构建回调在 T3 层先行做副本判定，本机制层只处理无冲突入库
            val skinsToImport = mutableListOf<StagedSkin>()
            val skippedSkins = mutableListOf<String>()
            for (staged in built.skins) {
                val existing = index.skins.firstOrNull { it.id == staged.id }
                if (existing != null && existing.hash == staged.hash) {
                    skippedSkins += staged.id
                } else {
                    skinsToImport += staged
                }
            }
            val layoutsToImport = mutableListOf<StagedLayout>()
            val skippedLayouts = mutableListOf<String>()
            for (staged in built.layouts) {
                val existing = index.layouts.firstOrNull { it.id == staged.id }
                if (existing != null && existing.hash == staged.hash) {
                    skippedLayouts += staged.id
                } else {
                    layoutsToImport += staged
                }
            }

            stage = "rename"
            val newSkinEntries = mutableListOf<SkinIndexEntry>()
            for (staged in skinsToImport) {
                val generation = nextGeneration(
                    index.skins.firstOrNull { it.id == staged.id }?.generation,
                    diskGenerations(layout.skinsDir, staged.id),
                )
                val target = layout.skinGenDir(staged.id, generation)
                target.parentFile?.mkdirs()
                check(staged.dir.renameTo(target)) { "rename staged skin ${staged.id} failed" }
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
            val newLayoutEntries = mutableListOf<LayoutIndexEntry>()
            for (staged in layoutsToImport) {
                val generation = nextGeneration(
                    index.layouts.firstOrNull { it.id == staged.id }?.generation,
                    diskGenerations(layout.layoutsDir, staged.id),
                )
                val target = layout.layoutGenDir(staged.id, generation)
                target.parentFile?.mkdirs()
                check(staged.dir.renameTo(target)) { "rename staged layout ${staged.id} failed" }
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
            val importedAssetShas = mutableListOf<String>()
            for (staged in built.assets) {
                if (layout.assetFiles(staged.sha256).isNotEmpty()) continue
                val ext = staged.file.extension.ifBlank { null }
                val target = layout.assetFile(staged.sha256, ext)
                check(staged.file.renameTo(target)) { "rename staged asset ${staged.sha256} failed" }
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
            store.writeIndex(newIndex)
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
        } catch (e: Exception) {
            // rename 前失败库零变化；rename 后失败已入库代次留作孤儿待对账（§2.3.3）
            runCatching { staging.deleteRecursively() }
            DiagLog.e(
                "store", "import_failed",
                mapOf("stage" to stage, "error" to e.javaClass.simpleName),
                e,
            )
            Result.Failed(stage, e.message ?: e.javaClass.simpleName)
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
