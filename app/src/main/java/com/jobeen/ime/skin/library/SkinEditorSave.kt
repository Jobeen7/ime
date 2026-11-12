// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.LayoutIndexEntry
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 内置皮肤文件编辑器保存裁决（W19-7，纯决策，可单测）：
 *
 * - 校验在落盘前做（先校验、通过才写）：原地改 skin.json 后
 *   若内容非法，重启时 ActiveResolver 的 hash 门（记录 hash 与
 *   磁盘 skin.json 不符）会回退 Last-Good，而 Last-Good 与当前
 *   生效指向同一代次、同一旧 hash，同样不符 → 直接回退内置
 *   默认（皮肤被清掉）。所以非法编辑绝不能先落盘再校验。
 *   校验口径与导入／快照构建同一条（SkinValidator /
 *   LayoutValidator），合法才写文件、更新 manifest sha 与生效
 *   记录 hash、重建快照安装；非法不写文件、不静默丢掉（文本
 *   留在编辑器里）、回落旧值（旧快照继续生效）、页内明示原因。
 * - manifest.json 的 sha256 只在导入时校验（JimeImporter），
 *   运行态构建只拿它做资产元数据、不验 sha；但保存后仍同步
 *   更新对应项的 sha256＋sizeBytes，使包保持自洽（后续导出／
 *   再导入不因清单过期而被拒）。
 */
sealed interface EditorSaveOutcome {
    /** 可落盘：字段级回退问题（已按回退值生效），页内明示。 */
    data class Commit(val issues: List<String>) : EditorSaveOutcome

    /** 不可落盘：不写文件，旧值继续生效，页内明示原因。 */
    data class Block(val reason: String, val issues: List<String> = emptyList()) : EditorSaveOutcome
}

object SkinEditorSave {

    /** skin.json 的 JSON 结构上限（与导入同口径，SkinValidator 十一.5）。 */
    fun skinJsonLimitsExceeded(tree: JsonObject): Boolean =
        SkinJson.depth(tree) > SkinValidator.MAX_JSON_DEPTH ||
            SkinJson.maxStringLength(tree) > SkinValidator.MAX_STRING_LENGTH

    /**
     * skin.json 保存裁决：解析失败／结构超限／校验拒绝 → Block；
     * 校验通过（带字段回退问题亦可）→ Commit。
     *
     * @param validate 与快照构建同一口径的 SkinValidator.validate
     *   调用（调用方传入已配好的 currentVersionCode／fallbacks／
     *   assetKind／assetBytes；isOverridable 缺省 KeyIds 口径）。
     */
    fun decideSkinJson(
        text: String,
        validate: (JsonObject) -> SkinValidator.Result,
    ): EditorSaveOutcome {
        val tree = SkinJson.parseObject(text) ?: return EditorSaveOutcome.Block("skin_unparseable")
        if (skinJsonLimitsExceeded(tree)) {
            return EditorSaveOutcome.Block("skin_json_limits_exceeded")
        }
        return when (val result = validate(tree)) {
            is SkinValidator.Result.Valid -> EditorSaveOutcome.Commit(
                result.issues.map { "${it.path}：${it.detail}" },
            )
            is SkinValidator.Result.Rejected -> EditorSaveOutcome.Block(result.reason)
        }
    }

    /**
     * 布局文件保存裁决：解析失败／结构超限／校验无效 → Block；
     * 校验通过（带问题亦可）→ Commit。
     *
     * @param validate LayoutValidator.validate（与导入／快照构建同一口径）。
     */
    fun decideLayout(
        text: String,
        layoutId: String,
        validate: (JsonElement?, String) -> LayoutValidation,
    ): EditorSaveOutcome {
        val tree = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            ?: return EditorSaveOutcome.Block("layout_unparseable")
        if (SkinJson.depth(tree) > SkinValidator.MAX_JSON_DEPTH ||
            SkinJson.maxStringLength(tree) > SkinValidator.MAX_STRING_LENGTH
        ) {
            return EditorSaveOutcome.Block("layout_json_limits_exceeded")
        }
        return when (val result = validate(tree, layoutId)) {
            is LayoutValidation.Valid -> EditorSaveOutcome.Commit(
                result.issues.map { "${it.path}：${it.reason}" },
            )
            is LayoutValidation.Invalid -> EditorSaveOutcome.Block(
                reason = "layout_invalid:$layoutId",
                issues = result.issues.map { "${it.path}：${it.reason}" },
            )
        }
    }

