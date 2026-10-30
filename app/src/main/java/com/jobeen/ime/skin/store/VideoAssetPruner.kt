// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.store

import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.PackageManifest
import java.io.File

/**
 * 无主视频资产清理（2026-10-29 外观轮，补参考实现缺失的
 * “旧文件清理”——Jime 语境下的等价实现）。
 *
 * 视频文件住在库共享内容寻址资产目录（assets/<sha>.<ext>），
 * 删皮肤只删皮肤代次目录、不动共享资产，故最后引用者被删后
 * 视频文件即成无主孤儿。本清理只在删皮肤后跑：凡视频文件，
 * 其 sha 不在任何现存皮肤代次清单中即删。
 *
 * 边界：回滚保留的旧代次清单可读，故其视频不受影响；
 * 清单读失败的代次跳过并计数（该代次已不可应用，删其视频
 * 不影响可应用皮肤）；图片／音效孤儿不在本项范围，不动。
 * 只记元数据（删除数＋跳过代次数），不记路径。
 */
object VideoAssetPruner {
    fun pruneUnreferencedVideos(layout: SkinLibraryLayout): Int {
        val referenced = mutableSetOf<String>()
        var skippedGens = 0
        val genDirs = layout.skinsDir.listFiles()
            ?.flatMap { skinDir ->
                skinDir.listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("gen-") }
                    ?: emptyList()
            } ?: emptyList()
        for (genDir in genDirs) {
            val manifest = runCatching {
                PackageManifest.parse(File(genDir, "manifest.json").readText())
            }.getOrNull()
            if (manifest == null) {
                skippedGens++
                continue
            }
            for (asset in manifest.assets()) {
                if (PackageManifest.assetKindOf(asset.path) == com.jobeen.ime.skin.model.AssetKind.VIDEO) {
                    referenced += asset.sha256
                }
            }
        }
        var pruned = 0
        val files = layout.assetsDir.listFiles() ?: emptyArray()
        for (file in files) {
            if (!file.isFile) continue
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext !in PackageManifest.VIDEO_EXTENSIONS) continue
            val sha = file.name.substringBeforeLast('.')
            if (sha !in referenced) {
                if (runCatching { file.delete() }.getOrDefault(false)) pruned++
            }
        }
        if (pruned > 0 || skippedGens > 0) {
            DiagLog.w(
                "admin", "video_assets_pruned",
                mapOf("pruned" to pruned, "skippedGens" to skippedGens),
            )
        }
        return pruned
    }
}
