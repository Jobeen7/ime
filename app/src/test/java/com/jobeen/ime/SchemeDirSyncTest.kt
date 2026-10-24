// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime

import com.jobeen.ime.engine.rime.data.userdict.WebDavSync
import com.jobeen.ime.engine.rime.data.userdict.WebDavSync.FileSyncAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 方案目录同步纯逻辑：mtime 末写胜出决策、同步排除规则。
 * 网络与引擎交互不在此测（需真机复核）。
 */
class SchemeDirSyncTest {

    @Test
    fun decide_bothMissing_isSkip() {
        assertEquals(FileSyncAction.SKIP, WebDavSync.decideFileSync(null, null))
    }

    @Test
    fun decide_localMissing_isDownload() {
        assertEquals(FileSyncAction.DOWNLOAD, WebDavSync.decideFileSync(null, 1000L))
    }

    @Test
    fun decide_remoteMissingMtime_isUpload() {
        assertEquals(FileSyncAction.UPLOAD, WebDavSync.decideFileSync(1000L, null))
    }

    @Test
    fun decide_equal_isSkip() {
        assertEquals(FileSyncAction.SKIP, WebDavSync.decideFileSync(5000L, 5000L))
    }

    @Test
    fun decide_withinOneSecond_isSkip() {
        assertEquals(FileSyncAction.SKIP, WebDavSync.decideFileSync(5000L, 5999L))
        assertEquals(FileSyncAction.SKIP, WebDavSync.decideFileSync(6000L, 5001L))
    }

    @Test
    fun decide_localNewer_isUpload() {
        assertEquals(FileSyncAction.UPLOAD, WebDavSync.decideFileSync(7000L, 5000L))
    }

    @Test
    fun decide_remoteNewer_isDownload() {
        assertEquals(FileSyncAction.DOWNLOAD, WebDavSync.decideFileSync(5000L, 7000L))
    }

    @Test
    fun exclude_buildDir() {
        assertTrue(WebDavSync.isExcludedSyncPath("build", true))
        assertTrue(WebDavSync.isExcludedSyncPath("build/wanxiang_lite.schema.yaml", false))
        assertFalse(WebDavSync.isExcludedSyncPath("build2/wanxiang_lite.schema.yaml", false))
    }

    @Test
    fun exclude_schemeArchive() {
        assertTrue(WebDavSync.isExcludedSyncPath("scheme-archive", true))
        assertTrue(WebDavSync.isExcludedSyncPath("scheme-archive/old.zip", false))
    }

    @Test
    fun exclude_userdb() {
        assertTrue(WebDavSync.isExcludedSyncPath("wanxiang_lite.userdb", true))
        assertTrue(WebDavSync.isExcludedSyncPath("sub/wanxiang_lite.userdb/000001.ldb", false))
        assertFalse(WebDavSync.isExcludedSyncPath("wanxiang_lite.dict.yaml", false))
    }

    @Test
    fun exclude_installationAndGramAndTemp() {
        assertTrue(WebDavSync.isExcludedSyncPath("installation.yaml", false))
        assertTrue(WebDavSync.isExcludedSyncPath("wanxiang-lts-zh-hans.gram", false))
        assertTrue(WebDavSync.isExcludedSyncPath("a.lock", false))
        assertTrue(WebDavSync.isExcludedSyncPath("a.log", false))
        assertTrue(WebDavSync.isExcludedSyncPath("a.tmp", false))
    }

    @Test
    fun exclude_userdictText_files() {
        assertTrue(WebDavSync.isExcludedSyncPath("wanxiang_lite.userdict.txt", false))
    }

    @Test
    fun include_normalSchemeFiles() {
        assertFalse(WebDavSync.isExcludedSyncPath("wanxiang_lite.schema.yaml", false))
        assertFalse(WebDavSync.isExcludedSyncPath("wanxiang_lite.dict.yaml", false))
        assertFalse(WebDavSync.isExcludedSyncPath("user.yaml", false))
        assertFalse(WebDavSync.isExcludedSyncPath("opencc/wanxiang.json", false))
        assertFalse(WebDavSync.isExcludedSyncPath("lua/wanxiang.lua", false))
    }
}