    /**
     * 清单同步更新（纯函数）：把指定 path 的清单项 sha256 与
     * sizeBytes 更新为新值；path 不在清单里时原样返回（不伪造条目）。
     */
    fun manifestWithUpdatedSha(
        manifest: PackageManifest,
        path: String,
        sha256: String,
        sizeBytes: Long,
    ): PackageManifest {
        if (manifest.contents.none { it.path == path }) return manifest
        return manifest.copy(
            contents = manifest.contents.map { content ->
                if (content.path == path) {
                    content.copy(sha256 = sha256, sizeBytes = sizeBytes)
                } else {
                    content
                }
            },
        )
    }

    // ---- 审计 7 问题事务化重构新增的纯决策（均可单测） ----

    /**
     * 编辑入口的 manifest fail-closed 校验（问题 5）：schema、
     * 必需条目、路径唯一性与当前文件 hash 四项全过才放行。
     * 任一项不过即 Invalid，调用方不得开始编辑、不写 active。
     */
    fun validateManifestForEdit(
        manifestText: String?,
        skinJsonHash: String,
    ): ManifestCheck {
        if (manifestText == null) return ManifestCheck.Invalid("manifest_missing")
        val manifest = PackageManifest.parse(manifestText)
            ?: return ManifestCheck.Invalid("manifest_unparseable")
        if (manifest.formatVersion < 1 ||
            manifest.formatVersion > SkinValidator.SUPPORTED_FORMAT_VERSION
        ) {
            return ManifestCheck.Invalid("manifest_version_unsupported")
        }
        val skinEntry = manifest.contents.firstOrNull {
            it.type == PackageManifest.TYPE_SKIN && it.path == "skin.json"
        } ?: return ManifestCheck.Invalid("manifest_skin_entry_missing")
        if (manifest.contents.map { it.path }.toSet().size != manifest.contents.size) {
            return ManifestCheck.Invalid("manifest_duplicate_path")
        }
        if (skinEntry.sha256 != skinJsonHash) {
            return ManifestCheck.Invalid("manifest_skin_hash_mismatch")
        }
        return ManifestCheck.Valid(manifest)
    }

    /**
     * 与快照工厂同一口径的资产解析映射（问题 7）：由已验证
     * manifest 生成，与 SkinSnapshotFactory.buildFromTree 的
     * assetKindById / assetShaById 逐项同构；编辑器校验改用
     * 它，不再传 assetKind={null}。
     */
    fun manifestAssetKindById(manifest: PackageManifest): Map<String, AssetKind> =
        manifest.assets().associate { it.id to PackageManifest.assetKindOf(it.path) }

    fun manifestAssetShaById(manifest: PackageManifest): Map<String, String> =
        manifest.assets().associate { it.id to it.sha256 }

    /**
     * 当前皮肤实际引用的布局 id 集（问题 6）：与快照工厂同一
     * 口径（顶层 layoutId／layoutIdLandscape＋keyboards 表各
     * 项的竖屏／横屏／深色绑定），只列这些，不列全局索引。
     */
    fun boundLayoutIds(tree: JsonObject): Set<String> =
        SkinSnapshotFactory.overrideLayoutIds(tree)

    /** 索引布局过滤为当前皮肤绑定项（问题 6，纯函数）。 */
    fun filterBoundLayouts(
        indexLayouts: List<LayoutIndexEntry>,
        boundIds: Set<String>,
    ): List<LayoutIndexEntry> = indexLayouts.filter { it.id in boundIds }

