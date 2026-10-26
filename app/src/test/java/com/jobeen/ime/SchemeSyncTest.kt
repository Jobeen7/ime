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
}
