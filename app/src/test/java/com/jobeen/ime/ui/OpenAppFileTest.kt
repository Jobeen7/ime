package com.jobeen.ime.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WO52 8.3：openAppFile 分发校验＋路径安全。
 * resolve()/isWithinRoot() 均为纯函数；openAppFile() 的
 * Context 分支（getExternalFilesDir／startActivity）不在
 * 主机单测覆盖内（无 Robolectric）。
 */
class OpenAppFileTest {

    @Test
    fun `wrong action is discarded`() {
        assertNull(
            OpenAppFileDispatcher.resolve(
                mapOf("action" to "openSomethingElse", "fileURL" to "a.txt"),
            ),
        )
    }

    @Test
    fun `missing action is discarded`() {
        assertNull(OpenAppFileDispatcher.resolve(mapOf("fileURL" to "a.txt")))
    }

    @Test
    fun `missing fileURL is discarded`() {
        assertNull(OpenAppFileDispatcher.resolve(mapOf("action" to "openAppFile")))
    }

    @Test
    fun `empty fileURL is discarded`() {
        assertNull(
            OpenAppFileDispatcher.resolve(
                mapOf("action" to "openAppFile", "fileURL" to ""),
            ),
        )
    }

    @Test
    fun `valid params resolve to fileURL`() {
        assertEquals(
            OpenAppFileDispatcher.Resolved("skins/bin.json"),
            OpenAppFileDispatcher.resolve(
                mapOf("action" to "openAppFile", "fileURL" to "skins/bin.json"),
            ),
        )
    }

    @Test
    fun `file inside root is within root`() {
        val root = File("/data/files")
        assertTrue(AppFilesOpener.isWithinRoot(root, File("/data/files/skins/a.json")))
        assertTrue(AppFilesOpener.isWithinRoot(root, root))
    }

    @Test
    fun `file outside root is rejected`() {
        val root = File("/data/files")
        assertFalse(AppFilesOpener.isWithinRoot(root, File("/data/other/a.json")))
        // 前缀欺骗：/data/files2 不是 /data/files 的子目录。
        assertFalse(AppFilesOpener.isWithinRoot(root, File("/data/files2/a.json")))
    }
}
