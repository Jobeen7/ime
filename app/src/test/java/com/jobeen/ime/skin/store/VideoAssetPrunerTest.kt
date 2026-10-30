// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.store

import com.jobeen.ime.skin.model.PackageManifest
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 无主视频资产清理（2026-10-29 外观轮）：只删无引用视频，保留引用／图片／回滚代次。 */
class VideoAssetPrunerTest {

    private fun writeGenManifest(genDir: File, videoShas: List<Pair<String, String>>) {
        genDir.mkdirs()
        val contents = videoShas.map { (id, sha) ->
            PackageManifest.Content(PackageManifest.TYPE_ASSET, id, "assets/$id.mp4", sha)
        }
        File(genDir, "manifest.json").writeText(
            PackageManifest.toJson(PackageManifest(3, contents)),
        )
    }

    @Test
    fun prunesOnlyUnreferencedVideos() {
        val root = Files.createTempDirectory("jime-video-prune").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        // 皮肤 A：现行代次引用 vid-kept；回滚代次引用 vid-rollback。
        writeGenManifest(
            File(layout.skinsDir, "skin-a/gen-2"),
            listOf("vid-a" to "aaa111"),
        )
        writeGenManifest(
            File(layout.skinsDir, "skin-a/gen-1"),
            listOf("vid-old" to "bbb222"),
        )
        // 皮肤 B 已删：其视频 vid-orphan 无任何清单引用。
        File(layout.assetsDir, "aaa111.mp4").writeBytes(byteArrayOf(1))
        File(layout.assetsDir, "bbb222.mp4").writeBytes(byteArrayOf(2))
        File(layout.assetsDir, "ccc333.mp4").writeBytes(byteArrayOf(3))
        // 图片孤儿不在本项范围，不动。
        File(layout.assetsDir, "ddd444.png").writeBytes(byteArrayOf(4))

        val pruned = VideoAssetPruner.pruneUnreferencedVideos(layout)

        assertEquals(1, pruned)
        assertTrue(File(layout.assetsDir, "aaa111.mp4").isFile)
        assertTrue(File(layout.assetsDir, "bbb222.mp4").isFile)
        assertFalse(File(layout.assetsDir, "ccc333.mp4").exists())
        assertTrue(File(layout.assetsDir, "ddd444.png").isFile)
    }

    @Test
    fun unparseableGenManifestIsSkippedNotFatal() {
        val root = Files.createTempDirectory("jime-video-prune-bad").toFile()
        val layout = SkinLibraryLayout(root)
        layout.ensureDirs()
        val genDir = File(layout.skinsDir, "skin-a/gen-1").apply { mkdirs() }
        File(genDir, "manifest.json").writeText("not json{")
        File(layout.assetsDir, "eee555.mp4").writeBytes(byteArrayOf(5))

        val pruned = VideoAssetPruner.pruneUnreferencedVideos(layout)

        assertEquals(1, pruned)
        assertFalse(File(layout.assetsDir, "eee555.mp4").exists())
    }
}
