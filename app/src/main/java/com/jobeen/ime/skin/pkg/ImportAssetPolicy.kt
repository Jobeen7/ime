package com.jobeen.ime.skin.pkg

import com.jobeen.ime.skin.model.AssetKind
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.model.SkinIssue

/**
 * 导入加固模式（2026-10-29 item-1 D2）：资源白名单＋预算。
 *
 * - 白名单：扩展名不在图片／音频／视频三类白名单内（即
 *   assetKindOf → OTHER）的资产逐项警告并排除出包，不拦整包。
 * - 预算：非视频资产单件 ≤ 32MB、总数 ≤ 512；超限逐项警告并
 *   排除。视频资产不受本策略影响（仍走 128MB／数量／维度
 *   三重门硬拒绝，避免策略预排除掩盖硬门）。
 * - 总量不另设预算：库 200MB 总配额已在导入入口先行硬卡，
 *   不重复设限。
 * - 排除是事务性的：被排除资产不进清单、不落盘、不进索引；
 *   皮肤树对它的引用走既有缺失回退（背景→纯色、单键图→
 *   颜色键面、音效→静默），绝不因排除而整包失败。
 * - 纯函数：调用方负责把排除项从清单与文件表中滤掉。
 */
object ImportAssetPolicy {

    /** 非视频资产单件上限（视频走自有 128MB 三重门，不在此限）。 */
    const val MAX_SINGLE_ASSET_BYTES = 32L * 1024 * 1024

    /** 非视频资产总数上限（视频不计入，避免掩盖视频数量硬门）。 */
    const val MAX_ASSETS = 512

    data class Decision(
        /** 保留资产（原清单顺序）。 */
        val kept: List<PackageManifest.Content>,
        /** 排除资产（原清单顺序）。 */
        val excluded: List<PackageManifest.Content>,
        /** 逐项警告（与 excluded 一一对应，进导入报告）。 */
        val warnings: List<SkinIssue>,
    )

    fun evaluate(
        assets: List<PackageManifest.Content>,
        sizeOf: (PackageManifest.Content) -> Long?,
    ): Decision {
        val kept = mutableListOf<PackageManifest.Content>()
        val excluded = mutableListOf<PackageManifest.Content>()
        val warnings = mutableListOf<SkinIssue>()
        var keptNonVideoCount = 0
        for (content in assets) {
            val kind = PackageManifest.assetKindOf(content.path)
            val reason = when {
                kind == AssetKind.OTHER -> "asset_extension_not_allowed"
                kind != AssetKind.VIDEO &&
                    (sizeOf(content) ?: 0L) > MAX_SINGLE_ASSET_BYTES -> "asset_file_too_large"
                kind != AssetKind.VIDEO && keptNonVideoCount >= MAX_ASSETS ->
                    "asset_count_exceeded"
                else -> null
            }
            if (reason != null) {
                excluded += content
                warnings += SkinIssue("asset:${content.id}", SkinIssue.Kind.INFO, reason)
            } else {
                kept += content
                if (kind != AssetKind.VIDEO) keptNonVideoCount++
            }
        }
        return Decision(kept, excluded, warnings)
    }
}
