// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.DictDownloadAction
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.HttpGetResult
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.SymlinkCheck
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * WebDAV 目录同步新增防护的 JVM 可测部分。
 * 需真实 WebDAV / RimeSession 的整次 syncSchemeDir（骤减暂停端到端、
 * 删后刷新端到端、单词典失败后 setKnownFiles 端到端）不在此测，
 * 列为真机待验收；此处覆盖决定这些行为的纯函数与接缝。
 */
class WebDavSyncTest {

    // ---- A3 入源校验与 canonical containment ----

    @Test
    fun unsafeRelPaths_rejected() {
        assertFalse(WebDavSync.isSafeSyncRelPath(""))
        assertFalse(WebDavSync.isSafeSyncRelPath("/abs.yaml"))
        assertFalse(WebDavSync.isSafeSyncRelPath("../evil.yaml"))
        assertFalse(WebDavSync.isSafeSyncRelPath("a/../../evil.yaml"))
        assertFalse(WebDavSync.isSafeSyncRelPath("a//b.yaml"))
        assertFalse(WebDavSync.isSafeSyncRelPath("a/b.yaml/"))
        assertFalse(WebDavSync.isSafeSyncRelPath("a\\b.yaml"))
        assertFalse(WebDavSync.isSafeSyncRelPath(".."))
        assertTrue(WebDavSync.isSafeSyncRelPath("a/b.yaml"))
        assertTrue(WebDavSync.isSafeSyncRelPath("user.yaml"))
    }

    @Test
    fun resolveSyncTarget_traversal_returnsNull() {
        val userDir = Files.createTempDirectory("webdav-user").toFile()
        try {
            assertNull(WebDavSync.resolveSyncTarget(userDir, "../evil.yaml"))
            assertNull(WebDavSync.resolveSyncTarget(userDir, "sub/../../evil.yaml"))
            val ok = WebDavSync.resolveSyncTarget(userDir, "sub/ok.yaml")
            assertNotNull(ok)
            assertEquals(File(userDir, "sub/ok.yaml"), ok)
        } finally {
            userDir.deleteRecursively()
        }
    }

