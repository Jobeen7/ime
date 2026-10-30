package com.jobeen.ime.skin.qr

import com.jobeen.ime.skin.model.PackageManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** App 侧二维码分享方案抉择（纯函数部分；位图编码需 Android 环境，不进单测）。 */
class SkinQrShareTest {

    private val skinContent = PackageManifest.Content(
        type = PackageManifest.TYPE_SKIN,
        id = "demo",
        path = "skin.json",
        sha256 = "0".repeat(64),
    )
    private val layoutContent = PackageManifest.Content(
        type = PackageManifest.TYPE_LAYOUT,
        id = "custom1",
        path = "layouts/custom1.json",
        sha256 = "0".repeat(64),
    )
    private val assetContent = PackageManifest.Content(
        type = PackageManifest.TYPE_ASSET,
        id = "bg",
        path = "assets/bg.png",
        sha256 = "0".repeat(64),
    )
    private val smallSkin = """{"meta":{"id":"demo"}}""".encodeToByteArray()

    @Test
    fun skinOnlySmallGoesInlineAndParsesBack() {
        val plan = SkinQrShare.planForExport(listOf(skinContent), smallSkin)
        assertTrue(plan is SkinQrShare.Plan.Inline)
        val payload = (plan as SkinQrShare.Plan.Inline).payload
        assertTrue(payload.startsWith(SkinQrPayload.INLINE_PREFIX))
        val parsed = SkinQrPayload.parse(payload)
        assertTrue(parsed is SkinQrPayload.ParseResult.Ok)
        val inline = (parsed as SkinQrPayload.ParseResult.Ok).parsed
        assertTrue(inline is SkinQrPayload.Parsed.Inline)
        assertEquals(smallSkin.decodeToString(), (inline as SkinQrPayload.Parsed.Inline).skinJson)
    }

    @Test
    fun layoutOrAssetForcesUrlPlan() {
        assertEquals(
            SkinQrShare.Plan.NeedsUrl,
            SkinQrShare.planForExport(listOf(skinContent, layoutContent), smallSkin),
        )
        assertEquals(
            SkinQrShare.Plan.NeedsUrl,
            SkinQrShare.planForExport(listOf(skinContent, assetContent), smallSkin),
        )
    }

    @Test
    fun oversizeSkinForcesUrlPlan() {
        val big = ByteArray(SkinQrPayload.MAX_INLINE_BYTES + 1) { ' '.code.toByte() }
        assertEquals(
            SkinQrShare.Plan.NeedsUrl,
            SkinQrShare.planForExport(listOf(skinContent), big),
        )
    }

    @Test
    fun urlPayloadRoundTripsThroughParse() {
        val sha = "a".repeat(64)
        val payload = SkinQrShare.buildUrlPayload("https://example.com/skins/demo.jime", sha)
        val parsed = SkinQrPayload.parse(payload)
        assertTrue(parsed is SkinQrPayload.ParseResult.Ok)
        val url = (parsed as SkinQrPayload.ParseResult.Ok).parsed as SkinQrPayload.Parsed.Url
        assertEquals("https://example.com/skins/demo.jime", url.url)
        assertEquals(sha, url.sha256)
        // 无 sha 也合法；http 则被导入侧拒绝，分享对话框须拦下。
        assertTrue(
            SkinQrPayload.parse(
                SkinQrShare.buildUrlPayload("https://example.com/skins/demo.jime", null),
            ) is SkinQrPayload.ParseResult.Ok,
        )
        assertTrue(
            SkinQrPayload.parse(
                SkinQrShare.buildUrlPayload("http://example.com/skins/demo.jime", null),
            ) is SkinQrPayload.ParseResult.Rejected,
        )
    }
}
