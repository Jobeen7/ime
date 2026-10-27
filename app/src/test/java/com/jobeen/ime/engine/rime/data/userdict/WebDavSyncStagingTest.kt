// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.engine.rime.data.userdict

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 方案下载 staging 换位与新旧比较的 JVM 可测部分。
 *
 * 覆盖口径（均从 spec 推导，不依赖网络与引擎）：
 * - 新旧比较：远端旧于/等于本地时不下载，远端新于本地、远端无时间、
 *   本地缺失时下载。
 * - 暂存底本：与上传侧同一过滤与深度（单目录内至多 6 段），受保护
 *   条目与超深文件不进暂存，换位不会触碰它们。
 * - 换位：已下载文件落盘并钉为远端时间；未下载的底本文件换位后
 *   内容与时间戳不变；换位不删除活目录里的任何文件——远端已删
 *   （下载 404）而本地仍保留的文件，换位后仍在。
 * 网络部分（PROPFIND 递归、GET 三态）不在此测，需真机复核；
 * 深度对齐的另一半（下载递归上限）由 downloadSchemes 的 depth 守卫
 * 与 collectUploadFiles 的 6 段上限在代码层面成对保证。
 */
class WebDavSyncStagingTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun createTempDir(): File {
        val dir = Files.createTempDirectory("scheme-staging-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    private fun touch(parent: File, rel: String, text: String, mtime: Long? = null) {
        val f = File(parent, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
        if (mtime != null) f.setLastModified(mtime)
    }

    // ---- 新旧比较 ----

    @Test
    fun shouldDownload_remoteNewerThanLocal_returnsTrue() {
        val dir = createTempDir()
        val local = File(dir, "a.yaml").apply {
            writeText("old")
            setLastModified(1000L)
        }
        assertTrue(WebDavSync.shouldDownloadSchemeFile(2000L, local))
    }

    @Test
    fun shouldDownload_remoteOlderThanLocal_returnsFalse() {
        val dir = createTempDir()
        val local = File(dir, "a.yaml").apply {
            writeText("new-local")
            setLastModified(2000L)
        }
        // 远端旧文件不得覆盖本地新改。
        assertFalse(WebDavSync.shouldDownloadSchemeFile(1000L, local))
    }

    @Test
    fun shouldDownload_remoteEqualToLocal_returnsFalse() {
        val dir = createTempDir()
        val local = File(dir, "a.yaml").apply {
            writeText("same")
            setLastModified(2000L)
        }
        assertFalse(WebDavSync.shouldDownloadSchemeFile(2000L, local))
    }

    @Test
    fun shouldDownload_remoteTimeUnknown_returnsTrue() {
        val dir = createTempDir()
        val local = File(dir, "a.yaml").apply {
            writeText("local")
            setLastModified(2000L)
        }
        // 无远端时间时无法证明陈旧，沿用旧行为下载，不静默丢更新。
        assertTrue(WebDavSync.shouldDownloadSchemeFile(null, local))
    }

    @Test
    fun shouldDownload_localMissing_returnsTrue() {
        val dir = createTempDir()
        val local = File(dir, "a.yaml")
        assertFalse(local.exists())
        assertTrue(WebDavSync.shouldDownloadSchemeFile(1000L, local))
    }

    @Test
    fun shouldDownload_localPathIsDirectory_returnsTrue() {
        val dir = createTempDir()
        val local = File(dir, "a.yaml").apply { mkdirs() }
        assertTrue(local.isDirectory)
        // 目录占位是损坏态：不跳过，让后续环节按旧行为报失败。
        assertTrue(WebDavSync.shouldDownloadSchemeFile(1000L, local))
    }

    // ---- 暂存底本 ----

    @Test
    fun buildStaging_copiesBaseWithUploadFilterAndMtime() {
        val root = createTempDir()
        touch(root, "d1/a.yaml", "a", 1111L)
        touch(root, "d1/sub/b.yaml", "b", 2222L)
        touch(root, "d1/build/c.yaml", "build")
        touch(root, "d1/installation.yaml", "install")
        touch(root, "d1/x.userdict.txt", "dict")
        touch(root, "d2/a/b/c/d/e/f.yaml", "deep6")
        touch(root, "d2/a/b/c/d/e/f/g.yaml", "deep7")
        touch(root, "loose.yaml", "loose")
        touch(root, ".hidden/h.yaml", "hidden")
        val staging = createTempDir()
        val rels = WebDavSync.buildSchemeStagingTree(root, staging)
        assertEquals(
            setOf("d1/a.yaml", "d1/sub/b.yaml", "d2/a/b/c/d/e/f.yaml"),
            rels.toSet(),
        )
        assertEquals("a", File(staging, "d1/a.yaml").readText())
        assertEquals(1111L, File(staging, "d1/a.yaml").lastModified())
        assertEquals("b", File(staging, "d1/sub/b.yaml").readText())
        assertEquals(2222L, File(staging, "d1/sub/b.yaml").lastModified())
        assertFalse(File(staging, "d1/build/c.yaml").exists())
        assertFalse(File(staging, "d1/installation.yaml").exists())
        assertFalse(File(staging, "d2/a/b/c/d/e/f/g.yaml").exists())
        assertFalse(File(staging, "loose.yaml").exists())
    }

    // ---- 换位 ----

    @Test
    fun commit_downloadedPinsRemoteMtimeAndBaseKeepsMtime() {
        val root = createTempDir()
        touch(root, "d1/new.yaml", "old-content", 1000L)
        touch(root, "d1/base.yaml", "base-content", 3000L)
        val staging = createTempDir()
        // 模拟叠加：已下载文件的新内容 + 底本文件（暂存时间被复制动作带偏）。
        touch(staging, "d1/new.yaml", "new-content", 9999L)
        touch(staging, "d1/base.yaml", "base-content", 8888L)
        val committed = WebDavSync.commitSchemeStagingTree(
            staging,
            root,
            setOf("d1/new.yaml"),
            mapOf("d1/new.yaml" to 5000L),
        )
        assertEquals(setOf("d1/base.yaml", "d1/new.yaml"), committed.toSet())
        assertEquals("new-content", File(root, "d1/new.yaml").readText())
        assertEquals(5000L, File(root, "d1/new.yaml").lastModified())
        assertEquals("base-content", File(root, "d1/base.yaml").readText())
        assertEquals(3000L, File(root, "d1/base.yaml").lastModified())
        assertFalse(File(staging, "d1/new.yaml").exists())
        assertFalse(File(staging, "d1/base.yaml").exists())
    }

    @Test
    fun commit_neverDeletesLiveFiles() {
        val root = createTempDir()
        // 本地独有文件（远端已删对应物、下载 404 时不在下载集内）
        // 与受保护条目都不在暂存树内，换位后必须原样保留。
        touch(root, "d1/local-only.yaml", "local-only", 4000L)
        touch(root, "d1/installation.yaml", "install", 4001L)
        touch(root, "d1/old.yaml", "old", 1000L)
        val staging = createTempDir()
        touch(staging, "d1/old.yaml", "new", 9999L)
        WebDavSync.commitSchemeStagingTree(
            staging,
            root,
            setOf("d1/old.yaml"),
            mapOf("d1/old.yaml" to 5000L),
        )
        assertEquals("new", File(root, "d1/old.yaml").readText())
        assertEquals("local-only", File(root, "d1/local-only.yaml").readText())
        assertEquals(4000L, File(root, "d1/local-only.yaml").lastModified())
        assertEquals("install", File(root, "d1/installation.yaml").readText())
        assertEquals(4001L, File(root, "d1/installation.yaml").lastModified())
    }

    @Test
    fun stagingFlow_buildOverlayCommit_keepsLocalOnlyAnd404File() {
        val root = createTempDir()
        touch(root, "d1/a.yaml", "old-a", 1000L)
        // local-only.yaml 不在远端：下载时对它无 GET（404 语义下也不删），
        // 它只以底本身份进暂存、换位后仍在。
        touch(root, "d1/local-only.yaml", "mine", 2000L)
        val staging = File(createTempDir(), "staging")
        WebDavSync.buildSchemeStagingTree(root, staging)
        // 模拟一次下载成功叠加到暂存（生产里由 httpGetToFile 写暂存目标）。
        touch(staging, "d1/a.yaml", "remote-a", 9999L)
        WebDavSync.commitSchemeStagingTree(
            staging,
            root,
            setOf("d1/a.yaml"),
            mapOf("d1/a.yaml" to 5000L),
        )
        assertEquals("remote-a", File(root, "d1/a.yaml").readText())
        assertEquals(5000L, File(root, "d1/a.yaml").lastModified())
        assertEquals("mine", File(root, "d1/local-only.yaml").readText())
        assertEquals(2000L, File(root, "d1/local-only.yaml").lastModified())
    }
}
