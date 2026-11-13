package com.jobeen.ime.skin.image

import com.jobeen.ime.skin.model.BackgroundSnapshot
import java.io.File

/**
 * 运行期背景产物自愈（2026-10-28 修复轮：演示包背景图持续缺失根因）。
 *
 * 产物键内含目标尺寸 + 档位模糊，产物文件却只在导入期写一次：
 * 导入与应用时的档位（trim 一次性升档）、方向、键盘高度任一漂移，
 * 键即分裂——导入写了 A 键文件，运行期找 B 键文件，永久缺失且
 * 只回退纯色（真机演示包三次 background_bitmap_missing，
 * 导入报告 0 issue，证明导入期预处理本身成功、丢在键分裂上）。
 * 本类在运行期按快照自带键/尺寸重建产物：导入仍是快路径，
 * 本重建是漂移/同步缺产物/导入后删除等一切缺失情形的统一兜底。
 * 不含 Android API：位图实现由调用方注入的 [BackgroundPreparer] 承载。
 */
object BackgroundRebuilder {

    sealed interface Outcome {
        data class Rebuilt(val file: File) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun rebuild(
        genDir: File,
        background: BackgroundSnapshot,
        overlayColorArgb: Int,
        assetBytes: ByteArray,
        preparer: BackgroundPreparer,
    ): Outcome {
        // 事务发布（WO38 E1-10/3）：BackgroundPreparer.prepare 直写
        // 最终产物 prepared/<key>.webp（非原子；中途失败留半写文件，
        // 且下次命中“非空即 Done”的缓存检查会把半写文件当成功产物。
        // prepare 本体在红线文件内不能动，故本层改走 staging：先在
        // 同文件系统的唯一 staging 目录内制备，成功后 rename 进位、
        // 失败则最终路径零变化。staging 每次调用唯一（并发重建互不
        // 干扰），finally 清理；崩溃残留的 staging 目录是惰性垃圾，
        // 不被当作产物读取。代价：staging 全新，preparer 的“已存在
        // 即复用”早退不再命中，每次重建都重算一次位图——重建本就
        // 是缺失产物时的冷路径冷修复，可接受。
        val stagingGenDir = File(genDir, ".prepared-staging-${System.nanoTime()}")
        try {
            val result = preparer.prepare(
                BackgroundPreparer.Request(
                    assetBytes = assetBytes,
                    assetSha256 = background.assetSha256,
                    genDir = stagingGenDir,
                    targetWidthPx = background.targetWidthPx,
                    targetHeightPx = background.targetHeightPx,
                    blurRadiusDp = background.blurRadius,
                    imageAdjust = background.imageAdjust,
                    scaleMode = background.scaleMode,
                    overlayColorArgb = overlayColorArgb,
                    overlayAlpha = background.overlayAlpha,
                    preparedKey = background.preparedKey,
                ),
            )
            if (result is BackgroundPreparer.Result.Failed) {
                return Outcome.Failed(result.reason)
            }
            val stagedFile = File(
                File(stagingGenDir, "prepared"),
                "${background.preparedKey}.webp",
            )
            if (!stagedFile.isFile || stagedFile.length() == 0L) {
                // preparer 报 Done 却未落产物（不应发生）：不发布空位
                return Outcome.Failed("publish_missing")
            }
            val destDir = File(genDir, "prepared").apply { mkdirs() }
            val destFile = File(destDir, "${background.preparedKey}.webp")
            // 同文件系统 rename：POSIX 语义原子替换。个别实现在目标
            // 已存在时拒绝 rename：先删旧文件再 rename——缺失态调用
            // 方按“缺产物”重建，不会把缺失误认作成功，故退化仍安全。
            val published = stagedFile.renameTo(destFile) ||
                (destFile.delete() && stagedFile.renameTo(destFile))
            if (!published) return Outcome.Failed("publish_failed")
            return Outcome.Rebuilt(destFile)
        } finally {
            runCatching { stagingGenDir.deleteRecursively() }
        }
    }
}
