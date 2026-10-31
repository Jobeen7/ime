// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 方案同步纯逻辑：上传相对路径校验与待上传文件收集。
 * 网络与引擎交互不在此测（需真机复核）。
 */
class SchemeSyncTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.walkBottomUp().forEach { f -> f.delete() } }
        tempDirs.clear()
    }

    private fun createTempDir(): File {
        val dir = Files.createTempDirectory("scheme-sync-test").toFile()
        tempDirs.add(dir)
        return dir
    }

    private fun touch(parent: File, rel: String) {
        val f = File(parent, rel)
        f.parentFile?.mkdirs()
        f.writeText("x")
    }

    @Test
    fun isValidSchemeRel_valid() {
        assertTrue(WebDavSync.isValidSchemeRel("a.yaml"))
        assertTrue(WebDavSync.isValidSchemeRel("sub/b.yaml"))
    }

    @Test
    fun isValidSchemeRel_malformed() {
        assertFalse(WebDavSync.isValidSchemeRel(""))
        assertFalse(WebDavSync.isValidSchemeRel("/a.yaml"))
        assertFalse(WebDavSync.isValidSchemeRel("a//b.yaml"))
        assertFalse(WebDavSync.isValidSchemeRel("../evil.yaml"))
        assertFalse(WebDavSync.isValidSchemeRel("a/../b.yaml"))
    }

    @Test
    fun isValidSchemeRel_protected() {
        assertFalse(WebDavSync.isValidSchemeRel("build/x.yaml"))
        assertFalse(WebDavSync.isValidSchemeRel("sub/build/y.yaml"))
        assertFalse(WebDavSync.isValidSchemeRel("x.userdb"))
        assertFalse(WebDavSync.isValidSchemeRel("x.log"))
        assertFalse(WebDavSync.isValidSchemeRel("a.tmp"))
        // .5 排除规则续留（已删 SchemeDirSyncTest 的用例口径）：
        assertFalse(WebDavSync.isValidSchemeRel("scheme-archive/old.zip"))
        assertFalse(WebDavSync.isValidSchemeRel("installation.yaml"))
        assertFalse(WebDavSync.isValidSchemeRel("sub/installation.yaml"))
        assertFalse(WebDavSync.isValidSchemeRel("wanxiang-lts-zh-hans.gram"))
        assertFalse(WebDavSync.isValidSchemeRel("a.lock"))
        assertFalse(WebDavSync.isValidSchemeRel("wanxiang_lite.userdict.txt"))
    }

    @Test
    fun collectUploadFiles_filtersAndCaps() {
        val root = createTempDir()
        touch(root, "d1/a.yaml")
        touch(root, "d1/sub/b.yaml")
        touch(root, "d1/build/c.yaml")
        touch(root, "d1/x.userdb")
        touch(root, "d1/x.log")
        touch(root, "build/top.yaml")
        touch(root, ".hidden/h.yaml")
        touch(root, "loose.yaml")
        touch(root, "d2/a/b/c/d/e/f.yaml")
        touch(root, "d2/a/b/c/d/e/f/g.yaml")
        val out = WebDavSync.collectUploadFiles(root).toSet()
        assertEquals(
            setOf(
                "d1/a.yaml",
                "d1/sub/b.yaml",
                "d2/a/b/c/d/e/f.yaml",
            ),
            out,
        )
    }

    @Test
    fun hasDotDirSegment_rejectsDotDirs() {
        assertTrue(WebDavSync.hasDotDirSegment(".hidden"))
        assertTrue(WebDavSync.hasDotDirSegment("a/.hidden"))
        assertTrue(WebDavSync.hasDotDirSegment("a/.hidden/b.yaml"))
        assertFalse(WebDavSync.hasDotDirSegment("a/b.yaml"))
        assertFalse(WebDavSync.hasDotDirSegment("mydir"))
        assertFalse(WebDavSync.hasDotDirSegment(""))
    }

    @Test
    fun collectUploadFiles_stopsAt1000InSingleDir() {
        val root = createTempDir()
        for (i in 0 until 1500) {
            touch(root, "big/f$i.yaml")
        }
        val out = WebDavSync.collectUploadFiles(root)
        // 单目录内达上限即真跳出，不再遍历剩余文件（旧 return@forEach
        // 只跳过当前文件，整棵树仍会被走完；上限本身前后都为 1000，
        // 遍历停止由 early return 保证，此处断言上限与单目录来源）。
        assertEquals(1000, out.size)
        assertTrue(out.all { it.startsWith("big/") })
    }
}
