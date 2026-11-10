// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import android.content.Context
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.runtime.SkinSnapshotFactory
import com.jobeen.ime.skin.runtime.SkinSnapshotProvider
import com.jobeen.ime.skin.store.ActiveRecord
import com.jobeen.ime.skin.store.AtomicFiles
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibrary
import com.jobeen.ime.skin.store.SkinLibraryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest

/** 编辑器可打开的文件（skin.json 或布局文件）。 */
data class EditorFile(
    val id: String,
    val displayName: String,
    val file: File,
    val kind: Kind,
) {
    enum class Kind { SKIN_JSON, LAYOUT }
}

/** 当前生效皮肤的可编辑文件集（skin.json＋该皮肤的布局文件）。 */
data class ActiveSkinFiles(
    val skinId: String,
    val generation: Int,
    val files: List<EditorFile>,
    /** 主布局 id（skin.json keyboards.pinyin.layoutId，缺失时回落首个布局）。 */
    val primaryLayoutId: String?,
)

sealed interface SkinEditorSaveResult {
    /** 已保存并重载生效；issues 为校验回落项（逐条页内明示）。 */
    data class Saved(val issues: List<String>) : SkinEditorSaveResult
    /** 校验未通过：文件未写、旧快照仍生效、文本留在编辑器里。 */
    data class Blocked(val reason: String, val issues: List<String>) : SkinEditorSaveResult
    /** 保存／重载过程失败（文件／清单／记录已尽力回滚，见实现注释）。 */
    data class Failed(val reason: String) : SkinEditorSaveResult
}

/**
 * App 内置皮肤文件编辑器保存编排（W19-7）：
 *
 * 口径（已核实，见汇报）：manifest.json sha256 只在**导入时**校验
 * （JimeImporter.validateManifestContents）；运行时重载只认
 * active.json 的 hash（ActiveResolver.isTargetValid）。原地改文件
 * 若不同步更新 manifest 与 active 记录，重启后会回滚到默认皮肤。
 * 所以保存顺序是**先校验、再落盘**（与任务字面「保存后校验」不同，
 * 原因：校验失败时不写文件才能保住旧快照；若先写后校验失败，
 * 旧文件已丢、回滚无从谈起）：
 * 1. decide（纯函数，见 SkinEditorSave）：解析＋限长＋校验器；
 * 2. 原子写文件（AtomicFiles.writeAtomically）；
 * 3. 同步更新 manifest.json 对应项 sha256＋sizeBytes；
 * 4. 同步更新 active.json（skin.json 的 hash）／index.json（布局的 hash）；
 * 5. SkinSnapshotFactory.buildFromGenDir 重建＋SkinSnapshotProvider.install，
 *    改完即刻生效，不重启键盘。
 * 任何一步失败：已改的项尽力还原为旧值，返回 Failed；Last-Good
 * 指针**不**动（旧内容仍是回滚目标）。
 */
class SkinEditorSaver(private val context: Context) {

    private val library: SkinLibrary = SkinLibrary
    private val store: SkinLibraryStore = library.store
    private val layout = library.layout

    /** 解析当前生效皮肤：skin.json＋该皮肤引用的全部布局文件。 */
    suspend fun resolveActiveFiles(): ActiveSkinFiles? = withContext(Dispatchers.IO) {
        val record = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: return@withContext null
        val skinId = record.skinId ?: return@withContext null
        val genDir = layout.skinGenDir(skinId, record.generation)
        val skinFile = File(genDir, "skin.json")
        if (!skinFile.isFile) return@withContext null
        val skinText = runCatching { skinFile.readText() }.getOrNull() ?: return@withContext null
        val tree = SkinJson.parseObject(skinText) ?: return@withContext null
        val primaryLayoutId = primaryLayoutId(tree)
        val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        val layoutFiles = (index?.layouts ?: emptyList()).mapNotNull { entry ->
            val file = File(layout.fileFromRelative(entry.dir), "layout.json")
            if (!file.isFile) null else EditorFile(
                id = entry.id,
                displayName = entry.id,
                file = file,
                kind = EditorFile.Kind.LAYOUT,
            )
        }
        ActiveSkinFiles(
            skinId = skinId,
            generation = record.generation,
            files = listOf(
                EditorFile(skinId, "skin.json", skinFile, EditorFile.Kind.SKIN_JSON),
            ) + layoutFiles,
            primaryLayoutId = primaryLayoutId,
        )
    }

    /** 读文件文本（失败返回 null，页内提示读取失败）。 */
    suspend fun loadText(file: File): String? = withContext(Dispatchers.IO) {
        runCatching { file.readText() }.getOrNull()
    }