    @Test
    fun resolveSyncTarget_symlinkParentOutside_returnsNull() {
        val userDir = Files.createTempDirectory("webdav-user").toFile()
        val outside = Files.createTempDirectory("webdav-outside").toFile()
        try {
            Files.createSymbolicLink(
                File(userDir, "link").toPath(),
                outside.toPath(),
            )
            assertNull(WebDavSync.resolveSyncTarget(userDir, "link/evil.yaml"))
            assertNotNull(WebDavSync.resolveSyncTarget(userDir, "plain.yaml"))
        } finally {
            userDir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---- A4 骤减暂停 / force / 删后刷新 / 404 ----

    @Test
    fun pauseDecision_threshold_isHalf() {
        val known = (1..10).map { "f$it.yaml" }.toSet()
        assertTrue(WebDavSync.shouldPauseDeletePropagation(known, 4))
        assertFalse(WebDavSync.shouldPauseDeletePropagation(known, 5))
        assertFalse(WebDavSync.shouldPauseDeletePropagation(known, 6))
        assertFalse(WebDavSync.shouldPauseDeletePropagation(emptySet(), 0))
    }

    @Test
    fun pauseDecision_force_bypassesPause_andMergeKeepsKnown() {
        val known = (1..10).map { "f$it.yaml" }.toSet()
        assertTrue(WebDavSync.isDeletePropagationPaused(known, 4, false))
        assertFalse(WebDavSync.isDeletePropagationPaused(known, 4, true))
        val merged = WebDavSync.mergeKnownOnPause(known, setOf("f1.yaml", "new.yaml"))
        assertEquals(known + "new.yaml", merged)
    }

    @Test
    fun downloadResult_notFound_countsNeitherDownloadNorFailed() {
        val failed = mutableListOf<String>()
        assertEquals(0, WebDavSync.downloadCountDelta(HttpGetResult.NOT_FOUND, "a.yaml", failed))
        assertTrue(failed.isEmpty())
        assertEquals(1, WebDavSync.downloadCountDelta(HttpGetResult.OK, "a.yaml", failed))
        assertTrue(failed.isEmpty())
        assertEquals(0, WebDavSync.downloadCountDelta(HttpGetResult.FAILED, "b.yaml", failed))
        assertEquals(listOf("b.yaml"), failed)
    }

    @Test
    fun deleteRefresh_removedRel_excludedFromStep2Rels() {
        val localKeys = setOf("keep.yaml")
        val remoteByRel = mutableMapOf(
            "gone.yaml" to WebDavSync.DirEntry("gone.yaml", false, null),
            "keep.yaml" to WebDavSync.DirEntry("keep.yaml", false, null),
        )
        // 步骤 1 删远端成功后从远端集合移除（生产代码同一操作）。
        remoteByRel.remove("gone.yaml")
        val allRels = WebDavSync.syncTargetRels(localKeys, remoteByRel.keys)
        assertFalse("gone.yaml" in allRels)
        assertTrue("keep.yaml" in allRels)
    }

    @Test
    fun dictDownloadAction_mapsTriState() {
        assertEquals(DictDownloadAction.IMPORT, WebDavSync.dictDownloadAction(HttpGetResult.OK))
        assertEquals(DictDownloadAction.SKIP, WebDavSync.dictDownloadAction(HttpGetResult.NOT_FOUND))
        assertEquals(DictDownloadAction.FAIL, WebDavSync.dictDownloadAction(HttpGetResult.FAILED))
    }

    // ---- C5 原子替换 ----

    @Test
    fun atomicReplace_success_replacesTarget() {
        val dir = Files.createTempDirectory("webdav-atomic").toFile()
        try {
            val target = File(dir, "t.yaml").apply { writeText("old") }
            val tmp = File(dir, "t.yaml.tmp").apply { writeText("new") }
            assertTrue(WebDavSync.atomicReplace(tmp, target))
            assertEquals("new", target.readText())
            assertFalse(tmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun atomicReplace_failure_keepsOldTargetAndDeletesTmp() {
        val dir = Files.createTempDirectory("webdav-atomic").toFile()
        try {
            val target = File(dir, "t.yaml").apply { writeText("old") }
            val tmp = File(dir, "t.yaml.tmp").apply { writeText("new") }
            assertFalse(WebDavSync.atomicReplace(tmp, target) { _, _ -> false })
            assertEquals("old", target.readText())
            assertFalse(tmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- walkRemoteDir 上限与非法条目 ----

    @Test
    fun walkRemoteDir_unsafeEntries_rejectedAndRecorded() = runBlocking {
        val failed = mutableListOf<String>()
        val out = WebDavSync.walkRemoteDir(
            base = "https://example.invalid/dav",
            relDir = "",
            failed = failed,
            fetchDir = {
                listOf(
                    WebDavSync.DirEntry("../evil.yaml", false, null),
                    WebDavSync.DirEntry("good.yaml", false, null),
                )
            },
        )
        assertEquals(listOf("good.yaml"), out.map { it.relPath })
        assertTrue("../evil.yaml" in failed)
    }

    @Test
    fun walkRemoteDir_depthLimit_stopsDrilling() = runBlocking {
        var calls = 0
        val failed = mutableListOf<String>()
        val out = WebDavSync.walkRemoteDir(
            base = "https://example.invalid/dav",
            relDir = "",
            failed = failed,
            fetchDir = {
                calls++
                listOf(WebDavSync.DirEntry("a", true, null))
            },
        )
        assertTrue(out.isEmpty())
        assertTrue(failed.isNotEmpty())
        // 深度 32 截断：调用次数应有界，不会无限递归。
        assertTrue(calls <= WebDavSync.MAX_REMOTE_WALK_DEPTH + 2)
    }

    @Test
    fun walkRemoteDir_totalLimit_truncates() = runBlocking {
        val failed = mutableListOf<String>()
        val entries = (1..(WebDavSync.MAX_REMOTE_WALK_ENTRIES + 5)).map {
            WebDavSync.DirEntry("f$it.yaml", false, null)
        }
        val out = WebDavSync.walkRemoteDir(
            base = "https://example.invalid/dav",
            relDir = "",
            failed = failed,
            fetchDir = { entries },
        )
        assertEquals(WebDavSync.MAX_REMOTE_WALK_ENTRIES, out.size)
        assertTrue(failed.any { it.contains("超过上限") })
    }

    // ---- 链接判定 fail-closed ----

    @Test
    fun symlinkCheck_indefinite_isUnknownAndFailClosed() {
        val userDir = Files.createTempDirectory("webdav-user").toFile()
        val outside = Files.createTempDirectory("webdav-outside").toFile()
        try {
            // 相对路径文件与绝对用户目录无法 relativize，判定不定。
            val indefinite = File("nonexistent-relative.yaml")
            assertEquals(SymlinkCheck.UNKNOWN, WebDavSync.checkSymlinkBetween(userDir, indefinite))
            assertTrue(WebDavSync.checkSymlinkBetween(userDir, indefinite) != SymlinkCheck.NOT_LINK)
            // 目录外同盘文件经 canonical 比对判为 LINK，同样跳过（不放行）。
            val outsideFile = File(outside, "x.yaml").apply { writeText("x") }
            assertEquals(SymlinkCheck.LINK, WebDavSync.checkSymlinkBetween(userDir, outsideFile))
            val inside = File(userDir, "y.yaml").apply { writeText("y") }
            assertEquals(SymlinkCheck.NOT_LINK, WebDavSync.checkSymlinkBetween(userDir, inside))
        } finally {
            userDir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun collectLocalSyncFiles_symlinkFile_skipped() {
        val userDir = Files.createTempDirectory("webdav-user").toFile()
        val outside = Files.createTempDirectory("webdav-outside").toFile()
        try {
            File(userDir, "real.yaml").writeText("real")
            val outsideFile = File(outside, "x.yaml").apply { writeText("x") }
            Files.createSymbolicLink(File(userDir, "link.yaml").toPath(), outsideFile.toPath())
            val failed = mutableListOf<String>()
            val rels = WebDavSync.collectLocalSyncFiles(userDir, failed).map { it.first }
            assertEquals(listOf("real.yaml"), rels)
        } finally {
            userDir.deleteRecursively()
            outside.deleteRecursively()
        }
    }
}
