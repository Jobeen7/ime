package com.jobeen.ime.skin.store

import com.jobeen.ime.skin.diag.DiagLog
import com.jobeen.ime.skin.model.PackageManifest
import com.jobeen.ime.skin.pkg.PortableSkinArchive
import java.io.File

/**
 * 删皮肤后无主资产清理（第 5 批 §1，Jime 形映射）：
 *
 * 对方口径是“收集模型引用的全部素材，仅 canonical 落在自有
 * 素材根内才删”；Jime 资产是库共享内容寻址文件（assets/），
 * 删皮肤只删皮肤代次目录、不动共享资产——故清理条件必须再加
 * 一条：该 sha 不再被任何现存代次／草稿清单引用（否则删了
 * 共享文件即毁其他皮肤）。引用图以清单 contents 为准
 * （AssetRefcounts 同口径），另加草稿目录递归扫描（草稿不
 * 在索引内，不扫会误删草稿引用的资产）。
 *
 * 安全方向：canonical 不在素材根内不删；清单读失败的代次
 * 跳过（不以其为由删资产）；图片缓存不全局清——键图缓存
 * 是按快照的（SkinKeyImageStore.bytesProvider 随快照安装／
 * 卸载切换），删非生效皮肤时全局清缓存会断掉生效皮肤的
 * 字节源（clear 置空 bytesProvider），删生效皮肤时回退流程
 * 已先经 install(null) 清过缓存，故此处不重复清。
 */
object SkinAssetPruner {

    /**
     * 清理不再被引用的资产文件（调用方指定排除的扩展名，如
     * 视频已由 VideoAssetPruner 专责时排除视频扩展名）。
     * @return 删除的文件数。
     */
    fun pruneUnreferenced(
        layout: SkinLibraryLayout,
        excludedExtensions: Set<String> = PackageManifest.VIDEO_EXTENSIONS,
    ): Int {
        val referenced = mutableSetOf<String>()
        val roots = listOf(layout.skinsDir, layout.layoutsDir, layout.draftsDir)
        for (root in roots) {
            root.walkTopDown()
                .filter { it.isFile && it.name == "manifest.json" }
                .forEach { manifestFile ->
                    val manifest = runCatching {
                        PackageManifest.parse(manifestFile.readText())
                    }.getOrNull() ?: return@forEach
                    for (asset in manifest.assets()) referenced += asset.sha256
                }
        }
        var pruned = 0
        val files = layout.assetsDir.listFiles() ?: emptyArray()
        for (file in files) {
            if (!file.isFile) continue
            val ext = file.name.substringAfterLast('.', "").lowercase()
            if (ext in excludedExtensions) continue
            val sha = file.name.substringBeforeLast('.')
            if (sha in referenced) continue
            if (!PortableSkinArchive.isOwnedAssetFile(layout.assetsDir, file)) continue
            if (runCatching { file.delete() }.getOrDefault(false)) pruned++
        }
        if (pruned > 0) {
            DiagLog.w(
                "admin", "assets_pruned",
                mapOf("pruned" to pruned),
            )
        }
        return pruned
    }
}
