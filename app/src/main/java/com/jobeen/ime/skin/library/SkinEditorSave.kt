// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.library

import com.jobeen.ime.skin.layout.LayoutValidation
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinValidator
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
}
