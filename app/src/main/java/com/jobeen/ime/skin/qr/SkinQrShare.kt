package com.jobeen.ime.skin.qr

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.jobeen.ime.skin.model.PackageManifest
import com.journeyapps.barcodescanner.BarcodeEncoder

/**
 * App 侧皮肤二维码分享（2026-10-28 新增；载荷契约见 [SkinQrPayload]，
 * 导入侧不改——生成的码必须能被既有导入原样接受）。
 *
 * 方案抉择（与编辑器同口径）：
 * - 内联型：清单仅含 skin 且 skin.json ≤ 16KB → `JIME-SKIN:1:` +
 *   base64url；注意 16KB 只是解析上限，码本身实际容量约 3KB，
 *   超大内联码扫码成功率下降，UI 应提示靠近扫码。
 * - URL 型：清单含 layout/asset，或内联超限 → 用户填已托管的
 *   .jime 直链，载荷 `JIME-SKIN-URL:1:<https-url>[#sha256=...]`；
 *   调用方须以 [SkinQrPayload.parse] 复核 Ok 才出码。
 * - 两者皆无（导出/编码失败）→ 明示只能用 .jime 文件分享。
 *
 * 隐私：本文件只处理皮肤包字节与载荷串，不记日志；调用方
 * （库页对话框）只记方案种类与字节数等元数据，绝不记 URL、
 * skin.json 内容与载荷原文。
 */
object SkinQrShare {

    /** 分享方案：内联码 / 须走 URL 型（有资产/布局，或内联超限）。 */
    sealed interface Plan {
        data class Inline(val payload: String) : Plan
        data object NeedsUrl : Plan
    }

    /**
     * 由导出包定分享方案（纯函数，可单测）：
     * 清单含 layout/asset 任一 → NeedsUrl；skin.json 字节 >
     * MAX_INLINE_BYTES → NeedsUrl；否则 Inline(encodeInline)。
     * 调用方须保证 skinJson 为合法 UTF-8（导出管线原样写出）。
     */
    fun planForExport(contents: List<PackageManifest.Content>, skinJson: ByteArray): Plan {
        if (contents.any { it.type != PackageManifest.TYPE_SKIN }) return Plan.NeedsUrl
        if (skinJson.size > SkinQrPayload.MAX_INLINE_BYTES) return Plan.NeedsUrl
        return Plan.Inline(SkinQrPayload.encodeInline(skinJson.decodeToString()))
    }

    /** URL 型载荷拼装（纯函数；调用方须以 parse 复核 Ok 才出码）。 */
    fun buildUrlPayload(url: String, sha256: String?): String =
        SkinQrPayload.URL_PREFIX + url + (sha256?.let { "#sha256=$it" } ?: "")

    /**
     * 二维码位图（纠错 M + UTF-8；内容超容量抛 WriterException，
     * 调用方转 .jime 提示）。位图生成需 Android 环境，不进单测。
     */
    fun encodeBitmap(payload: String, sizePx: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        return BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    }
}
