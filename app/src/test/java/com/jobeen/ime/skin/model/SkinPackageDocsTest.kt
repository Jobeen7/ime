// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.skin.model

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 皮肤包演示资料（审计 K 余项）：演示图与说明的校验口径。 */
class SkinPackageDocsTest {

    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    @Test
    fun validDemoAndReadmePass() {
        val docs = SkinPackageDocsParser.fromBytes(
            pngHeader + byteArrayOf(1, 2, 3),
            "切换中英文请按中英键。".toByteArray(Charsets.UTF_8),
        )
        assertTrue(docs.hasDemoImage)
        assertEquals("切换中英文请按中英键。", docs.readmeText)
    }

    @Test
    fun invalidDemoRejectedWithoutBlockingReadme() {
        val docs = SkinPackageDocsParser.fromBytes(
            "not-a-png".toByteArray(Charsets.UTF_8),
            "说明仍有效。".toByteArray(Charsets.UTF_8),
        )
        assertFalse(docs.hasDemoImage)
        assertEquals("说明仍有效。", docs.readmeText)
    }

    @Test
    fun invalidReadmeRejectedWithoutBlockingDemo() {
        val docs = SkinPackageDocsParser.fromBytes(
            pngHeader,
            // 非法 UTF-8 字节 + NUL 均判失效。
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
        )
        assertTrue(docs.hasDemoImage)
        assertNull(docs.readmeText)
        val nulDocs = SkinPackageDocsParser.fromBytes(
            null, byteArrayOf('a'.code.toByte(), 0),
        )
        assertNull(nulDocs.readmeText)
    }

    @Test
    fun missingFilesYieldEmptyDocs() {
        val docs = SkinPackageDocsParser.fromBytes(null, null)
        assertFalse(docs.hasDemoImage)
        assertNull(docs.readmeText)
    }

    @Test
    fun fromDirReadsGenDirFiles() {
        val dir = Files.createTempDirectory("skin-docs-").toFile()
        try {
            File(dir, "demo.png").writeBytes(pngHeader + byteArrayOf(9))
            File(dir, "README.md").writeText("说明文本。", Charsets.UTF_8)
            val docs = SkinPackageDocsParser.fromDir(dir)
            assertTrue(docs.hasDemoImage)
            assertEquals("说明文本。", docs.readmeText)
            assertEquals(File(dir, "demo.png"), SkinPackageDocsParser.demoImageFile(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun demoImageFileRejectsNonPng() {
        val dir = Files.createTempDirectory("skin-docs-bad-").toFile()
        try {
            File(dir, "demo.png").writeText("not a png", Charsets.UTF_8)
            assertNull(SkinPackageDocsParser.demoImageFile(dir))
            assertNull(SkinPackageDocsParser.demoImageFile(File(dir, "missing")))
        } finally {
            dir.deleteRecursively()
        }
    }
}
