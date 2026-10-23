package com.jobeen.ime.skin.draft

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.pkg.JimeImporter
import com.jobeen.ime.skin.pkg.ZipSafeExtractor
import com.jobeen.ime.skin.store.LibraryIndex
import com.jobeen.ime.skin.store.SkinLibraryLayout
import com.jobeen.ime.skin.store.SkinLibraryStore
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 草稿提交（详设 §5.4）：草稿树 + 库内既有实体（引用布局、源
 * 资产）+ 草稿新选资产，现场组包后走导入管线（§3.1 全量校验、
 * 背景预处理、事务入库），模式 UPDATE——同 id 入新代次、内容
 * 未变则跳过。提交只入库不生效，生效走应用事务（§2.4），
 * 两步分离保证草稿应用失败时旧代次与运行态不动。
 */
class SkinDraftCommitter(
    private val layout: SkinLibraryLayout,
    private val store: SkinLibraryStore,
    private val draftStore: DraftStore,
    private val importerProvider: () -> Pair<JimeImporter, JimeImporter.Context>,
) {

    sealed interface Result {
        data class Committed(val skinId: String, val generation: Int) : Result
        object NoChanges : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun commit(draft: DraftStore.Draft): Result {
        val skinId = draft.meta.skinId
        val treeBytes = Json.encodeToString(JsonObject.serializer(), draft.tree)
            .encodeToByteArray()
        val index = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
            ?: LibraryIndex()
        val contents = mutableListOf(
            PackageManifest.Content(
                PackageManifest.TYPE_SKIN, skinId, "skin.json",
                DigestUtil.sha256Hex(treeBytes),
            ),
        )
        val files = mutableListOf(ZipSafeExtractor.ExtractedFile("skin.json", treeBytes))

        // 引用布局结转（编辑不改布局，字节取库内当前代次）
        val layoutId = SkinTreeEditor.layoutIdOf(draft.tree)
        if (layoutId != null) {
            val layoutEntry = index.layouts.firstOrNull { it.id == layoutId }
                ?: return Result.Failed("layout_missing:$layoutId")
            val layoutBytes = runCatching {
                File(layout.fileFromRelative(layoutEntry.dir), "layout.json").readBytes()
            }.getOrNull() ?: return Result.Failed("layout_file_missing:$layoutId")
            val path = "layouts/$layoutId.json"
            files += ZipSafeExtractor.ExtractedFile(path, layoutBytes)
            contents += PackageManifest.Content(
                PackageManifest.TYPE_LAYOUT, layoutId, path, layoutEntry.hash,
            )
        }

        // 资产解析：源代次清单的 id→(sha,path) 为底，草稿新选资产覆盖背景项
        val assetMap = mutableMapOf<String, Pair<String, String>>()
        val skinEntry = index.skins.firstOrNull { it.id == skinId }
        if (skinEntry != null) {
            val sourceManifest = runCatching {
                PackageManifest.parse(
                    File(layout.fileFromRelative(skinEntry.dir), "manifest.json").readText(),
                )
            }.getOrNull()
            sourceManifest?.assets()?.forEach { assetMap[it.id] = it.sha256 to it.path }
        }
        val draftAssetBytes = mutableMapOf<String, ByteArray>()
        val backgroundId = SkinTreeEditor.backgroundOf(draft.tree)?.assetId
        if (backgroundId != null) {
            for (file in draftStore.draftAssetFiles(skinId)) {
                val bytes = file.readBytes()
                val sha = DigestUtil.sha256Hex(bytes)
                draftAssetBytes[sha] = bytes
                val ext = file.extension.ifBlank { "bin" }
                assetMap[backgroundId] = sha to "assets/${backgroundId}.$ext"
            }
        }
        for (assetId in SkinTreeEditor.referencedAssetIds(draft.tree)) {
            val (sha, path) = assetMap[assetId]
                ?: return Result.Failed("asset_unresolved:$assetId")
            val bytes = draftAssetBytes[sha]
                ?: layout.assetFiles(sha).firstOrNull()?.readBytes()
                ?: return Result.Failed("asset_bytes_missing:$assetId")
            files += ZipSafeExtractor.ExtractedFile(path, bytes)
            contents += PackageManifest.Content(
                PackageManifest.TYPE_ASSET, assetId, path, sha,
            )
        }

        val manifest = PackageManifest(formatVersion = 3, contents = contents)
        files += ZipSafeExtractor.ExtractedFile(
            "manifest.json", PackageManifest.toJson(manifest).encodeToByteArray(),
        )
        val (importer, ctx) = importerProvider()
        return when (val outcome = importer.importFiles(files, ctx, JimeImporter.ConflictMode.UPDATE)) {
            is JimeImporter.Outcome.Imported -> {
                if (skinId in outcome.report.skippedSkinIds) {
                    Result.NoChanges
                } else {
                    val refreshed = (store.readIndex() as? SkinLibraryStore.ReadResult.Ok)?.value
                    val generation = refreshed?.skins?.firstOrNull { it.id == skinId }
                        ?.generation ?: 0
                    Result.Committed(skinId, generation)
                }
            }
            is JimeImporter.Outcome.Rejected -> Result.Failed(outcome.reason)
            is JimeImporter.Outcome.ConflictPending -> Result.Failed("conflict_pending")
        }
    }
}