    /**
     * 编辑器 isOverridable 口径（WO30 §2 项 2）：与快照工厂
     * 完全一致——出厂键 ID（[KeyIds.isOverridable]）或任一绑定
     * 布局的坐标 ID（[SkinSnapshotFactory.layoutCoordIds] 算出
     * 的并集，顶层竖屏／横屏＋keyboards 表各绑定）。只传坐标
     * 并集、不传布局树：谓词本身是纯函数，可直接单测。
     *
     * 注意：今日行为上与旧缺省口径无差别——[SkinValidator] 在
     * 校验 keyMappings 前已把可解析的坐标 ID 另行放行
     * （`parseLogicalId(id) != null`，2026-10-30 口径），故
     * 旧编辑器也不会把坐标覆写当未知键剔掉。本函数是口径对齐
     * （去双重标准、防将来任一侧收紧时脱节），不是行为修复。
     */
    fun editorIsOverridable(coordIds: Set<String>): (String) -> Boolean =
        { it in coordIds || KeyIds.isOverridable(it) }

    /**
     * 下一代次号（问题 1，copy-on-write）：与导入端同口径，
     * 取索引代次与磁盘已存在代次的最大值加一。
     */
    fun nextGeneration(indexGeneration: Int?, diskGenerations: List<Int>): Int =
        maxOf(indexGeneration ?: 0, diskGenerations.maxOrNull() ?: 0) + 1

    /** 磁盘上已存在的代次号（与导入端同口径）。 */
    fun diskGenerations(parentDir: File, id: String): List<Int> {
        val idDir = File(parentDir, id)
        return idDir.listFiles { f -> f.isDirectory && f.name.startsWith("gen-") }
            ?.mapNotNull { it.name.removePrefix("gen-").toIntOrNull() }
            ?: emptyList()
    }

    /**
     * 保存前 compare-and-swap（问题 3）：重读的活动记录须与
     * 编辑页读到的 skinId／generation／hash 全等，否则中止。
     */
    fun casMatches(
        record: ActiveRecord?,
        skinId: String,
        generation: Int,
        hash: String?,
    ): Boolean =
        record != null &&
            record.skinId == skinId &&
            record.generation == generation &&
            record.hash == hash

    /** 代次目录整目录复制（问题 1，copy-on-write 的落盘一步）。 */
    fun copyGenDir(src: File, dst: File): Boolean =
        runCatching { src.copyRecursively(dst, overwrite = false) }.getOrDefault(false)

    /**
     * 回滚后校验（问题 4）：旧代次 skin.json 读回的 hash 须与
     * 期望旧 hash 一致，“已恢复”只建立在这一校验成功上。
     */
    fun genSkinHashMatches(genDir: File, expectedHash: String): Boolean {
        val bytes = runCatching { File(genDir, "skin.json").readBytes() }.getOrNull()
            ?: return false
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return hash == expectedHash
    }

    /** skin.json 保存前的备份集（问题 4）：任一缺失即 fail closed。 */
    data class SkinSaveBackups(
        val skinBytes: ByteArray?,
        val manifestText: String?,
        val record: ActiveRecord?,
    ) {
        fun isComplete(): Boolean =
            skinBytes != null && manifestText != null && record != null
    }

    /** 布局保存前的备份集（问题 4）：任一缺失即 fail closed。 */
    data class LayoutSaveBackups(
        val layoutBytes: ByteArray?,
        val index: LibraryIndex?,
        val record: ActiveRecord?,
    ) {
        fun isComplete(): Boolean =
            layoutBytes != null && index != null && record != null
    }

    /** 读 skin 保存备份（问题 4）：三项各自读取，失败只变 null，由 isComplete 裁决。 */
    suspend fun readSkinBackups(
        store: SkinLibraryStore,
        genDir: File,
    ): SkinSaveBackups = SkinSaveBackups(
        skinBytes = runCatching { File(genDir, "skin.json").readBytes() }.getOrNull(),
        manifestText = runCatching { File(genDir, "manifest.json").readText() }.getOrNull(),
        record = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value,
    )

    /** 读布局保存备份（问题 4）。 */
    suspend fun readLayoutBackups(
        store: SkinLibraryStore,
        layoutFile: File,
    ): LayoutSaveBackups = LayoutSaveBackups(
        layoutBytes = runCatching { layoutFile.readBytes() }.getOrNull(),
        index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value,
        record = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value,
    )
}

/** 编辑入口 manifest 校验结果（问题 5）。 */
sealed interface ManifestCheck {
    data class Valid(val manifest: PackageManifest) : ManifestCheck
    data class Invalid(val reason: String) : ManifestCheck
}
