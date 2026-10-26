// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.DictDownloadAction
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.HttpGetResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * WebDAV 同步的 JVM 可测部分：词典下载三态映射与原子替换。
 * 旧目录同步（mtime 末写胜出、删除传播、骤减暂停）已随多目录
 * 改写退场，其纯函数与用例一并删除，不再覆盖。
 */
class WebDavSyncTest {

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
}
