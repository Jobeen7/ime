package com.jobeen.ime.skin.draft

import com.jobeen.ime.base.util.DigestUtil
import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.layout.LayoutValidator
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.store.ImportTransaction
import java.io.File

/**
 * 布局草稿提交（B3 详设 §4.5 两段式：草稿 → 显式提交成新代次）：
 * 结构校验（与导入/运行时同一校验器，整布局无效拦在提交口）→
 * 经导入事务机制层以「仅布局」暂存包入库。提交只入库不切换
 * 生效皮肤；内容未变由事务层按同 id 同 hash 跳过（[Result.NoChanges]）。
 *
 * 注：布局单独提交不走 JimeImporter——导入管线按「一包含恰好
 * 一个皮肤」设计（B1 §3.1），布局-only 包不在其契约内；同步的
 * 独立布局单元（SkinLibrarySync.downloadLayout）是同一先例。
 */
class LayoutDraftCommitter(
    private val importTransaction: ImportTransaction,
) {

    sealed interface Result {
        data class Committed(val layoutId: String, val generation: Int) : Result
        data object NoChanges : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun commit(layoutId: String, layoutJsonBytes: ByteArray): Result {
        val tree = SkinJson.parseObject(layoutJsonBytes.decodeToString())
            ?: return Result.Failed("unparseable")
        val validation = LayoutValidator.validate(tree, layoutId)
        if (validation is LayoutValidation.Invalid) {
            com.jobeen.ime.skin.diag.DiagLog.w(
                "layout", "layout_validation_rejected",
                mapOf(
                    "layoutId" to layoutId, "source" to "layout_commit",
                    "issueCount" to validation.issues.size,
                    "firstPath" to validation.issues.firstOrNull()?.path,
                    "firstReason" to validation.issues.firstOrNull()?.reason,
                ),
            )
            return Result.Failed("layout_invalid")
        }
        val hash = DigestUtil.sha256Hex(layoutJsonBytes)
        val manifestJson = PackageManifest.toJson(
            PackageManifest(
                formatVersion = 3,
                contents = listOf(
                    PackageManifest.Content(
                        PackageManifest.TYPE_LAYOUT, layoutId, "layout.json", hash,
                    ),
                ),
            ),
        )
        return when (
            val outcome = importTransaction.importPackage { staging ->
                val layoutDir = File(staging, "layouts/$layoutId").apply { mkdirs() }
                File(layoutDir, "layout.json").writeBytes(layoutJsonBytes)
                File(layoutDir, "manifest.json").writeText(manifestJson)
                ImportTransaction.BuiltPackage(
                    layouts = listOf(
                        ImportTransaction.StagedLayout(layoutId, layoutDir, hash),
                    ),
                )
            }
        ) {
            is ImportTransaction.Result.Imported -> {
                val entry = outcome.layouts.firstOrNull { it.id == layoutId }
                when {
                    entry != null -> Result.Committed(layoutId, entry.generation)
                    layoutId in outcome.skippedLayoutIds -> Result.NoChanges
                    else -> Result.Failed("import_no_entry")
                }
            }
            is ImportTransaction.Result.Failed ->
                Result.Failed("${outcome.stage}:${outcome.reason}")
        }
    }
}
