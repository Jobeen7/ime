package com.jobeen.ime.skin.pkg

import com.jobeen.ime.skin.model.PackageManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导入加固（2026-10-29 item-1 D2）：白名单＋预算纯函数。 */
class ImportAssetPolicyTest {

    private fun asset(id: String, path: String) =
        PackageManifest.Content(PackageManifest.TYPE_ASSET, id, path, "sha-$id")

    @Test
    fun nonWhitelistedExtensionExcluded() {
        val decision = ImportAssetPolicy.evaluate(
            listOf(
                asset("a1", "assets/ok.png"),
                asset("a2", "assets/evil.exe"),
                asset("a3", "assets/noext"),
                asset("a4", "assets/sound.ogg"),
            ),
        ) { 100L }
        assertEquals(listOf("a1", "a4"), decision.kept.map { it.id })
        assertEquals(listOf("a2", "a3"), decision.excluded.map { it.id })
        assertEquals(
            listOf("asset_extension_not_allowed", "asset_extension_not_allowed"),
            decision.warnings.map { it.detail },
        )
    }

    @Test
    fun oversizedNonVideoExcludedButVideoUntouched() {
        val big = ImportAssetPolicy.MAX_SINGLE_ASSET_BYTES + 1
        val decision = ImportAssetPolicy.evaluate(
            listOf(
                asset("img", "assets/big.png"),
                asset("vid", "assets/big.mp4"),
            ),
        ) { if (it.id == "img") big else big }
        // 图片超 32MB 被排除；视频不受本策略单件预算影响（走三重门）
        assertEquals(listOf("vid"), decision.kept.map { it.id })
        assertEquals(listOf("img"), decision.excluded.map { it.id })
        assertEquals("asset_file_too_large", decision.warnings.single().detail)
    }

    @Test
    fun countBudgetExcludesOverflowInOrder() {
        val assets = (1..(ImportAssetPolicy.MAX_ASSETS + 2)).map {
            asset("a$it", "assets/a$it.png")
        }
        val decision = ImportAssetPolicy.evaluate(assets) { 10L }
        assertEquals(ImportAssetPolicy.MAX_ASSETS, decision.kept.size)
        assertEquals(listOf("a513", "a514"), decision.excluded.map { it.id })
        assertTrue(decision.warnings.all { it.detail == "asset_count_exceeded" })
    }

    @Test
    fun videosDoNotConsumeCountBudget() {
        val assets = (1..ImportAssetPolicy.MAX_ASSETS).map {
            asset("a$it", "assets/a$it.png")
        } + asset("v1", "assets/v1.mp4")
        val decision = ImportAssetPolicy.evaluate(assets) { 10L }
        assertTrue(decision.excluded.isEmpty())
        assertEquals(ImportAssetPolicy.MAX_ASSETS + 1, decision.kept.size)
    }
}
