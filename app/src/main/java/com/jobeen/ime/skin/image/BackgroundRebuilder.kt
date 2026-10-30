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
        val result = preparer.prepare(
            BackgroundPreparer.Request(
                assetBytes = assetBytes,
                assetSha256 = background.assetSha256,
                genDir = genDir,
                targetWidthPx = background.targetWidthPx,
                targetHeightPx = background.targetHeightPx,
                blurRadiusDp = background.blurRadius,
                imageAdjust = background.imageAdjust,
                overlayColorArgb = overlayColorArgb,
                overlayAlpha = background.overlayAlpha,
                preparedKey = background.preparedKey,
            ),
        )
        return when (result) {
            is BackgroundPreparer.Result.Done ->
                Outcome.Rebuilt(File(File(genDir, "prepared"), "${background.preparedKey}.webp"))
            is BackgroundPreparer.Result.Failed -> Outcome.Failed(result.reason)
        }
    }
}
