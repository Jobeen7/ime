package com.jobeen.ime.skin.runtime

import com.jobeen.ime.data.keyboard.theme.KeyboardColors
import com.jobeen.ime.input.keyboard.key.KeyIds
import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.GeometryFallbacks
import com.jobeen.ime.skin.model.MemoryTier
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinJson
import com.jobeen.ime.skin.model.SkinSnapshot
import com.jobeen.ime.skin.model.SkinSnapshotBuilder
import com.jobeen.ime.skin.model.SkinValidator
import com.jobeen.ime.skin.store.SkinGenRef
import com.jobeen.ime.skin.store.SkinLibraryLayout
import java.io.File

/**
 * 布局编辑器预览皮肤上下文（G1 第 5 项）：
 *
 * 独立布局编辑器此前只用 [com.jobeen.ime.skin.layout.LayoutKeyDefBuilder]
 * 建预览结构，不走任何皮肤——布局键自带的 style/styleOverrides
 * 无 keyStyles 表可解析，自定义键样式在预览里恒不可见。
 *
 * 本接法不改布局格式、不改编辑器数据流：编辑器选一个库内皮肤后，
 * 在 IO 线程用该皮肤的 skin.json + manifest.json 建一份预览快照
 * （与运行快照同一校验器 + 同一构建器，只是目标尺寸取占位、
 * 音效事实为空），再把预览结构过 [KeyOverrideApplier.apply]——
 * 样式表、逐键覆写（含本布局坐标 ID 的覆写）、分档字号、图片槽
 * 全部走运行时同一链路，预览所见即键盘所得（图片位图需 provider
 * 注入，编辑器预览不注入时图片键按颜色键面回退，属已知局限，
 * 见报告）。
 *
 * 坐标 ID 放宽：校验器的可覆写判定在内置登记表之外，另接受
 * 本布局全部逻辑 ID（`{layoutId}:K-<stableId>`）；内置锚点判定不变。
 */
object LayoutPreviewSkin {

    /** 预览皮肤下拉选项（库索引条目直出）。 */
    data class Entry(
        val id: String,
        val name: String,
        val dir: String,
        val hash: String,
        val generation: Int,
    )

    /**
     * 建预览快照；任一步失败返回 null（调用方回退无皮肤预览并记日志）。
     * @param layoutKeyIds 本布局全部逻辑 ID（用于放宽可覆写判定）。
     */
    fun buildSnapshot(
        layout: SkinLibraryLayout,
        entry: Entry,
        layoutKeyIds: Set<String>,
        defaultScheme: KeyboardColors.ColorScheme,
        currentVersionCode: Int,
        fallbacks: GeometryFallbacks,
    ): SkinSnapshot? {
        val genDir = layout.fileFromRelative(entry.dir)
        val tree = runCatching {
            SkinJson.parseObject(File(genDir, "skin.json").readText())
        }.getOrNull()
        if (tree == null) {
            DiagLog.w(
                "layout", "preview_skin_unparseable",
                mapOf("layoutId" to entry.id),
            )
            return null
        }
        val manifest = runCatching {
            PackageManifest.parse(File(genDir, "manifest.json").readText())
        }.getOrNull() ?: PackageManifest()
        val assetKindById = manifest.assets().associate { content ->
            content.id to PackageManifest.assetKindOf(content.path)
        }
        val assetShaById = manifest.assets().associate { it.id to it.sha256 }
        val result = SkinValidator.validate(
            tree = tree,
            currentVersionCode = currentVersionCode,
            fallbacks = fallbacks,
            assetKind = { assetKindById[it] },
            isOverridable = { it in layoutKeyIds || KeyIds.isOverridable(it) },
        )
        val validated = when (result) {
            is SkinValidator.Result.Rejected -> {
                DiagLog.w(
                    "layout", "preview_skin_rejected",
                    mapOf("layoutId" to entry.id, "reason" to result.reason),
                )
                return null
            }
            is SkinValidator.Result.Valid -> result.skin
        }
        return SkinSnapshotBuilder.build(
            validated = validated,
            ref = SkinGenRef(entry.id, entry.hash, entry.generation),
            defaultScheme = defaultScheme,
            tier = MemoryTier.NORMAL,
            assetSha256 = { assetShaById[it] },
            // 预览不消费背景位图：目标尺寸仅进 preparedKey 字符串，占位即可。
            targetWidthPx = 1,
            targetHeightPx = 1,
            audioAssetOrder = manifest.assets().map { it.id },
        )
    }
}
