// SPDX-License-Identifier: Apache-2.0

package com.jobeen.ime.base.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R1-2：资源 ZIP 单文件 rename 失败必须整体失败、不写版本标记。
 *
 * 注入法（主机可跑、无需 Android Context）：走无 Context 的
 * [ResourceExtractorUtil.extract] 流入口，预先把目标条目路径
 * 占成一个非空目录——文件 rename 到已存在的非空目录必然失败，
 * 而删目标（非空目录删不掉）后的二次 rename 同样失败，
 * 恰好命中生产代码的二次失败分支。只读文件/只读目录在
 * root 身份下仍可写，不可靠，故不用。
 *
 * 版本标记说明：AppStartup 本体需 Context/ImeApplication，
 * 主机单测无法执行；此处按 AppStartup 的调用合同断言——
 * extract 返回 false 时调用方不写标记（测试内的门控写法与
 * AppStartup runBlocking 块一致：先判返回值，通过才写标记）。
 * AppStartup 侧的真实门控（false 时抛错、写标记语句在其后）
 * 由代码对齐保证，见 AppStartup.releaseResourcesIfNeeded。
 */
class ResourceExtractFaultTest {

    private fun zipBytes(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun runWithTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("resource-extract-fault-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    /** 占位：把 destDir/a.txt 建成含文件的非空目录，rename 必败且删不掉。 */
    private fun blockEntryWithNonEmptyDir(destDir: File, name: String) {
        val blocked = File(destDir, name)
        assertTrue(blocked.mkdirs())
        File(blocked, "keep.txt").writeText("old")
    }

    @Test
    fun renameFailure_returnsFalse_cleansTmp_keepsOldEntry() {
        runWithTempDir { root ->
            val destDir = File(root, "dest")
            blockEntryWithNonEmptyDir(destDir, "a.txt")
            val ok = ResourceExtractorUtil.extract(
                ByteArrayInputStream(zipBytes(mapOf("a.txt" to "new"))),
                destDir,
            )
            assertFalse(ok)
            // 临时文件已清，不留半成品
            assertFalse(File(destDir, "a.txt.tmp").exists())
            // 被占位的旧条目未被替换
            assertTrue(File(destDir, "a.txt").isDirectory)
            assertEquals("old", File(destDir, "a.txt/keep.txt").readText())
        }
    }

    @Test
    fun renameFailure_callerGate_doesNotWriteVersionMarker() {
        runWithTempDir { root ->
            val destDir = File(root, "dest")
            blockEntryWithNonEmptyDir(destDir, "a.txt")
            val versionFile = File(destDir, "version.txt")
            val ok = ResourceExtractorUtil.extract(
                ByteArrayInputStream(zipBytes(mapOf("a.txt" to "new"))),
                destDir,
            )
            // AppStartup 合同：仅成功才写标记
            if (ok) versionFile.writeText("1:deadbeef")
            assertFalse(ok)
            assertFalse(versionFile.exists())
        }
    }

    @Test
    fun renameFailure_abortsRemainingEntries() {
        runWithTempDir { root ->
            val destDir = File(root, "dest")
            blockEntryWithNonEmptyDir(destDir, "a.txt")
            val ok = ResourceExtractorUtil.extract(
                ByteArrayInputStream(
                    zipBytes(mapOf("a.txt" to "new", "b.txt" to "good")),
                ),
                destDir,
            )
            assertFalse(ok)
            // 失败即中止：后续条目不被解压，下次启动全量重解
            assertFalse(File(destDir, "b.txt").exists())
        }
    }

    @Test
    fun success_returnsTrue_writesFiles_noTmpLeft() {
        runWithTempDir { root ->
            val destDir = File(root, "dest")
            val ok = ResourceExtractorUtil.extract(
                ByteArrayInputStream(
                    zipBytes(mapOf("a.txt" to "hello", "sub/b.txt" to "world")),
                ),
                destDir,
            )
            assertTrue(ok)
            assertEquals("hello", File(destDir, "a.txt").readText())
            assertEquals("world", File(destDir, "sub/b.txt").readText())
            assertFalse(File(destDir, "a.txt.tmp").exists())
            assertFalse(File(destDir, "sub/b.txt.tmp").exists())
        }
    }
}