    /** 保存 skin.json：校验→原子写→清单／记录 hash 更新→重建＋安装快照。 */
    suspend fun saveSkinJson(active: ActiveSkinFiles, text: String): SkinEditorSaveResult =
        withContext(Dispatchers.IO) {
            val outcome = SkinEditorSave.decideSkinJson(text) { tree ->
                SkinValidator.validate(
                    tree,
                    SkinSnapshotFactory.currentVersionCode(context),
                    SkinSnapshotFactory.geometryFallbacks(context),
                    assetKind = { null },
                )
            }
            if (outcome is EditorSaveOutcome.Block) {
                return@withContext SkinEditorSaveResult.Blocked(outcome.reason, outcome.issues)
            }
            val issues = (outcome as EditorSaveOutcome.Commit).issues
            val genDir = layout.skinGenDir(active.skinId, active.generation)
            val skinFile = File(genDir, "skin.json")
            val manifestFile = File(genDir, "manifest.json")
            val oldBytes = runCatching { skinFile.readBytes() }.getOrNull()
            val oldManifestText = runCatching { manifestFile.readText() }.getOrNull()
            val oldRecord = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
            val newBytes = text.toByteArray(Charsets.UTF_8)
            val newHash = sha256Hex(newBytes)
            try {
                AtomicFiles.writeAtomically(genDir, "skin.json", newBytes)
                updateManifestSha(manifestFile, "skin.json", newHash, newBytes.size.toLong())
                store.writeActive(
                    (oldRecord ?: ActiveRecord()).copy(
                        skinId = active.skinId,
                        hash = newHash,
                        generation = active.generation,
                    ),
                )
            } catch (e: Exception) {
                restoreSkinJson(skinFile, oldBytes, manifestFile, oldManifestText, oldRecord)
                return@withContext SkinEditorSaveResult.Failed("write_failed:${e.message}")
            }
            val snapshot = runCatching {
                SkinSnapshotFactory.buildFromGenDir(
                    context,
                    layout,
                    SkinGenRef(active.skinId, newHash, active.generation),
                )
            }.getOrNull()
            if (snapshot == null) {
                restoreSkinJson(skinFile, oldBytes, manifestFile, oldManifestText, oldRecord)
                return@withContext SkinEditorSaveResult.Failed("snapshot_rebuild_failed")
            }
            if (!SkinSnapshotProvider.install(context, snapshot)) {
                return@withContext SkinEditorSaveResult.Failed("snapshot_install_failed")
            }
            SkinEditorSaveResult.Saved(issues)
        }

    /** 保存布局文件：校验→原子写→index hash 更新→重建＋安装快照。 */
    suspend fun saveLayout(
        active: ActiveSkinFiles,
        file: EditorFile,
        text: String,
    ): SkinEditorSaveResult = withContext(Dispatchers.IO) {
        val outcome = SkinEditorSave.decideLayout(text, file.id) { tree, id ->
            LayoutValidator.validate(tree, id)
        }
        if (outcome is EditorSaveOutcome.Block) {
            return@withContext SkinEditorSaveResult.Blocked(outcome.reason, outcome.issues)
        }
        val issues = (outcome as EditorSaveOutcome.Commit).issues
        val oldBytes = runCatching { file.file.readBytes() }.getOrNull()
        val oldIndex = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
        val newBytes = text.toByteArray(Charsets.UTF_8)
        val newHash = sha256Hex(newBytes)
        try {
            AtomicFiles.writeAtomically(file.file.parentFile!!, "layout.json", newBytes)
            val index = oldIndex ?: return@withContext SkinEditorSaveResult.Failed("index_missing")
            store.writeIndex(
                index.copy(
                    layouts = index.layouts.map { entry ->
                        if (entry.id == file.id) {
                            entry.copy(hash = newHash, sizeBytes = newBytes.size.toLong())
                        } else {
                            entry
                        }
                    },
                ),
            )
        } catch (e: Exception) {
            runCatching {
                if (oldBytes != null) {
                    AtomicFiles.writeAtomically(file.file.parentFile!!, "layout.json", oldBytes)
                }
                if (oldIndex != null) store.writeIndex(oldIndex)
            }
            return@withContext SkinEditorSaveResult.Failed("write_failed:${e.message}")
        }
        val record = (store.readActive() as? SkinLibraryStore.ReadResult.Ok)?.value
        val snapshot = runCatching {
            SkinSnapshotFactory.buildFromGenDir(
                context,
                layout,
                SkinGenRef(active.skinId, record?.hash ?: "", active.generation),
            )
        }.getOrNull()
        if (snapshot == null) {
            runCatching {
                if (oldBytes != null) {
                    AtomicFiles.writeAtomically(file.file.parentFile!!, "layout.json", oldBytes)
                }
                if (oldIndex != null) store.writeIndex(oldIndex)
            }
            return@withContext SkinEditorSaveResult.Failed("snapshot_rebuild_failed")
        }
        if (!SkinSnapshotProvider.install(context, snapshot)) {
            return@withContext SkinEditorSaveResult.Failed("snapshot_install_failed")
        }
        SkinEditorSaveResult.Saved(issues)
    }

    private fun primaryLayoutId(tree: JsonObject): String? {
        val keyboards = tree["keyboards"] as? JsonObject ?: return null
        val pinyin = keyboards["pinyin"] as? JsonObject ?: return null
        return (pinyin["layoutId"] as? JsonPrimitive)?.contentOrNull
    }

    private fun updateManifestSha(manifestFile: File, path: String, sha: String, size: Long) {
        val text = runCatching { manifestFile.readText() }.getOrNull() ?: return
        val manifest = PackageManifest.parse(text) ?: return
        val updated = SkinEditorSave.manifestWithUpdatedSha(manifest, path, sha, size)
        if (updated !== manifest) {
            AtomicFiles.writeAtomically(
                manifestFile.parentFile!!,
                "manifest.json",
                PackageManifest.toJson(updated),
            )
        }
    }

    private suspend fun restoreSkinJson(
        skinFile: File,
        oldBytes: ByteArray?,
        manifestFile: File,
        oldManifestText: String?,
        oldRecord: ActiveRecord?,
    ) {
        runCatching {
            if (oldBytes != null) {
                AtomicFiles.writeAtomically(skinFile.parentFile!!, "skin.json", oldBytes)
            }
            if (oldManifestText != null) {
                AtomicFiles.writeAtomically(manifestFile.parentFile!!, "manifest.json", oldManifestText)
            }
            if (oldRecord != null) store.writeActive(oldRecord)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
